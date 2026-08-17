import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation

@MainActor
final class RoomService {
    private let firestore: Firestore?
    private let encryption = EncryptionService()
    private let storage: Storage?

    init() {
        firestore = FirebaseApp.app().map { _ in Firestore.firestore() }
        storage = FirebaseApp.app().map { _ in Storage.storage() }
    }

    func listenToRooms(in space: Space, onUpdate: @escaping (Result<[SpaceRoom], Error>) -> Void) -> ListenerRegistration? {
        guard let firestore else { onUpdate(.success([])); return nil }
        guard let uid = Auth.auth().currentUser?.uid, !uid.isEmpty else {
            onUpdate(.success([]))
            return nil
        }

        let rooms = firestore.collection("spaces").document(space.id).collection("rooms")
        var publicRooms: [String: SpaceRoom] = [:]
        var privateRooms: [String: SpaceRoom] = [:]

        func publish() {
            let merged = publicRooms.merging(privateRooms) { _, privateRoom in privateRoom }
            onUpdate(.success(merged.values.sorted { $0.updatedAt > $1.updatedAt }))
        }

        let publicListener = rooms
            .whereField("isPrivate", isEqualTo: false)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }
                publicRooms = Dictionary(
                    uniqueKeysWithValues: (snapshot?.documents.compactMap(Self.mapRoom) ?? [])
                        .map { ($0.id, $0) }
                )
                publish()
            }

        let privateListener = rooms
            .whereField("isPrivate", isEqualTo: true)
            .whereField("memberIds", arrayContains: uid)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }
                privateRooms = Dictionary(
                    uniqueKeysWithValues: (snapshot?.documents.compactMap(Self.mapRoom) ?? [])
                        .map { ($0.id, $0) }
                )
                publish()
            }

        return CompositeRoomListener(registrations: [publicListener, privateListener])
    }

    func listenToRoom(spaceID: String, roomID: String, onUpdate: @escaping (Result<SpaceRoom, Error>) -> Void) -> ListenerRegistration? {
        guard let firestore else { return nil }
        return firestore.collection("spaces").document(spaceID)
            .collection("rooms").document(roomID)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                } else if let snapshot, snapshot.exists, let room = Self.mapRoom(snapshot) {
                    onUpdate(.success(room))
                }
            }
    }

    func saveRoom(_ room: SpaceRoom) async throws {
        guard let firestore else { return }
        let reference = firestore.collection("spaces").document(room.spaceID).collection("rooms").document(room.id)
        let existing = try await reference.getDocument()
        let isNew = !existing.exists
        let keyMode = existing.data()?["keyMode"] as? String
            ?? (existing.exists ? "legacy-room-key-v1" : "space-member-key-v1")
        try await reference.setData([
            "spaceId": room.spaceID, "name": room.name, "topic": room.topic, "isPrivate": room.isPrivate,
            "memberIds": Array(room.memberIDs), "createdBy": room.createdBy,
            "createdAt": Timestamp(date: room.createdAt), "updatedAt": Timestamp(date: room.updatedAt),
            "postingMemberIds": room.postingMemberIDs.map(Array.init) ?? NSNull(),
            "keyMode": keyMode
        ])
        _ = try await roomKey(spaceID: room.spaceID, roomID: room.id)
        if isNew {
            await recordRoomActivity(
                spaceID: room.spaceID,
                type: .roomCreated,
                title: "created a Room",
                subtitle: room.name,
                targetID: room.id
            )
        }
    }

    func deleteRoom(_ room: SpaceRoom) async throws {
        guard let firestore, let storage else { return }
        let roomReference = firestore.collection("spaces").document(room.spaceID).collection("rooms").document(room.id)
        let messages = try await roomReference.collection("messages").getDocuments()
        for document in messages.documents {
            for attachment in (document.data()["attachments"] as? [[String: Any]] ?? []) {
                if let path = attachment["storagePath"] as? String {
                    try? await storage.reference(withPath: path).delete()
                }
            }
            try await document.reference.delete()
        }
        let encryptionDocuments = try await roomReference.collection("encryption").getDocuments()
        for document in encryptionDocuments.documents {
            try await document.reference.delete()
        }
        try await roomReference.delete()
    }

    func listenToMessages(spaceID: String, roomID: String, onUpdate: @escaping (Result<[RoomMessage], Error>) -> Void) -> ListenerRegistration? {
        guard let firestore else { onUpdate(.success([])); return nil }
        return firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID).collection("messages")
            .order(by: "createdAt").addSnapshotListener { [weak self] snapshot, error in
                if let error { onUpdate(.failure(error)); return }
                Task { @MainActor in
                    do {
                        guard let self else { return }
                        let key = try await self.roomKey(spaceID: spaceID, roomID: roomID)
                        let messages = try snapshot?.documents.compactMap { try self.mapMessage($0, key: key) } ?? []
                        onUpdate(.success(messages))
                    } catch { onUpdate(.failure(error)) }
                }
            }
    }

    func sendMessage(spaceID: String, roomID: String, senderName: String, body: String, reply: RoomMessage?, links: [SpaceLinkAttachment]) async throws {
        guard let firestore, let uid = Auth.auth().currentUser?.uid else { return }
        try await requirePostingPermission(spaceID: spaceID, roomID: roomID, userID: uid)
        let key = try await roomKey(spaceID: spaceID, roomID: roomID)
        let payload = try encryption.encryptText(body, using: key)
        let ref = firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID).collection("messages").document()
        try await ref.setData([
            "id": ref.documentID, "roomId": roomID, "senderId": uid, "senderName": senderName,
            "ciphertext": payload.ciphertext, "nonce": payload.nonce, "encryptionVersion": "aes-gcm-v1",
            "createdAt": FieldValue.serverTimestamp(), "replyToId": reply?.id ?? NSNull(),
            "replyPreview": reply?.body.prefix(120).description ?? NSNull(), "reactions": [],
            "isPinned": false, "links": links.map { ["id": $0.id, "moduleType": $0.moduleType.rawValue, "targetId": $0.targetId, "title": $0.title, "subtitle": $0.subtitle ?? "", "icon": $0.icon] }
        ])
        await recordRoomActivity(spaceID: spaceID, type: .roomMessageSent, title: "posted in a Room", targetID: roomID)
    }

    func updateMessage(spaceID: String, roomID: String, messageID: String, fields: [String: Any]) async throws {
        guard let firestore else { return }
        try await firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID).collection("messages").document(messageID).updateData(fields)
    }

    func editMessage(spaceID: String, roomID: String, message: RoomMessage, body: String) async throws {
        guard let firestore, let uid = Auth.auth().currentUser?.uid, uid == message.senderID else {
            throw NSError(domain: "RoomService", code: 403, userInfo: [NSLocalizedDescriptionKey: "You can only edit your own messages."])
        }
        try await requirePostingPermission(spaceID: spaceID, roomID: roomID, userID: uid)
        let payload = try encryption.encryptText(body, using: try await roomKey(spaceID: spaceID, roomID: roomID))
        try await firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID)
            .collection("messages").document(message.id).updateData([
                "ciphertext": payload.ciphertext,
                "nonce": payload.nonce,
                "editedAt": FieldValue.serverTimestamp()
            ])
    }

    func deleteMessage(spaceID: String, roomID: String, message: RoomMessage, canDeleteOthers: Bool) async throws {
        guard let firestore, let storage, let uid = Auth.auth().currentUser?.uid,
              uid == message.senderID || canDeleteOthers else {
            throw NSError(domain: "RoomService", code: 403, userInfo: [NSLocalizedDescriptionKey: "You do not have permission to delete this message."])
        }
        for attachment in message.attachments {
            try? await storage.reference(withPath: attachment.storagePath).delete()
        }
        try await firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID)
            .collection("messages").document(message.id).delete()
    }

    func sendAttachment(
        spaceID: String,
        roomID: String,
        senderName: String,
        data: Data,
        name: String,
        mimeType: String,
        isMedia: Bool
    ) async throws {
        guard let firestore, let storage, let uid = Auth.auth().currentUser?.uid else { return }
        try await requirePostingPermission(spaceID: spaceID, roomID: roomID, userID: uid)
        let key = try await roomKey(spaceID: spaceID, roomID: roomID)
        let encrypted = try encryption.encryptData(data, using: key)
        let attachmentID = UUID().uuidString
        let path = "spaces/\(spaceID)/rooms/\(roomID)/attachments/\(attachmentID).enc"
        guard let encryptedData = Data(base64Encoded: encrypted.ciphertext) else { return }
        let metadata = StorageMetadata()
        metadata.contentType = "application/octet-stream"
        _ = try await storage.reference(withPath: path).putDataAsync(encryptedData, metadata: metadata)
        let bodyPayload = try encryption.encryptText(name, using: key)

        let ref = firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID).collection("messages").document()
        try await ref.setData([
            "id": ref.documentID, "roomId": roomID, "senderId": uid, "senderName": senderName,
            "ciphertext": bodyPayload.ciphertext,
            "nonce": bodyPayload.nonce,
            "encryptionVersion": "aes-gcm-v1", "createdAt": FieldValue.serverTimestamp(),
            "reactions": [], "isPinned": false, "links": [],
            "attachments": [[
                "id": attachmentID, "name": name, "mimeType": mimeType,
                "storagePath": path, "nonce": encrypted.nonce, "isMedia": isMedia
            ]]
        ])
        await recordRoomActivity(spaceID: spaceID, type: .roomMessageSent, title: "shared an attachment in a Room", targetID: roomID)
    }

    private func recordRoomActivity(
        spaceID: String,
        type: ActivityItemType,
        title: String,
        subtitle: String? = nil,
        targetID: String
    ) async {
        guard let firestore,
              let snapshot = try? await firestore.collection("spaces").document(spaceID).getDocument(),
              let space = SpaceService().mapSpaceForFeature(document: snapshot) else { return }
        let service = SpaceService()
        await service.recordModuleActivity(
            type: type,
            in: space,
            title: title,
            subtitle: subtitle,
            targetID: targetID,
            targetType: .rooms,
            notifyMembers: false
        )
        let roomSnapshot = try? await firestore.collection("spaces").document(spaceID).collection("rooms").document(targetID).getDocument()
        let isPrivate = roomSnapshot?.data()?["isPrivate"] as? Bool ?? false
        let privateMembers = roomSnapshot?.data()?["memberIds"] as? [String] ?? []
        let recipients = isPrivate ? privateMembers : await service.memberIDs(in: space)
        await service.recordTargetedModuleNotification(
            recipientIDs: recipients,
            type: .room,
            in: space,
            title: title,
            subtitle: subtitle,
            targetID: targetID,
            targetType: .rooms
        )
    }


    private func requirePostingPermission(spaceID: String, roomID: String, userID: String) async throws {
        guard let firestore else { return }
        let spaceSnapshot = try await firestore.collection("spaces").document(spaceID).getDocument()
        guard let space = SpaceService().mapSpaceForFeature(document: spaceSnapshot),
              await SpaceService().canPerform(.postInRooms, in: space) else {
            throw NSError(domain: "RoomService", code: 403, userInfo: [NSLocalizedDescriptionKey: "You do not have permission to post in Rooms."])
        }
        let snapshot = try await firestore.collection("spaces").document(spaceID)
            .collection("rooms").document(roomID).getDocument()
        if snapshot.data()?["postingMemberIds"] is [String] {
            let memberSnapshot = try await firestore.collection("spaces").document(spaceID)
                .collection("members").document(userID).getDocument()
            let role = memberSnapshot.data()?["role"] as? String
            if role != SpaceMemberRole.owner.firestoreValue && role != SpaceMemberRole.admin.firestoreValue {
                throw NSError(
                    domain: "RoomService",
                    code: 403,
                    userInfo: [NSLocalizedDescriptionKey: "Only Space Owners and Admins can post in this Room."]
                )
            }
        }
    }

    func downloadAttachment(spaceID: String, roomID: String, attachment: RoomMessageAttachment) async throws -> Data {
        guard let storage else { return Data() }
        let key = try await roomKey(spaceID: spaceID, roomID: roomID)
        let encryptedData = try await storage.reference(withPath: attachment.storagePath)
            .data(maxSize: 250 * 1024 * 1024)
        return try encryption.decryptData(
            ciphertext: encryptedData.base64EncodedString(),
            nonce: attachment.nonce,
            using: key
        )
    }

    private func roomKey(spaceID: String, roomID: String) async throws -> SymmetricKey {
        let cacheID = "\(spaceID).room.\(roomID)"
        if let key = encryption.cachedSpaceKey(for: cacheID) { return key }
        guard let firestore else { return encryption.generateSpaceKey() }
        let roomSnapshot = try await firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID).getDocument()
        if roomSnapshot.data()?["keyMode"] as? String == "space-member-key-v1" {
            let key = try await SpaceService().encryptionKeyForAuthorizedFeature(spaceID: spaceID)
            encryption.cacheSpaceKey(key, for: cacheID)
            return key
        }
        let ref = firestore.collection("spaces").document(spaceID).collection("rooms").document(roomID).collection("encryption").document("key")
        let snapshot = try await ref.getDocument()
        if let encoded = snapshot.data()?["keyBase64"] as? String {
            let key = try encryption.decodeSpaceKey(encoded); encryption.cacheSpaceKey(key, for: cacheID); return key
        }
        let key = encryption.generateSpaceKey()
        try await ref.setData(["keyBase64": encryption.encodeSpaceKey(key), "keyVersion": "aes-gcm-v1", "createdBy": Auth.auth().currentUser?.uid ?? ""])
        encryption.cacheSpaceKey(key, for: cacheID)
        return key
    }

    private static func mapRoom(_ doc: DocumentSnapshot) -> SpaceRoom? {
        guard let d = doc.data() else { return nil }
        guard let spaceID = d["spaceId"] as? String, let name = d["name"] as? String, let creator = d["createdBy"] as? String else { return nil }
        return SpaceRoom(id: doc.documentID, spaceID: spaceID, name: name, topic: d["topic"] as? String ?? "", isPrivate: d["isPrivate"] as? Bool ?? false, memberIDs: Set(d["memberIds"] as? [String] ?? []), createdBy: creator, createdAt: (d["createdAt"] as? Timestamp)?.dateValue() ?? Date(), updatedAt: (d["updatedAt"] as? Timestamp)?.dateValue() ?? Date(), postingMemberIDs: (d["postingMemberIds"] as? [String]).map(Set.init))
    }

    private func mapMessage(_ doc: QueryDocumentSnapshot, key: SymmetricKey) throws -> RoomMessage? {
        let d = doc.data()
        guard let senderID = d["senderId"] as? String, let ciphertext = d["ciphertext"] as? String, let nonce = d["nonce"] as? String else { return nil }
        let body = try encryption.decryptText(ciphertext: ciphertext, nonce: nonce, using: key)
        let reactions = (d["reactions"] as? [[String: Any]] ?? []).compactMap { value -> RoomMessageReaction? in
            guard let emoji = value["emoji"] as? String else { return nil }; return .init(emoji: emoji, userIDs: Set(value["userIds"] as? [String] ?? []))
        }.uniquedByID()
        let links = (d["links"] as? [[String: Any]] ?? []).compactMap { value -> SpaceLinkAttachment? in
            guard
                let rawType = value["moduleType"] as? String,
                let moduleType = SpaceLinkModuleType(rawValue: rawType),
                let targetID = value["targetId"] as? String,
                let title = value["title"] as? String
            else { return nil }
            return SpaceLinkAttachment(
                id: value["id"] as? String ?? UUID().uuidString,
                moduleType: moduleType,
                targetId: targetID,
                title: title,
                subtitle: value["subtitle"] as? String,
                icon: value["icon"] as? String ?? moduleType.icon
            )
        }.uniquedByID()
        let attachments = (d["attachments"] as? [[String: Any]] ?? []).compactMap { value -> RoomMessageAttachment? in
            guard let id = value["id"] as? String, let name = value["name"] as? String,
                  let mimeType = value["mimeType"] as? String, let path = value["storagePath"] as? String,
                  let nonce = value["nonce"] as? String else { return nil }
            return .init(id: id, name: name, mimeType: mimeType, storagePath: path, nonce: nonce, isMedia: value["isMedia"] as? Bool ?? false)
        }.uniquedByID()
        return RoomMessage(id: doc.documentID, roomID: d["roomId"] as? String ?? "", senderID: senderID, senderName: d["senderName"] as? String ?? "Member", body: body, createdAt: (d["createdAt"] as? Timestamp)?.dateValue() ?? Date(), replyToID: d["replyToId"] as? String, replyPreview: d["replyPreview"] as? String, reactions: reactions, isPinned: d["isPinned"] as? Bool ?? false, links: links, attachments: attachments)
    }
}

private final class CompositeRoomListener: NSObject, ListenerRegistration {
    private let registrations: [ListenerRegistration]

    init(registrations: [ListenerRegistration]) {
        self.registrations = registrations
    }

    func remove() {
        registrations.forEach { $0.remove() }
    }
}

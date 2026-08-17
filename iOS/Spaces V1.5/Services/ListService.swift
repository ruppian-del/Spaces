import CryptoKit
import FirebaseAuth
import FirebaseFirestore
import FirebaseStorage
import Foundation

@MainActor
final class ListService {
    private let firestore = Firestore.firestore()
    private let storage = Storage.storage()
    private let encryption = EncryptionService()
    private let spaceService = SpaceService()

    func listenToLists(in space: Space, onUpdate: @escaping (Result<[SpaceList], Error>) -> Void) -> ListenerRegistration {
        firestore.collection("spaces").document(space.id).collection("lists")
            .order(by: "updatedAt", descending: true)
            .addSnapshotListener { [weak self] snapshot, error in
                if let error { onUpdate(.failure(error)); return }
                Task { @MainActor in
                    do {
                        guard let self else { return }
                        let documents = snapshot?.documents ?? []
                        guard !documents.isEmpty else {
                            onUpdate(.success([]))
                            return
                        }
                        let key = try await self.key(for: space.id)
                        let values = try documents.map { try self.mapList($0, spaceID: space.id, key: key) }
                        onUpdate(.success(values))
                    } catch { onUpdate(.failure(error)) }
                }
            }
    }

    func listenToItems(in space: Space, listID: String, onUpdate: @escaping (Result<[SpaceListItem], Error>) -> Void) -> ListenerRegistration {
        firestore.collection("spaces").document(space.id).collection("lists").document(listID).collection("items")
            .order(by: "order")
            .addSnapshotListener { [weak self] snapshot, error in
                if let error { onUpdate(.failure(error)); return }
                Task { @MainActor in
                    do {
                        guard let self else { return }
                        let documents = snapshot?.documents ?? []
                        guard !documents.isEmpty else {
                            onUpdate(.success([]))
                            return
                        }
                        let key = try await self.key(for: space.id)
                        onUpdate(.success(try documents.map { try self.mapItem($0, listID: listID, key: key) }))
                    } catch { onUpdate(.failure(error)) }
                }
            }
    }

    func saveList(_ list: SpaceList, in space: Space) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw ListServiceError.notSignedIn }
        let isNew = list.createdBy.isEmpty
        let permission: SpacePermission = isNew ? .createLists : (list.createdBy == uid ? .editOwnLists : .editAnyLists)
        guard await spaceService.canPerform(permission, in: space) else { throw ListServiceError.permissionDenied }
        let key = try await key(for: space.id)
        let payload = SpaceListPayload(title: list.title, sections: list.sections, links: list.links)
        let encrypted = try encryption.encryptData(JSONEncoder().encode(payload), using: key)
        let ref = firestore.collection("spaces").document(space.id).collection("lists").document(list.id)
        try await ref.setData([
            "ciphertext": encrypted.ciphertext,
            "nonce": encrypted.nonce,
            "createdBy": isNew ? uid : list.createdBy,
            "createdAt": isNew ? FieldValue.serverTimestamp() : Timestamp(date: list.createdAt),
            "updatedAt": FieldValue.serverTimestamp(),
            "encryptionVersion": "space-member-key-v1"
        ], merge: true)
        let validSectionIDs = Set(list.sections.map(\.id))
        let items = try await ref.collection("items").getDocuments()
        for item in items.documents {
            if let sectionID = item.data()["sectionId"] as? String, !validSectionIDs.contains(sectionID) {
                try await item.reference.updateData(["sectionId": NSNull()])
            }
        }
        if isNew {
            await spaceService.recordModuleActivity(
                type: .listCreated,
                in: space,
                title: "created a List",
                subtitle: list.title,
                targetID: list.id,
                targetType: .lists
            )
        }
    }

    func deleteList(_ list: SpaceList, in space: Space) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw ListServiceError.notSignedIn }
        guard await spaceService.canPerform(list.createdBy == uid ? .deleteOwnLists : .deleteAnyLists, in: space) else {
            throw ListServiceError.permissionDenied
        }
        let ref = firestore.collection("spaces").document(space.id).collection("lists").document(list.id)
        let items = try await ref.collection("items").getDocuments()
        for doc in items.documents {
            let key = try await key(for: space.id)
            if let item = try? mapItem(doc, listID: list.id, key: key) {
                for attachment in item.attachments { try? await storage.reference(withPath: attachment.storagePath).delete() }
            }
            try await doc.reference.delete()
        }
        try await ref.delete()
    }

    func saveItem(_ item: SpaceListItem, in list: SpaceList, space: Space) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw ListServiceError.notSignedIn }
        guard await spaceService.canPerform(list.createdBy == uid ? .editOwnLists : .editAnyLists, in: space) else {
            throw ListServiceError.permissionDenied
        }
        let payload = SpaceListItemPayload(
            title: item.title, notes: item.notes,
            assignedMemberIDs: Array(item.assignedMemberIDs), attachments: item.attachments,
            links: item.links
        )
        let encrypted = try encryption.encryptData(JSONEncoder().encode(payload), using: try await key(for: space.id))
        try await firestore.collection("spaces").document(space.id).collection("lists").document(list.id)
            .collection("items").document(item.id).setData([
                "ciphertext": encrypted.ciphertext, "nonce": encrypted.nonce,
                "isCompleted": item.isCompleted, "dueDate": item.dueDate.map(Timestamp.init(date:)) ?? NSNull(),
                "sectionId": item.sectionID ?? NSNull(), "order": item.order,
                "createdBy": item.createdBy.isEmpty ? uid : item.createdBy,
                "createdAt": item.createdBy.isEmpty ? FieldValue.serverTimestamp() : Timestamp(date: item.createdAt),
                "updatedAt": FieldValue.serverTimestamp(), "encryptionVersion": "space-member-key-v1"
            ], merge: true)
        let textMentions = await spaceService.mentionedMemberIDs(in: "\(item.title)\n\(item.notes)", space: space)
        let mentionedIDs = Array(Set(textMentions).union(item.assignedMemberIDs))
        await spaceService.recordTargetedModuleNotification(
            recipientIDs: mentionedIDs,
            type: .list,
            in: space,
            title: "mentioned you in a List",
            subtitle: list.title,
            targetID: list.id,
            targetType: .lists
        )
    }

    func deleteItem(_ item: SpaceListItem, list: SpaceList, space: Space) async throws {
        guard let uid = Auth.auth().currentUser?.uid,
              await spaceService.canPerform(list.createdBy == uid ? .editOwnLists : .editAnyLists, in: space)
        else { throw ListServiceError.permissionDenied }
        for attachment in item.attachments { try? await storage.reference(withPath: attachment.storagePath).delete() }
        try await firestore.collection("spaces").document(space.id).collection("lists").document(list.id)
            .collection("items").document(item.id).delete()
    }

    func uploadAttachment(data: Data, name: String, mimeType: String, isMedia: Bool, space: Space, listID: String) async throws -> SpaceListItemAttachment {
        let id = UUID().uuidString
        let encrypted = try encryption.encryptData(data, using: try await key(for: space.id))
        let path = "spaces/\(space.id)/lists/\(listID)/attachments/\(id).enc"
        try await storage.reference(withPath: path).putDataAsync(Data(base64Encoded: encrypted.ciphertext) ?? Data())
        return .init(id: id, name: name, mimeType: mimeType, storagePath: path, nonce: encrypted.nonce, isMedia: isMedia)
    }

    func downloadAttachment(_ attachment: SpaceListItemAttachment, space: Space) async throws -> Data {
        let data = try await storage.reference(withPath: attachment.storagePath).data(maxSize: 250 * 1024 * 1024)
        return try encryption.decryptData(ciphertext: data.base64EncodedString(), nonce: attachment.nonce, using: try await key(for: space.id))
    }

    private func key(for spaceID: String) async throws -> SymmetricKey {
        try await spaceService.encryptionKeyForModuleData(spaceID: spaceID)
    }

    private func mapList(_ doc: DocumentSnapshot, spaceID: String, key: SymmetricKey) throws -> SpaceList {
        let data = doc.data() ?? [:]
        let decrypted = try encryption.decryptData(
            ciphertext: data["ciphertext"] as? String ?? "", nonce: data["nonce"] as? String ?? "", using: key
        )
        let payload = try JSONDecoder().decode(SpaceListPayload.self, from: decrypted)
        return .init(
            id: doc.documentID, spaceID: spaceID, title: payload.title,
            sections: payload.sections.uniquedByID().sorted { $0.order < $1.order },
            links: payload.links.uniquedByID(),
            createdBy: data["createdBy"] as? String ?? "",
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue() ?? Date()
        )
    }

    private func mapItem(_ doc: DocumentSnapshot, listID: String, key: SymmetricKey) throws -> SpaceListItem {
        let data = doc.data() ?? [:]
        let decrypted = try encryption.decryptData(
            ciphertext: data["ciphertext"] as? String ?? "", nonce: data["nonce"] as? String ?? "", using: key
        )
        let payload = try JSONDecoder().decode(SpaceListItemPayload.self, from: decrypted)
        return .init(
            id: doc.documentID, listID: listID, title: payload.title, notes: payload.notes,
            isCompleted: data["isCompleted"] as? Bool ?? false,
            assignedMemberIDs: Set(payload.assignedMemberIDs),
            dueDate: (data["dueDate"] as? Timestamp)?.dateValue(), sectionID: data["sectionId"] as? String,
            order: data["order"] as? Int ?? 0, attachments: payload.attachments.uniquedByID(),
            links: (payload.links ?? []).uniquedByID(),
            createdBy: data["createdBy"] as? String ?? "",
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue() ?? Date()
        )
    }
}

enum ListServiceError: LocalizedError {
    case notSignedIn
    case permissionDenied
    var errorDescription: String? {
        switch self {
        case .notSignedIn: "Sign in to use Lists."
        case .permissionDenied: "You do not have permission to change this List."
        }
    }
}

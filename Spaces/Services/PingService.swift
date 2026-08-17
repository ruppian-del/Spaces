import Combine
import CryptoKit
import FirebaseCore
import FirebaseFirestore
import Foundation

@MainActor
final class PingService {
    private let authService: AuthService
    private let userProfileService: UserProfileService
    private let encryptionService: EncryptionService
    private let encryptedMediaService: EncryptedMediaService
    private let firestore: Firestore?
    private let generalEncryptionVersion = "aes-gcm-v1"
    private var verifiedPingEncryptionIDs: Set<String> = []

    init(
        authService: AuthService? = nil,
        userProfileService: UserProfileService? = nil,
        encryptionService: EncryptionService = EncryptionService(),
        encryptedMediaService: EncryptedMediaService? = nil,
        firestore: Firestore? = nil
    ) {
        self.authService = authService ?? AuthService()
        self.userProfileService = userProfileService ?? UserProfileService()
        self.encryptionService = encryptionService
        self.encryptedMediaService = encryptedMediaService ?? EncryptedMediaService(
            authService: authService,
            encryptionService: encryptionService,
            firestore: firestore
        )
        self.firestore = firestore ?? FirebaseApp.app().map { _ in Firestore.firestore() }
    }

    func currentUserID() -> String? {
        authService.currentSession()?.uid
    }

    func listenToPingsForCurrentUser(
        onUpdate: @escaping (Result<[Ping], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.failure(PingServiceError.firestoreNotConfigured))
            return nil
        }
        guard let session = authService.currentSession() else {
            onUpdate(.failure(PingServiceError.userNotSignedIn))
            return nil
        }

        return firestore.collection("pings")
            .whereField("participantIds", arrayContains: session.uid)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self else { return }
                if let error {
                    onUpdate(.failure(error))
                    return
                }
                guard let snapshot else {
                    onUpdate(.success([]))
                    return
                }

                let pings = snapshot.documents.compactMap { self.mapPing(document: $0, currentUserID: session.uid) }
                    .sorted { lhs, rhs in
                        switch (lhs.updatedAt, rhs.updatedAt) {
                        case let (left?, right?):
                            return left > right
                        case (.some, .none):
                            return true
                        case (.none, .some):
                            return false
                        case (.none, .none):
                            return lhs.id < rhs.id
                        }
                    }
                onUpdate(.success(pings))
            }
    }

    func listenToMessages(
        in ping: Ping,
        onUpdate: @escaping (Result<[SpaceMessage], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.failure(PingServiceError.firestoreNotConfigured))
            return nil
        }
        guard let session = authService.currentSession() else {
            onUpdate(.failure(PingServiceError.userNotSignedIn))
            return nil
        }

        return firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .order(by: "createdAt", descending: false)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self else { return }
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                Task { @MainActor in
                    do {
                        let pingKey = try await self.ensureEncryptionKey(pingID: ping.id)
                        try self.runMessageEncryptionSelfTestIfNeeded(pingID: ping.id, pingKey: pingKey)
                        var messages: [SpaceMessage] = []
                        for document in snapshot?.documents ?? [] {
                            let data = document.data()
                            let type = data["type"] as? String ?? ""
                            let mediaCategory = data["mediaCategory"] as? String ?? ""
                            let mediaType = data["mediaType"] as? String ?? ""
                            if type == "gif" || mediaCategory == "gif" || mediaType == "gif" {
                                print("[GIF Receive] message document received id=\(document.documentID) type=\(type) mediaCategory=\(mediaCategory) mediaType=\(mediaType)")
                            }
                            if let message = try self.mapMessage(document: document, currentUserID: session.uid, pingKey: pingKey) {
                                messages.append(message)
                            }
                        }
                        onUpdate(.success(messages))
                    } catch {
                        onUpdate(.failure(error))
                    }
                }
            }
    }

    func fetchRecentMessages(
        in ping: Ping,
        limit: Int = 20
    ) async throws -> [SpaceMessage] {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let snapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            firestore.collection("pings")
                .document(ping.id)
                .collection("messages")
                .order(by: "createdAt", descending: true)
                .limit(to: limit)
                .getDocuments { snapshot, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let snapshot {
                        continuation.resume(returning: snapshot)
                    } else {
                        continuation.resume(throwing: PingServiceError.unableToLoadPings)
                    }
                }
        }

        let pingKey = try await ensureEncryptionKey(pingID: ping.id)
        try runMessageEncryptionSelfTestIfNeeded(pingID: ping.id, pingKey: pingKey)
        return try snapshot.documents.compactMap {
            try mapMessage(document: $0, currentUserID: session.uid, pingKey: pingKey)
        }
    }

    func listenToReactions(
        for messageID: String,
        in ping: Ping,
        onUpdate: @escaping (Result<[MessageReaction], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.failure(PingServiceError.firestoreNotConfigured))
            return nil
        }

        let currentUserID = authService.currentSession()?.uid
        return firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .document(messageID)
            .collection("reactions")
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                let reactions = self.mapReactions(
                    documents: snapshot?.documents ?? [],
                    currentUserID: currentUserID
                )
                onUpdate(.success(reactions))
            }
    }

    func toggleReaction(_ emoji: String, in ping: Ping, messageID: String) async throws {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let reactionReference = firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .document(messageID)
            .collection("reactions")
            .document(session.uid)

        let snapshot = try await getDocument(reactionReference)
        let currentEmoji = (snapshot.data()?["emoji"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty

        if currentEmoji == emoji {
            try await deleteDocument(reactionReference)
        } else {
            try await setData([
                "emoji": emoji,
                "userId": session.uid,
                "createdAt": FieldValue.serverTimestamp()
            ], for: reactionReference)
        }
    }

    func sendTextMessage(
        in ping: Ping,
        text: String,
        replyContext: MessageReplyContext? = nil
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let trimmedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedText.isEmpty else {
            throw PingServiceError.invalidMessageText
        }

        let pingKey = try await ensureEncryptionKey(pingID: ping.id)
        try runMessageEncryptionSelfTestIfNeeded(pingID: ping.id, pingKey: pingKey)
        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let senderName = profile?.displayName ?? session.displayName
        let senderEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let encryptedPayload = try encryptionService.encryptText(trimmedText, using: pingKey)
        let messageReference = firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .document()

        var payload: [String: Any] = [
            "id": messageReference.documentID,
            "pingId": ping.id,
            "senderId": session.uid,
            "senderName": senderName,
            "senderEmoji": senderEmoji,
            "type": MessageType.text.rawValue,
            "encryptionVersion": generalEncryptionVersion,
            "deleted": false,
            "ciphertextBase64": encryptedPayload.ciphertext,
            "nonceBase64": encryptedPayload.nonce,
            "algorithm": "AES.GCM.256",
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "status": "sent"
        ]
        addReplyContext(replyContext, to: &payload)
        try await setData(payload, for: messageReference)

        if let recipientID = notificationRecipientID(for: ping, currentUserID: session.uid) {
            try await recordPingNotification(
                firestore: firestore,
                recipientID: recipientID,
                actorID: session.uid,
                actorName: senderName,
                actorEmoji: senderEmoji,
                title: "\(senderName) sent you a Ping",
                pingID: ping.id
            )
        } else {
            print("[PingService][Notifications] Unable to resolve ping recipient. pingID=\(ping.id) actorID=\(session.uid) participantIDs=\(ping.participantIds)")
            throw PingServiceError.unableToCreatePingNotification
        }

        try await updatePingMetadata(
            pingID: ping.id,
            at: Date(),
            previewType: MessageType.text.rawValue
        )

        return SpaceMessage(
            id: messageReference.documentID,
            spaceId: nil,
            senderId: session.uid,
            senderName: senderName,
            senderEmoji: senderEmoji,
            type: .text,
            encryptionVersion: generalEncryptionVersion,
            deleted: false,
            text: trimmedText,
            media: nil,
            createdAt: Date(),
            updatedAt: Date(),
            timestamp: Self.messageTimestampFormatter.string(from: Date()),
            isOutgoing: true,
            status: "sent",
            deliveryStatus: "Sent",
            isEdited: false,
            replyContext: replyContext
        )
    }

    func editTextMessage(
        in ping: Ping,
        messageID: String,
        newText: String
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let trimmedText = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedText.isEmpty else {
            throw PingServiceError.invalidMessageText
        }

        let messageReference = firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .document(messageID)
        let snapshot = try await getDocument(messageReference)
        guard snapshot.exists, let data = snapshot.data() else {
            throw PingServiceError.messageNotFound
        }

        let senderID = data["senderId"] as? String
        let type = (data["type"] as? String).flatMap(MessageType.init(rawValue:)) ?? .text
        let deleted = data["deleted"] as? Bool ?? false
        guard senderID == session.uid, type == .text, !deleted else {
            throw PingServiceError.messagePermissionDenied
        }

        let pingKey = try await ensureEncryptionKey(pingID: ping.id)
        try runMessageEncryptionSelfTestIfNeeded(pingID: ping.id, pingKey: pingKey)
        let encryptedPayload = try encryptionService.encryptText(trimmedText, using: pingKey)
        try await updateData([
            "ciphertextBase64": encryptedPayload.ciphertext,
            "nonceBase64": encryptedPayload.nonce,
            "edited": true,
            "editedAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: messageReference)
        try await updatePingMetadata(
            pingID: ping.id,
            at: Date(),
            previewType: MessageType.text.rawValue
        )

        return SpaceMessage(
            id: data["id"] as? String ?? messageID,
            spaceId: nil,
            senderId: senderID,
            senderName: data["senderName"] as? String ?? session.displayName,
            senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            type: .text,
            encryptionVersion: inferredEncryptionVersion(from: data),
            deleted: false,
            text: trimmedText,
            media: nil,
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            updatedAt: Date(),
            timestamp: Self.messageTimestampFormatter.string(from: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date()),
            isOutgoing: true,
            status: data["status"] as? String,
            deliveryStatus: deliveryStatus(for: data["status"] as? String, isOutgoing: true),
            isEdited: true,
            editedAt: Date(),
            replyContext: mappedReplyContext(from: data),
            reactions: []
        )
    }

    func sendImageMessage(
        in ping: Ping,
        imageData: Data,
        caption: String?,
        mediaCategory: String = "photo",
        previewImageData: Data? = nil,
        mimeType: String = "image/jpeg",
        replyContext: MessageReplyContext? = nil
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let senderName = profile?.displayName ?? session.displayName
        let senderEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let resolvedMediaType = MediaType(rawValue: mediaCategory) ?? .photo
        let resolvedMessageType: MessageType = {
            switch resolvedMediaType {
            case .gif:
                return .gif
            case .meme:
                return .meme
            default:
                return .image
            }
        }()

        let pingKey = try await ensureEncryptionKey(pingID: ping.id)
        try runMessageEncryptionSelfTestIfNeeded(pingID: ping.id, pingKey: pingKey)
        let trimmedCaption = caption?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let encryptedCaption = try trimmedCaption.map {
            try encryptionService.encryptText($0, using: pingKey)
        }

        let mediaID = firestore.collection("pings").document(ping.id).collection("messages").document().documentID
        let messageReference = firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .document(mediaID)
        let uploadResult: EncryptedMediaUploadResult
        if resolvedMediaType == .gif || resolvedMediaType == .meme {
            if resolvedMediaType == .gif {
                print("[GIF] Preparing upload")
                print("[GIF] Upload started")
            }
            uploadResult = try await encryptedMediaService.uploadAnimatedImage(
                spaceID: "ping:\(ping.id)",
                mediaID: mediaID,
                originalData: imageData,
                previewImageData: previewImageData ?? imageData,
                mediaType: resolvedMediaType,
                mimeType: mimeType,
                uploadedBy: session.uid
            )
            if resolvedMediaType == .gif {
                print("[GIF] Upload finished")
            }
        } else {
            uploadResult = try await encryptedMediaService.uploadImage(
                spaceID: "ping:\(ping.id)",
                mediaID: mediaID,
                originalData: imageData,
                mediaType: resolvedMediaType,
                mimeType: mimeType,
                uploadedBy: session.uid
            )
        }
        let metadata = uploadResult.metadata
        print("[PingService][ImageMessage] mediaSelected=true imageDataByteCount=\(imageData.count) thumbnailByteCount=\(previewImageData?.count ?? imageData.count) mediaCategory=\(mediaCategory)")
        print("[PingService][ImageMessage] uploadPath=\(metadata.storagePath)")
        if let thumbnailStoragePath = metadata.thumbnailStoragePath {
            print("[PingService][ImageMessage] uploadPath=\(thumbnailStoragePath)")
        }

        var messageData: [String: Any] = [
            "id": mediaID,
            "mediaId": metadata.mediaId,
            "pingId": ping.id,
            "senderId": session.uid,
            "senderName": senderName,
            "senderEmoji": senderEmoji,
            "type": resolvedMessageType.rawValue,
            "mediaCategory": mediaCategory,
            "mediaType": metadata.mediaType.rawValue,
            "storagePath": metadata.storagePath,
            "nonce": metadata.nonce,
            "mediaStoragePath": metadata.storagePath,
            "mediaNonceBase64": metadata.nonce,
            "encryptionVersion": metadata.encryptionVersion,
            "mimeType": metadata.mimeType,
            "fileSize": metadata.fileSize,
            "uploadedBy": metadata.uploadedBy,
            "deleted": false,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "status": "sent"
        ]
        if let thumbnailStoragePath = metadata.thumbnailStoragePath {
            messageData["thumbnailStoragePath"] = thumbnailStoragePath
        }
        if let thumbnailNonce = metadata.thumbnailNonce {
            messageData["thumbnailNonce"] = thumbnailNonce
            messageData["thumbnailNonceBase64"] = thumbnailNonce
        }
        if let width = metadata.width {
            messageData["width"] = width
        }
        if let height = metadata.height {
            messageData["height"] = height
        }
        if let encryptedCaption {
            messageData["captionCiphertextBase64"] = encryptedCaption.ciphertext
            messageData["captionNonceBase64"] = encryptedCaption.nonce
        }
        addReplyContext(replyContext, to: &messageData)
        if resolvedMediaType == .gif {
            print("[GIF] Firestore write starting")
        }
        do {
            try await setData(messageData, for: messageReference)
            if resolvedMediaType == .gif {
                print("[GIF] Firestore write succeeded")
            }
        } catch {
            if resolvedMediaType == .gif {
                print("[GIF] Firestore write failed: \(error)")
            }
            throw error
        }
        print("[PingService][ImageMessage] messageDocumentCreated=true messageId=\(mediaID)")

        if let recipientID = notificationRecipientID(for: ping, currentUserID: session.uid) {
            try await recordPingNotification(
                firestore: firestore,
                recipientID: recipientID,
                actorID: session.uid,
                actorName: senderName,
                actorEmoji: senderEmoji,
                title: "\(senderName) sent you a Ping",
                pingID: ping.id
            )
        }

        try await updatePingMetadata(
            pingID: ping.id,
            at: Date(),
            previewType: resolvedMessageType.rawValue
        )

        return SpaceMessage(
            id: mediaID,
            spaceId: "ping:\(ping.id)",
            senderId: session.uid,
            senderName: senderName,
            senderEmoji: senderEmoji,
            type: resolvedMessageType,
            encryptionVersion: metadata.encryptionVersion,
            deleted: false,
            text: nil,
            media: SpaceMedia(
                id: mediaID,
                spaceID: "ping:\(ping.id)",
                type: resolvedMessageType == .gif ? .gif : .image,
                mediaCategory: mediaCategory,
                mediaType: metadata.mediaType,
                placeholderImageName: metadata.mediaType.defaultPlaceholderImageName,
                caption: trimmedCaption,
                senderName: senderName,
                timestamp: Self.messageTimestampFormatter.string(from: Date()),
                metadata: metadata,
                mediaStoragePath: metadata.storagePath,
                thumbnailStoragePath: metadata.thumbnailStoragePath,
                mediaNonceBase64: metadata.nonce,
                thumbnailNonceBase64: metadata.thumbnailNonce
            ),
            createdAt: Date(),
            updatedAt: Date(),
            timestamp: Self.messageTimestampFormatter.string(from: Date()),
            isOutgoing: true,
            status: "sent",
            deliveryStatus: "Sent",
            isEdited: false,
            replyContext: replyContext
        )
    }

    func sendVideoMessage(
        in ping: Ping,
        videoData: Data,
        caption: String?,
        mimeType: String,
        replyContext: MessageReplyContext? = nil
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let senderName = profile?.displayName ?? session.displayName
        let senderEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let pingKey = try await ensureEncryptionKey(pingID: ping.id)
        try runMessageEncryptionSelfTestIfNeeded(pingID: ping.id, pingKey: pingKey)
        let trimmedCaption = caption?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let encryptedCaption = try trimmedCaption.map {
            try encryptionService.encryptText($0, using: pingKey)
        }

        let mediaID = firestore.collection("pings").document(ping.id).collection("messages").document().documentID
        let messageReference = firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .document(mediaID)
        let uploadResult = try await encryptedMediaService.uploadVideo(
            spaceID: "ping:\(ping.id)",
            mediaID: mediaID,
            originalData: videoData,
            mimeType: mimeType,
            uploadedBy: session.uid
        )
        let metadata = uploadResult.metadata
        print("[PingService][VideoMessage] mediaSelected=true videoDataByteCount=\(videoData.count)")
        print("[PingService][VideoMessage] uploadPath=\(metadata.storagePath)")
        if let thumbnailStoragePath = metadata.thumbnailStoragePath {
            print("[PingService][VideoMessage] uploadPath=\(thumbnailStoragePath)")
        }

        var messageData: [String: Any] = [
            "id": mediaID,
            "mediaId": metadata.mediaId,
            "pingId": ping.id,
            "senderId": session.uid,
            "senderName": senderName,
            "senderEmoji": senderEmoji,
            "type": MessageType.video.rawValue,
            "mediaCategory": "video",
            "mediaType": metadata.mediaType.rawValue,
            "storagePath": metadata.storagePath,
            "nonce": metadata.nonce,
            "mediaStoragePath": metadata.storagePath,
            "mediaNonceBase64": metadata.nonce,
            "encryptionVersion": metadata.encryptionVersion,
            "mimeType": metadata.mimeType,
            "fileSize": metadata.fileSize,
            "uploadedBy": metadata.uploadedBy,
            "deleted": false,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "status": "sent"
        ]
        if let thumbnailStoragePath = metadata.thumbnailStoragePath {
            messageData["thumbnailStoragePath"] = thumbnailStoragePath
        }
        if let thumbnailNonce = metadata.thumbnailNonce {
            messageData["thumbnailNonce"] = thumbnailNonce
            messageData["thumbnailNonceBase64"] = thumbnailNonce
        }
        if let width = metadata.width {
            messageData["width"] = width
        }
        if let height = metadata.height {
            messageData["height"] = height
        }
        if let duration = metadata.duration {
            messageData["duration"] = duration
        }
        if let encryptedCaption {
            messageData["captionCiphertextBase64"] = encryptedCaption.ciphertext
            messageData["captionNonceBase64"] = encryptedCaption.nonce
        }
        addReplyContext(replyContext, to: &messageData)
        try await setData(messageData, for: messageReference)
        print("[PingService][VideoMessage] messageDocumentCreated=true messageId=\(mediaID)")

        if let recipientID = notificationRecipientID(for: ping, currentUserID: session.uid) {
            try await recordPingNotification(
                firestore: firestore,
                recipientID: recipientID,
                actorID: session.uid,
                actorName: senderName,
                actorEmoji: senderEmoji,
                title: "\(senderName) sent you a Ping",
                pingID: ping.id
            )
        }

        try await updatePingMetadata(
            pingID: ping.id,
            at: Date(),
            previewType: MessageType.video.rawValue
        )

        return SpaceMessage(
            id: mediaID,
            spaceId: "ping:\(ping.id)",
            senderId: session.uid,
            senderName: senderName,
            senderEmoji: senderEmoji,
            type: .video,
            encryptionVersion: metadata.encryptionVersion,
            deleted: false,
            text: nil,
            media: SpaceMedia(
                id: mediaID,
                spaceID: "ping:\(ping.id)",
                type: .video,
                mediaCategory: "video",
                mediaType: metadata.mediaType,
                placeholderImageName: metadata.mediaType.defaultPlaceholderImageName,
                caption: trimmedCaption,
                senderName: senderName,
                timestamp: Self.messageTimestampFormatter.string(from: Date()),
                metadata: metadata,
                mediaStoragePath: metadata.storagePath,
                thumbnailStoragePath: metadata.thumbnailStoragePath,
                mediaNonceBase64: metadata.nonce,
                thumbnailNonceBase64: metadata.thumbnailNonce
            ),
            createdAt: Date(),
            updatedAt: Date(),
            timestamp: Self.messageTimestampFormatter.string(from: Date()),
            isOutgoing: true,
            status: "sent",
            deliveryStatus: "Sent",
            isEdited: false,
            replyContext: replyContext
        )
    }

    func deleteMessage(in ping: Ping, messageID: String) async throws {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let messageReference = firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .document(messageID)
        let snapshot = try await getDocument(messageReference)
        guard snapshot.exists, let data = snapshot.data() else {
            throw PingServiceError.messageNotFound
        }
        guard (data["senderId"] as? String) == session.uid else {
            throw PingServiceError.messagePermissionDenied
        }

        try await updateData([
            "deleted": true,
            "deletedAt": FieldValue.serverTimestamp(),
            "deletedBy": session.uid,
            "text": "",
            "ciphertextBase64": "",
            "nonceBase64": "",
            "updatedAt": FieldValue.serverTimestamp()
        ], for: messageReference)
    }

    func fetchAvailableParticipants() async throws -> [PingParticipant] {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let spacesSnapshot = try await getDocuments(
            firestore.collection("spaces").whereField("memberIds", arrayContains: session.uid)
        )

        var participantsByID: [String: PingParticipant] = [:]
        for spaceDocument in spacesSnapshot.documents {
            let membersSnapshot = try await getDocuments(
                firestore.collection("spaces").document(spaceDocument.documentID).collection("members")
            )
            for memberDocument in membersSnapshot.documents {
                let data = memberDocument.data()
                let userID = (data["userId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? memberDocument.documentID
                guard userID != session.uid else { continue }
                let displayName = (data["displayName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "Member"
                let emojiAvatar = (data["emojiAvatar"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🙂"
                participantsByID[userID] = PingParticipant(
                    id: userID,
                    displayName: displayName,
                    emojiAvatar: emojiAvatar
                )
            }
        }

        return participantsByID.values.sorted {
            $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
    }

    func createOrOpenPing(with participant: PingParticipant) async throws -> Ping {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let existingSnapshot = try await getDocuments(
            firestore.collection("pings").whereField("participantIds", arrayContains: session.uid)
        )
        if let existing = existingSnapshot.documents
            .compactMap({ mapPing(document: $0, currentUserID: session.uid) })
            .first(where: {
                let ids = Set($0.participantIds)
                return ids == Set([session.uid, participant.id])
            }) {
            return existing
        }

        let currentProfile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let currentName = currentProfile?.displayName ?? session.displayName
        let currentEmoji = currentProfile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let pingReference = firestore.collection("pings").document()
        let ordered = [
            (id: session.uid, name: currentName, emoji: currentEmoji),
            (id: participant.id, name: participant.displayName, emoji: participant.emojiAvatar)
        ].sorted { $0.id < $1.id }

        let now = Date()
        let payload: [String: Any] = [
            "id": pingReference.documentID,
            "participantIds": ordered.map { $0.id },
            "participantNames": ordered.map { $0.name },
            "participantEmojis": ordered.map { $0.emoji },
            "lastMessageAt": FieldValue.serverTimestamp(),
            "lastMessagePreviewType": "",
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp()
        ]
        try await setData(payload, for: pingReference)
        try await setData([
            "keyVersion": generalEncryptionVersion,
            "keyBase64": encryptionService.generateSpaceKeyBase64(),
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "createdBy": session.uid
        ], for: firestore.collection("pings").document(pingReference.documentID).collection("encryption").document("key"))

        return Ping(
            id: pingReference.documentID,
            participantIds: ordered.map { $0.id },
            participantNames: ordered.map { $0.name },
            participantEmojis: ordered.map { $0.emoji },
            lastMessageAt: now,
            lastMessagePreviewType: nil,
            createdAt: now,
            updatedAt: now,
            unreadCount: 0
        )
    }

    private func mapPing(document: DocumentSnapshot, currentUserID: String) -> Ping? {
        guard let data = document.data() else { return nil }
        let participantIds = data["participantIds"] as? [String] ?? []
        let participantNames = data["participantNames"] as? [String] ?? []
        let participantEmojis = data["participantEmojis"] as? [String] ?? []
        guard participantIds.count == 2 else { return nil }

        return Ping(
            id: (data["id"] as? String) ?? document.documentID,
            participantIds: participantIds,
            participantNames: participantNames,
            participantEmojis: participantEmojis,
            lastMessageAt: (data["lastMessageAt"] as? Timestamp)?.dateValue(),
            lastMessagePreviewType: (data["lastMessagePreviewType"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue(),
            unreadCount: 0
        )
    }

    private func mapMessage(
        document: DocumentSnapshot,
        currentUserID: String?,
        pingKey: SymmetricKey
    ) throws -> SpaceMessage? {
        guard let data = document.data() else { return nil }
        let type = (data["type"] as? String).flatMap(MessageType.init(rawValue:)) ?? .text
        let createdAt = (data["createdAt"] as? Timestamp)?.dateValue()
        let updatedAt = (data["updatedAt"] as? Timestamp)?.dateValue()
        let senderID = data["senderId"] as? String
        let status = data["status"] as? String
        let isOutgoing = senderID == currentUserID
        let deleted = data["deleted"] as? Bool ?? false
        let isEdited = data["edited"] as? Bool ?? false
        let editedAt = (data["editedAt"] as? Timestamp)?.dateValue()
        let replyContext = mappedReplyContext(from: data)
        let encryptionVersion = inferredEncryptionVersion(from: data)

        if deleted {
            return SpaceMessage(
                id: data["id"] as? String ?? document.documentID,
                senderId: senderID,
                senderName: data["senderName"] as? String ?? "User",
                senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                type: type,
                encryptionVersion: encryptionVersion,
                deleted: true,
                text: nil,
                media: nil,
                createdAt: createdAt,
                updatedAt: updatedAt,
                timestamp: Self.messageTimestampFormatter.string(from: createdAt ?? Date()),
                isOutgoing: isOutgoing,
                status: status,
                deliveryStatus: deliveryStatus(for: status, isOutgoing: isOutgoing),
                isEdited: isEdited,
                editedAt: editedAt,
                replyContext: replyContext,
                reactions: []
            )
        }

        if [.image, .video, .meme, .gif, .screenshot].contains(type) {
            let caption: String?
            if let captionCiphertext = (data["captionCiphertextBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
               let captionNonce = (data["captionNonceBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
                do {
                    caption = try encryptionService.decryptText(
                        ciphertext: captionCiphertext,
                        nonce: captionNonce,
                        using: pingKey
                    )
                } catch {
                    if type == .gif {
                        print("[GIF Receive] caption decrypt failed id=\(document.documentID) error=\(error)")
                    }
                    caption = nil
                }
            } else {
                caption = nil
            }

            let mediaCategory = (data["mediaCategory"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            let resolvedMediaType = (data["mediaType"] as? String)
                .flatMap(MediaType.init(rawValue:))
                ?? MediaType(rawValue: mediaCategory ?? "")
                ?? (type == .video ? .video : (type == .meme ? .meme : (type == .gif ? .gif : .photo)))
            let pingIdentifier = (data["pingId"] as? String) ?? document.reference.parent.parent?.documentID ?? ""
            let metadata = EncryptedMediaMetadata(
                mediaId: (data["mediaId"] as? String) ?? (data["id"] as? String ?? document.documentID),
                mediaType: resolvedMediaType,
                storagePath: (data["storagePath"] as? String) ?? (data["mediaStoragePath"] as? String) ?? "",
                thumbnailStoragePath: (data["thumbnailStoragePath"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                encryptionVersion: encryptionVersion,
                nonce: (data["nonce"] as? String) ?? (data["mediaNonceBase64"] as? String) ?? "",
                thumbnailNonce: (data["thumbnailNonce"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                    ?? (data["thumbnailNonceBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                mimeType: (data["mimeType"] as? String) ?? "image/jpeg",
                fileSize: data["fileSize"] as? Int ?? 0,
                width: data["width"] as? Int,
                height: data["height"] as? Int,
                duration: data["duration"] as? Double,
                createdAt: createdAt,
                uploadedBy: (data["uploadedBy"] as? String) ?? (senderID ?? "")
            )
            if type == .gif || mediaCategory == "gif" || resolvedMediaType == .gif {
                print("[GIF Receive] message model mapped id=\(document.documentID) type=\(type.rawValue) mediaCategory=\(mediaCategory ?? "") mediaType=\(resolvedMediaType.rawValue) storagePath=\(metadata.storagePath) thumbnailStoragePath=\(metadata.thumbnailStoragePath ?? "")")
            }

            return SpaceMessage(
                id: data["id"] as? String ?? document.documentID,
                spaceId: pingIdentifier.isEmpty ? nil : "ping:\(pingIdentifier)",
                senderId: senderID,
                senderName: data["senderName"] as? String ?? "User",
                senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                type: type,
                encryptionVersion: encryptionVersion,
                deleted: false,
                text: nil,
                media: SpaceMedia(
                    id: data["id"] as? String ?? document.documentID,
                    spaceID: pingIdentifier.isEmpty ? nil : "ping:\(pingIdentifier)",
                    type: type,
                    mediaCategory: mediaCategory,
                    mediaType: resolvedMediaType,
                    placeholderImageName: resolvedMediaType.defaultPlaceholderImageName,
                    caption: caption,
                    senderName: data["senderName"] as? String ?? "User",
                    timestamp: Self.messageTimestampFormatter.string(from: createdAt ?? Date()),
                    metadata: metadata,
                    mediaStoragePath: metadata.storagePath,
                    thumbnailStoragePath: metadata.thumbnailStoragePath,
                    mediaNonceBase64: metadata.nonce,
                    thumbnailNonceBase64: metadata.thumbnailNonce
                ),
                createdAt: createdAt,
                updatedAt: updatedAt,
                timestamp: Self.messageTimestampFormatter.string(from: createdAt ?? Date()),
                isOutgoing: isOutgoing,
                status: status,
                deliveryStatus: deliveryStatus(for: status, isOutgoing: isOutgoing),
                isEdited: isEdited,
                editedAt: editedAt,
                replyContext: replyContext,
                reactions: []
            )
        }

        guard type == .text else { return nil }

        let resolvedText: String
        switch encryptionVersion {
        case generalEncryptionVersion:
            guard
                let ciphertext = (data["ciphertextBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                let nonce = (data["nonceBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            else {
                resolvedText = "Unable to decrypt message"
                break
            }
            do {
                resolvedText = try encryptionService.decryptText(
                    ciphertext: ciphertext,
                    nonce: nonce,
                    using: pingKey
                )
            } catch {
                print("[PingService][DecryptFailure] messageId=\(data["id"] as? String ?? document.documentID) reason=\(error.localizedDescription)")
                resolvedText = "Unable to decrypt message"
            }
        default:
            return nil
        }

        return SpaceMessage(
            id: data["id"] as? String ?? document.documentID,
            senderId: senderID,
            senderName: data["senderName"] as? String ?? "User",
            senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            type: .text,
            encryptionVersion: encryptionVersion,
            deleted: false,
            text: resolvedText,
            media: nil,
            createdAt: createdAt,
            updatedAt: updatedAt,
            timestamp: Self.messageTimestampFormatter.string(from: createdAt ?? Date()),
            isOutgoing: isOutgoing,
            status: status,
            deliveryStatus: deliveryStatus(for: status, isOutgoing: isOutgoing),
            isEdited: isEdited,
            editedAt: editedAt,
            replyContext: replyContext,
            reactions: []
        )
    }

    private func ensureEncryptionKey(pingID: String) async throws -> SymmetricKey {
        if let cachedKey = encryptionService.cachedSpaceKey(for: "ping:\(pingID)") {
            return cachedKey
        }
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw PingServiceError.userNotSignedIn
        }

        let reference = firestore.collection("pings").document(pingID).collection("encryption").document("key")
        if let snapshot = try? await getDocument(reference),
           snapshot.exists,
           let data = snapshot.data(),
           let keyBase64 = (data["keyBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
            let key = try encryptionService.decodeSpaceKey(keyBase64)
            encryptionService.cacheSpaceKey(key, for: "ping:\(pingID)")
            return key
        }

        let keyBase64 = encryptionService.generateSpaceKeyBase64()
        try await setData([
            "keyVersion": generalEncryptionVersion,
            "keyBase64": keyBase64,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "createdBy": session.uid
        ], for: reference)
        let key = try encryptionService.decodeSpaceKey(keyBase64)
        encryptionService.cacheSpaceKey(key, for: "ping:\(pingID)")
        return key
    }

    private func updatePingMetadata(
        pingID: String,
        at date: Date,
        previewType: String
    ) async throws {
        guard let firestore else {
            throw PingServiceError.firestoreNotConfigured
        }
        try await updateData([
            "lastMessageAt": Timestamp(date: date),
            "lastMessagePreviewType": previewType,
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("pings").document(pingID))
    }

    private func recordPingNotification(
        firestore: Firestore,
        recipientID: String,
        actorID: String,
        actorName: String,
        actorEmoji: String?,
        title: String,
        pingID: String
    ) async throws {
        guard recipientID != actorID else {
            throw PingServiceError.unableToCreatePingNotification
        }

        for attempt in 1...2 {
            let notificationReference = firestore.collection("notifications").document()
            var payload: [String: Any] = [
                "id": notificationReference.documentID,
                "recipientId": recipientID,
                "actorId": actorID,
                "actorName": actorName,
                "spaceId": "",
                "spaceName": "Ping",
                "spaceEmoji": "💬",
                "type": SpaceNotificationType.pingMessage.rawValue,
                "title": title,
                "subtitle": "New encrypted message",
                "targetId": pingID,
                "targetType": "ping",
                "createdAt": FieldValue.serverTimestamp(),
                "read": false,
                "delivered": false
            ]
            if let actorEmoji, !actorEmoji.isEmpty {
                payload["actorEmoji"] = actorEmoji
            }

            do {
                try await setData(payload, for: notificationReference)
                return
            } catch {
                if attempt == 1 {
                    try? await Task.sleep(nanoseconds: 250_000_000)
                } else {
                    print("[PingService] Failed to create ping notification: \(error.localizedDescription)")
                    throw PingServiceError.unableToCreatePingNotification
                }
            }
        }
    }

    private func notificationRecipientID(for ping: Ping, currentUserID: String) -> String? {
        if let recipient = ping.otherParticipant(for: currentUserID) {
            return recipient.id.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        }

        return ping.participantIds
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first(where: { !$0.isEmpty && $0 != currentUserID })
    }

    private func addReplyContext(_ replyContext: MessageReplyContext?, to data: inout [String: Any]) {
        guard let replyContext else { return }
        data["replyToMessageId"] = replyContext.messageId
        data["replyToSenderName"] = replyContext.senderName
        data["replyToType"] = replyContext.type
        data["replyPreview"] = replyContext.preview
    }

    private func mappedReplyContext(from data: [String: Any]) -> MessageReplyContext? {
        guard
            let messageId = (data["replyToMessageId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            let senderName = (data["replyToSenderName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            let type = (data["replyToType"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            let preview = (data["replyPreview"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        else {
            return nil
        }

        return MessageReplyContext(
            messageId: messageId,
            senderName: senderName,
            type: type,
            preview: preview
        )
    }

    private func inferredEncryptionVersion(from data: [String: Any]) -> String {
        (data["encryptionVersion"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? generalEncryptionVersion
    }

    private func deliveryStatus(for status: String?, isOutgoing: Bool) -> String? {
        guard isOutgoing else { return nil }
        switch status {
        case "sent":
            return "Sent"
        case "delivered":
            return "Delivered"
        case "seen":
            return "Seen"
        default:
            return nil
        }
    }

    private func mapReactions(
        documents: [DocumentSnapshot],
        currentUserID: String?
    ) -> [MessageReaction] {
        var countsByEmoji: [String: Int] = [:]
        var selectedEmoji: String?
        let defaultOrder = ["👍", "❤️", "😂", "😮", "😢", "👎"]

        for document in documents {
            guard let data = document.data() else { continue }
            guard let emoji = (data["emoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
                continue
            }
            countsByEmoji[emoji, default: 0] += 1
            let userID = (data["userId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? document.documentID
            if userID == currentUserID {
                selectedEmoji = emoji
            }
        }

        return countsByEmoji.map { emoji, count in
            MessageReaction(
                emoji: emoji,
                count: count,
                isSelectedByCurrentUser: emoji == selectedEmoji
            )
        }
        .sorted { lhs, rhs in
            let leftIndex = defaultOrder.firstIndex(of: lhs.emoji) ?? Int.max
            let rightIndex = defaultOrder.firstIndex(of: rhs.emoji) ?? Int.max
            if leftIndex != rightIndex { return leftIndex < rightIndex }
            if lhs.count != rhs.count { return lhs.count > rhs.count }
            return lhs.emoji < rhs.emoji
        }
    }

    private func getDocument(_ reference: DocumentReference) async throws -> DocumentSnapshot {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<DocumentSnapshot, Error>) in
            reference.getDocument { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: PingServiceError.unableToLoadPings)
                }
            }
        }
    }

    private func getDocuments(_ query: Query) async throws -> QuerySnapshot {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            query.getDocuments { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: PingServiceError.unableToLoadPings)
                }
            }
        }
    }

    private func setData(_ data: [String: Any], for reference: DocumentReference) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            reference.setData(data) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func updateData(_ data: [AnyHashable: Any], for reference: DocumentReference) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            reference.updateData(data) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func deleteDocument(_ reference: DocumentReference) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            reference.delete { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func runMessageEncryptionSelfTestIfNeeded(pingID: String, pingKey: SymmetricKey) throws {
#if DEBUG
        guard !verifiedPingEncryptionIDs.contains(pingID) else { return }
        let plaintext = "hello encryption test"
        do {
            let encryptedPayload = try encryptionService.encryptText(plaintext, using: pingKey)
            let decryptedText = try encryptionService.decryptText(
                ciphertext: encryptedPayload.ciphertext,
                nonce: encryptedPayload.nonce,
                using: pingKey
            )
            let matchesPlaintext = decryptedText == plaintext
            guard matchesPlaintext else {
                throw PingServiceError.localEncryptionSelfTestFailed
            }
            verifiedPingEncryptionIDs.insert(pingID)
        } catch {
            throw PingServiceError.localEncryptionSelfTestFailed
        }
#endif
    }

    private static let messageTimestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "h:mm a"
        return formatter
    }()
}

enum PingServiceError: LocalizedError {
    case firestoreNotConfigured
    case userNotSignedIn
    case unableToLoadPings
    case invalidMessageText
    case messageNotFound
    case messagePermissionDenied
    case localEncryptionSelfTestFailed
    case unableToCreatePingNotification

    var errorDescription: String? {
        switch self {
        case .firestoreNotConfigured:
            return "Firestore is not configured yet."
        case .userNotSignedIn:
            return "Sign in before using Ping."
        case .unableToLoadPings:
            return "Unable to load your Pings right now."
        case .invalidMessageText:
            return "Enter a message before sending."
        case .messageNotFound:
            return "That message could not be found."
        case .messagePermissionDenied:
            return "You can only manage your own messages."
        case .localEncryptionSelfTestFailed:
            return "Local encryption self-test failed."
        case .unableToCreatePingNotification:
            return "The Ping message was sent, but Spaces could not create the notification."
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

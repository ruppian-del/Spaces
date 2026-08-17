import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import UIKit
import UniformTypeIdentifiers

@MainActor
final class SpaceService {
    private let authService: AuthService
    private let userProfileService: UserProfileService
    private let encryptionService: EncryptionService
    private let encryptedMediaService: EncryptedMediaService
    private let firestore: Firestore?
    private var verifiedMessageEncryptionSpaceIDs: Set<String> = []
    private let generalEncryptionVersion = "aes-gcm-v1"
    private let defaultReactionOrder = ["👍", "❤️", "😂", "😮", "😢", "👎"]

    init() {
        self.authService = AuthService()
        self.encryptionService = EncryptionService()
        let firestore = FirebaseApp.app().map { _ in Firestore.firestore() }
        self.firestore = firestore
        self.encryptedMediaService = EncryptedMediaService(authService: self.authService, encryptionService: self.encryptionService, firestore: firestore)
        self.userProfileService = UserProfileService(firestore: firestore, encryptionService: self.encryptionService)
    }

    init(
        authService: AuthService,
        userProfileService: UserProfileService,
        encryptionService: EncryptionService = EncryptionService(),
        firestore: Firestore?
    ) {
        self.authService = authService
        self.userProfileService = userProfileService
        self.encryptionService = encryptionService
        self.firestore = firestore
        self.encryptedMediaService = EncryptedMediaService(authService: authService, encryptionService: encryptionService, firestore: firestore)
    }

    func listenToSpacesForCurrentUser(onUpdate: @escaping (Result<[Space], Error>) -> Void) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success(isRunningPreview ? MockData.spaces : []))
            return nil
        }
        guard let session = authService.currentSession() else {
            onUpdate(.success(isRunningPreview ? MockData.spaces : []))
            return nil
        }

        return firestore.collection("spaces")
            .whereField("memberIds", arrayContains: session.uid)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                guard let snapshot else {
                    onUpdate(.failure(SpaceServiceError.unableToLoadSpaces))
                    return
                }

                let spaces = snapshot.documents.compactMap(self.mapSpace(document:))
                onUpdate(.success(spaces))
            }
    }

    func listenToMembers(
        for space: Space,
        onUpdate: @escaping (Result<[SpaceMember], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success(MockData.spaceMembers(for: space)))
            return nil
        }

        return firestore.collection("spaces")
            .document(space.id)
            .collection("members")
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                guard let snapshot else {
                    onUpdate(.failure(SpaceServiceError.unableToLoadMembers))
                    return
                }

                let members = snapshot.documents.compactMap(self.mapMember(document:))
                    .uniquedByID()
                    .sorted { lhs, rhs in
                        if lhs.role == rhs.role {
                            return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
                        }
                        return lhs.role.sortOrder < rhs.role.sortOrder
                    }
                onUpdate(.success(members))
            }
    }

    func listenToFolders(
        in space: Space,
        onUpdate: @escaping (Result<[SpaceFolder], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }

        return firestore.collection("spaces")
            .document(space.id)
            .collection("fileFolders")
            .order(by: "createdAt", descending: false)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                let folders = snapshot?.documents.compactMap(self.mapFolder(document:)) ?? []
                onUpdate(.success(folders))
            }
    }

    func listenToFiles(
        in space: Space,
        onUpdate: @escaping (Result<[SpaceFileItem], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }

        return firestore.collection("spaces")
            .document(space.id)
            .collection("files")
            .order(by: "createdAt", descending: true)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                Task { @MainActor in
                    do {
                        let spaceKey = try await self.ensureGeneralEncryptionKey(in: space)
                        let documents = snapshot?.documents ?? []
                        let files = try documents.compactMap { document in
                            try self.mapFile(document: document, spaceKey: spaceKey)
                        }.filter { !$0.deleted }
                        onUpdate(.success(files))
                    } catch {
                        onUpdate(.failure(error))
                    }
                }
            }
    }

    func listenToPolls(
        in space: Space,
        onUpdate: @escaping (Result<[SpacePoll], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }

        return firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .order(by: "createdAt", descending: true)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                let polls = (snapshot?.documents.compactMap(self.mapPoll(document:)) ?? [])
                    .filter { !$0.deleted }
                onUpdate(.success(polls))
            }
    }

    func listenToPollVotes(
        in space: Space,
        pollID: String,
        onUpdate: @escaping (Result<[SpacePollVote], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }

        return firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .document(pollID)
            .collection("votes")
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                let votes = snapshot?.documents.compactMap(self.mapPollVote(document:)) ?? []
                onUpdate(.success(votes))
            }
    }

    func listenToEvents(
        in space: Space,
        onUpdate: @escaping (Result<[SpaceEvent], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }

        return firestore.collection("spaces")
            .document(space.id)
            .collection("events")
            .order(by: "startDate", descending: false)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                let events = (snapshot?.documents.compactMap(self.mapEvent(document:)) ?? [])
                    .filter { !$0.deleted }
                onUpdate(.success(events))
            }
    }

    func fetchFiles(in space: Space) async throws -> [SpaceFileItem] {
        guard let firestore else { return [] }
        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        let snapshot = try await getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("files")
                .order(by: "createdAt", descending: true)
        )
        return try snapshot.documents.compactMap { document in
            try mapFile(document: document, spaceKey: spaceKey)
        }.filter { !$0.deleted }
    }

    func fetchPolls(in space: Space) async throws -> [SpacePoll] {
        guard let firestore else { return [] }
        let snapshot = try await getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("polls")
                .order(by: "createdAt", descending: true)
        )
        return snapshot.documents.compactMap(mapPoll(document:)).filter { !$0.deleted }
    }

    func fetchEvents(in space: Space) async throws -> [SpaceEvent] {
        guard let firestore else { return [] }
        let snapshot = try await getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("events")
                .order(by: "startDate", descending: false)
        )
        return snapshot.documents.compactMap(mapEvent(document:)).filter { !$0.deleted }
    }

    func listenToActivity(
        forSpaceIDs spaceIDs: [String],
        onUpdate: @escaping (Result<[ActivityItem], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }

        let uniqueSpaceIDs = Array(Set(spaceIDs)).sorted()
        guard !uniqueSpaceIDs.isEmpty else {
            onUpdate(.success([]))
            return nil
        }
        guard let currentUserID = authService.currentSession()?.uid else {
            onUpdate(.success([]))
            return nil
        }

        let allowedSpaceIDs = Set(uniqueSpaceIDs)
        return firestore.collection("activity")
            .whereField("visibleTo", arrayContains: currentUserID)
            .order(by: "createdAt", descending: true)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                let items = snapshot?.documents.compactMap { document -> ActivityItem? in
                    let data = document.data()
                    let spaceID = (data["spaceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? ""
                    guard allowedSpaceIDs.contains(spaceID) else {
                        return nil
                    }
                    let hiddenBy = data["hiddenBy"] as? [String] ?? []
                    guard !hiddenBy.contains(currentUserID) else {
                        return nil
                    }
                    return self.mapActivity(document: document)
                } ?? []
                onUpdate(.success(items.sorted { ($0.createdAt ?? .distantPast) > ($1.createdAt ?? .distantPast) }))
            }
    }

    func listenToNotifications(
        onUpdate: @escaping (Result<[SpaceNotificationItem], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }
        guard let currentUserID = authService.currentSession()?.uid else {
            onUpdate(.success([]))
            return nil
        }

        return firestore.collection("notifications")
            .whereField("recipientId", isEqualTo: currentUserID)
            .order(by: "createdAt", descending: true)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                let items = snapshot?.documents.compactMap(self.mapNotification(document:)) ?? []
                onUpdate(.success(items))
            }
    }

    func markActivityRead(_ activity: ActivityItem) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        try await updateData([
            "readBy": FieldValue.arrayUnion([session.uid])
        ], for: firestore.collection("activity").document(activity.id))
    }

    func markNotificationRead(_ notification: SpaceNotificationItem) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard authService.currentSession() != nil else {
            throw SpaceServiceError.userNotSignedIn
        }

        try await updateData([
            "read": true,
            "readAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("notifications").document(notification.id))
    }

    func markAllNotificationsRead() async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let snapshot = try await getDocuments(
            firestore.collection("notifications")
                .whereField("recipientId", isEqualTo: session.uid)
                .whereField("read", isEqualTo: false)
                .order(by: "createdAt", descending: true)
        )

        for document in snapshot.documents {
            try await updateData([
                "read": true,
                "readAt": FieldValue.serverTimestamp()
            ], for: document.reference)
        }
    }

    func clearActivity(_ activity: ActivityItem) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        try await updateData([
            "hiddenBy": FieldValue.arrayUnion([session.uid])
        ], for: firestore.collection("activity").document(activity.id))
    }

    func clearAllActivity(forSpaceIDs spaceIDs: [String]) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let uniqueSpaceIDs = Array(Set(spaceIDs)).sorted()
        guard !uniqueSpaceIDs.isEmpty else { return }

        let snapshot = try await getDocuments(
            firestore.collection("activity")
                .whereField("visibleTo", arrayContains: session.uid)
                .order(by: "createdAt", descending: true)
        )

        for document in snapshot.documents {
            let data = document.data()
            let spaceID = (data["spaceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? ""
            guard uniqueSpaceIDs.contains(spaceID) else { continue }

            let hiddenBy = data["hiddenBy"] as? [String] ?? []
            guard !hiddenBy.contains(session.uid) else { continue }

            try await updateData([
                "hiddenBy": FieldValue.arrayUnion([session.uid])
            ], for: document.reference)
        }
    }

    func listenToMessages(
        in space: Space,
        onUpdate: @escaping (Result<[SpaceMessage], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success(isRunningPreview ? MockData.generalMessages(for: space) : []))
            return nil
        }
        let currentUserID = authService.currentSession()?.uid

        return firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .order(by: "createdAt", descending: false)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                guard let snapshot else {
                    onUpdate(.failure(SpaceServiceError.unableToLoadMessages))
                    return
                }

                Task { @MainActor in
                    do {
                        let spaceKey = try await self.ensureGeneralEncryptionKey(in: space)
                        try self.runMessageEncryptionSelfTestIfNeeded(spaceID: space.id, spaceKey: spaceKey)
                        var messages: [SpaceMessage] = []
                        for document in snapshot.documents {
                            let data = document.data()
                            let type = data["type"] as? String ?? ""
                            let mediaCategory = data["mediaCategory"] as? String ?? ""
                            let mediaType = data["mediaType"] as? String ?? ""
                            if type == "gif" || mediaCategory == "gif" || mediaType == "gif" {
                                print("[GIF Receive] message document received id=\(document.documentID) type=\(type) mediaCategory=\(mediaCategory) mediaType=\(mediaType)")
                            }
                            if let message = try self.mapMessage(document: document, currentUserID: currentUserID, spaceKey: spaceKey) {
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
        in space: Space,
        limit: Int = 20
    ) async throws -> [SpaceMessage] {
        guard let firestore else {
            return isRunningPreview ? MockData.generalMessages(for: space) : []
        }

        let currentUserID = authService.currentSession()?.uid
        let snapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            firestore.collection("spaces")
                .document(space.id)
                .collection("messages")
                .order(by: "createdAt", descending: true)
                .limit(to: limit)
                .getDocuments { snapshot, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let snapshot {
                        continuation.resume(returning: snapshot)
                    } else {
                        continuation.resume(throwing: SpaceServiceError.unableToLoadMessages)
                    }
                }
        }

        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        try runMessageEncryptionSelfTestIfNeeded(spaceID: space.id, spaceKey: spaceKey)

        return try snapshot.documents.compactMap { document in
            try mapMessage(document: document, currentUserID: currentUserID, spaceKey: spaceKey)
        }
    }

    func listenToReactions(
        for messageID: String,
        in space: Space,
        onUpdate: @escaping (Result<[MessageReaction], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }
        let currentUserID = authService.currentSession()?.uid

        return firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageID)
            .collection("reactions")
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }

                guard let snapshot else {
                    onUpdate(.success([]))
                    return
                }

                onUpdate(.success(self.mapReactions(snapshot.documents, currentUserID: currentUserID)))
            }
    }

    func toggleReaction(
        _ emoji: String,
        in space: Space,
        messageID: String
    ) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageID)
        let messageSnapshot = try await getDocument(messageReference)
        let reactedToName = (messageSnapshot.data()?["senderName"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? "a member"

        let reference = messageReference
            .collection("reactions")
            .document(session.uid)
        let snapshot = try await getDocument(reference)
        let existingEmoji = (snapshot.data()?["emoji"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty

        if existingEmoji == emoji {
            try await deleteDocument(reference)
            return
        }

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let reactingName = profile?.displayName ?? session.displayName

        try await setData([
            "emoji": emoji,
            "userId": session.uid,
            "userName": reactingName,
            "createdAt": FieldValue.serverTimestamp()
        ], for: reference)

        await recordActivity(
            type: .reactionAdded,
            in: space,
            actorID: session.uid,
            actorName: reactingName,
            actorEmoji: profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            title: "reacted \(emoji) to \(reactedToName)'s message",
            targetID: messageID,
            targetType: .general
        )
    }

    func createSpace(
        name: String,
        emoji: String,
        tintHex: String,
        description: String,
        template: SpaceTemplate,
        enabledModules: [SpaceModule]
    ) async throws -> Space {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else {
            throw SpaceServiceError.invalidName
        }

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let memberDisplayName = profile?.displayName ?? session.displayName
        let trimmedMemberEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let memberEmoji = trimmedMemberEmoji.isEmpty ? "🧑‍💻" : trimmedMemberEmoji
        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedDescription = trimmedDescription.isEmpty ? template.defaultSubtitle : trimmedDescription
        let resolvedEnabledModules = sanitizeEnabledModules(enabledModules, for: template)
        let resolvedModuleOrder = sanitizeModuleOrder(template.defaultModuleOrder, enabledModules: resolvedEnabledModules)
        let spaceID = firestore.collection("spaces").document().documentID
        let spaceReference = firestore.collection("spaces").document(spaceID)
        let memberReference = spaceReference.collection("members").document(session.uid)
        let generalEncryptionReference = spaceReference.collection("encryption").document("key")
        let generalEncryptionKey = encryptionService.generateSpaceKey()
        let generalEncryptionKeyBase64 = encryptionService.encodeSpaceKey(generalEncryptionKey)

        try await setData([
            "id": spaceID,
            "name": trimmedName,
            "emoji": emoji,
            "color": tintHex,
            "description": resolvedDescription,
            "template": template.rawValue,
            "enabledModules": resolvedEnabledModules.map(\.id),
            "moduleOrder": resolvedModuleOrder.map(\.id),
            "ownerId": session.uid,
            "memberIds": [session.uid],
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: spaceReference)
        try await setData([
            "userId": session.uid,
            "displayName": memberDisplayName,
            "emojiAvatar": memberEmoji,
            "role": SpaceMemberRole.owner.firestoreValue,
            "joinedAt": FieldValue.serverTimestamp()
        ], for: memberReference)
        try await setData([
            "keyVersion": generalEncryptionVersion,
            "keyBase64": generalEncryptionKeyBase64,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "createdBy": session.uid
        ], for: generalEncryptionReference)
        encryptionService.cacheSpaceKey(generalEncryptionKey, for: spaceID)
        let newSpace = Space(
            id: spaceID,
            name: trimmedName,
            emoji: emoji,
            tintHex: tintHex,
            description: resolvedDescription,
            template: template,
            ownerId: session.uid,
            memberIds: [session.uid],
            unreadCount: nil,
            enabledModules: resolvedEnabledModules,
            moduleOrder: resolvedModuleOrder
        )
        await recordActivity(
            type: .spaceCreated,
            in: newSpace,
            actorID: session.uid,
            actorName: memberDisplayName,
            actorEmoji: memberEmoji,
            title: "created the Space",
            targetType: .space
        )
        return newSpace
    }

    func createInvite(for space: Space) async throws -> SpaceInvite {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        try await requirePermission(.inviteMembers, in: space, error: .invitePermissionDenied)

        let code = try await reserveInviteCode()
        let createdAt = Date()
        let expiresAt = Calendar.current.date(byAdding: .day, value: 7, to: createdAt) ?? createdAt.addingTimeInterval(604800)
        let invite = SpaceInvite(
            id: code,
            code: code,
            spaceId: space.id,
            spaceName: space.name,
            spaceEmoji: space.emoji,
            createdBy: session.uid,
            createdAt: createdAt,
            expiresAt: expiresAt,
            maxUses: 25,
            usedCount: 0,
            active: true
        )

        try await setData([
            "code": invite.code,
            "spaceId": invite.spaceId,
            "spaceName": invite.spaceName,
            "spaceEmoji": invite.spaceEmoji,
            "createdBy": invite.createdBy,
            "createdAt": Timestamp(date: invite.createdAt),
            "expiresAt": Timestamp(date: invite.expiresAt),
            "maxUses": invite.maxUses,
            "usedCount": invite.usedCount,
            "active": invite.active
        ], for: firestore.collection("spaceInvites").document(code))

        return invite
    }

    func fetchLatestInvite(for space: Space) async throws -> SpaceInvite? {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        let snapshot = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            firestore.collection("spaceInvites")
                .whereField("spaceId", isEqualTo: space.id)
                .order(by: "createdAt", descending: true)
                .limit(to: 1)
                .getDocuments { snapshot, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let snapshot {
                        continuation.resume(returning: snapshot)
                    } else {
                        continuation.resume(throwing: SpaceServiceError.unableToLoadInvite)
                    }
                }
        }

        guard let document = snapshot.documents.first else { return nil }
        return mapInvite(document: document)
    }

    func updateInviteActiveState(code: String, isActive: Bool) async throws -> SpaceInvite {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        let reference = firestore.collection("spaceInvites").document(normalizeInviteCode(code))
        try await setData([
            "active": isActive
        ], for: reference, merge: true)

        let snapshot = try await getDocument(reference)
        guard let invite = mapInvite(document: snapshot) else {
            throw SpaceServiceError.unableToLoadInvite
        }
        return invite
    }

    func regenerateInvite(for space: Space, replacing existingInvite: SpaceInvite?) async throws -> SpaceInvite {
        if let existingInvite {
            _ = try await updateInviteActiveState(code: existingInvite.code, isActive: false)
        }
        return try await createInvite(for: space)
    }

    func redeemInvite(code: String) async throws -> Space {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let normalizedCode = normalizeInviteCode(code)
        guard !normalizedCode.isEmpty else {
            throw SpaceServiceError.invalidInviteCode
        }

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let displayName = profile?.displayName ?? session.displayName
        let emojiAvatar = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let inviteReference = firestore.collection("spaceInvites").document(normalizedCode)

        let joinedSpace: Space = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Space, Error>) in
            let db = firestore
            db.runTransaction({ transaction, errorPointer -> Any? in
                do {
                    let inviteSnapshot = try transaction.getDocument(inviteReference)
                    let inviteData = try self.validatedInviteData(from: inviteSnapshot)
                    let usedCount = inviteData["usedCount"] as? Int ?? 0
                    let spaceID = inviteData["spaceId"] as? String ?? ""
                    let spaceReference = db.collection("spaces").document(spaceID)
                    let memberReference = spaceReference.collection("members").document(session.uid)
                    let spaceSnapshot = try transaction.getDocument(spaceReference)
                    guard spaceSnapshot.exists, let spaceData = spaceSnapshot.data() else {
                        throw SpaceServiceError.unableToLoadSpaces
                    }

                    let updatedMemberIDs = try self.joinedMemberIDs(
                        from: spaceData,
                        appending: session.uid
                    )

                    transaction.updateData(
                        [
                            "memberIds": updatedMemberIDs,
                            "updatedAt": FieldValue.serverTimestamp()
                        ],
                        forDocument: spaceReference
                    )
                    transaction.setData(
                        self.memberDocumentData(
                            userID: session.uid,
                            displayName: displayName,
                            emojiAvatar: emojiAvatar,
                            role: .member
                        ),
                        forDocument: memberReference
                    )
                    transaction.updateData(["usedCount": usedCount + 1], forDocument: inviteReference)

                    var joinedData = spaceData
                    joinedData["memberIds"] = updatedMemberIDs
                    return self.mappedSpace(data: joinedData, documentID: spaceSnapshot.documentID)
                } catch let error as NSError {
                    errorPointer?.pointee = error
                    return nil
                } catch {
                    errorPointer?.pointee = error as NSError
                    return nil
                }
            }, completion: { object, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    guard let space = object as? Space else {
                        continuation.resume(throwing: SpaceServiceError.unableToJoinSpace)
                        return
                    }
                    continuation.resume(returning: space)
                }
            })
        }
        await recordActivity(
            type: .memberJoined,
            in: joinedSpace,
            actorID: session.uid,
            actorName: displayName,
            actorEmoji: emojiAvatar,
            title: "joined the Space",
            targetType: .members
        )
        return joinedSpace
    }

    func updateMemberRole(in space: Space, memberID: String, role: SpaceMemberRole) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard authService.currentSession() != nil else {
            throw SpaceServiceError.userNotSignedIn
        }

        try await ensureCanChangeMemberRole(in: space, memberID: memberID, to: role)

        try await updateData([
            "role": role.firestoreValue
        ], for: firestore.collection("spaces").document(space.id).collection("members").document(memberID))
    }

    func canManageModules(in space: Space) async -> Bool {
        do {
            return try await currentUserHasPermission(.manageModules, in: space)
        } catch {
            return false
        }
    }

    func canPerform(_ permission: SpacePermission, in space: Space) async -> Bool {
        do {
            return try await currentUserHasPermission(permission, in: space)
        } catch {
            return false
        }
    }

    func setPermissions(_ permissions: Set<SpacePermission>, for role: SpaceMemberRole, in space: Space) async throws {
        guard let firestore else { throw SpaceServiceError.firestoreNotConfigured }
        try await firestore.collection("spaces").document(space.id).setData([
            "rolePermissionOverrides": [role.firestoreValue: permissions.map(\.rawValue).sorted()]
        ], merge: true)
    }

    func filesModuleHasContent(in space: Space) async throws -> Bool {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        let filesSnapshot = try await getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("files")
                .limit(to: 1)
        )
        if !filesSnapshot.documents.isEmpty {
            return true
        }

        let foldersSnapshot = try await getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("fileFolders")
                .limit(to: 1)
        )
        return !foldersSnapshot.documents.isEmpty
    }

    func setFilesEnabled(in space: Space, isEnabled: Bool) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)

        var enabledModules = try await latestEnabledModules(in: space)
        if isEnabled {
            if !enabledModules.contains(.files) {
                enabledModules.append(.files)
            }
        } else {
            enabledModules.removeAll { $0 == .files }
        }

        enabledModules = sanitizeEnabledModules(enabledModules, for: space.template)

        try await updateData([
            "enabledModules": enabledModules.map(\.id),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id))
    }

    func setEventsEnabled(in space: Space, isEnabled: Bool) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)

        var enabledModules = try await latestEnabledModules(in: space)
        if isEnabled {
            if !enabledModules.contains(.events) {
                enabledModules.append(.events)
            }
        } else {
            enabledModules.removeAll { $0 == .events }
        }

        enabledModules = sanitizeEnabledModules(enabledModules, for: space.template)

        try await updateData([
            "enabledModules": enabledModules.map(\.id),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id))
    }

    func setAnnouncementsEnabled(in space: Space, isEnabled: Bool) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)

        var enabledModules = try await latestEnabledModules(in: space)
        if isEnabled {
            if !enabledModules.contains(.announcements) {
                enabledModules.append(.announcements)
            }
        } else {
            enabledModules.removeAll { $0 == .announcements }
        }

        enabledModules = sanitizeEnabledModules(enabledModules, for: space.template)

        try await updateData([
            "enabledModules": enabledModules.map(\.id),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id))
    }

    func setRoomsEnabled(in space: Space, isEnabled: Bool) async throws {
        guard let firestore else { throw SpaceServiceError.firestoreNotConfigured }
        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)
        var modules = try await latestEnabledModules(in: space)
        if isEnabled {
            if !modules.contains(.rooms) { modules.append(.rooms) }
        } else {
            modules.removeAll { $0 == .rooms }
        }
        modules = sanitizeEnabledModules(modules, for: space.template)
        try await updateData([
            "enabledModules": modules.map(\.id),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id))
    }

    func setListsEnabled(in space: Space, isEnabled: Bool) async throws {
        guard let firestore else { throw SpaceServiceError.firestoreNotConfigured }
        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)
        var modules = try await latestEnabledModules(in: space)
        if isEnabled {
            if !modules.contains(.lists) { modules.append(.lists) }
        } else {
            modules.removeAll { $0 == .lists }
        }
        let resolved = sanitizeEnabledModules(modules, for: space.template)
        try await updateData([
            "enabledModules": resolved.map(\.id),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id))
    }

    func setNotesEnabled(in space: Space, isEnabled: Bool) async throws {
        guard let firestore else { throw SpaceServiceError.firestoreNotConfigured }
        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)
        var modules = try await latestEnabledModules(in: space)
        if isEnabled { if !modules.contains(.notes) { modules.append(.notes) } }
        else { modules.removeAll { $0 == .notes } }
        let resolved = sanitizeEnabledModules(modules, for: space.template)
        try await updateData(["enabledModules": resolved.map(\.id), "updatedAt": FieldValue.serverTimestamp()], for: firestore.collection("spaces").document(space.id))
    }

    func setPollsEnabled(in space: Space, isEnabled: Bool) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)

        var enabledModules = try await latestEnabledModules(in: space)
        if isEnabled {
            if !enabledModules.contains(.polls) {
                enabledModules.append(.polls)
            }
        } else {
            enabledModules.removeAll { $0 == .polls }
        }

        enabledModules = sanitizeEnabledModules(enabledModules, for: space.template)

        try await updateData([
            "enabledModules": enabledModules.map(\.id),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id))
    }

    func fetchSpaceOrderForCurrentUser() async throws -> [String] {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let snapshot = try await getDocument(firestore.collection("users").document(session.uid))
        let data = snapshot.data() ?? [:]
        return normalizedSpaceOrder(data["spaceOrder"] as? [String] ?? [])
    }

    func saveSpaceOrderForCurrentUser(_ spaceIDs: [String]) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        try await setData([
            "spaceOrder": normalizedSpaceOrder(spaceIDs),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("users").document(session.uid), merge: true)
    }

    func fetchModuleOrder(in space: Space) async throws -> [SpaceModule] {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        let snapshot = try await getDocument(firestore.collection("spaces").document(space.id))
        let data = snapshot.data() ?? [:]
        let enabledModules = parseEnabledModules(from: data, template: space.template)
        return parseModuleOrder(from: data, template: space.template, enabledModules: enabledModules)
    }

    func updateModuleOrder(in space: Space, modules: [SpaceModule]) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        try await requirePermission(.manageModules, in: space, error: .invitePermissionDenied)
        let latestEnabled = try await latestEnabledModules(in: space)
        let resolvedOrder = sanitizeModuleOrder(modules, enabledModules: latestEnabled)

        try await updateData([
            "moduleOrder": resolvedOrder.map(\.id),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id))
    }

    func removeMember(from space: Space, memberID: String) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard authService.currentSession() != nil else {
            throw SpaceServiceError.userNotSignedIn
        }

        try await ensureCanRemoveMember(in: space, memberID: memberID)

        let spaceReference = firestore.collection("spaces").document(space.id)
        let memberReference = spaceReference.collection("members").document(memberID)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            firestore.runTransaction({ transaction, errorPointer -> Any? in
                do {
                    let spaceSnapshot = try transaction.getDocument(spaceReference)
                    guard let data = spaceSnapshot.data() else {
                        throw SpaceServiceError.unableToLoadSpaces
                    }

                    let memberIds = (data["memberIds"] as? [String] ?? []).filter { $0 != memberID }
                    transaction.updateData([
                        "memberIds": memberIds,
                        "updatedAt": FieldValue.serverTimestamp()
                    ], forDocument: spaceReference)
                    transaction.deleteDocument(memberReference)
                    return true
                } catch let error as NSError {
                    errorPointer?.pointee = error
                    return nil
                } catch {
                    errorPointer?.pointee = error as NSError
                    return nil
                }
            }, completion: { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            })
        }
    }

    func sendTextMessage(
        in space: Space,
        text: String,
        linkPreview: LinkPreviewData? = nil,
        spaceLinks: [SpaceLinkAttachment] = [],
        replyContext: MessageReplyContext? = nil,
        messageID: String? = nil
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let trimmedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedText.isEmpty || !spaceLinks.isEmpty else {
            throw SpaceServiceError.invalidMessageText
        }

        try await requirePermission(.postPings, in: space, error: .messageDeletePermissionDenied)

        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        try runMessageEncryptionSelfTestIfNeeded(spaceID: space.id, spaceKey: spaceKey)
        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let senderName = profile?.displayName ?? session.displayName
        let senderEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let plaintext = try encodeTextMessageContent(text: trimmedText, linkPreview: linkPreview, spaceLinks: spaceLinks)
        let encryptedPayload = try encryptionService.encryptText(plaintext, using: spaceKey)
        let messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageID ?? UUID().uuidString)

        var messageData: [String: Any] = [
            "id": messageReference.documentID,
            "spaceId": space.id,
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
        addReplyContext(replyContext, to: &messageData)
        try await setData(messageData, for: messageReference)
        logStoredMessagePayload(
            messageID: messageReference.documentID,
            senderID: session.uid,
            encryptionVersion: generalEncryptionVersion,
            ciphertext: encryptedPayload.ciphertext,
            nonce: encryptedPayload.nonce
        )
        await recordActivity(
            type: replyContext == nil ? .messageSent : .replyAdded,
            in: space,
            actorID: session.uid,
            actorName: senderName,
            actorEmoji: senderEmoji,
            title: replyContext == nil
                ? "sent a message"
                : "replied to \(replyContext?.senderName ?? "a member")'s message",
            targetID: messageReference.documentID,
            targetType: .general
        )

        return SpaceMessage(
            id: messageReference.documentID,
            spaceId: space.id,
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
            replyContext: replyContext,
            linkPreview: linkPreview,
            spaceLinks: spaceLinks
        )
    }

    func editTextMessage(
        in space: Space,
        messageID: String,
        newText: String,
        linkPreview: LinkPreviewData? = nil,
        spaceLinks: [SpaceLinkAttachment] = []
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let trimmedText = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedText.isEmpty || !spaceLinks.isEmpty else {
            throw SpaceServiceError.invalidMessageText
        }

        let messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageID)
        let snapshot = try await getDocument(messageReference)
        guard snapshot.exists, let data = snapshot.data() else {
            throw SpaceServiceError.messageNotFound
        }

        let senderID = data["senderId"] as? String
        let type = (data["type"] as? String).flatMap(MessageType.init(rawValue:)) ?? .text
        let deleted = data["deleted"] as? Bool ?? false
        guard senderID == session.uid, type == .text, !deleted else {
            throw SpaceServiceError.messageDeletePermissionDenied
        }

        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        try runMessageEncryptionSelfTestIfNeeded(spaceID: space.id, spaceKey: spaceKey)
        let plaintext = try encodeTextMessageContent(text: trimmedText, linkPreview: linkPreview, spaceLinks: spaceLinks)
        let encryptedPayload = try encryptionService.encryptText(plaintext, using: spaceKey)

        try await updateData([
            "ciphertextBase64": encryptedPayload.ciphertext,
            "nonceBase64": encryptedPayload.nonce,
            "edited": true,
            "editedAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: messageReference)

        return SpaceMessage(
            id: data["id"] as? String ?? messageID,
            spaceId: data["spaceId"] as? String ?? space.id,
            senderId: senderID,
            senderName: data["senderName"] as? String ?? session.displayName,
            senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            type: .text,
            encryptionVersion: inferredGeneralEncryptionVersion(from: data),
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
            linkPreview: linkPreview,
            spaceLinks: spaceLinks,
            reactions: []
        )
    }

    struct ImageAttachmentUpload {
        let data: Data
        let previewImageData: Data
        let mimeType: String
        let mediaCategory: String
    }

    func sendImageMessage(
        in space: Space,
        imageData: Data,
        caption: String?,
        mediaCategory: String = "photo",
        previewImageData: Data? = nil,
        mimeType: String = "image/jpeg",
        replyContext: MessageReplyContext? = nil,
        messageID: String? = nil
    ) async throws -> SpaceMessage {
        try await sendImageMessage(
            in: space,
            imageAttachments: [
                ImageAttachmentUpload(
                    data: imageData,
                    previewImageData: previewImageData ?? imageData,
                    mimeType: mimeType,
                    mediaCategory: mediaCategory
                )
            ],
            caption: caption,
            replyContext: replyContext,
            messageID: messageID
        )
    }

    func sendImageMessage(
        in space: Space,
        imageAttachments: [ImageAttachmentUpload],
        caption: String?,
        replyContext: MessageReplyContext? = nil,
        messageID: String? = nil
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        guard !imageAttachments.isEmpty else {
            throw SpaceServiceError.invalidMediaData
        }
        try await requirePermission(.uploadPhotosVideos, in: space, error: .messageDeletePermissionDenied)
        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let senderName = profile?.displayName ?? session.displayName
        let senderEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let firstAttachment = imageAttachments[0]
        let resolvedMediaType = MediaType(rawValue: firstAttachment.mediaCategory) ?? .photo
        let resolvedMessageType: MessageType = {
            if imageAttachments.count > 1 {
                return .image
            }
            switch resolvedMediaType {
            case .gif:
                return .gif
            case .meme:
                return .meme
            default:
                return .image
            }
        }()
        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        try runMessageEncryptionSelfTestIfNeeded(spaceID: space.id, spaceKey: spaceKey)
        let trimmedCaption = caption?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let encryptedCaption = try trimmedCaption.map {
            try encryptionService.encryptText($0, using: spaceKey)
        }

        let messageID = messageID ?? firestore.collection("spaces").document(space.id).collection("messages").document().documentID
        let messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageID)
        let attachmentResults = try await withThrowingTaskGroup(of: (Int, EncryptedMediaUploadResult).self) { group in
            for (index, attachment) in imageAttachments.enumerated() {
                group.addTask { [encryptedMediaService] in
                    let attachmentMediaType = MediaType(rawValue: attachment.mediaCategory) ?? .photo
                    let mediaID = imageAttachments.count == 1 ? messageID : "\(messageID)_\(index)"
                    let uploadResult: EncryptedMediaUploadResult
                    if attachmentMediaType == .gif {
                        print("[GIF] Preparing upload")
                        print("[GIF] Upload started")
                        uploadResult = try await encryptedMediaService.uploadAnimatedImage(
                            spaceID: space.id,
                            mediaID: mediaID,
                            originalData: attachment.data,
                            previewImageData: attachment.previewImageData,
                            mediaType: attachmentMediaType,
                            mimeType: attachment.mimeType,
                            uploadedBy: session.uid
                        )
                        print("[GIF] Upload finished")
                    } else {
                        uploadResult = try await encryptedMediaService.uploadImage(
                            spaceID: space.id,
                            mediaID: mediaID,
                            originalData: attachment.data,
                            mediaType: attachmentMediaType,
                            mimeType: attachment.mimeType,
                            uploadedBy: session.uid
                        )
                    }
                    return (index, uploadResult)
                }
            }

            var collected: [(Int, EncryptedMediaUploadResult)] = []
            for try await result in group {
                collected.append(result)
            }
            return collected.sorted { $0.0 < $1.0 }
        }
        let uploadedMedia = attachmentResults.map(\.1.metadata)
        let metadata = uploadedMedia[0]
        print("[SpaceService][ImageMessage] photoSelected=true imageDataByteCount=\(imageAttachments.reduce(0) { $0 + $1.data.count }) thumbnailByteCount=\(metadata.fileSize)")
        for item in uploadedMedia {
            print("[SpaceService][ImageMessage] uploadPath=\(item.storagePath)")
            if let thumbnailStoragePath = item.thumbnailStoragePath {
                print("[SpaceService][ImageMessage] uploadPath=\(thumbnailStoragePath)")
            }
        }

        let mediaItemsPayload: [[String: Any]] = uploadedMedia.enumerated().map { index, item in
            var payload: [String: Any] = [
                "id": item.mediaId,
                "mediaId": item.mediaId,
                "order": index,
                "mediaCategory": imageAttachments[index].mediaCategory,
                "mediaType": item.mediaType.rawValue,
                "storagePath": item.storagePath,
                "mediaStoragePath": item.storagePath,
                "nonce": item.nonce,
                "mediaNonceBase64": item.nonce,
                "encryptionVersion": item.encryptionVersion,
                "mimeType": item.mimeType,
                "fileSize": item.fileSize,
                "uploadedBy": item.uploadedBy
            ]
            if let thumbnailStoragePath = item.thumbnailStoragePath {
                payload["thumbnailStoragePath"] = thumbnailStoragePath
            }
            if let thumbnailNonce = item.thumbnailNonce {
                payload["thumbnailNonce"] = thumbnailNonce
                payload["thumbnailNonceBase64"] = thumbnailNonce
            }
            if let width = item.width {
                payload["width"] = width
            }
            if let height = item.height {
                payload["height"] = height
            }
            if let duration = item.duration {
                payload["duration"] = duration
            }
            return payload
        }

        var messageData: [String: Any] = [
            "id": messageID,
            "mediaId": metadata.mediaId,
            "spaceId": space.id,
            "senderId": session.uid,
            "senderName": senderName,
            "senderEmoji": senderEmoji,
            "type": resolvedMessageType.rawValue,
            "mediaCategory": firstAttachment.mediaCategory,
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
            "status": "sent",
            "mediaItems": mediaItemsPayload
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
        print("[SpaceService][ImageMessage] messageDocumentCreated=true messageId=\(messageID)")
        await recordActivity(
            type: .photoShared,
            in: space,
            actorID: session.uid,
            actorName: senderName,
            actorEmoji: senderEmoji,
            title: imageAttachments.count > 1 ? "shared photos" : (resolvedMediaType == .gif ? "shared a GIF" : (resolvedMediaType == .meme ? "shared a meme" : "shared a photo")),
            subtitle: trimmedCaption,
            targetID: messageID,
            targetType: .photos
        )

        let mediaItems = zip(uploadedMedia, imageAttachments).map { item, attachment in
            SpaceMedia(
                id: item.mediaId,
                spaceID: space.id,
                type: .image,
                mediaCategory: attachment.mediaCategory,
                mediaType: item.mediaType,
                placeholderImageName: item.mediaType.defaultPlaceholderImageName,
                caption: trimmedCaption,
                senderName: senderName,
                timestamp: Self.messageTimestampFormatter.string(from: Date()),
                metadata: item,
                mediaStoragePath: item.storagePath,
                thumbnailStoragePath: item.thumbnailStoragePath,
                mediaNonceBase64: item.nonce,
                thumbnailNonceBase64: item.thumbnailNonce
            )
        }

        return SpaceMessage(
            id: messageID,
            spaceId: space.id,
            senderId: session.uid,
            senderName: senderName,
            senderEmoji: senderEmoji,
            type: resolvedMessageType,
            encryptionVersion: metadata.encryptionVersion,
            deleted: false,
            text: nil,
            media: mediaItems.first,
            mediaItems: mediaItems,
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
        in space: Space,
        videoData: Data,
        caption: String?,
        mimeType: String,
        replyContext: MessageReplyContext? = nil,
        messageID: String? = nil
    ) async throws -> SpaceMessage {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        try await requirePermission(.uploadPhotosVideos, in: space, error: .messageDeletePermissionDenied)

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let senderName = profile?.displayName ?? session.displayName
        let senderEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🧑‍💻"
        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        try runMessageEncryptionSelfTestIfNeeded(spaceID: space.id, spaceKey: spaceKey)
        let trimmedCaption = caption?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let encryptedCaption = try trimmedCaption.map {
            try encryptionService.encryptText($0, using: spaceKey)
        }

        let mediaID = messageID ?? firestore.collection("spaces").document(space.id).collection("messages").document().documentID
        let messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(mediaID)
        let uploadResult = try await encryptedMediaService.uploadVideo(
            spaceID: space.id,
            mediaID: mediaID,
            originalData: videoData,
            mimeType: mimeType,
            uploadedBy: session.uid
        )
        let metadata = uploadResult.metadata

        var messageData: [String: Any] = [
            "id": mediaID,
            "mediaId": metadata.mediaId,
            "spaceId": space.id,
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
        await recordActivity(
            type: .videoShared,
            in: space,
            actorID: session.uid,
            actorName: senderName,
            actorEmoji: senderEmoji,
            title: "shared a video",
            subtitle: trimmedCaption,
            targetID: mediaID,
            targetType: .photos
        )

        return SpaceMessage(
            id: mediaID,
            spaceId: space.id,
            senderId: session.uid,
            senderName: senderName,
            senderEmoji: senderEmoji,
            type: .video,
            encryptionVersion: metadata.encryptionVersion,
            deleted: false,
            text: nil,
            media: SpaceMedia(
                id: mediaID,
                spaceID: space.id,
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

    func loadThumbnailData(for media: SpaceMedia) async throws -> Data {
        try await encryptedMediaService.thumbnailData(for: media)
    }

    func loadFullMediaData(for media: SpaceMedia) async throws -> Data {
        try await encryptedMediaService.fullData(for: media)
    }

    func currentUserID() -> String? {
        authService.currentSession()?.uid
    }

    func createPoll(
        in space: Space,
        question: String,
        optionTexts: [String],
        closesAt: Date?,
        allowMultipleVotes: Bool,
        anonymous: Bool
    ) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let trimmedQuestion = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedOptions = optionTexts
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        guard !trimmedQuestion.isEmpty else {
            throw SpaceServiceError.invalidPollQuestion
        }
        guard trimmedOptions.count >= 2 else {
            throw SpaceServiceError.invalidPollOptions
        }

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let createdByName = profile?.displayName ?? session.displayName
        let pollReference = firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .document()
        let optionsPayload = trimmedOptions.enumerated().map { index, text in
            ["id": "option-\(index + 1)", "text": text]
        }

        var payload: [String: Any] = [
            "id": pollReference.documentID,
            "spaceId": space.id,
            "question": trimmedQuestion,
            "options": optionsPayload,
            "createdBy": session.uid,
            "createdByName": createdByName,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "allowMultipleVotes": allowMultipleVotes,
            "anonymous": anonymous,
            "deleted": false
        ]
        if let closesAt {
            payload["closesAt"] = Timestamp(date: closesAt)
        }

        try await setData(payload, for: pollReference)
        await recordActivity(
            type: .pollCreated,
            in: space,
            actorID: session.uid,
            actorName: createdByName,
            actorEmoji: nil,
            title: "created a poll",
            subtitle: trimmedQuestion,
            targetID: pollReference.documentID,
            targetType: .polls
        )
    }

    func submitPollVote(
        in space: Space,
        poll: SpacePoll,
        optionIDs: [String]
    ) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        guard !poll.isClosed else {
            throw SpaceServiceError.pollClosed
        }

        let validOptionIDs = Set(poll.options.map(\.id))
        let sanitizedOptionIDs = Array(Set(optionIDs.filter { validOptionIDs.contains($0) })).sorted()
        guard !sanitizedOptionIDs.isEmpty else {
            throw SpaceServiceError.invalidPollOptions
        }
        if !poll.allowMultipleVotes && sanitizedOptionIDs.count > 1 {
            throw SpaceServiceError.invalidPollOptions
        }

        let voteReference = firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .document(poll.id)
            .collection("votes")
            .document(session.uid)

        let existingVote = try await getDocument(voteReference)
        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let voterName = profile?.displayName ?? session.displayName
        let voterEmoji = profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        var votePayload: [String: Any] = [
            "userId": session.uid,
            "displayName": voterName,
            "optionIds": sanitizedOptionIDs,
            "updatedAt": FieldValue.serverTimestamp()
        ]
        if let voterEmoji { votePayload["emojiAvatar"] = voterEmoji }
        if !existingVote.exists { votePayload["createdAt"] = FieldValue.serverTimestamp() }
        try await setData(votePayload, for: voteReference, merge: true)
        guard !existingVote.exists else { return }
        await recordActivity(
            type: .pollVoted,
            in: space,
            actorID: session.uid,
            actorName: voterName,
            actorEmoji: voterEmoji,
            title: "voted in a poll",
            subtitle: poll.question,
            targetID: poll.id,
            targetType: .polls
        )
    }

    func removePollVote(in space: Space, poll: SpacePoll) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        guard !poll.isClosed else {
            throw SpaceServiceError.pollClosed
        }

        let voteReference = firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .document(poll.id)
            .collection("votes")
            .document(session.uid)
        try await deleteDocument(voteReference)
    }

    func updatePoll(
        in space: Space,
        poll: SpacePoll,
        question: String,
        optionTexts: [String],
        closesAt: Date?,
        allowMultipleVotes: Bool,
        anonymous: Bool
    ) async throws {
        guard let firestore else { throw SpaceServiceError.firestoreNotConfigured }
        guard let session = authService.currentSession() else { throw SpaceServiceError.userNotSignedIn }
        let canEditPoll: Bool
        if poll.createdBy == session.uid {
            canEditPoll = true
        } else {
            canEditPoll = await canManageModules(in: space)
        }
        guard canEditPoll else {
            throw SpaceServiceError.pollUpdatePermissionDenied
        }
        let trimmedQuestion = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedOptions = optionTexts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard !trimmedQuestion.isEmpty else { throw SpaceServiceError.invalidPollQuestion }
        guard trimmedOptions.count >= 2 else { throw SpaceServiceError.invalidPollOptions }
        let optionsPayload = trimmedOptions.enumerated().map { index, text in
            ["id": index < poll.options.count ? poll.options[index].id : "option-\(index + 1)", "text": text]
        }
        var payload: [String: Any] = [
            "question": trimmedQuestion,
            "options": optionsPayload,
            "allowMultipleVotes": allowMultipleVotes,
            "anonymous": anonymous,
            "updatedAt": FieldValue.serverTimestamp()
        ]
        payload["closesAt"] = closesAt.map(Timestamp.init(date:)) ?? FieldValue.delete()
        try await setData(payload, for: firestore.collection("spaces").document(space.id).collection("polls").document(poll.id), merge: true)
    }

    func deletePoll(
        in space: Space,
        poll: SpacePoll
    ) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        if poll.createdBy != session.uid {
            guard try await currentUserHasPermission(.deleteOthersContent, in: space) else {
                throw SpaceServiceError.pollDeletePermissionDenied
            }
        }

        try await updateData([
            "deleted": true,
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id).collection("polls").document(poll.id))
    }

    func createEvent(
        in space: Space,
        title: String,
        description: String,
        location: String,
        startDate: Date,
        endDate: Date,
        allDay: Bool
    ) async throws -> SpaceEvent {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedTitle.isEmpty else {
            throw SpaceServiceError.invalidEventTitle
        }
        guard endDate >= startDate else {
            throw SpaceServiceError.invalidEventDateRange
        }

        try await requirePermission(.createEvents, in: space, error: .eventUpdatePermissionDenied)

        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let createdByName = profile?.displayName ?? session.displayName
        let eventReference = firestore.collection("spaces")
            .document(space.id)
            .collection("events")
            .document()
        let timeZoneIdentifier = TimeZone.current.identifier

        let payload: [String: Any] = [
            "id": eventReference.documentID,
            "spaceId": space.id,
            "title": trimmedTitle,
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "location": location.trimmingCharacters(in: .whitespacesAndNewlines),
            "startDate": Timestamp(date: startDate),
            "endDate": Timestamp(date: endDate),
            "allDay": allDay,
            "timezone": timeZoneIdentifier,
            "createdBy": session.uid,
            "createdByName": createdByName,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "deleted": false
        ]

        try await setData(payload, for: eventReference)
        await recordActivity(
            type: .eventCreated,
            in: space,
            actorID: session.uid,
            actorName: createdByName,
            actorEmoji: nil,
            title: "created an event",
            subtitle: trimmedTitle,
            targetID: eventReference.documentID,
            targetType: .events
        )

        return SpaceEvent(
            id: eventReference.documentID,
            spaceID: space.id,
            title: trimmedTitle,
            description: description.trimmingCharacters(in: .whitespacesAndNewlines),
            location: location.trimmingCharacters(in: .whitespacesAndNewlines),
            startDate: startDate,
            endDate: endDate,
            allDay: allDay,
            timeZoneIdentifier: timeZoneIdentifier,
            createdBy: session.uid,
            createdByName: createdByName,
            createdAt: Date(),
            updatedAt: Date(),
            deleted: false
        )
    }

    func updateEvent(
        in space: Space,
        event: SpaceEvent,
        title: String,
        description: String,
        location: String,
        startDate: Date,
        endDate: Date,
        allDay: Bool
    ) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedTitle.isEmpty else {
            throw SpaceServiceError.invalidEventTitle
        }
        guard endDate >= startDate else {
            throw SpaceServiceError.invalidEventDateRange
        }

        if event.createdBy != session.uid {
            guard try await currentUserHasPermission(.deleteOthersContent, in: space) else {
                throw SpaceServiceError.eventUpdatePermissionDenied
            }
        }

        try await updateData([
            "title": trimmedTitle,
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "location": location.trimmingCharacters(in: .whitespacesAndNewlines),
            "startDate": Timestamp(date: startDate),
            "endDate": Timestamp(date: endDate),
            "allDay": allDay,
            "timezone": TimeZone.current.identifier,
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id).collection("events").document(event.id))
        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        await recordActivity(
            type: .eventUpdated,
            in: space,
            actorID: session.uid,
            actorName: profile?.displayName ?? session.displayName,
            actorEmoji: profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            title: "updated an event",
            subtitle: trimmedTitle,
            targetID: event.id,
            targetType: .events
        )
    }

    func deleteEvent(
        in space: Space,
        event: SpaceEvent
    ) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        if event.createdBy != session.uid {
            guard try await currentUserHasPermission(.deleteOthersContent, in: space) else {
                throw SpaceServiceError.eventDeletePermissionDenied
            }
        }

        try await updateData([
            "deleted": true,
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id).collection("events").document(event.id))
    }

    func uploadFile(in space: Space, from fileURL: URL) async throws -> SpaceFileItem {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        try await requirePermission(.uploadFiles, in: space, error: .filePermissionDenied)

        let didAccess = fileURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                fileURL.stopAccessingSecurityScopedResource()
            }
        }

        let fileData = try Data(contentsOf: fileURL)
        let fileName = fileURL.lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !fileName.isEmpty else {
            throw SpaceServiceError.invalidFileName
        }

        let mimeType = mimeType(for: fileURL)
        let profile = try await userProfileService.fetchUserProfile(uid: session.uid)
        let uploaderName = profile?.displayName ?? session.displayName
        let resolvedFileExtension = fileExtension(for: fileURL, mimeType: mimeType)
        let fileReference = firestore.collection("spaces")
            .document(space.id)
            .collection("files")
            .document()
        let storagePath = "spaces/\(space.id)/files/\(fileReference.documentID).enc"
        let uploadResult = try await encryptedMediaService.uploadFile(
            spaceID: space.id,
            storagePath: storagePath,
            originalData: fileData,
            mimeType: mimeType,
            uploadedBy: session.uid
        )

        let metadata = uploadResult.metadata
        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        let encryptedFileName = try encryptionService.encryptText(fileName, using: spaceKey)
        try await setData([
            "id": fileReference.documentID,
            "spaceId": space.id,
            "nameCiphertextBase64": encryptedFileName.ciphertext,
            "nameNonceBase64": encryptedFileName.nonce,
            "mimeType": mimeType,
            "fileExtension": resolvedFileExtension,
            "storagePath": metadata.storagePath,
            "encryptionVersion": metadata.encryptionVersion,
            "nonceBase64": metadata.nonce,
            "uploadedBy": session.uid,
            "uploadedByName": uploaderName,
            "fileSize": metadata.fileSize,
            "deleted": false,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp()
        ], for: fileReference)
        await recordActivity(
            type: .fileUploaded,
            in: space,
            actorID: session.uid,
            actorName: uploaderName,
            actorEmoji: profile?.emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            title: "uploaded a file",
            subtitle: fileName,
            targetID: fileReference.documentID,
            targetType: .files
        )

        return SpaceFileItem(
            id: fileReference.documentID,
            spaceID: space.id,
            name: fileName,
            mimeType: mimeType,
            folderId: nil,
            storagePath: metadata.storagePath,
            encryptionVersion: metadata.encryptionVersion,
            nonceBase64: metadata.nonce,
            uploadedBy: session.uid,
            uploadedByName: uploaderName,
            fileExtension: resolvedFileExtension,
            createdAt: Date(),
            updatedAt: Date(),
            sizeBytes: Int64(metadata.fileSize),
            deleted: false
        )
    }

    func downloadFileData(_ file: SpaceFileItem, in space: Space) async throws -> Data {
        try await encryptedMediaService.fileData(
            spaceID: space.id,
            storagePath: file.storagePath,
            nonce: file.nonceBase64
        )
    }

    func renameFile(_ file: SpaceFileItem, in space: Space, to newName: String) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        let trimmedName = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else {
            throw SpaceServiceError.invalidFileName
        }
        try await ensureFileManagementPermission(for: file, in: space)
        let spaceKey = try await ensureGeneralEncryptionKey(in: space)
        let encryptedFileName = try encryptionService.encryptText(trimmedName, using: spaceKey)
        let resolvedFileExtension = (trimmedName as NSString).pathExtension.lowercased().nilIfEmpty
            ?? file.fileExtension

        try await updateData([
            "nameCiphertextBase64": encryptedFileName.ciphertext,
            "nameNonceBase64": encryptedFileName.nonce,
            "name": FieldValue.delete(),
            "fileExtension": resolvedFileExtension,
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id).collection("files").document(file.id))
    }

    func softDeleteFile(_ file: SpaceFileItem, in space: Space) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        try await ensureFileManagementPermission(for: file, in: space)

        try await updateData([
            "deleted": true,
            "deletedAt": FieldValue.serverTimestamp(),
            "deletedBy": session.uid,
            "updatedAt": FieldValue.serverTimestamp()
        ], for: firestore.collection("spaces").document(space.id).collection("files").document(file.id))
    }

    func canManageFile(_ file: SpaceFileItem, in space: Space) async -> Bool {
        do {
            try await ensureFileManagementPermission(for: file, in: space)
            return true
        } catch {
            return false
        }
    }

    func canManageEvent(_ event: SpaceEvent, in space: Space) async -> Bool {
        guard let session = authService.currentSession() else {
            return false
        }
        if event.createdBy == session.uid || space.ownerId == session.uid {
            return true
        }
        do {
            return try await currentUserHasPermission(.deleteOthersContent, in: space)
        } catch {
            return false
        }
    }

    func deleteMessage(in space: Space, messageID: String) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageID)
        let snapshot = try await getDocument(messageReference)
        guard snapshot.exists, let data = snapshot.data() else {
            throw SpaceServiceError.messageNotFound
        }

        let senderID = data["senderId"] as? String
        if senderID != session.uid {
            guard try await currentUserHasPermission(.deleteOthersContent, in: space) else {
                throw SpaceServiceError.messageDeletePermissionDenied
            }
        }

        try await updateData([
            "deleted": true,
            "deletedAt": FieldValue.serverTimestamp(),
            "deletedBy": session.uid,
            "text": "",
            "ciphertextBase64": "",
            "nonceBase64": "",
            "captionCiphertextBase64": "",
            "captionNonceBase64": "",
            "mediaStoragePath": "",
            "thumbnailStoragePath": "",
            "mediaNonceBase64": "",
            "thumbnailNonceBase64": ""
        ], for: messageReference)
    }

    private func fetchCurrentUserRole(in space: Space) async throws -> SpaceMemberRole {
        try await fetchCurrentUserMember(in: space).role
    }

    private func fetchMemberRole(in space: Space, memberID: String) async throws -> SpaceMemberRole {
        if memberID == space.ownerId {
            return .owner
        }

        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        let snapshot = try await getDocument(
            firestore.collection("spaces").document(space.id).collection("members").document(memberID)
        )
        guard snapshot.exists, let member = mapMember(document: snapshot) else {
            throw SpaceServiceError.invitePermissionDenied
        }
        return member.role
    }

    private func fetchCurrentUserMember(in space: Space) async throws -> SpaceMember {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        if session.uid == space.ownerId {
            return SpaceMember(
                id: session.uid,
                displayName: session.displayName,
                emojiAvatar: "🙂",
                role: .owner,
                status: "Active"
            )
        }

        let snapshot = try await getDocument(
            firestore.collection("spaces").document(space.id).collection("members").document(session.uid)
        )
        guard snapshot.exists, let member = mapMember(document: snapshot) else {
            throw SpaceServiceError.invitePermissionDenied
        }
        return member
    }

    private func currentUserHasPermission(_ permission: SpacePermission, in space: Space) async throws -> Bool {
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        if session.uid == space.ownerId {
            return true
        }
        let role = try await fetchCurrentUserRole(in: space)
        return (space.rolePermissionOverrides[role] ?? role.capabilities).contains(permission)
    }

    private func requirePermission(
        _ permission: SpacePermission,
        in space: Space,
        error: SpaceServiceError
    ) async throws {
        guard try await currentUserHasPermission(permission, in: space) else {
            throw error
        }
    }

    private func ensureFileManagementPermission(for file: SpaceFileItem, in space: Space) async throws {
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        if file.uploadedBy == session.uid {
            return
        }

        guard try await currentUserHasPermission(.deleteOthersContent, in: space) else {
            throw SpaceServiceError.filePermissionDenied
        }
    }

    private func ensureCanChangeMemberRole(
        in space: Space,
        memberID: String,
        to newRole: SpaceMemberRole
    ) async throws {
        let currentRole = try await fetchCurrentUserRole(in: space)
        let targetRole = try await fetchMemberRole(in: space, memberID: memberID)

        guard currentRole.canChangeRole(
            of: targetRole,
            to: newRole,
            isTargetOwner: memberID == space.ownerId
        ) else {
            throw memberID == space.ownerId || newRole == .owner
                ? SpaceServiceError.cannotModifyOwner
                : SpaceServiceError.invitePermissionDenied
        }
    }

    private func ensureCanRemoveMember(in space: Space, memberID: String) async throws {
        let currentRole = try await fetchCurrentUserRole(in: space)
        let targetRole = try await fetchMemberRole(in: space, memberID: memberID)

        guard currentRole.canRemove(
            targetRole: targetRole,
            isTargetOwner: memberID == space.ownerId
        ) else {
            throw memberID == space.ownerId
                ? SpaceServiceError.cannotModifyOwner
                : SpaceServiceError.invitePermissionDenied
        }
    }

    private func mimeType(for fileURL: URL) -> String {
        if #available(iOS 14.0, *),
           let contentType = UTType(filenameExtension: fileURL.pathExtension) {
            return contentType.preferredMIMEType ?? "application/octet-stream"
        }
        return "application/octet-stream"
    }

    private func fileExtension(for fileURL: URL, mimeType: String) -> String {
        let pathExtension = fileURL.pathExtension.trimmingCharacters(in: .whitespacesAndNewlines)
        if !pathExtension.isEmpty {
            return pathExtension.lowercased()
        }

        switch mimeType.lowercased() {
        case "application/pdf":
            return "pdf"
        case "image/png":
            return "png"
        case "image/heic":
            return "heic"
        case "image/jpeg":
            return "jpg"
        case "video/quicktime":
            return "mov"
        case "video/mp4":
            return "mp4"
        case "text/plain":
            return "txt"
        case "application/json":
            return "json"
        case "text/csv":
            return "csv"
        default:
            return "dat"
        }
    }

    private func reserveInviteCode() async throws -> String {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        for _ in 0..<10 {
            let code = Self.randomInviteCode()
            let snapshot = try await getDocument(firestore.collection("spaceInvites").document(code))
            if !snapshot.exists {
                return code
            }
        }

        throw SpaceServiceError.unableToCreateInvite
    }

    private func validatedInviteData(from snapshot: DocumentSnapshot) throws -> [String: Any] {
        guard snapshot.exists, let inviteData = snapshot.data() else {
            throw SpaceServiceError.inviteNotFound
        }
        guard (inviteData["active"] as? Bool) == true else {
            throw SpaceServiceError.inviteInactive
        }

        let expiresAt = (inviteData["expiresAt"] as? Timestamp)?.dateValue() ?? .distantPast
        guard expiresAt > Date() else {
            throw SpaceServiceError.inviteExpired
        }

        let maxUses = inviteData["maxUses"] as? Int ?? 0
        let usedCount = inviteData["usedCount"] as? Int ?? 0
        guard usedCount < maxUses else {
            throw SpaceServiceError.inviteMaxedOut
        }

        return inviteData
    }

    private func joinedMemberIDs(from spaceData: [String: Any], appending userID: String) throws -> [String] {
        var memberIDs = spaceData["memberIds"] as? [String] ?? []
        guard !memberIDs.contains(userID) else {
            throw SpaceServiceError.alreadyMember
        }
        memberIDs.append(userID)
        return memberIDs
    }

    private func memberDocumentData(
        userID: String,
        displayName: String,
        emojiAvatar: String,
        role: SpaceMemberRole
    ) -> [String: Any] {
        [
            "userId": userID,
            "displayName": displayName,
            "emojiAvatar": emojiAvatar,
            "role": role.firestoreValue,
            "joinedAt": FieldValue.serverTimestamp()
        ]
    }

    private func sanitizeEnabledModules(_ modules: [SpaceModule], for template: SpaceTemplate) -> [SpaceModule] {
        let requested = Set(modules.filter { $0 != .settings })
        var resolved = SpaceModule.requiredModules

        for module in SpaceModule.optionalModules where requested.contains(module) {
            resolved.append(module)
        }

        return SpaceModule.configurableModules.filter { resolved.contains($0) }
    }

    private func sanitizeModuleOrder(_ modules: [SpaceModule], enabledModules: [SpaceModule]) -> [SpaceModule] {
        var ordered: [SpaceModule] = []

        for module in modules where SpaceModule.allModules.contains(module) && !ordered.contains(module) {
            ordered.append(module)
        }

        let fallback = enabledModules + SpaceModule.optionalModules.filter { !enabledModules.contains($0) } + [.settings]
        for module in fallback where !ordered.contains(module) {
            ordered.append(module)
        }

        for module in SpaceModule.allModules where !ordered.contains(module) {
            ordered.append(module)
        }

        return ordered
    }

    private func latestEnabledModules(in space: Space) async throws -> [SpaceModule] {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }

        let snapshot = try await getDocument(
            firestore.collection("spaces").document(space.id)
        )
        guard let data = snapshot.data() else {
            return sanitizeEnabledModules(space.enabledModules, for: space.template)
        }
        return parseEnabledModules(from: data, template: space.template)
    }

    private func parseEnabledModules(from data: [String: Any], template: SpaceTemplate) -> [SpaceModule] {
        let storedIDs = (data["enabledModules"] as? [String] ?? [])
            .compactMap(SpaceModule.init(rawValue:))
        if !storedIDs.isEmpty {
            return sanitizeEnabledModules(storedIDs, for: template)
        }
        return sanitizeEnabledModules(template.defaultEnabledModules, for: template)
    }

    private func parseModuleOrder(
        from data: [String: Any],
        template: SpaceTemplate,
        enabledModules: [SpaceModule]
    ) -> [SpaceModule] {
        let storedOrder = (data["moduleOrder"] as? [String] ?? [])
            .compactMap(SpaceModule.init(rawValue:))
        let fallback = storedOrder.isEmpty ? template.defaultModuleOrder : storedOrder
        return sanitizeModuleOrder(fallback, enabledModules: enabledModules)
    }

    private func mappedSpace(data: [String: Any], documentID: String) -> Space {
        let template = (data["template"] as? String).flatMap(SpaceTemplate.init(rawValue:)) ?? .custom
        let enabledModules = parseEnabledModules(from: data, template: template)
        let moduleOrder = parseModuleOrder(from: data, template: template, enabledModules: enabledModules)
        let overrideData = data["rolePermissionOverrides"] as? [String: [String]] ?? [:]
        var overrides: [SpaceMemberRole: Set<SpacePermission>] = [:]
        for (key, values) in overrideData {
            guard let role = SpaceMemberRole(firestoreValue: key) else { continue }
            overrides[role] = Set(values.compactMap { SpacePermission(rawValue: $0) })
        }
        return Space(
            id: data["id"] as? String ?? documentID,
            name: data["name"] as? String ?? "Untitled Space",
            emoji: (data["emoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🏠",
            tintHex: data["color"] as? String ?? "#4F46E5",
            description: data["description"] as? String ?? template.defaultSubtitle,
            template: template,
            ownerId: data["ownerId"] as? String ?? "",
            memberIds: data["memberIds"] as? [String] ?? [],
            unreadCount: nil,
            enabledModules: enabledModules,
            moduleOrder: moduleOrder,
            rolePermissionOverrides: overrides
        )
    }

    private func normalizedSpaceOrder(_ spaceIDs: [String]) -> [String] {
        var seen = Set<String>()
        return spaceIDs.compactMap { rawID in
            let trimmed = rawID.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, seen.insert(trimmed).inserted else {
                return nil
            }
            return trimmed
        }
    }

    func mapSpaceForFeature(document: DocumentSnapshot) -> Space? {
        mapSpace(document: document)
    }

    func encryptionKeyForAuthorizedFeature(spaceID: String) async throws -> SymmetricKey {
        guard let firestore else { throw SpaceServiceError.firestoreNotConfigured }
        let snapshot = try await firestore.collection("spaces").document(spaceID).getDocument()
        guard let space = mapSpace(document: snapshot) else { throw SpaceServiceError.spaceKeyUnavailable }
        return try await ensureSpaceKey(in: space)
    }

    func encryptionKeyForModuleData(spaceID: String) async throws -> SymmetricKey {
        try await ensureGeneralEncryptionKey(spaceID: spaceID)
    }

    private func mapSpace(document: DocumentSnapshot) -> Space? {
        guard let data = document.data() else { return nil }
        return mappedSpace(data: data, documentID: document.documentID)
    }

    private func mapMember(document: DocumentSnapshot) -> SpaceMember? {
        guard let data = document.data() else { return nil }
        let role = (data["role"] as? String).flatMap(SpaceMemberRole.init(firestoreValue:)) ?? .member
        return SpaceMember(
            id: data["userId"] as? String ?? document.documentID,
            displayName: data["displayName"] as? String ?? "Member",
            emojiAvatar: (data["emojiAvatar"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🙂",
            role: role,
            status: "Active"
        )
    }

    private func mapFolder(document: DocumentSnapshot) -> SpaceFolder? {
        guard let data = document.data() else { return nil }
        return SpaceFolder(
            id: data["id"] as? String ?? document.documentID,
            name: data["name"] as? String ?? "Folder",
            createdBy: data["createdBy"] as? String ?? "Member",
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue()
        )
    }

    private func mapFile(document: DocumentSnapshot, spaceKey: SymmetricKey) throws -> SpaceFileItem? {
        guard let data = document.data() else { return nil }
        let sizeNumber = data["fileSize"] as? NSNumber
        let resolvedName: String
        if let ciphertext = (data["nameCiphertextBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
           let nonce = (data["nameNonceBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
            resolvedName = (try? encryptionService.decryptText(
                ciphertext: ciphertext,
                nonce: nonce,
                using: spaceKey
            )) ?? "Untitled File"
        } else {
            resolvedName = (data["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "Untitled File"
        }
        return SpaceFileItem(
            id: data["id"] as? String ?? document.documentID,
            spaceID: data["spaceId"] as? String ?? document.reference.parent.parent?.documentID ?? "",
            name: resolvedName,
            mimeType: data["mimeType"] as? String ?? "application/octet-stream",
            folderId: (data["folderId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            storagePath: data["storagePath"] as? String ?? "spaces/\(document.reference.parent.parent?.documentID ?? "")/files/\(document.documentID).enc",
            encryptionVersion: data["encryptionVersion"] as? String ?? generalEncryptionVersion,
            nonceBase64: data["nonceBase64"] as? String ?? "",
            uploadedBy: data["uploadedBy"] as? String ?? "",
            uploadedByName: data["uploadedByName"] as? String ?? "Member",
            fileExtension: data["fileExtension"] as? String ?? (resolvedName as NSString).pathExtension.lowercased().nilIfEmpty ?? "dat",
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue(),
            sizeBytes: sizeNumber?.int64Value ?? 0,
            deleted: data["deleted"] as? Bool ?? false
        )
    }

    private func mapPoll(document: DocumentSnapshot) -> SpacePoll? {
        guard let data = document.data() else { return nil }
        let optionMaps = data["options"] as? [[String: Any]] ?? []
        let options = optionMaps.compactMap { optionData -> SpacePollOption? in
            guard let id = optionData["id"] as? String,
                  let text = optionData["text"] as? String else {
                return nil
            }
            return SpacePollOption(id: id, text: text)
        }

        return SpacePoll(
            id: data["id"] as? String ?? document.documentID,
            spaceID: data["spaceId"] as? String ?? document.reference.parent.parent?.documentID ?? "",
            question: data["question"] as? String ?? "Untitled Poll",
            options: options,
            createdBy: data["createdBy"] as? String ?? "",
            createdByName: data["createdByName"] as? String ?? "Member",
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue(),
            closesAt: (data["closesAt"] as? Timestamp)?.dateValue(),
            allowMultipleVotes: data["allowMultipleVotes"] as? Bool ?? false,
            anonymous: data["anonymous"] as? Bool ?? false,
            deleted: data["deleted"] as? Bool ?? false
        )
    }

    private func mapPollVote(document: DocumentSnapshot) -> SpacePollVote? {
        guard let data = document.data() else { return nil }
        return SpacePollVote(
            id: document.documentID,
            userID: data["userId"] as? String ?? document.documentID,
            displayName: data["displayName"] as? String,
            emojiAvatar: data["emojiAvatar"] as? String,
            optionIDs: data["optionIds"] as? [String] ?? [],
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue()
        )
    }

    private func mapEvent(document: DocumentSnapshot) -> SpaceEvent? {
        guard let data = document.data() else { return nil }
        guard let startDate = (data["startDate"] as? Timestamp)?.dateValue(),
              let endDate = (data["endDate"] as? Timestamp)?.dateValue() else {
            return nil
        }

        return SpaceEvent(
            id: data["id"] as? String ?? document.documentID,
            spaceID: data["spaceId"] as? String ?? document.reference.parent.parent?.documentID ?? "",
            title: data["title"] as? String ?? "Untitled Event",
            description: data["description"] as? String ?? "",
            location: data["location"] as? String ?? "",
            startDate: startDate,
            endDate: endDate,
            allDay: data["allDay"] as? Bool ?? false,
            timeZoneIdentifier: data["timezone"] as? String ?? TimeZone.current.identifier,
            createdBy: data["createdBy"] as? String ?? "",
            createdByName: data["createdByName"] as? String ?? "Member",
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue(),
            deleted: data["deleted"] as? Bool ?? false
        )
    }

    private func mapInvite(document: DocumentSnapshot) -> SpaceInvite? {
        guard let data = document.data() else { return nil }
        let code = data["code"] as? String ?? document.documentID
        let createdAt = (data["createdAt"] as? Timestamp)?.dateValue() ?? Date()
        let expiresAt = (data["expiresAt"] as? Timestamp)?.dateValue() ?? createdAt.addingTimeInterval(604800)
        return SpaceInvite(
            id: code,
            code: code,
            spaceId: data["spaceId"] as? String ?? "",
            spaceName: data["spaceName"] as? String ?? "Space",
            spaceEmoji: (data["spaceEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🏠",
            createdBy: data["createdBy"] as? String ?? "",
            createdAt: createdAt,
            expiresAt: expiresAt,
            maxUses: data["maxUses"] as? Int ?? 0,
            usedCount: data["usedCount"] as? Int ?? 0,
            active: data["active"] as? Bool ?? false
        )
    }

    private func mapActivity(document: DocumentSnapshot) -> ActivityItem? {
        guard let data = document.data() else { return nil }
        guard let typeRaw = (data["type"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
#if DEBUG
            print("[Activity] Missing type for document \(document.documentID): \(data)")
#endif
            return nil
        }
        guard let type = ActivityItemType(rawValue: typeRaw) else {
            print("[Activity] Unable to map type: \(typeRaw)")
#if DEBUG
            print("[Activity] Document data: \(data)")
#endif
            return nil
        }
        let targetTypeRaw = (data["targetType"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let targetType = targetTypeRaw.flatMap(ActivityTargetType.init(rawValue:))
        if let targetTypeRaw, targetType == nil {
#if DEBUG
            print("[Activity] Unable to map targetType: \(targetTypeRaw)")
            print("[Activity] Document data: \(data)")
#endif
        }

        return ActivityItem(
            id: data["id"] as? String ?? document.documentID,
            spaceID: data["spaceId"] as? String ?? "",
            spaceName: data["spaceName"] as? String ?? "Space",
            spaceEmoji: (data["spaceEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🏠",
            actorID: data["actorId"] as? String ?? "",
            actorName: data["actorName"] as? String ?? "Member",
            actorEmoji: (data["actorEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            type: type,
            title: data["title"] as? String ?? "updated this Space",
            subtitle: (data["subtitle"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            targetID: (data["targetId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            targetType: targetType,
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            readBy: data["readBy"] as? [String] ?? [],
            hiddenBy: data["hiddenBy"] as? [String] ?? []
        )
    }

    private func mapNotification(document: DocumentSnapshot) -> SpaceNotificationItem? {
        guard let data = document.data() else { return nil }
        guard let typeRaw = (data["type"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
#if DEBUG
            print("[Notifications] Missing type for document \(document.documentID): \(data)")
#endif
            return nil
        }
        guard let type = SpaceNotificationType(rawValue: typeRaw) else {
#if DEBUG
            print("[Notifications] Unable to map type: \(typeRaw)")
            print("[Notifications] Document data: \(data)")
#endif
            return nil
        }

        let targetTypeRaw = (data["targetType"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let targetType = targetTypeRaw.flatMap(ActivityTargetType.init(rawValue:))

        return SpaceNotificationItem(
            id: data["id"] as? String ?? document.documentID,
            recipientId: data["recipientId"] as? String ?? "",
            actorId: data["actorId"] as? String ?? "",
            actorName: data["actorName"] as? String ?? "Member",
            actorEmoji: (data["actorEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            spaceId: data["spaceId"] as? String ?? "",
            spaceName: data["spaceName"] as? String ?? "Space",
            spaceEmoji: (data["spaceEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "🏠",
            type: type,
            title: data["title"] as? String ?? "updated a Space",
            subtitle: (data["subtitle"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            targetId: (data["targetId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            targetType: targetType,
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
            read: data["read"] as? Bool ?? false,
            readAt: (data["readAt"] as? Timestamp)?.dateValue(),
            delivered: data["delivered"] as? Bool ?? false,
            deliveredAt: (data["deliveredAt"] as? Timestamp)?.dateValue()
        )
    }

    func recordModuleActivity(
        type: ActivityItemType,
        in space: Space,
        title: String,
        subtitle: String? = nil,
        targetID: String? = nil,
        targetType: ActivityTargetType,
        notifyMembers: Bool = true
    ) async {
        guard let user = Auth.auth().currentUser else { return }
        await recordActivity(
            type: type,
            in: space,
            actorID: user.uid,
            actorName: user.displayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? user.displayName! : "Member",
            actorEmoji: nil,
            title: title,
            subtitle: subtitle,
            targetID: targetID,
            targetType: targetType,
            shouldNotify: notifyMembers
        )
    }

    func mentionedMemberIDs(in text: String, space: Space) async -> [String] {
        guard let firestore else { return [] }
        guard let snapshot = try? await firestore.collection("spaces").document(space.id).collection("members").getDocuments() else {
            return []
        }
        let lowered = text.lowercased()
        return snapshot.documents.compactMap { document in
            let data = document.data()
            let displayName = (data["displayName"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let userID = (data["userId"] as? String ?? document.documentID).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !displayName.isEmpty, !userID.isEmpty, lowered.contains("@\(displayName.lowercased())") else { return nil }
            return userID
        }
    }

    func memberIDs(in space: Space) async -> [String] {
        await resolvedVisibleUserIDs(for: space)
    }

    func recordTargetedModuleNotification(
        recipientIDs: [String],
        type: SpaceNotificationType,
        in space: Space,
        title: String,
        subtitle: String? = nil,
        targetID: String,
        targetType: ActivityTargetType
    ) async {
        guard let firestore, let user = Auth.auth().currentUser else { return }
        let actorName = user.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        for recipientID in Set(recipientIDs) where !recipientID.isEmpty && recipientID != user.uid {
            guard await notificationsEnabled(
                for: recipientID,
                spaceID: space.id,
                notificationType: type,
                targetType: targetType,
                title: title
            ) else { continue }
            let reference = firestore.collection("notifications").document()
            var payload: [String: Any] = [
                "id": reference.documentID,
                "recipientId": recipientID,
                "actorId": user.uid,
                "actorName": actorName?.isEmpty == false ? actorName! : "Member",
                "spaceId": space.id,
                "spaceName": space.name,
                "spaceEmoji": space.emoji,
                "type": type.rawValue,
                "title": notificationPushTitle(
                    actorName: actorName?.isEmpty == false ? actorName! : "Member",
                    actionTitle: title
                ),
                "targetId": targetID,
                "targetType": targetType.rawValue,
                "createdAt": FieldValue.serverTimestamp(),
                "read": false,
                "delivered": false
            ]
            if let subtitle, !subtitle.isEmpty { payload["subtitle"] = subtitle }
            try? await setData(payload, for: reference)
        }
    }

    private func recordActivity(
        type: ActivityItemType,
        in space: Space,
        actorID: String,
        actorName: String,
        actorEmoji: String?,
        title: String,
        subtitle: String? = nil,
        targetID: String? = nil,
        targetType: ActivityTargetType? = nil,
        shouldNotify: Bool = true
    ) async {
        let visibleActivityTypes: Set<ActivityItemType> = [
            .spaceCreated, .memberJoined, .messageSent, .replyAdded, .reactionAdded, .photoShared, .videoShared,
            .fileUploaded, .pollCreated, .eventCreated, .announcementCreated,
            .roomCreated, .roomMessageSent, .listCreated, .noteCreated
        ]
        guard visibleActivityTypes.contains(type) else { return }
        guard let firestore else { return }

        let activityReference = firestore.collection("activity").document()
        let visibleUserIDs = await resolvedVisibleUserIDs(for: space)
        guard !visibleUserIDs.isEmpty else {
            print("[SpaceService][Activity] Skipping activity write because no visible users were resolved for space \(space.id)")
            return
        }
        let sanitizedSubtitle = sanitizedActivitySubtitle(for: type, original: subtitle)
        var payload: [String: Any] = [
            "id": activityReference.documentID,
            "spaceId": space.id,
            "spaceName": space.name,
            "spaceEmoji": space.emoji,
            "actorId": actorID,
            "actorName": actorName,
            "type": type.rawValue,
            "title": title,
            "createdAt": FieldValue.serverTimestamp(),
            "readBy": [actorID],
            "hiddenBy": [],
            "visibleTo": visibleUserIDs
        ]
        if let actorEmoji, !actorEmoji.isEmpty {
            payload["actorEmoji"] = actorEmoji
        }
        if let sanitizedSubtitle, !sanitizedSubtitle.isEmpty {
            payload["subtitle"] = sanitizedSubtitle
        }
        if let targetID, !targetID.isEmpty {
            payload["targetId"] = targetID
        }
        if let targetType {
            payload["targetType"] = targetType.rawValue
        }

        do {
            try await setData(payload, for: activityReference)
            if shouldNotify {
                await recordNotifications(
                for: type,
                in: space,
                actorID: actorID,
                actorName: actorName,
                actorEmoji: actorEmoji,
                title: title,
                subtitle: sanitizedSubtitle,
                targetID: targetID,
                targetType: targetType,
                visibleUserIDs: visibleUserIDs
                )
            }
        } catch {
            print("[SpaceService][Activity] Failed to record activity: \(error.localizedDescription)")
        }
    }

    private func recordNotifications(
        for activityType: ActivityItemType,
        in space: Space,
        actorID: String,
        actorName: String,
        actorEmoji: String?,
        title: String,
        subtitle: String?,
        targetID: String?,
        targetType: ActivityTargetType?,
        visibleUserIDs: [String]
    ) async {
        guard let firestore else { return }
        guard let notificationType = notificationType(for: activityType) else { return }

        let recipientIDs = visibleUserIDs.filter { $0 != actorID }
        guard !recipientIDs.isEmpty else { return }
        let notificationSubtitle = sanitizedNotificationSubtitle(for: activityType, original: subtitle)

        for recipientID in recipientIDs {
            guard await notificationsEnabled(
                for: recipientID,
                spaceID: space.id,
                notificationType: notificationType,
                targetType: targetType,
                title: title
            ) else { continue }
            let notificationReference = firestore.collection("notifications").document()
            var payload: [String: Any] = [
                "id": notificationReference.documentID,
                "recipientId": recipientID,
                "actorId": actorID,
                "actorName": actorName,
                "spaceId": space.id,
                "spaceName": space.name,
                "spaceEmoji": space.emoji,
                "type": notificationType.rawValue,
                "title": notificationPushTitle(actorName: actorName, actionTitle: title),
                "createdAt": FieldValue.serverTimestamp(),
                "read": false,
                "delivered": false
            ]
            if let actorEmoji, !actorEmoji.isEmpty {
                payload["actorEmoji"] = actorEmoji
            }
            if let notificationSubtitle, !notificationSubtitle.isEmpty {
                payload["subtitle"] = notificationSubtitle
            }
            if let targetID, !targetID.isEmpty {
                payload["targetId"] = targetID
            }
            if let targetType {
                payload["targetType"] = targetType.rawValue
            }

            do {
                try await setData(payload, for: notificationReference)
            } catch {
                print("[SpaceService][Notifications] Failed to record notification: \(error.localizedDescription)")
            }
        }
    }

    private func notificationPushTitle(actorName: String, actionTitle: String) -> String {
        let name = actorName.trimmingCharacters(in: .whitespacesAndNewlines)
        let action = actionTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return action }
        let actionAlreadyIncludesActor = action.range(
            of: name,
            options: [.caseInsensitive, .anchored]
        ) != nil
        guard !actionAlreadyIncludesActor else { return action }
        return "\(name) \(action)"
    }

    private func notificationsEnabled(
        for userID: String,
        spaceID: String,
        notificationType: SpaceNotificationType,
        targetType: ActivityTargetType?,
        title: String
    ) async -> Bool {
        guard let firestore,
              let snapshot = try? await firestore.collection("users").document(userID).getDocument() else { return true }
        let settings = SpaceNotificationPreferencesService.decode(snapshot.data()?["spaceNotificationSettings"])
        guard let preference = settings[spaceID], preference.allEnabled else { return settings[spaceID] == nil }
        return preference.isEnabled(notificationCategory(for: notificationType, targetType: targetType, title: title))
    }

    private func notificationCategory(
        for type: SpaceNotificationType,
        targetType: ActivityTargetType?,
        title: String
    ) -> String {
        let normalizedTitle = title.lowercased()
        if normalizedTitle.contains("mention") {
            return SpaceNotificationCategory.mentions
        }
        if normalizedTitle.contains("comment") || type == .reply {
            return SpaceNotificationCategory.commentsAndReplies
        }
        if normalizedTitle.contains("assign") {
            return SpaceNotificationCategory.assignments
        }
        switch type {
        case .announcement:
            return SpaceNotificationCategory.announcements
        case .room:
            return SpaceNotificationCategory.rooms
        case .note:
            return SpaceNotificationCategory.notes
        case .list:
            return SpaceNotificationCategory.lists
        case .eventCreated, .eventUpdated:
            return SpaceNotificationCategory.events
        case .pollCreated:
            return SpaceNotificationCategory.polls
        case .photoShared, .videoShared, .fileUploaded:
            return SpaceNotificationCategory.mediaAndFiles
        default:
            switch targetType {
            case .announcements: return SpaceNotificationCategory.announcements
            case .rooms: return SpaceNotificationCategory.rooms
            case .notes: return SpaceNotificationCategory.notes
            case .lists: return SpaceNotificationCategory.lists
            case .events: return SpaceNotificationCategory.events
            case .polls: return SpaceNotificationCategory.polls
            case .photos, .files: return SpaceNotificationCategory.mediaAndFiles
            default: return SpaceNotificationCategory.rooms
            }
        }
    }

    private func notificationType(for activityType: ActivityItemType) -> SpaceNotificationType? {
        switch activityType {
        case .messageSent:
            return .newMessage
        case .replyAdded:
            return .reply
        case .reactionAdded:
            return .reaction
        case .photoShared:
            return .photoShared
        case .videoShared:
            return .videoShared
        case .fileUploaded:
            return .fileUploaded
        case .pollCreated:
            return .pollCreated
        case .eventCreated:
            return .eventCreated
        case .eventUpdated:
            return .eventUpdated
        case .memberJoined:
            return .memberJoined
        case .announcementCreated, .announcementUpdated, .announcementCommented:
            return .announcement
        case .roomCreated, .roomMessageSent:
            return .room
        case .listCreated, .listUpdated:
            return .list
        case .noteCreated, .noteUpdated, .noteCommented:
            return .note
        case .spaceCreated, .pollVoted:
            return nil
        }
    }

    private func sanitizedActivitySubtitle(for activityType: ActivityItemType, original: String?) -> String? {
        switch activityType {
        case .photoShared, .videoShared, .fileUploaded:
            return nil
        default:
            return original
        }
    }

    private func sanitizedNotificationSubtitle(for activityType: ActivityItemType, original: String?) -> String? {
        switch activityType {
        case .messageSent, .replyAdded, .reactionAdded, .photoShared, .videoShared, .memberJoined:
            return nil
        case .fileUploaded:
            return nil
        case .pollCreated, .eventCreated, .eventUpdated,
             .announcementCreated, .announcementUpdated, .announcementCommented,
             .roomCreated, .roomMessageSent, .listCreated, .listUpdated,
             .noteCreated, .noteUpdated, .noteCommented:
            return original
        case .spaceCreated, .pollVoted:
            return nil
        }
    }

    private func resolvedVisibleUserIDs(for space: Space) async -> [String] {
        let trimmedMemberIDs = Array(
            Set(
                space.memberIds
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
            )
        ).sorted()
        if !trimmedMemberIDs.isEmpty {
            return trimmedMemberIDs
        }

        guard let firestore else {
            return []
        }

        do {
            let snapshot = try await getDocuments(
                firestore.collection("spaces")
                    .document(space.id)
                    .collection("members")
            )
            return Array(
                Set(
                    snapshot.documents.compactMap { document in
                        ((document.data()["userId"] as? String) ?? document.documentID)
                            .trimmingCharacters(in: .whitespacesAndNewlines)
                            .nilIfEmpty
                    }
                )
            ).sorted()
        } catch {
            print("[SpaceService][Activity] Failed to resolve visible users for space \(space.id): \(error.localizedDescription)")
            return []
        }
    }

    private func mapMessage(
        document: DocumentSnapshot,
        currentUserID: String?,
        spaceKey: SymmetricKey
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
        if type == .image || type == .video || type == .meme || type == .gif {
            if deleted {
                return SpaceMessage(
                    id: data["id"] as? String ?? document.documentID,
                    spaceId: data["spaceId"] as? String,
                    senderId: senderID,
                    senderName: data["senderName"] as? String ?? "Member",
                    senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                    type: type,
                    encryptionVersion: inferredGeneralEncryptionVersion(from: data),
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
            let caption: String?
            if let captionCiphertext = (data["captionCiphertextBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
               let captionNonce = (data["captionNonceBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
                do {
                    caption = try encryptionService.decryptText(
                        ciphertext: captionCiphertext,
                        nonce: captionNonce,
                        using: spaceKey
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
            let mediaItems = mappedMediaItems(
                from: data,
                documentID: document.documentID,
                spaceID: data["spaceId"] as? String,
                senderID: senderID,
                senderName: data["senderName"] as? String ?? "Member",
                caption: caption,
                createdAt: createdAt,
                fallbackType: type
            )
            let primaryMedia = mediaItems.first
            if let primaryMedia, type == .gif || primaryMedia.mediaCategory == "gif" || primaryMedia.mediaType == .gif {
                print("[GIF Receive] message model mapped id=\(document.documentID) type=\(type.rawValue) mediaCategory=\(primaryMedia.mediaCategory ?? "") mediaType=\(primaryMedia.mediaType.rawValue) storagePath=\(primaryMedia.mediaStoragePath ?? "") thumbnailStoragePath=\(primaryMedia.thumbnailStoragePath ?? "")")
            }
            return SpaceMessage(
                id: data["id"] as? String ?? document.documentID,
                spaceId: data["spaceId"] as? String,
                senderId: senderID,
                senderName: data["senderName"] as? String ?? "Member",
                senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                type: type,
                encryptionVersion: inferredGeneralEncryptionVersion(from: data),
                deleted: deleted,
                text: nil,
                media: primaryMedia,
                mediaItems: mediaItems,
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
        let inferredEncryptionVersion = (data["encryptionVersion"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? "none"
        if deleted {
            return SpaceMessage(
                id: data["id"] as? String ?? document.documentID,
                spaceId: data["spaceId"] as? String,
                senderId: senderID,
                senderName: data["senderName"] as? String ?? "Member",
                senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                type: .text,
                encryptionVersion: inferredEncryptionVersion,
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
        let resolvedText: String
        let resolvedLinkPreview: LinkPreviewData?
        let resolvedSpaceLinks: [SpaceLinkAttachment]
        switch inferredEncryptionVersion {
        case let version where version == generalEncryptionVersion:
            guard
                let ciphertext = (data["ciphertextBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                let nonce = (data["nonceBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            else {
                resolvedText = "Unable to decrypt message"
                resolvedLinkPreview = nil
                resolvedSpaceLinks = []
                break
            }
            logStoredMessagePayload(
                messageID: data["id"] as? String ?? document.documentID,
                senderID: senderID,
                encryptionVersion: inferredEncryptionVersion,
                ciphertext: ciphertext,
                nonce: nonce
            )

            do {
                let decryptedContent = try encryptionService.decryptText(
                    ciphertext: ciphertext,
                    nonce: nonce,
                    using: spaceKey
                )
                let decodedContent = decodeTextMessageContent(from: decryptedContent)
                resolvedText = decodedContent.text
                resolvedLinkPreview = decodedContent.linkPreview
                resolvedSpaceLinks = decodedContent.spaceLinks
            } catch {
                print("[SpaceService][DecryptFailure] messageId=\(data["id"] as? String ?? document.documentID) reason=\(error.localizedDescription)")
                resolvedText = "Unable to decrypt message"
                resolvedLinkPreview = nil
                resolvedSpaceLinks = []
            }
        default:
            return nil
        }

        return SpaceMessage(
            id: data["id"] as? String ?? document.documentID,
            spaceId: data["spaceId"] as? String,
            senderId: senderID,
            senderName: data["senderName"] as? String ?? "Member",
            senderEmoji: (data["senderEmoji"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            type: type,
            encryptionVersion: inferredEncryptionVersion,
            deleted: deleted,
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
            linkPreview: resolvedLinkPreview,
            spaceLinks: resolvedSpaceLinks,
            reactions: []
        )
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
        return MessageReplyContext(messageId: messageId, senderName: senderName, type: type, preview: preview)
    }

    private func encodeTextMessageContent(
        text: String,
        linkPreview: LinkPreviewData?,
        spaceLinks: [SpaceLinkAttachment]
    ) throws -> String {
        let content = EncryptedTextMessageContent(
            version: 1,
            text: text,
            linkPreview: linkPreview,
            spaceLinks: spaceLinks.isEmpty ? nil : spaceLinks
        )
        let data = try JSONEncoder().encode(content)
        guard let json = String(data: data, encoding: .utf8) else {
            throw SpaceServiceError.unableToLoadMessages
        }
        return json
    }

    private func decodeTextMessageContent(from decryptedValue: String) -> (text: String, linkPreview: LinkPreviewData?, spaceLinks: [SpaceLinkAttachment]) {
        guard let data = decryptedValue.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(EncryptedTextMessageContent.self, from: data) else {
            return (decryptedValue, nil, [])
        }
        return (decoded.text, decoded.linkPreview, decoded.spaceLinks ?? [])
    }

    private func mappedMediaItems(
        from data: [String: Any],
        documentID: String,
        spaceID: String?,
        senderID: String?,
        senderName: String,
        caption: String?,
        createdAt: Date?,
        fallbackType: MessageType
    ) -> [SpaceMedia] {
        let sortedItems = (data["mediaItems"] as? [[String: Any]])?.sorted {
            ($0["order"] as? Int ?? 0) < ($1["order"] as? Int ?? 0)
        } ?? []
        let itemDictionaries = sortedItems.isEmpty ? [data] : sortedItems

        return itemDictionaries.compactMap { item in
            let mediaCategory = (item["mediaCategory"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            let resolvedMediaType = (item["mediaType"] as? String)
                .flatMap(MediaType.init(rawValue:))
                ?? MediaType(rawValue: mediaCategory ?? "")
                ?? .photo
            let metadata = EncryptedMediaMetadata(
                mediaId: (item["mediaId"] as? String) ?? (item["id"] as? String) ?? documentID,
                mediaType: resolvedMediaType,
                storagePath: (item["storagePath"] as? String) ?? (item["mediaStoragePath"] as? String) ?? "",
                thumbnailStoragePath: (item["thumbnailStoragePath"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                encryptionVersion: inferredGeneralEncryptionVersion(from: item),
                nonce: (item["nonce"] as? String) ?? (item["mediaNonceBase64"] as? String) ?? "",
                thumbnailNonce: (item["thumbnailNonce"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                    ?? (item["thumbnailNonceBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                mimeType: (item["mimeType"] as? String) ?? "image/jpeg",
                fileSize: item["fileSize"] as? Int ?? 0,
                width: item["width"] as? Int,
                height: item["height"] as? Int,
                duration: item["duration"] as? Double,
                createdAt: createdAt,
                uploadedBy: (item["uploadedBy"] as? String) ?? (senderID ?? "")
            )
            guard !metadata.storagePath.isEmpty, !metadata.nonce.isEmpty else {
                return nil
            }

            let resolvedType: MessageType
            switch resolvedMediaType {
            case .video:
                resolvedType = .video
            case .gif:
                resolvedType = .gif
            case .meme:
                resolvedType = .meme
            default:
                resolvedType = fallbackType == .video ? .video : .image
            }

            return SpaceMedia(
                id: (item["id"] as? String) ?? metadata.mediaId,
                spaceID: spaceID,
                type: resolvedType,
                mediaCategory: mediaCategory,
                mediaType: resolvedMediaType,
                placeholderImageName: resolvedMediaType.defaultPlaceholderImageName,
                caption: caption,
                senderName: senderName,
                timestamp: Self.messageTimestampFormatter.string(from: createdAt ?? Date()),
                metadata: metadata,
                mediaStoragePath: metadata.storagePath,
                thumbnailStoragePath: metadata.thumbnailStoragePath,
                mediaNonceBase64: metadata.nonce,
                thumbnailNonceBase64: metadata.thumbnailNonce
            )
        }
    }

    private func deliveryStatus(for status: String?, isOutgoing: Bool) -> String? {
        isOutgoing ? status?.capitalized : nil
    }

    private func mapReactions(_ documents: [QueryDocumentSnapshot], currentUserID: String?) -> [MessageReaction] {
        var countsByEmoji: [String: Int] = [:]
        var namesByEmoji: [String: [String]] = [:]
        var selectedEmoji: String?

        for document in documents {
            guard let emoji = (document.data()["emoji"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty else { continue }
            countsByEmoji[emoji, default: 0] += 1

            if let userName = (document.data()["userName"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty,
               !(namesByEmoji[emoji] ?? []).contains(userName) {
                namesByEmoji[emoji, default: []].append(userName)
            }

            let userID = (document.data()["userId"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty ?? document.documentID
            if userID == currentUserID {
                selectedEmoji = emoji
            }
        }

        let defaultIndices = Dictionary(uniqueKeysWithValues: defaultReactionOrder.enumerated().map { ($1, $0) })

        return countsByEmoji.map { emoji, count in
            MessageReaction(
                emoji: emoji,
                count: count,
                isSelectedByCurrentUser: emoji == selectedEmoji,
                userNames: namesByEmoji[emoji] ?? []
            )
        }
        .sorted { lhs, rhs in
            let lhsIndex = defaultIndices[lhs.emoji] ?? Int.max
            let rhsIndex = defaultIndices[rhs.emoji] ?? Int.max
            if lhsIndex != rhsIndex { return lhsIndex < rhsIndex }
            if lhs.count != rhs.count { return lhs.count > rhs.count }
            return lhs.emoji < rhs.emoji
        }
    }

    private func ensureGeneralEncryptionKey(in space: Space) async throws -> SymmetricKey {
        try await ensureGeneralEncryptionKey(spaceID: space.id)
    }

    private func ensureGeneralEncryptionKey(spaceID: String) async throws -> SymmetricKey {
        if let cachedKey = encryptionService.cachedSpaceKey(for: spaceID) {
            return cachedKey
        }
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }

        let reference = generalEncryptionKeyReference(spaceID: spaceID)
        if let snapshot = try? await getDocument(reference),
           snapshot.exists,
           let data = snapshot.data(),
           let keyBase64 = (data["keyBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
            let key = try encryptionService.decodeSpaceKey(keyBase64)
            encryptionService.cacheSpaceKey(key, for: spaceID)
            return key
        }

        let keyBase64 = encryptionService.generateSpaceKeyBase64()
        try? await setData([
            "keyVersion": generalEncryptionVersion,
            "keyBase64": keyBase64,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "createdBy": session.uid
        ], for: reference)

        let createdSnapshot = try await getDocument(reference)
        guard
            createdSnapshot.exists,
            let createdData = createdSnapshot.data(),
            let createdKeyBase64 = (createdData["keyBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        else {
            throw SpaceServiceError.unableToLoadMessages
        }

        let key = try encryptionService.decodeSpaceKey(createdKeyBase64)
        encryptionService.cacheSpaceKey(key, for: spaceID)
        return key
    }

    private func inferredGeneralEncryptionVersion(from data: [String: Any]) -> String {
        (data["encryptionVersion"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? generalEncryptionVersion
    }

    private func ensureSpaceKey(in space: Space) async throws -> SymmetricKey {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        let identity = try await userProfileService.prepareCurrentDeviceIdentity(for: session)
        let logContext = SecureAccessLogContext(uid: session.uid, spaceID: space.id, deviceID: identity.deviceID)

        if let cachedKey = encryptionService.cachedSpaceKey(for: space.id) {
            let currentReference = memberKeyDeviceReference(spaceID: space.id, userID: session.uid, deviceID: identity.deviceID)
            let currentSnapshot = try? await getDocument(currentReference)
            let exists = currentSnapshot?.exists == true
            logSecureAccess(
                logContext,
                exists: exists,
                setupAttempted: !exists,
                setupSuccess: exists,
                error: nil
            )
            if !exists {
                do {
                    try await createCurrentDeviceMemberKeyDocument(
                        in: space,
                        session: session,
                        identity: identity,
                        spaceKey: cachedKey
                    )
                    logSecureAccess(
                        logContext,
                        exists: false,
                        setupAttempted: true,
                        setupSuccess: true,
                        error: nil
                    )
                } catch {
                    logSecureAccess(
                        logContext,
                        exists: false,
                        setupAttempted: true,
                        setupSuccess: false,
                        error: error
                    )
                    throw SpaceServiceError.secureAccessNotSetUpOnDevice
                }
            }
            return cachedKey
        }

        let currentReference = memberKeyDeviceReference(spaceID: space.id, userID: session.uid, deviceID: identity.deviceID)
        let currentSnapshot = try? await getDocument(currentReference)
        let currentExists = currentSnapshot?.exists == true
        let setupAttempted = !currentExists

        do {
            if let currentSnapshot, currentSnapshot.exists {
                let key = try await decryptSpaceKey(from: currentSnapshot, session: session)
                encryptionService.cacheSpaceKey(key, for: space.id)
                logSecureAccess(
                    logContext,
                    exists: true,
                    setupAttempted: false,
                    setupSuccess: true,
                    error: nil
                )
                return key
            }

            let recoveryCandidates = try await memberKeyRecoverySnapshots(
                firestore: firestore,
                spaceID: space.id,
                userID: session.uid,
                currentDeviceID: identity.deviceID
            )

            for candidate in recoveryCandidates {
                do {
                    let recoveredKey = try await decryptSpaceKey(from: candidate, session: session)
                    try await createCurrentDeviceMemberKeyDocument(
                        in: space,
                        session: session,
                        identity: identity,
                        spaceKey: recoveredKey
                    )
                    encryptionService.cacheSpaceKey(recoveredKey, for: space.id)
                    logSecureAccess(
                        logContext,
                        exists: false,
                        setupAttempted: true,
                        setupSuccess: true,
                        error: nil
                    )
                    return recoveredKey
                } catch {
                    continue
                }
            }

            if session.uid == space.ownerId && recoveryCandidates.isEmpty {
                let spaceKey = encryptionService.generateSpaceKey()
                try await createCurrentDeviceMemberKeyDocument(
                    in: space,
                    session: session,
                    identity: identity,
                    spaceKey: spaceKey
                )
                encryptionService.cacheSpaceKey(spaceKey, for: space.id)
                logSecureAccess(
                    logContext,
                    exists: false,
                    setupAttempted: true,
                    setupSuccess: true,
                    error: nil
                )
                return spaceKey
            }

            throw SpaceServiceError.secureAccessNotSetUpOnDevice
        } catch {
            logSecureAccess(
                logContext,
                exists: currentExists,
                setupAttempted: setupAttempted,
                setupSuccess: false,
                error: error
            )
            if let serviceError = error as? SpaceServiceError {
                throw serviceError
            }
            throw SpaceServiceError.secureAccessNotSetUpOnDevice
        }
    }

    private func shareSpaceKeyIfPossible(in space: Space, spaceKey: SymmetricKey) async throws {
        guard let firestore else {
            throw SpaceServiceError.firestoreNotConfigured
        }
        guard let session = authService.currentSession() else {
            throw SpaceServiceError.userNotSignedIn
        }
        let senderIdentity = try await userProfileService.prepareCurrentDeviceIdentity(for: session)

        let membersSnapshot = try await getDocuments(
            firestore.collection("spaces").document(space.id).collection("members")
        )
        let memberIDs = membersSnapshot.documents.map { $0.data()["userId"] as? String ?? $0.documentID }

        guard !memberIDs.isEmpty else { return }

        let batch = firestore.batch()
        var didAddWrites = false
        for userID in memberIDs {
            let recipientIdentities = try await userProfileService.fetchEncryptionIdentities(uid: userID)
            for recipientIdentity in recipientIdentities {
                let wrappedKey = try encryptionService.wrapSpaceKey(spaceKey, for: recipientIdentity.publicKey, senderUserID: session.uid)
                let reference = memberKeyDeviceReference(spaceID: space.id, userID: userID, deviceID: recipientIdentity.deviceID)
                batch.setData([
                    "userId": userID,
                    "deviceId": recipientIdentity.deviceID,
                    "platform": recipientIdentity.platform,
                    "publicKey": recipientIdentity.publicKey,
                    "encryptedSpaceKeyForDevice": wrappedKey,
                    "wrappedKey": wrappedKey,
                    "wrappedByUserId": session.uid,
                    "wrappedBy": session.uid,
                    "wrappedByDeviceId": senderIdentity.deviceID,
                    "createdAt": FieldValue.serverTimestamp(),
                    "updatedAt": FieldValue.serverTimestamp(),
                    "keyVersion": "v1"
                ], forDocument: reference)
                didAddWrites = true
            }
        }

        if didAddWrites {
            try await commit(batch: batch)
        }
    }

    private func syncCurrentDeviceAccessIfPossible(for spaces: [Space]) async {
        guard !spaces.isEmpty else { return }
        for space in spaces {
            do {
                let spaceKey = try await ensureSpaceKey(in: space)
                try? await shareSpaceKeyIfPossible(in: space, spaceKey: spaceKey)
            } catch {
                continue
            }
        }
    }

    private func memberKeyRecoverySnapshots(
        firestore: Firestore,
        spaceID: String,
        userID: String,
        currentDeviceID: String
    ) async throws -> [DocumentSnapshot] {
        var snapshots: [DocumentSnapshot] = []

        let nestedDevicesQuery = firestore.collection("spaces")
            .document(spaceID)
            .collection("memberKeys")
            .document(userID)
            .collection("devices")
        if let nestedSnapshots = try? await getDocuments(nestedDevicesQuery) {
            snapshots.append(contentsOf: nestedSnapshots.documents.filter { $0.documentID != currentDeviceID })
        }

        let legacyFlatCurrentReference = firestore.collection("spaces")
            .document(spaceID)
            .collection("memberKeys")
            .document(Self.memberKeyDocumentID(userID: userID, deviceID: currentDeviceID))
        if let legacyFlatCurrentSnapshot = try? await getDocument(legacyFlatCurrentReference), legacyFlatCurrentSnapshot.exists {
            snapshots.append(legacyFlatCurrentSnapshot)
        }

        let legacyReference = firestore.collection("spaces")
            .document(spaceID)
            .collection("memberKeys")
            .document(userID)
        if let legacySnapshot = try? await getDocument(legacyReference), legacySnapshot.exists {
            snapshots.append(legacySnapshot)
        }

        return snapshots
    }

    private func decryptSpaceKey(
        from snapshot: DocumentSnapshot,
        session: AuthSession
    ) async throws -> SymmetricKey {
        guard
            let data = snapshot.data(),
            let wrappedKey = (data["encryptedSpaceKeyForDevice"] as? String) ?? (data["wrappedKey"] as? String),
            let wrappedBy = (data["wrappedByUserId"] as? String) ?? (data["wrappedBy"] as? String)
        else {
            throw SpaceServiceError.secureAccessNotSetUpOnDevice
        }

        let wrappedByDeviceID = (data["wrappedByDeviceId"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        guard let senderPublicKey = try await userProfileService.fetchEncryptionPublicKey(uid: wrappedBy, deviceID: wrappedByDeviceID) else {
            throw SpaceServiceError.secureAccessNotSetUpOnDevice
        }

        return try encryptionService.unwrapSpaceKey(
            wrappedKey,
            wrappedBy: senderPublicKey,
            recipientUserID: session.uid
        )
    }

    private func createCurrentDeviceMemberKeyDocument(
        in space: Space,
        session: AuthSession,
        identity: DeviceEncryptionIdentity,
        spaceKey: SymmetricKey
    ) async throws {
        try await storeMemberKey(
            spaceID: space.id,
            recipientUserID: session.uid,
            recipientIdentity: identity,
            senderUserID: session.uid,
            senderDeviceID: identity.deviceID,
            spaceKey: spaceKey
        )
    }

    private func getDocument(_ reference: DocumentReference) async throws -> DocumentSnapshot {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<DocumentSnapshot, Error>) in
            reference.getDocument { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: SpaceServiceError.unableToLoadSpaces)
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
                    continuation.resume(throwing: SpaceServiceError.unableToLoadSpaces)
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

    private func setData(_ data: [String: Any], for reference: DocumentReference, merge: Bool = false) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            reference.setData(data, merge: merge) { error in
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

    private func commit(batch: WriteBatch) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            batch.commit { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func normalizeInviteCode(_ code: String) -> String {
        code.uppercased().filter { $0.isLetter || $0.isNumber }
    }

    private var isRunningPreview: Bool {
        ProcessInfo.processInfo.environment["XCODE_RUNNING_FOR_PREVIEWS"] == "1"
    }

    private static func randomInviteCode(length: Int = 6) -> String {
        let characters = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return String((0..<length).compactMap { _ in characters.randomElement() })
    }

    private static let messageTimestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "h:mm a"
        return formatter
    }()

    private static func memberKeyDocumentID(userID: String, deviceID: String) -> String {
        deviceID == "__legacy__" ? userID : "\(userID)__\(deviceID)"
    }

    private func memberKeyDeviceReference(spaceID: String, userID: String, deviceID: String) -> DocumentReference {
        firestore!.collection("spaces")
            .document(spaceID)
            .collection("memberKeys")
            .document(userID)
            .collection("devices")
            .document(deviceID)
    }

    private func generalEncryptionKeyReference(spaceID: String) -> DocumentReference {
        firestore!.collection("spaces")
            .document(spaceID)
            .collection("encryption")
            .document("key")
    }

    private func storeMemberKey(
        spaceID: String,
        recipientUserID: String,
        recipientIdentity: DeviceEncryptionIdentity,
        senderUserID: String,
        senderDeviceID: String,
        spaceKey: SymmetricKey
    ) async throws {
        let wrappedKey = try encryptionService.wrapSpaceKey(spaceKey, for: recipientIdentity.publicKey, senderUserID: senderUserID)
        let nestedData: [String: Any] = [
            "userId": recipientUserID,
            "deviceId": recipientIdentity.deviceID,
            "platform": recipientIdentity.platform,
            "publicKey": recipientIdentity.publicKey,
            "encryptedSpaceKeyForDevice": wrappedKey,
            "wrappedKey": wrappedKey,
            "wrappedByUserId": senderUserID,
            "wrappedBy": senderUserID,
            "wrappedByDeviceId": senderDeviceID,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "keyVersion": "v1"
        ]
        do {
            try await setData(nestedData, for: memberKeyDeviceReference(spaceID: spaceID, userID: recipientUserID, deviceID: recipientIdentity.deviceID), merge: true)
        } catch {
            try await setData([
                "userId": recipientUserID,
                "deviceId": recipientIdentity.deviceID,
                "platform": recipientIdentity.platform,
                "publicKey": recipientIdentity.publicKey,
                "encryptedSpaceKeyForDevice": wrappedKey,
                "wrappedKey": wrappedKey,
                "wrappedByUserId": senderUserID,
                "wrappedBy": senderUserID,
                "wrappedByDeviceId": senderDeviceID,
                "createdAt": FieldValue.serverTimestamp(),
                "updatedAt": FieldValue.serverTimestamp(),
                "keyVersion": "v1"
            ], for: firestore!.collection("spaces").document(spaceID).collection("memberKeys").document(Self.memberKeyDocumentID(userID: recipientUserID, deviceID: recipientIdentity.deviceID)), merge: true)
        }
    }

    private func logSecureAccess(
        _ context: SecureAccessLogContext,
        exists: Bool,
        setupAttempted: Bool,
        setupSuccess: Bool,
        error: Error?
    ) {
        let message = [
            "[SpaceService][SecureAccess]",
            "uid=\(context.uid)",
            "spaceId=\(context.spaceID)",
            "deviceId=\(context.deviceID)",
            "deviceKeyDocExists=\(exists)",
            "setupAttempted=\(setupAttempted)",
            "setupSuccess=\(setupSuccess)",
            "error=\(error?.localizedDescription ?? "none")"
        ].joined(separator: " ")
        print(message)
    }

    private func runMessageEncryptionSelfTestIfNeeded(spaceID: String, spaceKey: SymmetricKey) throws {
#if DEBUG
        guard !verifiedMessageEncryptionSpaceIDs.contains(spaceID) else { return }
        let plaintext = "hello encryption test"
        do {
            let encryptedPayload = try encryptionService.encryptText(plaintext, using: spaceKey)
            let decryptedText = try encryptionService.decryptText(
                ciphertext: encryptedPayload.ciphertext,
                nonce: encryptedPayload.nonce,
                using: spaceKey
            )
            let keyLengthBytes = spaceKey.withUnsafeBytes { $0.count }
            let nonceLengthBytes = Data(base64Encoded: encryptedPayload.nonce)?.count ?? 0
            let matchesPlaintext = decryptedText == plaintext
            print(
                "[SpaceService][EncryptionSelfTest] PASS " +
                "plaintext=\(plaintext) " +
                "keyLengthBytes=\(keyLengthBytes) " +
                "nonceBase64=\(encryptedPayload.nonce) " +
                "nonceLengthBytes=\(nonceLengthBytes) " +
                "ciphertextBase64Length=\(encryptedPayload.ciphertext.count) " +
                "decryptedText=\(decryptedText) " +
                "matchesPlaintext=\(matchesPlaintext)"
            )
            guard matchesPlaintext else {
                throw SpaceServiceError.localEncryptionSelfTestFailed
            }
            verifiedMessageEncryptionSpaceIDs.insert(spaceID)
        } catch {
            print("[SpaceService][EncryptionSelfTest] FAIL reason=\(error.localizedDescription)")
            throw SpaceServiceError.localEncryptionSelfTestFailed
        }
#endif
    }

    private func logStoredMessagePayload(
        messageID: String,
        senderID: String?,
        encryptionVersion: String,
        ciphertext: String,
        nonce: String
    ) {
#if DEBUG
        print(
            "[SpaceService][StoredMessage] " +
            "messageId=\(messageID) " +
            "senderId=\(senderID ?? "nil") " +
            "encryptionVersion=\(encryptionVersion) " +
            "nonceBase64=\(nonce) " +
            "nonceLengthBytes=\(Data(base64Encoded: nonce)?.count ?? 0) " +
            "ciphertextBase64Length=\(ciphertext.count)"
        )
#endif
    }
}

private struct SecureAccessLogContext {
    let uid: String
    let spaceID: String
    let deviceID: String
}

enum SpaceServiceError: LocalizedError {
    case firestoreNotConfigured
    case userNotSignedIn
    case invalidName
    case invalidInviteCode
    case inviteNotFound
    case inviteInactive
    case inviteExpired
    case inviteMaxedOut
    case invitePermissionDenied
    case alreadyMember
    case unableToLoadInvite
    case unableToCreateInvite
    case unableToJoinSpace
    case unableToLoadSpaces
    case unableToLoadMembers
    case unableToLoadMessages
    case invalidMessageText
    case spaceKeyUnavailable
    case encryptionIdentityUnavailable
    case secureAccessNotSetUpOnDevice
    case messageNotFound
    case messageDeletePermissionDenied
    case cannotModifyOwner
    case localEncryptionSelfTestFailed
    case storageNotConfigured
    case invalidMediaData
    case invalidFileName
    case filePermissionDenied
    case fileNotFound
        case invalidPollQuestion
        case invalidPollOptions
        case pollClosed
        case pollDeletePermissionDenied
        case pollUpdatePermissionDenied
        case invalidEventTitle
        case invalidEventDateRange
        case eventUpdatePermissionDenied
        case eventDeletePermissionDenied

    var errorDescription: String? {
        switch self {
        case .firestoreNotConfigured:
            return "Firestore is not configured yet."
        case .userNotSignedIn:
            return "Sign in before creating or loading Spaces."
        case .invalidName:
            return "Space Name is required."
        case .invalidInviteCode:
            return "Enter a valid invite code."
        case .inviteNotFound:
            return "That invite code could not be found."
        case .inviteInactive:
            return "That invite is no longer active."
        case .inviteExpired:
            return "That invite has expired."
        case .inviteMaxedOut:
            return "That invite has reached its usage limit."
        case .invitePermissionDenied:
            return "Only Space owners or admins can manage invites and members."
        case .alreadyMember:
            return "You are already a member of this Space."
        case .unableToLoadInvite:
            return "Unable to load the current invite link."
        case .unableToCreateInvite:
            return "Unable to create an invite right now."
        case .unableToJoinSpace:
            return "Unable to join that Space right now."
        case .unableToLoadSpaces:
            return "Unable to load your Spaces."
        case .unableToLoadMembers:
            return "Unable to load members for this Space."
        case .unableToLoadMessages:
            return "Unable to load messages for this Space."
        case .invalidMessageText:
            return "Enter a message before sending."
        case .spaceKeyUnavailable:
            return "Secure access not set up on this device"
        case .encryptionIdentityUnavailable:
            return "Secure access not set up on this device"
        case .secureAccessNotSetUpOnDevice:
            return "Secure access not set up on this device"
        case .messageNotFound:
            return "That message could not be found."
        case .messageDeletePermissionDenied:
            return "Only the sender, a Space owner, or a Space admin can delete this message."
        case .cannotModifyOwner:
            return "The Space owner cannot be modified or removed."
        case .localEncryptionSelfTestFailed:
            return "Local encryption self-test failed."
        case .storageNotConfigured:
            return "Storage is not configured yet."
        case .invalidMediaData:
            return "Unable to process the selected image."
        case .invalidFileName:
            return "Enter a file name before saving."
        case .filePermissionDenied:
            return "Only the uploader, a Space owner, or a Space admin can manage this file."
        case .fileNotFound:
            return "That file could not be found."
        case .invalidPollQuestion:
            return "Enter a poll question."
        case .invalidPollOptions:
            return "Add at least two valid poll options."
        case .pollClosed:
            return "This poll is closed."
        case .pollDeletePermissionDenied:
            return "Only the creator, a Space owner, or a Space admin can delete this poll."
        case .pollUpdatePermissionDenied:
            return "Only the creator, a Space owner, or a Space admin can edit this poll."
        case .invalidEventTitle:
            return "Enter an event title."
        case .invalidEventDateRange:
            return "The event end time must be after the start time."
        case .eventUpdatePermissionDenied:
            return "Only the creator, a Space owner, or a Space admin can edit this event."
        case .eventDeletePermissionDenied:
            return "Only the creator, a Space owner, or a Space admin can delete this event."
        }
    }
}

private extension SpaceMemberRole {
    var sortOrder: Int {
        switch self {
        case .owner: return 0
        case .admin: return 1
        case .moderator: return 2
        case .member: return 3
        case .guest: return 4
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        guard size > 0, !isEmpty else { return isEmpty ? [] : [self] }
        return stride(from: 0, to: count, by: size).map { startIndex in
            Array(self[startIndex..<Swift.min(startIndex + size, count)])
        }
    }
}

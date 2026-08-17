import FirebaseCore
import FirebaseFirestore
import Foundation

@MainActor
final class UserProfileService {
    private let firestore: Firestore?
    private let encryptionService: EncryptionService
    private let deviceIdentityService: DeviceIdentityService

    init() {
        self.firestore = FirebaseApp.app().map { _ in Firestore.firestore() }
        self.encryptionService = EncryptionService()
        self.deviceIdentityService = DeviceIdentityService()
    }

    init(
        firestore: Firestore?,
        encryptionService: EncryptionService = EncryptionService(),
        deviceIdentityService: DeviceIdentityService = DeviceIdentityService()
    ) {
        self.firestore = firestore
        self.encryptionService = encryptionService
        self.deviceIdentityService = deviceIdentityService
    }

    func fetchUserProfile(uid: String) async throws -> UserProfile? {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let snapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<DocumentSnapshot, Error>) in
            firestore.collection("users").document(uid).getDocument { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }

        guard snapshot.exists, let data = snapshot.data() else {
            return nil
        }

        return userProfile(from: data, documentID: snapshot.documentID)
    }

    func createUserProfile(
        session: AuthSession,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String
    ) async throws -> UserProfile {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDisplayName.isEmpty else {
            throw UserProfileServiceError.missingDisplayName
        }

        let trimmedEmojiAvatar = emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedEmojiAvatar = trimmedEmojiAvatar.isEmpty ? "🧑‍💻" : String(trimmedEmojiAvatar.prefix(1))
        let trimmedStatus = statusMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        let identity = try await prepareCurrentDeviceIdentity(for: session)

        let document = firestore.collection("users").document(session.uid)
        let data: [String: Any] = [
            "uid": session.uid,
            "displayName": trimmedDisplayName,
            "emojiAvatar": resolvedEmojiAvatar,
            "status": trimmedStatus,
            "e2eePublicKey": identity.publicKey,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "email": session.email ?? NSNull(),
            "phoneNumber": session.phoneNumber ?? NSNull(),
            "providers": session.providers.map(\.rawValue)
        ]

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            document.setData(data) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }

        return UserProfile(
            id: session.uid,
            uid: session.uid,
            displayName: trimmedDisplayName,
            emojiAvatar: resolvedEmojiAvatar,
            statusMessage: trimmedStatus,
            email: session.email,
            phoneNumber: session.phoneNumber,
            linkedProviders: session.providers
        )
    }

    func updateUserProfile(
        session: AuthSession,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String
    ) async throws -> UserProfile {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDisplayName.isEmpty else {
            throw UserProfileServiceError.missingDisplayName
        }

        let trimmedEmojiAvatar = emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedEmojiAvatar = trimmedEmojiAvatar.isEmpty ? "🧑‍💻" : String(trimmedEmojiAvatar.prefix(1))
        let trimmedStatus = statusMessage.trimmingCharacters(in: .whitespacesAndNewlines)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            firestore.collection("users").document(session.uid).setData([
                "displayName": trimmedDisplayName,
                "emojiAvatar": resolvedEmojiAvatar,
                "status": trimmedStatus,
                "updatedAt": FieldValue.serverTimestamp()
            ], merge: true) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }

        return UserProfile(
            id: session.uid,
            uid: session.uid,
            displayName: trimmedDisplayName,
            emojiAvatar: resolvedEmojiAvatar,
            statusMessage: trimmedStatus,
            email: session.email,
            phoneNumber: session.phoneNumber,
            linkedProviders: session.providers
        )
    }

    func syncAuthSessionIfProfileExists(_ session: AuthSession) async throws -> UserProfile? {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }
        let identity = try await prepareCurrentDeviceIdentity(for: session)

        let document = firestore.collection("users").document(session.uid)
        let snapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<DocumentSnapshot, Error>) in
            document.getDocument { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }

        guard snapshot.exists, let data = snapshot.data() else {
            return nil
        }

        let mergedData: [String: Any] = [
            "email": session.email ?? NSNull(),
            "phoneNumber": session.phoneNumber ?? NSNull(),
            "providers": session.providers.map(\.rawValue),
            "e2eePublicKey": identity.publicKey,
            "updatedAt": FieldValue.serverTimestamp()
        ]

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            document.setData(mergedData, merge: true) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }

        return UserProfile(
            id: snapshot.documentID,
            uid: data["uid"] as? String ?? snapshot.documentID,
            displayName: data["displayName"] as? String ?? session.displayName,
            emojiAvatar: (data["emojiAvatar"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "🧑‍💻",
            statusMessage: data["status"] as? String ?? "",
            email: session.email,
            phoneNumber: session.phoneNumber,
            linkedProviders: session.providers
        )
    }

    private func userProfile(from data: [String: Any], documentID: String) -> UserProfile {
        let providers = (data["providers"] as? [String] ?? [])
            .compactMap(LinkedProvider.init(rawValue:))
        let blockedUsers = (data["blockedUsers"] as? [[String: Any]] ?? []).compactMap { entry -> BlockedUser? in
            let uid = (entry["uid"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !uid.isEmpty else { return nil }

            let rawName = (entry["displayName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let rawEmoji = (entry["emojiAvatar"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            return BlockedUser(
                id: uid,
                uid: uid,
                displayName: rawName.isEmpty ? "Blocked User" : rawName,
                emojiAvatar: rawEmoji.isEmpty ? "🚫" : rawEmoji,
                blockedAt: (entry["blockedAt"] as? Timestamp)?.dateValue()
            )
        }.sorted { lhs, rhs in
            (lhs.blockedAt ?? .distantPast) > (rhs.blockedAt ?? .distantPast)
        }

        return UserProfile(
            id: documentID,
            uid: data["uid"] as? String ?? documentID,
            displayName: data["displayName"] as? String ?? "Spaces User",
            emojiAvatar: (data["emojiAvatar"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "🧑‍💻",
            statusMessage: data["status"] as? String ?? "",
            email: data["email"] as? String,
            phoneNumber: data["phoneNumber"] as? String,
            linkedProviders: providers,
            blockedUsers: blockedUsers
        )
    }

    func ensureEncryptionIdentity(for session: AuthSession) throws -> String {
        try encryptionService.ensurePublicKey(for: session.uid)
    }

    func currentDeviceID() -> String {
        deviceIdentityService.currentDeviceID()
    }

    func prepareCurrentDeviceIdentity(for session: AuthSession) async throws -> DeviceEncryptionIdentity {
        let deviceID = currentDeviceID()
        let platform = deviceIdentityService.currentPlatform()
        let publicKey = try ensureEncryptionIdentity(for: session)
        if let firestore {
            let reference = firestore.collection("users")
                .document(session.uid)
                .collection("devices")
                .document(deviceID)

            do {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    reference.setData([
                        "userId": session.uid,
                        "deviceId": deviceID,
                        "publicKey": publicKey,
                        "platform": platform,
                        "createdAt": FieldValue.serverTimestamp(),
                        "lastActiveAt": FieldValue.serverTimestamp()
                    ], merge: true) { error in
                        if let error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(returning: ())
                        }
                    }
                }
            } catch {
                // Fall back to the legacy user-level key path when device-key rules are not deployed yet.
            }
        }

        return DeviceEncryptionIdentity(deviceID: deviceID, platform: platform, publicKey: publicKey)
    }

    func fetchEncryptionIdentities(uid: String) async throws -> [DeviceEncryptionIdentity] {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        var identities: [DeviceEncryptionIdentity] = []

        if let deviceSnapshot = try? await withCheckedThrowingContinuation({
            (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            firestore.collection("users").document(uid).collection("devices").getDocuments { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }) {
            identities = deviceSnapshot.documents.compactMap { document -> DeviceEncryptionIdentity? in
                guard
                    let publicKey = (document.data()["publicKey"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                    !publicKey.isEmpty
                else {
                    return nil
                }
                let rawDeviceID = (document.data()["deviceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let deviceID = rawDeviceID.isEmpty ? document.documentID : rawDeviceID

                let rawPlatform = (document.data()["platform"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let platform = rawPlatform.isEmpty ? "unknown" : rawPlatform

                return DeviceEncryptionIdentity(deviceID: deviceID, platform: platform, publicKey: publicKey)
            }
        }

        if let legacyPublicKey = try await fetchEncryptionPublicKey(uid: uid, deviceID: nil) {
            let legacyIdentity = DeviceEncryptionIdentity(deviceID: "__legacy__", platform: "legacy", publicKey: legacyPublicKey)
            if !identities.contains(legacyIdentity) {
                identities.append(legacyIdentity)
            }
        }

        return identities
    }

    func fetchEncryptionPublicKey(uid: String, deviceID: String?) async throws -> String? {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        if let deviceID, !deviceID.isEmpty, deviceID != "__legacy__" {
            if let snapshot = try? await withCheckedThrowingContinuation({
                (continuation: CheckedContinuation<DocumentSnapshot, Error>) in
                firestore.collection("users").document(uid).collection("devices").document(deviceID).getDocument { snapshot, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let snapshot {
                        continuation.resume(returning: snapshot)
                    } else {
                        continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                    }
                }
            }), let key = snapshot.data()?["publicKey"] as? String {
                return key
            }
        }

        return try await fetchEncryptionPublicKey(uid: uid)
    }

    func fetchEncryptionPublicKey(uid: String) async throws -> String? {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let snapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<DocumentSnapshot, Error>) in
            firestore.collection("users").document(uid).getDocument { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }

        return snapshot.data()?["e2eePublicKey"] as? String
    }

    func fetchDevices(uid: String) async throws -> [AccountDevice] {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let snapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            firestore.collection("users").document(uid).collection("devices").getDocuments { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }

        return snapshot.documents.compactMap { document -> AccountDevice? in
            let data = document.data()
            if (data["removed"] as? Bool) == true {
                return nil
            }
            let rawDeviceID = (data["deviceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let deviceID = rawDeviceID.isEmpty ? document.documentID : rawDeviceID
            let trimmedPlatform = (data["platform"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let platform = trimmedPlatform.isEmpty ? "unknown" : trimmedPlatform
            let trimmedPublicKey = (data["publicKey"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return AccountDevice(
                id: document.documentID,
                deviceID: deviceID,
                platform: platform,
                publicKey: trimmedPublicKey.isEmpty ? nil : trimmedPublicKey,
                createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
                lastActiveAt: (data["lastActiveAt"] as? Timestamp)?.dateValue()
            )
        }.sorted { lhs, rhs in
            (lhs.lastActiveAt ?? .distantPast) > (rhs.lastActiveAt ?? .distantPast)
        }
    }

    func fetchPushTokens(uid: String) async throws -> [PushTokenRecord] {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let snapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            firestore.collection("users").document(uid).collection("pushTokens").getDocuments { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }

        return snapshot.documents.compactMap { document -> PushTokenRecord? in
            let data = document.data()
            if (data["removed"] as? Bool) == true {
                return nil
            }
            guard let token = (data["token"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty else {
                return nil
            }

            let trimmedPlatform = (data["platform"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let platform = trimmedPlatform.isEmpty ? "unknown" : trimmedPlatform
            let trimmedDeviceID = (data["deviceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            return PushTokenRecord(
                id: document.documentID,
                userID: (data["userId"] as? String) ?? uid,
                token: token,
                platform: platform,
                deviceID: trimmedDeviceID.isEmpty ? nil : trimmedDeviceID,
                enabled: (data["enabled"] as? Bool) ?? false,
                createdAt: (data["createdAt"] as? Timestamp)?.dateValue(),
                updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue()
            )
        }.sorted { lhs, rhs in
            (lhs.updatedAt ?? .distantPast) > (rhs.updatedAt ?? .distantPast)
        }
    }

    func removeDevice(
        uid: String,
        device: AccountDevice,
        relatedPushTokens: [PushTokenRecord]
    ) async throws {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let deviceReference = firestore.collection("users").document(uid).collection("devices").document(device.id)

        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                deviceReference.delete { error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(returning: ())
                    }
                }
            }
        } catch {
            if let publicKey = device.publicKey, !publicKey.isEmpty {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    deviceReference.setData([
                        "userId": uid,
                        "deviceId": device.deviceID,
                        "platform": device.platform,
                        "publicKey": publicKey,
                        "removed": true,
                        "removedAt": FieldValue.serverTimestamp(),
                        "lastActiveAt": FieldValue.serverTimestamp()
                    ], merge: true) { updateError in
                        if let updateError {
                            continuation.resume(throwing: updateError)
                        } else {
                            continuation.resume(returning: ())
                        }
                    }
                }
            }
        }

        for token in relatedPushTokens {
            let tokenReference = firestore.collection("users").document(uid).collection("pushTokens").document(token.id)

            do {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    tokenReference.delete { deleteError in
                        if let deleteError {
                            continuation.resume(throwing: deleteError)
                        } else {
                            continuation.resume(returning: ())
                        }
                    }
                }
            } catch {
                try? await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    tokenReference.setData([
                        "id": token.id,
                        "userId": uid,
                        "token": token.token,
                        "platform": token.platform,
                        "deviceId": token.deviceID ?? NSNull(),
                        "enabled": false,
                        "removed": true,
                        "removedAt": FieldValue.serverTimestamp(),
                        "updatedAt": FieldValue.serverTimestamp()
                    ], merge: true) { updateError in
                        if let updateError {
                            continuation.resume(throwing: updateError)
                        } else {
                            continuation.resume(returning: ())
                        }
                    }
                }
            }
        }
    }

    func updateBlockedUsers(uid: String, blockedUsers: [BlockedUser]) async throws {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let payload = blockedUsers.map { user in
            [
                "uid": user.uid,
                "displayName": user.displayName,
                "emojiAvatar": user.emojiAvatar,
                "blockedAt": user.blockedAt.map(Timestamp.init(date:)) ?? Timestamp(date: Date())
            ] as [String: Any]
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            firestore.collection("users").document(uid).setData([
                "blockedUsers": payload,
                "updatedAt": FieldValue.serverTimestamp()
            ], merge: true) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    func deleteAccountData(uid: String) async throws {
        guard let firestore else {
            throw UserProfileServiceError.firestoreNotConfigured
        }

        let userReference = firestore.collection("users").document(uid)
        let devicesSnapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            userReference.collection("devices").getDocuments { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }

        for document in devicesSnapshot.documents {
            try await deleteDocument(document.reference)
        }

        let pushTokensSnapshot = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<QuerySnapshot, Error>) in
            userReference.collection("pushTokens").getDocuments { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: UserProfileServiceError.profileFetchFailed)
                }
            }
        }

        for document in pushTokensSnapshot.documents {
            try await deleteDocument(document.reference)
        }

        try await deleteDocument(userReference)
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
}

enum UserProfileServiceError: LocalizedError {
    case firestoreNotConfigured
    case missingDisplayName
    case profileFetchFailed

    var errorDescription: String? {
        switch self {
        case .firestoreNotConfigured:
            return "Firestore is not configured. Add a valid GoogleService-Info.plist and Firebase Firestore setup to continue."
        case .missingDisplayName:
            return "Display Name is required."
        case .profileFetchFailed:
            return "Unable to load the current user profile."
        }
    }
}

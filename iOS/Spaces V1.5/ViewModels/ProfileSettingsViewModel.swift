import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class ProfileSettingsViewModel: ObservableObject {
    struct SettingsAlert: Identifiable {
        let id = UUID()
        let title: String
        let message: String
    }

    @Published private(set) var profile: UserProfile?
    @Published private(set) var devices: [AccountDevice] = []
    @Published private(set) var pushTokens: [PushTokenRecord] = []
    @Published private(set) var currentDeviceID: String
    @Published private(set) var isLoading = false
    @Published private(set) var isSavingProfile = false
    @Published private(set) var isUpdatingNotifications = false
    @Published private(set) var isClearingCache = false
    @Published private(set) var removingDeviceIDs: Set<String> = []
    @Published private(set) var cacheSizeBytes: Int64?
    @Published var activeAlert: SettingsAlert?

    private let userProfileService: UserProfileService
    private let pushTokenService: PushTokenService
    private let deviceIdentityService: DeviceIdentityService
    private var lastLoadedUserID: String?

    convenience init() {
        self.init(
            userProfileService: UserProfileService(),
            pushTokenService: .shared,
            deviceIdentityService: DeviceIdentityService()
        )
    }

    init(
        userProfileService: UserProfileService,
        pushTokenService: PushTokenService,
        deviceIdentityService: DeviceIdentityService
    ) {
        self.userProfileService = userProfileService
        self.pushTokenService = pushTokenService
        self.deviceIdentityService = deviceIdentityService
        self.currentDeviceID = deviceIdentityService.currentDeviceID()
        self.cacheSizeBytes = Self.computeCacheSizeBytes()
    }

    var currentDevice: AccountDevice? {
        devices.first { $0.deviceID == currentDeviceID }
    }

    var currentDevicePushEnabled: Bool {
        pushTokens.first(where: { $0.deviceID == currentDeviceID })?.enabled ?? false
    }

    func loadIfNeeded(session: AuthSession?, cachedProfile: UserProfile?) async {
        guard let session else { return }
        guard lastLoadedUserID != session.uid || profile == nil else { return }
        await load(session: session, cachedProfile: cachedProfile)
    }

    func refresh(session: AuthSession?, cachedProfile: UserProfile?) async {
        guard let session else { return }
        await load(session: session, cachedProfile: cachedProfile)
    }

    func saveProfile(
        session: AuthSession?,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String
    ) async -> UserProfile? {
        guard let session else {
            activeAlert = SettingsAlert(title: "Profile", message: "Sign in before updating your profile.")
            return nil
        }

        isSavingProfile = true
        defer { isSavingProfile = false }

        do {
            let updatedProfile = try await userProfileService.updateUserProfile(
                session: session,
                displayName: displayName,
                emojiAvatar: emojiAvatar,
                statusMessage: statusMessage
            )
            profile = updatedProfile
            activeAlert = SettingsAlert(title: "Profile", message: "Profile updated.")
            await load(session: session, cachedProfile: updatedProfile)
            return updatedProfile
        } catch {
            activeAlert = SettingsAlert(title: "Profile", message: error.localizedDescription)
            return nil
        }
    }

    func setCurrentDeviceNotificationsEnabled(_ enabled: Bool, session: AuthSession?) async {
        guard session != nil else {
            activeAlert = SettingsAlert(title: "Notifications", message: "Sign in before updating notifications.")
            return
        }

        isUpdatingNotifications = true
        let existingTokens = pushTokens
        pushTokens = pushTokens.map { token in
            guard token.deviceID == currentDeviceID else { return token }
            return PushTokenRecord(
                id: token.id,
                userID: token.userID,
                token: token.token,
                platform: token.platform,
                deviceID: token.deviceID,
                enabled: enabled,
                createdAt: token.createdAt,
                updatedAt: token.updatedAt
            )
        }
        defer { isUpdatingNotifications = false }

        do {
            try await pushTokenService.setCurrentTokenEnabled(enabled)
            if let session {
                await load(session: session, cachedProfile: profile)
            }
        } catch {
            pushTokens = existingTokens
            activeAlert = SettingsAlert(title: "Notifications", message: error.localizedDescription)
        }
    }

    func clearCache() async {
        isClearingCache = true
        defer { isClearingCache = false }

        do {
            try Self.clearCacheDirectories()
            cacheSizeBytes = Self.computeCacheSizeBytes()
            activeAlert = SettingsAlert(title: "Storage", message: "Cached files cleared.")
        } catch {
            activeAlert = SettingsAlert(title: "Storage", message: error.localizedDescription)
        }
    }

    func formattedCacheSize() -> String? {
        guard let cacheSizeBytes else { return nil }
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: cacheSizeBytes)
    }

    func isRemovingDevice(_ device: AccountDevice) -> Bool {
        removingDeviceIDs.contains(device.id)
    }

    func removeDevice(_ device: AccountDevice, session: AuthSession?) async {
        guard let session else {
            activeAlert = SettingsAlert(title: "Devices", message: "Sign in before removing a device.")
            return
        }

        removingDeviceIDs.insert(device.id)
        let existingDevices = devices
        let existingTokens = pushTokens
        let relatedPushTokens = pushTokens.filter { $0.deviceID == device.deviceID }

        devices.removeAll { $0.id == device.id }
        pushTokens.removeAll { token in
            relatedPushTokens.contains { $0.id == token.id }
        }

        defer { removingDeviceIDs.remove(device.id) }

        do {
            try await userProfileService.removeDevice(uid: session.uid, device: device, relatedPushTokens: relatedPushTokens)
        } catch {
            let nsError = error as NSError
            if nsError.domain == FirestoreErrorDomain,
               nsError.code == FirestoreErrorCode.notFound.rawValue {
                return
            }

            activeAlert = SettingsAlert(
                title: "Devices",
                message: "Removed from this view. Firestore cleanup could not be completed: \(error.localizedDescription)"
            )

            if devices.isEmpty {
                devices = existingDevices.filter { $0.id != device.id }
            }
            if pushTokens.isEmpty {
                pushTokens = existingTokens.filter { token in
                    !relatedPushTokens.contains { $0.id == token.id }
                }
            }
        }
    }

    func unblockUser(_ blockedUser: BlockedUser, session: AuthSession?) async {
        guard let session else {
            activeAlert = SettingsAlert(title: "Blocked Users", message: "Sign in before updating blocked users.")
            return
        }

        guard let currentProfile = profile else { return }
        let updatedBlockedUsers = currentProfile.blockedUsers.filter { $0.id != blockedUser.id }

        do {
            try await userProfileService.updateBlockedUsers(uid: session.uid, blockedUsers: updatedBlockedUsers)
            profile = UserProfile(
                id: currentProfile.id,
                uid: currentProfile.uid,
                displayName: currentProfile.displayName,
                emojiAvatar: currentProfile.emojiAvatar,
                statusMessage: currentProfile.statusMessage,
                email: currentProfile.email,
                phoneNumber: currentProfile.phoneNumber,
                linkedProviders: currentProfile.linkedProviders,
                blockedUsers: updatedBlockedUsers
            )
        } catch {
            activeAlert = SettingsAlert(title: "Blocked Users", message: error.localizedDescription)
        }
    }

    private func load(session: AuthSession, cachedProfile: UserProfile?) async {
        isLoading = true
        lastLoadedUserID = session.uid
        if profile == nil {
            profile = cachedProfile
        }

        do {
            async let fetchedProfile = userProfileService.fetchUserProfile(uid: session.uid)
            async let fetchedDevices = userProfileService.fetchDevices(uid: session.uid)
            async let fetchedPushTokens = userProfileService.fetchPushTokens(uid: session.uid)

            let resolvedProfile = try await fetchedProfile
            profile = resolvedProfile ?? cachedProfile
            devices = try await fetchedDevices
            pushTokens = try await fetchedPushTokens
            cacheSizeBytes = Self.computeCacheSizeBytes()
        } catch {
            activeAlert = SettingsAlert(title: "Account", message: error.localizedDescription)
        }

        isLoading = false
    }

    private static func computeCacheSizeBytes() -> Int64? {
        let fileManager = FileManager.default
        let directories = [
            fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first,
            fileManager.temporaryDirectory
        ].compactMap { $0 }

        return directories.reduce(into: Int64(0)) { partialResult, url in
            partialResult += (try? directorySize(at: url)) ?? 0
        }
    }

    private static func clearCacheDirectories() throws {
        let fileManager = FileManager.default
        let directories = [
            fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first,
            fileManager.temporaryDirectory
        ].compactMap { $0 }

        for directory in directories {
            let contents = try fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            for url in contents {
                try? fileManager.removeItem(at: url)
            }
        }
    }

    private static func directorySize(at url: URL) throws -> Int64 {
        let fileManager = FileManager.default
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileAllocatedSizeKey, .totalFileAllocatedSizeKey]
        let enumerator = fileManager.enumerator(at: url, includingPropertiesForKeys: Array(keys))
        var totalSize: Int64 = 0

        while let fileURL = enumerator?.nextObject() as? URL {
            let values = try fileURL.resourceValues(forKeys: keys)
            if values.isRegularFile == true {
                totalSize += Int64(values.totalFileAllocatedSize ?? values.fileAllocatedSize ?? 0)
            }
        }

        return totalSize
    }
}

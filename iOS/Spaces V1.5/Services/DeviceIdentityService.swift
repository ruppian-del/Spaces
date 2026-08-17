import Foundation

struct DeviceEncryptionIdentity: Hashable {
    let deviceID: String
    let platform: String
    let publicKey: String
}

final class DeviceIdentityService {
    private let defaults: UserDefaults
    private let key = "spaces.device.id"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func currentDeviceID() -> String {
        if let existing = defaults.string(forKey: key)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !existing.isEmpty {
            return existing
        }

        let newID = "ios-" + UUID().uuidString.lowercased()
        defaults.set(newID, forKey: key)
        return newID
    }

    func currentPlatform() -> String {
        "ios"
    }
}

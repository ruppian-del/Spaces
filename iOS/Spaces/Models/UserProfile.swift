import Foundation

struct UserProfile: Identifiable, Hashable {
    let id: String
    let uid: String
    let displayName: String
    let emojiAvatar: String
    let statusMessage: String
    let email: String?
    let phoneNumber: String?
    let linkedProviders: [LinkedProvider]
    let blockedUsers: [BlockedUser]

    init(
        id: String,
        uid: String,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String,
        email: String?,
        phoneNumber: String?,
        linkedProviders: [LinkedProvider],
        blockedUsers: [BlockedUser] = []
    ) {
        self.id = id
        self.uid = uid
        self.displayName = displayName
        self.emojiAvatar = emojiAvatar
        self.statusMessage = statusMessage
        self.email = email
        self.phoneNumber = phoneNumber
        self.linkedProviders = linkedProviders
        self.blockedUsers = blockedUsers
    }
}

struct AccountDevice: Identifiable, Hashable {
    let id: String
    let deviceID: String
    let platform: String
    let publicKey: String?
    let createdAt: Date?
    let lastActiveAt: Date?
}

struct PushTokenRecord: Identifiable, Hashable {
    let id: String
    let userID: String
    let token: String
    let platform: String
    let deviceID: String?
    let enabled: Bool
    let createdAt: Date?
    let updatedAt: Date?
}

struct BlockedUser: Identifiable, Hashable {
    let id: String
    let uid: String
    let displayName: String
    let emojiAvatar: String
    let blockedAt: Date?
}

enum LinkedProvider: String, CaseIterable, Identifiable, Hashable {
    case apple = "Apple"
    case google = "Google"
    case phone = "Phone"

    var id: String { rawValue }

    init?(firebaseProviderID: String) {
        switch firebaseProviderID {
        case "apple.com":
            self = .apple
        case "google.com":
            self = .google
        case "phone":
            self = .phone
        default:
            return nil
        }
    }
}

import Foundation

struct SpaceMember: Identifiable, Hashable {
    let id: String
    let displayName: String
    let emojiAvatar: String
    let role: SpaceMemberRole
    let status: String

    func hasPermission(_ permission: SpacePermission) -> Bool {
        role.capabilities.contains(permission)
    }
}

enum SpaceMemberRole: String, Codable, CaseIterable, Identifiable, Hashable {
    case owner = "Owner"
    case admin = "Admin"
    case moderator = "Moderator"
    case member = "Member"
    case guest = "Guest"

    var id: String { rawValue }

    var firestoreValue: String {
        rawValue.lowercased()
    }

    init?(firestoreValue: String) {
        switch firestoreValue.lowercased() {
        case "owner":
            self = .owner
        case "admin":
            self = .admin
        case "moderator":
            self = .moderator
        case "member":
            self = .member
        case "guest":
            self = .guest
        default:
            return nil
        }
    }
}

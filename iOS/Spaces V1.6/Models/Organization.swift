import Foundation

struct Organization: Identifiable, Hashable {
    let id: String
    let name: String
    let status: OrganizationStatus
    let entitlements: OrganizationEntitlements
    let usage: OrganizationUsage
    let createdAt: Date?
}

enum OrganizationStatus: String, Hashable {
    case active
    case suspended
}

struct OrganizationMember: Identifiable, Hashable {
    let id: String
    let userID: String
    let displayName: String
    let email: String?
    let role: OrganizationRole
    let status: OrganizationMemberStatus
    let joinedAt: Date?
}

enum OrganizationRole: String, CaseIterable, Hashable {
    case primaryAdministrator = "primary_admin"
    case administrator = "admin"
    case member

    var canManageOrganization: Bool {
        self == .primaryAdministrator || self == .administrator
    }
}

enum OrganizationMemberStatus: String, Hashable {
    case active
    case suspended
}

struct OrganizationEntitlements: Hashable {
    let peopleCapacity: Int?
    let activeSpaceCapacity: Int?
    let enabledModuleIDs: Set<String>
    let mediaStorageCapacityBytes: Int64?

    static let foundation = OrganizationEntitlements(
        peopleCapacity: 250,
        activeSpaceCapacity: 10,
        enabledModuleIDs: ["general", "events", "polls", "members", "settings"],
        mediaStorageCapacityBytes: 10 * 1_024 * 1_024 * 1_024
    )

    static func effective(stored: OrganizationEntitlements, defaults: UserDefaults = .standard) -> OrganizationEntitlements {
        return stored
    }

    func allowsAddingPerson(currentPeople: Int) -> Bool {
        guard let peopleCapacity, peopleCapacity > 0 else { return false }
        return currentPeople < peopleCapacity
    }

    func allowsActivatingSpace(currentActiveSpaces: Int) -> Bool {
        guard let activeSpaceCapacity, activeSpaceCapacity > 0 else { return false }
        return currentActiveSpaces < activeSpaceCapacity
    }

    func allowsModule(_ moduleID: String) -> Bool {
        enabledModuleIDs.contains(moduleID)
    }

    func allowsStorageIncrease(currentBytes: Int64, additionalBytes: Int64) -> Bool {
        guard let mediaStorageCapacityBytes,
              currentBytes >= 0,
              additionalBytes >= 0 else {
            return false
        }
        return currentBytes <= mediaStorageCapacityBytes - additionalBytes
    }
}

struct OrganizationUsage: Hashable {
    let peopleCount: Int
    let activeSpaceCount: Int
    let mediaStorageBytes: Int64
}

struct OrganizationOwnedSpace: Identifiable, Hashable {
    let id: String
    let name: String
    let emoji: String
    let memberCount: Int
    let memberIDs: [String]
    let isArchived: Bool
}

struct OrganizationInvite: Identifiable, Hashable {
    let id: String
    let organizationID: String
    let organizationName: String
    let role: OrganizationRole
}

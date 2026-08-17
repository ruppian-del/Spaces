import Foundation

enum SpacePermission: String, CaseIterable, Identifiable, Hashable {
    case manageSpaceSettings = "manage_space_settings"
    case manageMembers = "manage_members"
    case manageRoles = "manage_roles"
    case manageModules = "manage_modules"
    case inviteMembers = "invite_members"
    case removeMembers = "remove_members"
    case postPings = "post_pings"
    case createEvents = "create_events"
    case uploadPhotosVideos = "upload_photos_videos"
    case uploadFiles = "upload_files"
    case editOwnContent = "edit_own_content"
    case deleteOwnContent = "delete_own_content"
    case deleteOthersContent = "delete_others_content"
    case viewContent = "view_content"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .manageSpaceSettings: "Manage Space Settings"
        case .manageMembers: "Manage Members"
        case .manageRoles: "Manage Roles"
        case .manageModules: "Manage Modules"
        case .inviteMembers: "Invite Members"
        case .removeMembers: "Remove Members"
        case .postPings: "Post Pings"
        case .createEvents: "Create Events"
        case .uploadPhotosVideos: "Upload Photos/Videos"
        case .uploadFiles: "Upload Files"
        case .editOwnContent: "Edit Own Content"
        case .deleteOwnContent: "Delete Own Content"
        case .deleteOthersContent: "Delete Others' Content"
        case .viewContent: "View Content"
        }
    }
}

extension SpaceMemberRole {
    var capabilities: Set<SpacePermission> {
        switch self {
        case .owner:
            return Set(SpacePermission.allCases)
        case .admin:
            return [
                .manageMembers,
                .manageRoles,
                .manageModules,
                .inviteMembers,
                .removeMembers,
                .postPings,
                .createEvents,
                .uploadPhotosVideos,
                .uploadFiles,
                .editOwnContent,
                .deleteOwnContent,
                .deleteOthersContent,
                .viewContent
            ]
        case .moderator:
            return [
                .postPings,
                .createEvents,
                .uploadPhotosVideos,
                .uploadFiles,
                .editOwnContent,
                .deleteOwnContent,
                .deleteOthersContent,
                .viewContent
            ]
        case .member:
            return [
                .postPings,
                .createEvents,
                .uploadPhotosVideos,
                .uploadFiles,
                .editOwnContent,
                .deleteOwnContent,
                .viewContent
            ]
        case .guest:
            return [.viewContent]
        }
    }

    func canRemove(targetRole: SpaceMemberRole, isTargetOwner: Bool) -> Bool {
        if isTargetOwner {
            return false
        }

        switch self {
        case .owner:
            return true
        case .admin:
            return targetRole != .admin
        case .moderator, .member, .guest:
            return false
        }
    }

    func canChangeRole(of targetRole: SpaceMemberRole, to newRole: SpaceMemberRole, isTargetOwner: Bool) -> Bool {
        if isTargetOwner || newRole == .owner {
            return false
        }

        switch self {
        case .owner:
            return targetRole != .owner
        case .admin:
            guard targetRole == .moderator || targetRole == .member || targetRole == .guest else {
                return false
            }
            return newRole == .moderator || newRole == .member || newRole == .guest
        case .moderator, .member, .guest:
            return false
        }
    }
}

extension Space {
    func role(for userID: String?, members: [SpaceMember]) -> SpaceMemberRole? {
        guard let userID else { return nil }
        if userID == ownerId {
            return .owner
        }
        return members.first(where: { $0.id == userID })?.role
    }

    func hasCapability(_ capability: SpacePermission, for userID: String?, members: [SpaceMember]) -> Bool {
        guard let role = role(for: userID, members: members) else {
            return false
        }
        return role.capabilities.contains(capability)
    }
}

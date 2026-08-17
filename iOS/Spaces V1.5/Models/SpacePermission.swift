import Foundation

enum SpacePermission: String, Codable, CaseIterable, Identifiable, Hashable {
    case manageSpaceSettings = "manage_space_settings"
    case manageMembers = "manage_members"
    case manageRoles = "manage_roles"
    case manageModules = "manage_modules"
    case inviteMembers = "invite_members"
    case removeMembers = "remove_members"
    case postPings = "post_pings"
    case createAnnouncements = "create_announcements"
    case editOwnAnnouncements = "edit_own_announcements"
    case deleteOwnAnnouncements = "delete_own_announcements"
    case deleteOthersAnnouncements = "delete_others_announcements"
    case createRooms = "create_rooms"
    case editOwnRooms = "edit_own_rooms"
    case editOthersRooms = "edit_others_rooms"
    case deleteOwnRooms = "delete_own_rooms"
    case deleteOthersRooms = "delete_others_rooms"
    case manageRoomMembers = "manage_room_members"
    case postInRooms = "post_in_rooms"
    case createEvents = "create_events"
    case createLists = "create_lists"
    case editOwnLists = "edit_own_lists"
    case editAnyLists = "edit_any_lists"
    case deleteOwnLists = "delete_own_lists"
    case deleteAnyLists = "delete_any_lists"
    case createNotes = "create_notes"
    case editOwnNotes = "edit_own_notes"
    case editAnyNotes = "edit_any_notes"
    case deleteOwnNotes = "delete_own_notes"
    case deleteAnyNotes = "delete_any_notes"
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
        case .createAnnouncements: "Create Announcements"
        case .editOwnAnnouncements: "Edit Own Announcements"
        case .deleteOwnAnnouncements: "Delete Own Announcements"
        case .deleteOthersAnnouncements: "Delete Others' Announcements"
        case .createRooms: "Create Rooms"
        case .editOwnRooms: "Edit Own Rooms"
        case .editOthersRooms: "Edit Others' Rooms"
        case .deleteOwnRooms: "Delete Own Rooms"
        case .deleteOthersRooms: "Delete Others' Rooms"
        case .manageRoomMembers: "Manage Room Members"
        case .postInRooms: "Post in Rooms"
        case .createEvents: "Create Events"
        case .createLists: "Create Lists"
        case .editOwnLists: "Edit Own Lists"
        case .editAnyLists: "Edit Any Lists"
        case .deleteOwnLists: "Delete Own Lists"
        case .deleteAnyLists: "Delete Any Lists"
        case .createNotes: "Create Notes"
        case .editOwnNotes: "Edit Own Notes"
        case .editAnyNotes: "Edit Any Notes"
        case .deleteOwnNotes: "Delete Own Notes"
        case .deleteAnyNotes: "Delete Any Notes"
        case .uploadPhotosVideos: "Upload Media"
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
                .createAnnouncements,
                .editOwnAnnouncements,
                .deleteOwnAnnouncements,
                .deleteOthersAnnouncements,
                .createRooms, .editOwnRooms, .editOthersRooms, .deleteOwnRooms, .deleteOthersRooms, .manageRoomMembers, .postInRooms,
                .createEvents,
                .createLists, .editOwnLists, .editAnyLists, .deleteOwnLists, .deleteAnyLists,
                .createNotes, .editOwnNotes, .editAnyNotes, .deleteOwnNotes, .deleteAnyNotes,
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
                .createRooms, .editOwnRooms, .deleteOwnRooms, .postInRooms,
                .createEvents,
                .createLists, .editOwnLists, .deleteOwnLists,
                .createNotes, .editOwnNotes, .deleteOwnNotes,
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
                .postInRooms,
                .createEvents,
                .createLists, .editOwnLists, .deleteOwnLists,
                .createNotes, .editOwnNotes, .deleteOwnNotes,
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

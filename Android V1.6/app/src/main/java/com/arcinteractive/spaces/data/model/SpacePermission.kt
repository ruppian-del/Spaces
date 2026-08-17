package com.arcinteractive.spaces.data.model

enum class SpacePermission(
    val wireValue: String,
    val title: String
) {
    ManageSpaceSettings("manage_space_settings", "Manage Space Settings"),
    ManageMembers("manage_members", "Manage Members"),
    ManageRoles("manage_roles", "Manage Roles"),
    ManageModules("manage_modules", "Manage Modules"),
    InviteMembers("invite_members", "Invite Members"),
    RemoveMembers("remove_members", "Remove Members"),
    CreateEvents("create_events", "Create Events"),
    CreateLists("create_lists", "Create Lists"),
    EditOwnLists("edit_own_lists", "Edit Own Lists"),
    EditAnyLists("edit_any_lists", "Edit Any Lists"),
    DeleteOwnLists("delete_own_lists", "Delete Own Lists"),
    DeleteAnyLists("delete_any_lists", "Delete Any Lists"),
    CreateNotes("create_notes", "Create Notes"),
    EditOwnNotes("edit_own_notes", "Edit Own Notes"),
    EditAnyNotes("edit_any_notes", "Edit Any Notes"),
    DeleteOwnNotes("delete_own_notes", "Delete Own Notes"),
    DeleteAnyNotes("delete_any_notes", "Delete Any Notes"),
    PostPings("post_pings", "Post Pings"),
    CreateAnnouncements("create_announcements", "Create Announcements"),
    EditOwnAnnouncements("edit_own_announcements", "Edit Own Announcements"),
    DeleteOwnAnnouncements("delete_own_announcements", "Delete Own Announcements"),
    DeleteOthersAnnouncements("delete_others_announcements", "Delete Others' Announcements"),
    CreateRooms("create_rooms", "Create Rooms"),
    EditOwnRooms("edit_own_rooms", "Edit Own Rooms"),
    EditOthersRooms("edit_others_rooms", "Edit Others' Rooms"),
    DeleteOwnRooms("delete_own_rooms", "Delete Own Rooms"),
    DeleteOthersRooms("delete_others_rooms", "Delete Others' Rooms"),
    ManageRoomMembers("manage_room_members", "Manage Room Members"),
    PostInRooms("post_in_rooms", "Post in Rooms"),
    UploadFiles("upload_files", "Upload Files"),
    UploadPhotosVideos("upload_photos_videos", "Upload Media"),
    EditOwnContent("edit_own_content", "Edit Own Content"),
    DeleteOwnContent("delete_own_content", "Delete Own Content"),
    DeleteOthersContent("delete_others_content", "Delete Others' Content"),
    ViewContent("view_content", "View Content")
}

val SpaceMemberRole.capabilities: Set<SpacePermission>
    get() = when (this) {
        SpaceMemberRole.Owner -> SpacePermission.entries.toSet()
        SpaceMemberRole.Admin -> setOf(
            SpacePermission.ManageMembers,
            SpacePermission.ManageRoles,
            SpacePermission.ManageModules,
            SpacePermission.InviteMembers,
            SpacePermission.RemoveMembers,
            SpacePermission.CreateEvents,
            SpacePermission.CreateLists,
            SpacePermission.EditOwnLists,
            SpacePermission.EditAnyLists,
            SpacePermission.DeleteOwnLists,
            SpacePermission.DeleteAnyLists,
            SpacePermission.CreateNotes,
            SpacePermission.EditOwnNotes,
            SpacePermission.EditAnyNotes,
            SpacePermission.DeleteOwnNotes,
            SpacePermission.DeleteAnyNotes,
            SpacePermission.PostPings,
            SpacePermission.CreateAnnouncements,
            SpacePermission.EditOwnAnnouncements,
            SpacePermission.DeleteOwnAnnouncements,
            SpacePermission.DeleteOthersAnnouncements,
            SpacePermission.CreateRooms,
            SpacePermission.EditOwnRooms,
            SpacePermission.EditOthersRooms,
            SpacePermission.DeleteOwnRooms,
            SpacePermission.DeleteOthersRooms,
            SpacePermission.ManageRoomMembers,
            SpacePermission.PostInRooms,
            SpacePermission.UploadFiles,
            SpacePermission.UploadPhotosVideos,
            SpacePermission.EditOwnContent,
            SpacePermission.DeleteOwnContent,
            SpacePermission.DeleteOthersContent,
            SpacePermission.ViewContent
        )
        SpaceMemberRole.Moderator -> setOf(
            SpacePermission.CreateRooms,
            SpacePermission.EditOwnRooms,
            SpacePermission.DeleteOwnRooms,
            SpacePermission.PostInRooms,
            SpacePermission.CreateEvents,
            SpacePermission.CreateLists,
            SpacePermission.EditOwnLists,
            SpacePermission.DeleteOwnLists,
            SpacePermission.CreateNotes,
            SpacePermission.EditOwnNotes,
            SpacePermission.DeleteOwnNotes,
            SpacePermission.PostPings,
            SpacePermission.UploadFiles,
            SpacePermission.UploadPhotosVideos,
            SpacePermission.EditOwnContent,
            SpacePermission.DeleteOwnContent,
            SpacePermission.DeleteOthersContent,
            SpacePermission.ViewContent
        )
        SpaceMemberRole.Member -> setOf(
            SpacePermission.PostInRooms,
            SpacePermission.CreateEvents,
            SpacePermission.CreateLists,
            SpacePermission.EditOwnLists,
            SpacePermission.DeleteOwnLists,
            SpacePermission.CreateNotes,
            SpacePermission.EditOwnNotes,
            SpacePermission.DeleteOwnNotes,
            SpacePermission.PostPings,
            SpacePermission.UploadFiles,
            SpacePermission.UploadPhotosVideos,
            SpacePermission.EditOwnContent,
            SpacePermission.DeleteOwnContent,
            SpacePermission.ViewContent
        )
        SpaceMemberRole.Guest -> setOf(SpacePermission.ViewContent)
    }

fun SpaceMemberRole.canRemove(targetRole: SpaceMemberRole, isTargetOwner: Boolean): Boolean {
    if (isTargetOwner) return false

    return when (this) {
        SpaceMemberRole.Owner -> true
        SpaceMemberRole.Admin -> targetRole != SpaceMemberRole.Admin
        SpaceMemberRole.Moderator, SpaceMemberRole.Member, SpaceMemberRole.Guest -> false
    }
}

fun SpaceMemberRole.canChangeRole(
    targetRole: SpaceMemberRole,
    newRole: SpaceMemberRole,
    isTargetOwner: Boolean
): Boolean {
    if (isTargetOwner || newRole == SpaceMemberRole.Owner) return false

    return when (this) {
        SpaceMemberRole.Owner -> targetRole != SpaceMemberRole.Owner
        SpaceMemberRole.Admin -> {
            val isAllowedTarget = targetRole == SpaceMemberRole.Moderator ||
                targetRole == SpaceMemberRole.Member ||
                targetRole == SpaceMemberRole.Guest
            val isAllowedNewRole = newRole == SpaceMemberRole.Moderator ||
                newRole == SpaceMemberRole.Member ||
                newRole == SpaceMemberRole.Guest
            isAllowedTarget && isAllowedNewRole
        }
        SpaceMemberRole.Moderator, SpaceMemberRole.Member, SpaceMemberRole.Guest -> false
    }
}

fun Space.roleFor(userId: String?, members: List<SpaceMember>): SpaceMemberRole? {
    if (userId == null) return null
    if (userId == ownerId) return SpaceMemberRole.Owner
    return members.firstOrNull { it.id == userId }?.role
}

fun Space.hasCapability(
    userId: String?,
    members: List<SpaceMember>,
    capability: SpacePermission
): Boolean {
    val role = roleFor(userId, members) ?: return false
    return role.capabilities.contains(capability)
}

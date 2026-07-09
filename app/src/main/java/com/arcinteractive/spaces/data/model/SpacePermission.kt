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
    PostPings("post_pings", "Post Pings"),
    UploadFiles("upload_files", "Upload Files"),
    UploadPhotosVideos("upload_photos_videos", "Upload Photos/Videos"),
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
            SpacePermission.PostPings,
            SpacePermission.UploadFiles,
            SpacePermission.UploadPhotosVideos,
            SpacePermission.EditOwnContent,
            SpacePermission.DeleteOwnContent,
            SpacePermission.DeleteOthersContent,
            SpacePermission.ViewContent
        )
        SpaceMemberRole.Moderator -> setOf(
            SpacePermission.CreateEvents,
            SpacePermission.PostPings,
            SpacePermission.UploadFiles,
            SpacePermission.UploadPhotosVideos,
            SpacePermission.EditOwnContent,
            SpacePermission.DeleteOwnContent,
            SpacePermission.DeleteOthersContent,
            SpacePermission.ViewContent
        )
        SpaceMemberRole.Member -> setOf(
            SpacePermission.CreateEvents,
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

package com.arcinteractive.spaces.data.model

import java.util.Date

data class Organization(
    val id: String,
    val name: String,
    val status: OrganizationStatus,
    val entitlements: OrganizationEntitlements,
    val usage: OrganizationUsage,
    val createdAt: Date?
)

enum class OrganizationStatus(val wireValue: String) {
    Active("active"),
    Suspended("suspended")
}

data class OrganizationMember(
    val id: String,
    val userId: String,
    val displayName: String,
    val email: String?,
    val role: OrganizationRole,
    val status: OrganizationMemberStatus,
    val joinedAt: Date?
)

enum class OrganizationRole(val wireValue: String) {
    PrimaryAdministrator("primary_admin"),
    Administrator("admin"),
    Member("member");

    val canManageOrganization: Boolean
        get() = this == PrimaryAdministrator || this == Administrator
}

enum class OrganizationMemberStatus(val wireValue: String) {
    Active("active"),
    Suspended("suspended")
}

data class OrganizationEntitlements(
    val peopleCapacity: Int?,
    val activeSpaceCapacity: Int?,
    val enabledModuleIds: Set<String>,
    val mediaStorageCapacityBytes: Long?
) {
    companion object {
        val Foundation = OrganizationEntitlements(
            peopleCapacity = 250,
            activeSpaceCapacity = 10,
            enabledModuleIds = setOf("general", "events", "polls", "members", "settings"),
            mediaStorageCapacityBytes = 10L * 1024L * 1024L * 1024L
        )

        fun effective(context: android.content.Context, stored: OrganizationEntitlements): OrganizationEntitlements {
            return stored
        }
    }
    fun allowsAddingPerson(currentPeople: Int): Boolean {
        val capacity = peopleCapacity ?: return false
        return capacity > 0 && currentPeople < capacity
    }

    fun allowsActivatingSpace(currentActiveSpaces: Int): Boolean {
        val capacity = activeSpaceCapacity ?: return false
        return capacity > 0 && currentActiveSpaces < capacity
    }

    fun allowsModule(moduleId: String): Boolean = enabledModuleIds.contains(moduleId)

    fun allowsStorageIncrease(currentBytes: Long, additionalBytes: Long): Boolean {
        val capacity = mediaStorageCapacityBytes ?: return false
        if (currentBytes < 0 || additionalBytes < 0) return false
        return currentBytes <= capacity - additionalBytes
    }
}

data class OrganizationUsage(
    val peopleCount: Int,
    val activeSpaceCount: Int,
    val mediaStorageBytes: Long
) {
    fun afterStorageRelease(bytes: Long): OrganizationUsage = copy(
        mediaStorageBytes = maxOf(0L, mediaStorageBytes - maxOf(0L, bytes))
    )
}

data class OrganizationOwnedSpace(
    val id: String,
    val name: String,
    val emoji: String,
    val memberCount: Int,
    val memberIds: List<String>,
    val isArchived: Boolean
)

data class OrganizationInvite(
    val id: String,
    val organizationId: String,
    val organizationName: String,
    val role: OrganizationRole
)

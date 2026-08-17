package com.arcinteractive.spaces.data.model

data class SpaceMember(
    val id: String,
    val displayName: String,
    val emojiAvatar: String,
    val role: SpaceMemberRole,
    val status: String
)

enum class SpaceMemberRole(
    val label: String
) {
    Owner("Owner"),
    Admin("Admin"),
    Moderator("Moderator"),
    Member("Member"),
    Guest("Guest");

    val firestoreValue: String
        get() = name.lowercase()

    companion object {
        fun fromFirestoreValue(value: String): SpaceMemberRole? = entries.firstOrNull {
            it.firestoreValue == value.lowercase()
        }
    }
}

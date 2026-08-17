package com.arcinteractive.spaces.data.model

data class UserProfile(
    val id: String,
    val uid: String,
    val displayName: String,
    val emojiAvatar: String,
    val statusMessage: String,
    val email: String?,
    val phoneNumber: String?,
    val linkedProviders: List<LinkedProvider>,
    val blockedUsers: List<BlockedUser> = emptyList()
)

data class BlockedUser(
    val id: String,
    val uid: String,
    val displayName: String,
    val emojiAvatar: String,
    val blockedAt: java.util.Date?
)

data class AccountDevice(
    val id: String,
    val deviceId: String,
    val platform: String,
    val publicKey: String?,
    val createdAt: java.util.Date?,
    val lastActiveAt: java.util.Date?
)

data class PushTokenRecord(
    val id: String,
    val userId: String,
    val token: String,
    val platform: String,
    val deviceId: String?,
    val enabled: Boolean,
    val createdAt: java.util.Date?,
    val updatedAt: java.util.Date?
)

enum class LinkedProvider(
    val label: String
) {
    Apple("Apple"),
    Google("Google"),
    Phone("Phone");

    companion object {
        fun fromFirebaseProviderId(providerId: String): LinkedProvider? = when (providerId) {
            "google.com" -> Google
            "phone" -> Phone
            "apple.com" -> Apple
            else -> null
        }
    }
}

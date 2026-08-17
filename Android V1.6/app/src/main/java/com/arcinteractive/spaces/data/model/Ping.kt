package com.arcinteractive.spaces.data.model

import java.text.DateFormat
import java.util.Date

data class Ping(
    val id: String,
    val participantIds: List<String>,
    val participantNames: List<String>,
    val participantEmojis: List<String>,
    val lastMessageAt: Date?,
    val lastMessagePreviewType: String?,
    val createdAt: Date?,
    val updatedAt: Date?,
    val unreadCount: Int
) {
    fun otherParticipant(currentUserId: String?): PingParticipant? {
        if (currentUserId == null) return null
        val index = participantIds.indexOfFirst { it != currentUserId }
        if (index < 0) return null
        return PingParticipant(
            id = participantIds[index],
            displayName = participantNames.getOrNull(index) ?: "User",
            emojiAvatar = participantEmojis.getOrNull(index) ?: "🙂"
        )
    }

    fun title(currentUserId: String?): String {
        return otherParticipant(currentUserId)?.displayName ?: participantNames.firstOrNull() ?: "Ping"
    }

    fun emoji(currentUserId: String?): String {
        return otherParticipant(currentUserId)?.emojiAvatar ?: participantEmojis.firstOrNull() ?: "\uD83D\uDCAC"
    }

    val timestampText: String
        get() = dateFormatter.format(lastMessageAt ?: updatedAt ?: createdAt ?: Date())

    val lastMessagePreviewText: String
        get() = when (lastMessagePreviewType) {
            "video" -> "Video"
            "image" -> "Photo"
            "file" -> "File"
            "text" -> "Message"
            else -> "No messages yet"
        }
}

data class PingParticipant(
    val id: String,
    val displayName: String,
    val emojiAvatar: String
)

private val dateFormatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

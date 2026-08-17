package com.arcinteractive.spaces.data.model

import java.util.Date

data class SpaceRoom(
    val id: String,
    val spaceId: String,
    val name: String,
    val topic: String,
    val isPrivate: Boolean,
    val memberIds: Set<String>,
    val createdBy: String,
    val createdAt: Date,
    val updatedAt: Date,
    val postingMemberIds: Set<String>? = null
) {
    fun isVisibleTo(userId: String): Boolean =
        !isPrivate || memberIds.contains(userId) || createdBy == userId
}

data class RoomMessageReaction(val emoji: String, val userIds: Set<String>)

data class RoomMessageAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val storagePath: String,
    val nonce: String,
    val isMedia: Boolean
)

data class RoomMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val createdAt: Date,
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val reactions: List<RoomMessageReaction> = emptyList(),
    val isPinned: Boolean = false,
    val links: List<SpaceLinkAttachment> = emptyList(),
    val attachments: List<RoomMessageAttachment> = emptyList()
)

package com.arcinteractive.spaces.data.model

import java.util.Date

data class MessageReplyContext(
    val messageId: String,
    val senderName: String,
    val type: String,
    val preview: String
)

data class SpaceMessage(
    val id: String,
    val spaceId: String? = null,
    val senderId: String? = null,
    val senderName: String,
    val senderEmoji: String? = null,
    val type: MessageType = MessageType.Text,
    val encryptionVersion: String = "none",
    val deleted: Boolean = false,
    val text: String? = null,
    val media: SpaceMedia? = null,
    val createdAt: Date? = null,
    val updatedAt: Date? = null,
    val timestamp: String,
    val isOutgoing: Boolean,
    val status: String? = null,
    val deliveryStatus: String? = null,
    val isEdited: Boolean = false,
    val editedAt: Date? = null,
    val replyContext: MessageReplyContext? = null,
    val reactions: List<MessageReaction> = emptyList()
)

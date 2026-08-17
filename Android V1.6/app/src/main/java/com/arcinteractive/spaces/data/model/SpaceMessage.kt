package com.arcinteractive.spaces.data.model

import java.util.Date

data class MessageReplyContext(
    val messageId: String,
    val senderName: String,
    val type: String,
    val preview: String
)

data class LinkPreviewData(
    val originalUrl: String,
    val canonicalUrl: String? = null,
    val domain: String,
    val title: String,
    val summary: String? = null,
    val siteName: String? = null,
    val imageDataBase64: String? = null,
    val imageMimeType: String? = null
)

enum class SpaceLinkModuleType(
    val title: String,
    val icon: String,
    val emoji: String
) {
    Announcements("Announcement", "megaphone.fill", "\uD83D\uDCE2"),
    Polls("Poll", "chart.bar.xaxis", "\uD83D\uDCCA"),
    Files("File", "folder.fill", "\uD83D\uDCC1"),
    Events("Event", "calendar", "\uD83D\uDCC5"),
    Rooms("Room", "bubble.left.and.text.bubble.right.fill", "\uD83D\uDCAC"),
    Media("Media", "photo.on.rectangle", "\uD83D\uDDBC\uFE0F"),
    Lists("List", "checklist", "✅"),
    Notes("Note", "note.text", "📝")
}

data class SpaceLinkAttachment(
    val id: String,
    val moduleType: SpaceLinkModuleType,
    val targetId: String,
    val title: String,
    val subtitle: String? = null,
    val icon: String = moduleType.icon,
    val version: Int = 1
) {
    val searchableText: String
        get() = listOf(title, subtitle.orEmpty(), moduleType.title).joinToString("\n")
}

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
    val mediaItems: List<SpaceMedia> = emptyList(),
    val createdAt: Date? = null,
    val updatedAt: Date? = null,
    val timestamp: String,
    val isOutgoing: Boolean,
    val status: String? = null,
    val deliveryStatus: String? = null,
    val isEdited: Boolean = false,
    val editedAt: Date? = null,
    val replyContext: MessageReplyContext? = null,
    val linkPreview: LinkPreviewData? = null,
    val spaceLinks: List<SpaceLinkAttachment> = emptyList(),
    val reactions: List<MessageReaction> = emptyList(),
    val localDeliveryState: LocalMessageDeliveryState? = null,
    val localFailureMessage: String? = null
) {
    val resolvedMediaItems: List<SpaceMedia>
        get() = if (mediaItems.isNotEmpty()) mediaItems else listOfNotNull(media)

    val primaryMedia: SpaceMedia?
        get() = resolvedMediaItems.firstOrNull()

    val hasMediaAttachments: Boolean
        get() = resolvedMediaItems.isNotEmpty()
}

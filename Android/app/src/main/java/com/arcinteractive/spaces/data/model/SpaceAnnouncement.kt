package com.arcinteractive.spaces.data.model

import java.util.Date
import java.util.UUID

enum class AnnouncementAttachmentKind(val title: String, val emoji: String) {
    Image("Image", "\uD83D\uDDBC\uFE0F"),
    Video("Video", "\uD83C\uDFA5"),
    File("File", "\uD83D\uDCC4"),
    Link("Link", "\uD83D\uDD17")
}

data class AnnouncementAttachment(
    val id: String = UUID.randomUUID().toString(),
    val kind: AnnouncementAttachmentKind,
    val title: String,
    val url: String? = null,
    val storagePath: String? = null,
    val nonce: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val uploadedBy: String? = null
)

enum class AnnouncementReferenceKind(val title: String, val emoji: String) {
    Event("Event", "\uD83D\uDCC5"),
    Note("Note", "\uD83D\uDCDD"),
    ListItem("List", "✅"),
    Media("Media", "\uD83D\uDCF7"),
    File("File", "\uD83D\uDCC1")
}

data class AnnouncementReference(
    val id: String = UUID.randomUUID().toString(),
    val kind: AnnouncementReferenceKind,
    val targetId: String,
    val title: String,
    val subtitle: String? = null
)

data class AnnouncementReaction(
    val emoji: String,
    val userIds: Set<String>
)

data class AnnouncementComment(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val authorName: String,
    val body: String,
    val createdAt: Date = Date()
)

data class SpaceAnnouncement(
    val id: String = UUID.randomUUID().toString(),
    val spaceId: String,
    val title: String,
    val body: String,
    val authorId: String,
    val authorName: String,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val isPinned: Boolean = false,
    val expiresAt: Date? = null,
    val commentsEnabled: Boolean = true,
    val attachments: List<AnnouncementAttachment> = emptyList(),
    val references: List<AnnouncementReference> = emptyList(),
    val reactions: List<AnnouncementReaction> = emptyList(),
    val comments: List<AnnouncementComment> = emptyList()
) {
    val isExpired: Boolean
        get() = expiresAt?.let { !it.after(Date()) } ?: false
}

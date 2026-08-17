package com.arcinteractive.spaces.data.model

import java.util.Date

data class SpaceNoteAttachment(val id: String, val name: String, val mimeType: String, val storagePath: String, val nonce: String, val isMedia: Boolean)
data class SpaceNote(
    val id: String, val spaceId: String, val title: String, val markdown: String,
    val attachments: List<SpaceNoteAttachment>, val links: List<SpaceLinkAttachment>,
    val createdBy: String, val createdAt: Date, val updatedAt: Date
)
data class SpaceNoteComment(
    val id: String, val noteId: String, val authorId: String, val authorName: String,
    val body: String, val createdAt: Date
)

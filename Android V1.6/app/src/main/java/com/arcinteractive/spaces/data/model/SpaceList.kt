package com.arcinteractive.spaces.data.model

import java.util.Date
import java.util.UUID

data class SpaceListSection(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val order: Int
)

data class SpaceList(
    val id: String,
    val spaceId: String,
    val title: String,
    val sections: List<SpaceListSection>,
    val links: List<SpaceLinkAttachment>,
    val createdBy: String,
    val createdAt: Date,
    val updatedAt: Date
)

data class SpaceListItemAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val storagePath: String,
    val nonce: String,
    val isMedia: Boolean
)

data class SpaceListItem(
    val id: String,
    val listId: String,
    val title: String,
    val notes: String,
    val isCompleted: Boolean,
    val assignedMemberIds: Set<String>,
    val dueDate: Date?,
    val sectionId: String?,
    val order: Int,
    val attachments: List<SpaceListItemAttachment>,
    val links: List<SpaceLinkAttachment>,
    val createdBy: String,
    val createdAt: Date,
    val updatedAt: Date
)

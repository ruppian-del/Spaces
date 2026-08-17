package com.arcinteractive.spaces.data.spaces

import android.content.Context
import com.arcinteractive.spaces.data.model.AnnouncementAttachment
import com.arcinteractive.spaces.data.model.AnnouncementAttachmentKind
import com.arcinteractive.spaces.data.model.AnnouncementComment
import com.arcinteractive.spaces.data.model.AnnouncementReaction
import com.arcinteractive.spaces.data.model.AnnouncementReference
import com.arcinteractive.spaces.data.model.AnnouncementReferenceKind
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceAnnouncement
import com.arcinteractive.spaces.data.media.EncryptedMediaService
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.arcinteractive.spaces.data.model.ActivityTargetType
import com.arcinteractive.spaces.data.model.ActivityType

object AnnouncementStore {
    private val _announcements = MutableStateFlow<Map<String, List<SpaceAnnouncement>>>(emptyMap())
    val announcements: StateFlow<Map<String, List<SpaceAnnouncement>>> = _announcements.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private val listeners = mutableMapOf<String, ListenerRegistration>()
    private var firestore: FirebaseFirestore? = null
    private var applicationContext: Context? = null
    private val spacesById = mutableMapOf<String, Space>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startListening(context: Context, space: Space) {
        applicationContext = context.applicationContext
        spacesById[space.id] = space
        if (listeners.containsKey(space.id)) return
        val database = firestore(context) ?: run {
            ensureSeeded(space)
            return
        }

        if (!_announcements.value.containsKey(space.id)) {
            _announcements.value = _announcements.value + (space.id to emptyList())
        }
        listeners[space.id] = database.collection("spaces")
            .document(space.id)
            .collection("announcements")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _lastErrorMessage.value = error.localizedMessage ?: "Announcements could not be loaded."
                    return@addSnapshotListener
                }
                val values = snapshot?.documents.orEmpty().mapNotNull { mapAnnouncement(it, space.id) }
                _announcements.value = _announcements.value + (space.id to values)
                _lastErrorMessage.value = null
            }
    }

    fun clearError() {
        _lastErrorMessage.value = null
    }

    suspend fun uploadAttachment(
        context: Context,
        spaceId: String,
        userId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): AnnouncementAttachment {
        val id = java.util.UUID.randomUUID().toString()
        val metadata = EncryptedMediaService().uploadFile(
            context = context,
            spaceId = spaceId,
            storagePath = "spaces/$spaceId/announcements/$id",
            originalBytes = bytes,
            mimeType = mimeType,
            uploadedBy = userId
        ).metadata
        val kind = when {
            mimeType.startsWith("image/") -> AnnouncementAttachmentKind.Image
            mimeType.startsWith("video/") -> AnnouncementAttachmentKind.Video
            else -> AnnouncementAttachmentKind.File
        }
        return AnnouncementAttachment(
            id = id,
            kind = kind,
            title = fileName,
            storagePath = metadata.storagePath,
            nonce = metadata.nonce,
            mimeType = metadata.mimeType,
            fileSize = metadata.fileSize,
            uploadedBy = metadata.uploadedBy
        )
    }

    fun forSpace(space: Space): List<SpaceAnnouncement> {
        if (!_announcements.value.containsKey(space.id)) ensureSeeded(space)
        return _announcements.value[space.id].orEmpty()
            .filterNot { it.isExpired }
            .sortedWith(compareByDescending<SpaceAnnouncement> { it.isPinned }.thenByDescending { it.createdAt })
    }

    fun save(announcement: SpaceAnnouncement) {
        val values = _announcements.value[announcement.spaceId].orEmpty().toMutableList()
        val index = values.indexOfFirst { it.id == announcement.id }
        val isNew = index < 0
        if (index >= 0) values[index] = announcement else values += announcement
        _announcements.value = _announcements.value + (announcement.spaceId to values)
        persist(announcement)
        val context = applicationContext
        val space = spacesById[announcement.spaceId]
        if (context != null && space != null) scope.launch {
            val service = SpaceService()
            if (isNew) {
                service.recordModuleActivity(
                    context, ActivityType.AnnouncementCreated, space, "posted an Announcement",
                    announcement.title, announcement.id, ActivityTargetType.Announcements
                )
            }
            if (!isNew) {
                val recipients = service.mentionedMemberIds(context, announcement.body, space)
                service.recordTargetedModuleNotification(
                    context, recipients, "announcement", space, "mentioned you in an Announcement",
                    announcement.title, announcement.id, ActivityTargetType.Announcements
                )
            }
        }
    }

    fun delete(spaceId: String, announcementId: String) {
        _announcements.value = _announcements.value + (
            spaceId to _announcements.value[spaceId].orEmpty().filterNot { it.id == announcementId }
        )
        firestore?.collection("spaces")?.document(spaceId)?.collection("announcements")
            ?.document(announcementId)?.delete()
            ?.addOnFailureListener { _lastErrorMessage.value = it.localizedMessage }
    }

    fun toggleReaction(spaceId: String, announcementId: String, emoji: String, userId: String) {
        update(spaceId, announcementId) { announcement ->
            val reactions = announcement.reactions.toMutableList()
            val index = reactions.indexOfFirst { it.emoji == emoji }
            if (index >= 0) {
                val users = reactions[index].userIds.toMutableSet()
                if (!users.add(userId)) users.remove(userId)
                if (users.isEmpty()) reactions.removeAt(index) else reactions[index] = reactions[index].copy(userIds = users)
            } else {
                reactions += AnnouncementReaction(emoji, setOf(userId))
            }
            announcement.copy(reactions = reactions, updatedAt = Date())
        }
    }

    fun addComment(spaceId: String, announcementId: String, comment: AnnouncementComment) {
        val creatorId = _announcements.value[spaceId].orEmpty().firstOrNull { it.id == announcementId }?.authorId
        val title = _announcements.value[spaceId].orEmpty().firstOrNull { it.id == announcementId }?.title
        update(spaceId, announcementId) { announcement ->
            if (!announcement.commentsEnabled) announcement
            else announcement.copy(comments = announcement.comments + comment, updatedAt = Date())
        }
        val context = applicationContext
        val space = spacesById[spaceId]
        if (context != null && space != null && creatorId != null) scope.launch {
            val service = SpaceService()
            val recipients = (service.mentionedMemberIds(context, comment.body, space) + creatorId).distinct()
            SpaceService().recordTargetedModuleNotification(
                context, recipients, "announcement", space, "commented on your Announcement",
                title, announcementId, ActivityTargetType.Announcements
            )
        }
    }

    private fun update(spaceId: String, announcementId: String, transform: (SpaceAnnouncement) -> SpaceAnnouncement) {
        var changed: SpaceAnnouncement? = null
        val values = _announcements.value[spaceId].orEmpty().map {
            if (it.id == announcementId) transform(it).also { updated -> changed = updated } else it
        }
        _announcements.value = _announcements.value + (spaceId to values)
        changed?.let(::persist)
    }

    private fun persist(announcement: SpaceAnnouncement) {
        firestore?.collection("spaces")?.document(announcement.spaceId)?.collection("announcements")
            ?.document(announcement.id)?.set(documentData(announcement))
            ?.addOnFailureListener { _lastErrorMessage.value = it.localizedMessage }
    }

    private fun firestore(context: Context): FirebaseFirestore? {
        firestore?.let { return it }
        if (FirebaseApp.getApps(context.applicationContext).isEmpty()) return null
        return FirebaseFirestore.getInstance().also { firestore = it }
    }

    private fun documentData(value: SpaceAnnouncement): Map<String, Any?> = mapOf(
        "spaceId" to value.spaceId,
        "title" to value.title,
        "body" to value.body,
        "authorId" to value.authorId,
        "authorName" to value.authorName,
        "createdAt" to Timestamp(value.createdAt),
        "updatedAt" to Timestamp(value.updatedAt),
        "isPinned" to value.isPinned,
        "expiresAt" to value.expiresAt?.let(::Timestamp),
        "commentsEnabled" to value.commentsEnabled,
        "attachments" to value.attachments.map {
            mapOf(
                "id" to it.id, "kind" to it.kind.name.lowercase(), "title" to it.title, "url" to it.url,
                "storagePath" to it.storagePath, "nonce" to it.nonce, "mimeType" to it.mimeType,
                "fileSize" to it.fileSize, "uploadedBy" to it.uploadedBy
            )
        },
        "references" to value.references.map {
            mapOf(
                "id" to it.id,
                "kind" to if (it.kind == AnnouncementReferenceKind.ListItem) "list" else it.kind.name.lowercase(),
                "targetId" to it.targetId,
                "title" to it.title,
                "subtitle" to it.subtitle
            )
        },
        "reactions" to value.reactions.map { mapOf("emoji" to it.emoji, "userIds" to it.userIds.toList()) },
        "comments" to value.comments.map {
            mapOf(
                "id" to it.id,
                "authorId" to it.authorId,
                "authorName" to it.authorName,
                "body" to it.body,
                "createdAt" to Timestamp(it.createdAt)
            )
        }
    )

    private fun mapAnnouncement(document: DocumentSnapshot, fallbackSpaceId: String): SpaceAnnouncement? {
        val data = document.data ?: return null
        val attachments = data.listOfMaps("attachments").mapNotNull {
            val kind = enumValues<AnnouncementAttachmentKind>().firstOrNull { value ->
                value.name.equals(it["kind"] as? String, ignoreCase = true)
            } ?: return@mapNotNull null
            AnnouncementAttachment(
                id = it["id"] as? String ?: return@mapNotNull null,
                kind = kind,
                title = it["title"] as? String ?: "",
                url = it["url"] as? String,
                storagePath = it["storagePath"] as? String,
                nonce = it["nonce"] as? String,
                mimeType = it["mimeType"] as? String,
                fileSize = (it["fileSize"] as? Number)?.toLong(),
                uploadedBy = it["uploadedBy"] as? String
            )
        }
        val references = data.listOfMaps("references").mapNotNull {
            val rawKind = it["kind"] as? String ?: return@mapNotNull null
            val kind = if (rawKind.equals("list", true)) AnnouncementReferenceKind.ListItem
            else enumValues<AnnouncementReferenceKind>().firstOrNull { value -> value.name.equals(rawKind, true) }
                ?: return@mapNotNull null
            AnnouncementReference(
                id = it["id"] as? String ?: return@mapNotNull null,
                kind = kind,
                targetId = it["targetId"] as? String ?: return@mapNotNull null,
                title = it["title"] as? String ?: "",
                subtitle = it["subtitle"] as? String
            )
        }
        return SpaceAnnouncement(
            id = document.id,
            spaceId = data["spaceId"] as? String ?: fallbackSpaceId,
            title = data["title"] as? String ?: return null,
            body = data["body"] as? String ?: "",
            authorId = data["authorId"] as? String ?: "",
            authorName = data["authorName"] as? String ?: "Unknown",
            createdAt = data.date("createdAt") ?: Date(),
            updatedAt = data.date("updatedAt") ?: Date(),
            isPinned = data["isPinned"] as? Boolean ?: false,
            expiresAt = data.date("expiresAt"),
            commentsEnabled = data["commentsEnabled"] as? Boolean ?: true,
            attachments = attachments,
            references = references,
            reactions = data.listOfMaps("reactions").mapNotNull {
                val emoji = it["emoji"] as? String ?: return@mapNotNull null
                AnnouncementReaction(emoji, (it["userIds"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty())
            },
            comments = data.listOfMaps("comments").mapNotNull {
                AnnouncementComment(
                    id = it["id"] as? String ?: return@mapNotNull null,
                    authorId = it["authorId"] as? String ?: "",
                    authorName = it["authorName"] as? String ?: "Unknown",
                    body = it["body"] as? String ?: "",
                    createdAt = it.date("createdAt") ?: Date()
                )
            }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.listOfMaps(key: String): List<Map<String, Any>> =
        (this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any> }.orEmpty()

    private fun Map<String, Any>.date(key: String): Date? = when (val value = this[key]) {
        is Timestamp -> value.toDate()
        is Date -> value
        else -> null
    }

    private fun ensureSeeded(space: Space) {
        if (_announcements.value.containsKey(space.id)) return
        _announcements.value = _announcements.value + (space.id to emptyList())
    }
}

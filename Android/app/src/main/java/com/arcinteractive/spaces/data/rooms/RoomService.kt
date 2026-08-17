package com.arcinteractive.spaces.data.rooms

import com.arcinteractive.spaces.data.model.RoomMessage
import com.arcinteractive.spaces.data.model.RoomMessageReaction
import com.arcinteractive.spaces.data.model.SpaceRoom
import com.arcinteractive.spaces.data.spaces.EncryptionService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import android.util.Base64
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RoomService {
    private val firestore = FirebaseFirestore.getInstance()
    private val encryption = EncryptionService()

    fun listenToRooms(
        spaceId: String,
        onUpdate: (Result<List<SpaceRoom>>) -> Unit
    ): ListenerRegistration? {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        val collection = firestore.collection("spaces").document(spaceId).collection("rooms")
        var publicRooms = emptyList<SpaceRoom>()
        var privateRooms = emptyList<SpaceRoom>()

        fun publish() {
            onUpdate(Result.success(
                (publicRooms + privateRooms).distinctBy { it.id }.sortedByDescending { it.updatedAt }
            ))
        }

        val publicListener = collection.whereEqualTo("isPrivate", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onUpdate(Result.failure(error))
                else {
                    publicRooms = snapshot?.documents?.mapNotNull(::mapRoom).orEmpty()
                    publish()
                }
            }
        val privateListener = collection.whereEqualTo("isPrivate", true)
            .whereArrayContains("memberIds", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onUpdate(Result.failure(error))
                else {
                    privateRooms = snapshot?.documents?.mapNotNull(::mapRoom).orEmpty()
                    publish()
                }
            }
        return CompositeListener(listOf(publicListener, privateListener))
    }

    fun listenToRoom(
        spaceId: String,
        roomId: String,
        onUpdate: (Result<SpaceRoom>) -> Unit
    ): ListenerRegistration {
        return firestore.collection("spaces").document(spaceId)
            .collection("rooms").document(roomId)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> onUpdate(Result.failure(error))
                    snapshot != null && snapshot.exists() -> {
                        mapRoom(snapshot)?.let { onUpdate(Result.success(it)) }
                    }
                }
            }
    }

    fun saveRoom(room: SpaceRoom, onComplete: (Result<Unit>) -> Unit) {
        val reference = firestore.collection("spaces").document(room.spaceId).collection("rooms").document(room.id)
        reference.get().addOnSuccessListener { existing ->
            val keyMode = existing.getString("keyMode")
                ?: if (existing.exists()) "legacy-room-key-v1" else "space-member-key-v1"
            reference.set(
                mapOf(
                    "spaceId" to room.spaceId,
                    "name" to room.name,
                    "topic" to room.topic,
                    "isPrivate" to room.isPrivate,
                    "memberIds" to room.memberIds.toList(),
                    "createdBy" to room.createdBy,
                    "createdAt" to room.createdAt,
                    "updatedAt" to room.updatedAt,
                    "postingMemberIds" to room.postingMemberIds?.toList(),
                    "keyMode" to keyMode
                )
            )
            .addOnSuccessListener {
                ensureRoomKey(room.spaceId, room.id) { result -> onComplete(result.map { Unit }) }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
        }.addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun deleteRoom(room: SpaceRoom, onComplete: (Result<Unit>) -> Unit) {
        val roomReference = firestore.collection("spaces").document(room.spaceId).collection("rooms").document(room.id)
        roomReference.collection("messages").get()
            .addOnSuccessListener { snapshot ->
                val attachmentDeletes = snapshot.documents.flatMap { document ->
                    (document.get("attachments") as? List<*>).orEmpty().mapNotNull { value ->
                        val path = (value as? Map<*, *>)?.get("storagePath") as? String
                        path?.let { FirebaseStorage.getInstance().reference.child(it).delete() }
                    }
                }
                com.google.android.gms.tasks.Tasks.whenAllComplete(attachmentDeletes)
                    .addOnSuccessListener {
                        roomReference.collection("encryption").get()
                            .addOnSuccessListener { encryptionSnapshot ->
                                val references = snapshot.documents.map { it.reference } +
                                    encryptionSnapshot.documents.map { it.reference }
                                val commits = references.chunked(400).map { chunk ->
                                    val cleanupBatch = firestore.batch()
                                    chunk.forEach(cleanupBatch::delete)
                                    cleanupBatch.commit()
                                }
                                com.google.android.gms.tasks.Tasks.whenAll(commits)
                                    .continueWithTask { roomReference.delete() }
                                    .addOnSuccessListener { onComplete(Result.success(Unit)) }
                                    .addOnFailureListener { onComplete(Result.failure(it)) }
                            }
                            .addOnFailureListener { onComplete(Result.failure(it)) }
                    }
                    .addOnFailureListener { onComplete(Result.failure(it)) }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun listenToMessages(
        spaceId: String,
        roomId: String,
        onUpdate: (Result<List<RoomMessage>>) -> Unit
    ): ListenerRegistration {
        val holder = DeferredCompositeListener()
        ensureRoomKey(spaceId, roomId) { keyResult ->
            keyResult.onFailure { onUpdate(Result.failure(it)) }
            keyResult.onSuccess { key ->
                holder.set(
                    firestore.collection("spaces").document(spaceId)
                        .collection("rooms").document(roomId)
                        .collection("messages")
                        .orderBy("createdAt", Query.Direction.ASCENDING)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                onUpdate(Result.failure(error))
                            } else {
                                val messages = snapshot?.documents.orEmpty().mapNotNull { document ->
                                    runCatching {
                                        val ciphertext = document.getString("ciphertext") ?: return@runCatching null
                                        val nonce = document.getString("nonce") ?: return@runCatching null
                                        RoomMessage(
                                            id = document.id,
                                            roomId = document.getString("roomId").orEmpty(),
                                            senderId = document.getString("senderId").orEmpty(),
                                            senderName = document.getString("senderName") ?: "Member",
                                            body = encryption.decryptText(ciphertext, nonce, null, key),
                                            createdAt = document.getTimestamp("createdAt")?.toDate() ?: Date(),
                                            replyToId = document.getString("replyToId"),
                                            replyPreview = document.getString("replyPreview"),
                                            reactions = (document.get("reactions") as? List<*>).orEmpty()
                                                .mapNotNull { value ->
                                                    val map = value as? Map<*, *> ?: return@mapNotNull null
                                                    val emoji = map["emoji"] as? String ?: return@mapNotNull null
                                                    RoomMessageReaction(emoji, (map["userIds"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty())
                                                },
                                            isPinned = document.getBoolean("isPinned") ?: false,
                                            links = (document.get("links") as? List<*>).orEmpty().mapNotNull { value ->
                                                val map = value as? Map<*, *> ?: return@mapNotNull null
                                                val rawType = map["moduleType"] as? String ?: return@mapNotNull null
                                                val moduleType = com.arcinteractive.spaces.data.model.SpaceLinkModuleType.entries
                                                    .firstOrNull { it.name.equals(rawType, true) } ?: return@mapNotNull null
                                                com.arcinteractive.spaces.data.model.SpaceLinkAttachment(
                                                    id = map["id"] as? String ?: UUID.randomUUID().toString(),
                                                    moduleType = moduleType,
                                                    targetId = map["targetId"] as? String ?: return@mapNotNull null,
                                                    title = map["title"] as? String ?: return@mapNotNull null,
                                                    subtitle = map["subtitle"] as? String,
                                                    icon = map["icon"] as? String ?: moduleType.icon
                                                )
                                            },
                                            attachments = (document.get("attachments") as? List<*>).orEmpty().mapNotNull { value ->
                                                val map = value as? Map<*, *> ?: return@mapNotNull null
                                                com.arcinteractive.spaces.data.model.RoomMessageAttachment(
                                                    id = map["id"] as? String ?: return@mapNotNull null,
                                                    name = map["name"] as? String ?: return@mapNotNull null,
                                                    mimeType = map["mimeType"] as? String ?: "application/octet-stream",
                                                    storagePath = map["storagePath"] as? String ?: return@mapNotNull null,
                                                    nonce = map["nonce"] as? String ?: return@mapNotNull null,
                                                    isMedia = map["isMedia"] as? Boolean ?: false
                                                )
                                            }
                                        )
                                    }.getOrNull()
                                }
                                onUpdate(Result.success(messages))
                            }
                        }
                )
            }
        }
        return holder
    }

    fun sendMessage(
        spaceId: String,
        roomId: String,
        senderName: String,
        body: String,
        reply: RoomMessage? = null,
        links: List<com.arcinteractive.spaces.data.model.SpaceLinkAttachment> = emptyList(),
        onComplete: (Result<Unit>) -> Unit
    ) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
            ?: return onComplete(Result.failure(IllegalStateException("You must be signed in.")))
        requirePostingPermission(spaceId, roomId, userId) { permissionResult ->
            permissionResult.onFailure {
                onComplete(Result.failure(it))
                return@requirePostingPermission
            }
            ensureRoomKey(spaceId, roomId) { keyResult ->
                keyResult.onFailure { onComplete(Result.failure(it)) }
                keyResult.onSuccess { key ->
                val payload = encryption.encryptText(body, key)
                val id = UUID.randomUUID().toString()
                firestore.collection("spaces").document(spaceId)
                    .collection("rooms").document(roomId)
                    .collection("messages").document(id)
                    .set(
                        mapOf(
                            "id" to id,
                            "roomId" to roomId,
                            "senderId" to userId,
                            "senderName" to senderName,
                            "ciphertext" to payload.ciphertext,
                            "nonce" to payload.nonce,
                            "encryptionVersion" to "aes-gcm-v1",
                            "createdAt" to FieldValue.serverTimestamp(),
                            "replyToId" to reply?.id,
                            "replyPreview" to reply?.body?.take(120),
                            "reactions" to emptyList<Map<String, Any>>(),
                            "isPinned" to false,
                            "links" to links.map {
                                mapOf(
                                    "id" to it.id,
                                    "moduleType" to it.moduleType.name.lowercase(),
                                    "targetId" to it.targetId,
                                    "title" to it.title,
                                    "subtitle" to it.subtitle.orEmpty(),
                                    "icon" to it.icon
                                )
                            }
                        )
                    )
                    .addOnSuccessListener { onComplete(Result.success(Unit)) }
                    .addOnFailureListener { onComplete(Result.failure(it)) }
                }
            }
        }
    }

    fun updateMessage(
        spaceId: String,
        roomId: String,
        messageId: String,
        fields: Map<String, Any>,
        onComplete: (Result<Unit>) -> Unit = {}
    ) {
        firestore.collection("spaces").document(spaceId)
            .collection("rooms").document(roomId)
            .collection("messages").document(messageId)
            .update(fields)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun editMessage(
        spaceId: String,
        roomId: String,
        message: RoomMessage,
        body: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null || userId != message.senderId) {
            onComplete(Result.failure(IllegalAccessException("You can only edit your own messages.")))
            return
        }
        ensureRoomKey(spaceId, roomId) { keyResult ->
            keyResult.onFailure { onComplete(Result.failure(it)) }
            keyResult.onSuccess { key ->
                val payload = encryption.encryptText(body, key)
                firestore.collection("spaces").document(spaceId).collection("rooms").document(roomId)
                    .collection("messages").document(message.id)
                    .update(
                        mapOf(
                            "ciphertext" to payload.ciphertext,
                            "nonce" to payload.nonce,
                            "editedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .addOnSuccessListener { onComplete(Result.success(Unit)) }
                    .addOnFailureListener { onComplete(Result.failure(it)) }
            }
        }
    }

    fun deleteMessage(
        spaceId: String,
        roomId: String,
        message: RoomMessage,
        canDeleteOthers: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null || (userId != message.senderId && !canDeleteOthers)) {
            onComplete(Result.failure(IllegalAccessException("You do not have permission to delete this message.")))
            return
        }
        val deletes = message.attachments.map {
            FirebaseStorage.getInstance().reference.child(it.storagePath).delete()
        }
        com.google.android.gms.tasks.Tasks.whenAllComplete(deletes).addOnCompleteListener {
            firestore.collection("spaces").document(spaceId).collection("rooms").document(roomId)
                .collection("messages").document(message.id).delete()
                .addOnSuccessListener { onComplete(Result.success(Unit)) }
                .addOnFailureListener { onComplete(Result.failure(it)) }
        }
    }

    fun sendAttachment(
        spaceId: String,
        roomId: String,
        senderName: String,
        bytes: ByteArray,
        name: String,
        mimeType: String,
        isMedia: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
            ?: return onComplete(Result.failure(IllegalStateException("You must be signed in.")))
        requirePostingPermission(spaceId, roomId, userId) { permissionResult ->
            permissionResult.onFailure {
                onComplete(Result.failure(it))
                return@requirePostingPermission
            }
            ensureRoomKey(spaceId, roomId) { keyResult ->
                keyResult.onFailure { onComplete(Result.failure(it)) }
                keyResult.onSuccess { key ->
                val attachmentId = UUID.randomUUID().toString()
                val path = "spaces/$spaceId/rooms/$roomId/attachments/$attachmentId.enc"
                val encrypted = encryption.encryptBytes(bytes, key)
                FirebaseStorage.getInstance().reference.child(path)
                    .putBytes(Base64.decode(encrypted.ciphertext, Base64.NO_WRAP))
                    .addOnFailureListener { onComplete(Result.failure(it)) }
                    .addOnSuccessListener {
                        val body = encryption.encryptText(name, key)
                        val messageId = UUID.randomUUID().toString()
                        firestore.collection("spaces").document(spaceId)
                            .collection("rooms").document(roomId)
                            .collection("messages").document(messageId)
                            .set(
                                mapOf(
                                    "id" to messageId, "roomId" to roomId,
                                    "senderId" to userId, "senderName" to senderName,
                                    "ciphertext" to body.ciphertext, "nonce" to body.nonce,
                                    "encryptionVersion" to "aes-gcm-v1",
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "reactions" to emptyList<Map<String, Any>>(),
                                    "isPinned" to false, "links" to emptyList<Map<String, Any>>(),
                                    "attachments" to listOf(
                                        mapOf(
                                            "id" to attachmentId, "name" to name,
                                            "mimeType" to mimeType, "storagePath" to path,
                                            "nonce" to encrypted.nonce, "isMedia" to isMedia
                                        )
                                    )
                                )
                            )
                            .addOnSuccessListener { onComplete(Result.success(Unit)) }
                            .addOnFailureListener { onComplete(Result.failure(it)) }
                    }
                }
            }
        }
    }

    private fun requirePostingPermission(
        spaceId: String,
        roomId: String,
        userId: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val context = com.google.firebase.FirebaseApp.getInstance().applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val allowed = runCatching {
                com.arcinteractive.spaces.data.spaces.SpaceService().canPerformFeature(
                    context,
                    spaceId,
                    com.arcinteractive.spaces.data.model.SpacePermission.PostInRooms
                )
            }.getOrElse {
                onComplete(Result.failure(it))
                return@launch
            }
            if (!allowed) {
                onComplete(Result.failure(IllegalAccessException("You do not have permission to post in Rooms.")))
                return@launch
            }
            requireRoomPostingMode(spaceId, roomId, userId, onComplete)
        }
    }

    private fun requireRoomPostingMode(
        spaceId: String,
        roomId: String,
        userId: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        firestore.collection("spaces").document(spaceId)
            .collection("rooms").document(roomId)
            .get()
            .addOnSuccessListener { document ->
                val postingMemberIds = (document.get("postingMemberIds") as? List<*>)
                    ?.filterIsInstance<String>()
                if (postingMemberIds != null) {
                    firestore.collection("spaces").document(spaceId)
                        .collection("members").document(userId)
                        .get()
                        .addOnSuccessListener { memberDocument ->
                            val role = memberDocument.getString("role")?.lowercase()
                            if (role == "owner" || role == "admin") {
                                onComplete(Result.success(Unit))
                            } else {
                                onComplete(
                                    Result.failure(
                                        IllegalAccessException("Only Space Owners and Admins can post in this Room.")
                                    )
                                )
                            }
                        }
                        .addOnFailureListener { onComplete(Result.failure(it)) }
                } else {
                    onComplete(Result.success(Unit))
                }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun downloadAttachment(
        spaceId: String,
        roomId: String,
        attachment: com.arcinteractive.spaces.data.model.RoomMessageAttachment,
        onComplete: (Result<ByteArray>) -> Unit
    ) {
        ensureRoomKey(spaceId, roomId) { keyResult ->
            keyResult.onFailure { onComplete(Result.failure(it)) }
            keyResult.onSuccess { key ->
                FirebaseStorage.getInstance().reference.child(attachment.storagePath)
                    .getBytes(250L * 1024L * 1024L)
                    .addOnSuccessListener { encrypted ->
                        runCatching {
                            encryption.decryptBytes(
                                Base64.encodeToString(encrypted, Base64.NO_WRAP),
                                attachment.nonce,
                                key
                            )
                        }.also(onComplete)
                    }
                    .addOnFailureListener { onComplete(Result.failure(it)) }
            }
        }
    }

    private fun ensureRoomKey(spaceId: String, roomId: String, onComplete: (Result<ByteArray>) -> Unit) {
        val cacheId = "$spaceId.room.$roomId"
        encryption.cachedSpaceKey(cacheId)?.let {
            onComplete(Result.success(it))
            return
        }
        val roomRef = firestore.collection("spaces").document(spaceId).collection("rooms").document(roomId)
        roomRef.get().addOnSuccessListener { roomSnapshot ->
            if (roomSnapshot.getString("keyMode") == "space-member-key-v1") {
                val context = com.google.firebase.FirebaseApp.getInstance().applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        com.arcinteractive.spaces.data.spaces.SpaceService()
                            .encryptionKeyForAuthorizedFeature(context, spaceId)
                    }.onSuccess { key ->
                        encryption.cacheSpaceKey(cacheId, key)
                        onComplete(Result.success(key))
                    }.onFailure { onComplete(Result.failure(it)) }
                }
                return@addOnSuccessListener
            }
            ensureLegacyRoomKey(spaceId, roomId, cacheId, onComplete)
        }.addOnFailureListener { onComplete(Result.failure(it)) }
    }

    private fun ensureLegacyRoomKey(
        spaceId: String,
        roomId: String,
        cacheId: String,
        onComplete: (Result<ByteArray>) -> Unit
    ) {
        val ref = firestore.collection("spaces").document(spaceId)
            .collection("rooms").document(roomId)
            .collection("encryption").document("key")
        ref.get().addOnSuccessListener { snapshot ->
            val existing = snapshot.getString("keyBase64")
            if (existing != null) {
                val key = encryption.decodeSpaceKey(existing)
                encryption.cacheSpaceKey(cacheId, key)
                onComplete(Result.success(key))
            } else {
                val key = encryption.generateSpaceKey()
                ref.set(
                    mapOf(
                        "keyBase64" to encryption.encodeSpaceKey(key),
                        "keyVersion" to "aes-gcm-v1",
                        "createdBy" to FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    ),
                    SetOptions.merge()
                ).addOnSuccessListener {
                    encryption.cacheSpaceKey(cacheId, key)
                    onComplete(Result.success(key))
                }.addOnFailureListener { onComplete(Result.failure(it)) }
            }
        }.addOnFailureListener { onComplete(Result.failure(it)) }
    }

    private fun mapRoom(document: com.google.firebase.firestore.DocumentSnapshot): SpaceRoom? {
        val spaceId = document.getString("spaceId") ?: return null
        val name = document.getString("name") ?: return null
        val createdBy = document.getString("createdBy") ?: return null
        return SpaceRoom(
            id = document.id,
            spaceId = spaceId,
            name = name,
            topic = document.getString("topic").orEmpty(),
            isPrivate = document.getBoolean("isPrivate") ?: false,
            memberIds = (document.get("memberIds") as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
            createdBy = createdBy,
            createdAt = document.getTimestamp("createdAt")?.toDate() ?: Date(),
            updatedAt = document.getTimestamp("updatedAt")?.toDate() ?: Date(),
            postingMemberIds = (document.get("postingMemberIds") as? List<*>)?.filterIsInstance<String>()?.toSet()
        )
    }
}

private class CompositeListener(private val listeners: List<ListenerRegistration>) : ListenerRegistration {
    override fun remove() = listeners.forEach(ListenerRegistration::remove)
}

private class DeferredCompositeListener : ListenerRegistration {
    private var removed = false
    private var listener: ListenerRegistration? = null

    fun set(value: ListenerRegistration) {
        if (removed) value.remove() else listener = value
    }

    override fun remove() {
        removed = true
        listener?.remove()
    }
}

package com.arcinteractive.spaces.data.pings

import android.util.Log
import android.content.Context
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.firestore.FirestoreListenerRegistry
import com.arcinteractive.spaces.data.media.EncryptedMediaService
import com.arcinteractive.spaces.data.model.EncryptedMediaMetadata
import com.arcinteractive.spaces.data.model.MediaType
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Ping
import com.arcinteractive.spaces.data.model.PingParticipant
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.data.spaces.EncryptionService
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PingService(
    private val authService: AuthService = AuthService(),
    private val userProfileService: UserProfileService = UserProfileService(),
    private val encryptionService: EncryptionService = EncryptionService(),
    private val encryptedMediaService: EncryptedMediaService = EncryptedMediaService()
) {
    private val receiveLogTag = "GifReceive"
    private val generalEncryptionVersion = "aes-gcm-v1"
    private val verifiedPingEncryptionIds = mutableSetOf<String>()

    private fun registerListener(
        listenerKey: String?,
        registration: ListenerRegistration?
    ): ListenerRegistration? {
        val key = listenerKey ?: "pings.${System.identityHashCode(registration)}"
        return FirestoreListenerRegistry.register(key, registration)
    }

    fun currentUserId(context: Context): String? = authService.currentSession(context)?.uid

    fun listenToPingsForCurrentUser(
        context: Context,
        listenerKey: String? = null,
        onUpdate: (Result<List<Ping>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.failure(IllegalStateException("Firestore is not configured yet.")))
            return null
        }
        val session = authService.currentSession(context) ?: run {
            onUpdate(Result.failure(IllegalStateException("Sign in before using Ping.")))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("pings")
            .whereArrayContains("participantIds", session.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val pings = snapshot?.documents.orEmpty()
                    .mapNotNull { mapPing(it) }
                    .sortedWith { left, right ->
                        val leftDate = left.updatedAt ?: left.createdAt
                        val rightDate = right.updatedAt ?: right.createdAt
                        when {
                            leftDate != null && rightDate != null -> rightDate.compareTo(leftDate)
                            leftDate != null -> -1
                            rightDate != null -> 1
                            else -> left.id.compareTo(right.id)
                        }
                    }
                onUpdate(Result.success(pings))
            }
        )
    }

    fun listenToMessages(
        context: Context,
        ping: Ping,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpaceMessage>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.failure(IllegalStateException("Firestore is not configured yet.")))
            return null
        }
        val session = authService.currentSession(context) ?: run {
            onUpdate(Result.failure(IllegalStateException("Sign in before opening this Ping.")))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("pings")
            .document(ping.id)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(receiveLogTag, "Firestore listener failed pingId=${ping.id}", error)
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    try {
                        val pingKey = ensureEncryptionKey(context, ping.id)
                        runMessageEncryptionSelfTestIfNeeded(ping.id, pingKey)
                        val mappedMessages = snapshot?.documents.orEmpty().mapNotNull {
                            logGifSnapshot(it)
                            mapMessage(it, session.uid, pingKey)
                        }
                        onUpdate(Result.success(mappedMessages))
                    } catch (error: Throwable) {
                        Log.e(receiveLogTag, "model mapping failed pingId=${ping.id}", error)
                        onUpdate(Result.failure(error))
                    }
                }
            }
        )
    }

    suspend fun fetchRecentMessages(
        context: Context,
        ping: Ping,
        limit: Int = 20
    ): List<SpaceMessage> {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context)
            ?: throw IllegalStateException("Sign in before opening this Ping.")

        val snapshot = getDocuments(
            firestore.collection("pings")
                .document(ping.id)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
        )
        val pingKey = ensureEncryptionKey(context, ping.id)
        runMessageEncryptionSelfTestIfNeeded(ping.id, pingKey)
        return snapshot.documents.mapNotNull {
            mapMessage(it, session.uid, pingKey)
        }
    }

    suspend fun fetchAvailableParticipants(context: Context): List<PingParticipant> {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before starting a Ping.")

        val spaces = getDocuments(
            firestore.collection("spaces").whereArrayContains("memberIds", session.uid)
        )

        val participants = linkedMapOf<String, PingParticipant>()
        for (space in spaces.documents) {
            val members = getDocuments(firestore.collection("spaces").document(space.id).collection("members"))
            for (member in members.documents) {
                val data = member.data ?: continue
                val userId = (data["userId"] as? String)?.trim().orEmpty().ifBlank { member.id }
                if (userId == session.uid) continue
                participants[userId] = PingParticipant(
                    id = userId,
                    displayName = (data["displayName"] as? String)?.trim().orEmpty().ifBlank { "Member" },
                    emojiAvatar = (data["emojiAvatar"] as? String)?.trim().orEmpty().ifBlank { "🙂" }
                )
            }
        }

        return participants.values.sortedBy { it.displayName.lowercase(Locale.US) }
    }

    suspend fun createOrOpenPing(context: Context, participant: PingParticipant): Ping {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before starting a Ping.")

        val existing = getDocuments(firestore.collection("pings").whereArrayContains("participantIds", session.uid))
            .documents
            .mapNotNull(::mapPing)
            .firstOrNull { it.participantIds.toSet() == setOf(session.uid, participant.id) }
        if (existing != null) return existing

        val currentProfile = userProfileService.fetchUserProfile(context, session.uid)
        val currentName = currentProfile?.displayName ?: session.displayName
        val currentEmoji = currentProfile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val ordered = listOf(
            Triple(session.uid, currentName, currentEmoji),
            Triple(participant.id, participant.displayName, participant.emojiAvatar)
        ).sortedBy { it.first }

        val reference = firestore.collection("pings").document()
        val now = Date()
        setData(
            reference,
            mapOf(
                "id" to reference.id,
                "participantIds" to ordered.map { it.first },
                "participantNames" to ordered.map { it.second },
                "participantEmojis" to ordered.map { it.third },
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "lastMessagePreviewType" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        setData(
            firestore.collection("pings").document(reference.id).collection("encryption").document("key"),
            mapOf(
                "keyVersion" to generalEncryptionVersion,
                "keyBase64" to encryptionService.generateSpaceKeyBase64(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "createdBy" to session.uid
            )
        )
        return Ping(
            id = reference.id,
            participantIds = ordered.map { it.first },
            participantNames = ordered.map { it.second },
            participantEmojis = ordered.map { it.third },
            lastMessageAt = now,
            lastMessagePreviewType = null,
            createdAt = now,
            updatedAt = now,
            unreadCount = 0
        )
    }

    suspend fun sendTextMessage(
        context: Context,
        ping: Ping,
        text: String,
        replyContext: MessageReplyContext? = null
    ): SpaceMessage {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before sending a Ping.")
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Enter a message before sending." }

        val pingKey = ensureEncryptionKey(context, ping.id)
        runMessageEncryptionSelfTestIfNeeded(ping.id, pingKey)
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val senderName = profile?.displayName ?: session.displayName
        val senderEmoji = profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val encryptedPayload = encryptionService.encryptText(trimmed, pingKey)
        val messageReference = firestore.collection("pings").document(ping.id).collection("messages").document()
        val payload = mutableMapOf<String, Any>(
            "id" to messageReference.id,
            "pingId" to ping.id,
            "senderId" to session.uid,
            "senderName" to senderName,
            "senderEmoji" to senderEmoji,
            "type" to "text",
            "encryptionVersion" to generalEncryptionVersion,
            "deleted" to false,
            "ciphertextBase64" to encryptedPayload.ciphertext,
            "nonceBase64" to encryptedPayload.nonce,
            "algorithm" to "AES.GCM.256",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "status" to "sent"
        )
        addReplyContext(replyContext, payload)
        setData(messageReference, payload)

        val recipientId = notificationRecipientId(ping, session.uid)
        if (recipientId != null) {
            recordPingNotification(
                firestore = firestore,
                recipientId = recipientId,
                actorId = session.uid,
                actorName = senderName,
                actorEmoji = senderEmoji,
                title = "$senderName sent you a Ping",
                pingId = ping.id
            )
        } else {
            android.util.Log.d(
                "PingService",
                "[Notifications] Unable to resolve ping recipient. pingId=${ping.id} actorId=${session.uid} participantIds=${ping.participantIds}"
            )
        }

        updateData(
            firestore.collection("pings").document(ping.id),
            mapOf(
                "lastMessageAt" to Timestamp.now(),
                "lastMessagePreviewType" to "text",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        return SpaceMessage(
            id = messageReference.id,
            senderId = session.uid,
            senderName = senderName,
            senderEmoji = senderEmoji,
            type = MessageType.Text,
            encryptionVersion = generalEncryptionVersion,
            deleted = false,
            text = trimmed,
            createdAt = Date(),
            updatedAt = Date(),
            timestamp = messageTimestampFormatter.format(Date()),
            isOutgoing = true,
            status = "sent",
            deliveryStatus = "Sent",
            replyContext = replyContext
        )
    }

    suspend fun sendImageMessage(
        context: Context,
        ping: Ping,
        imageBytes: ByteArray,
        caption: String?,
        mediaCategory: String = "gif",
        mimeType: String = "image/gif",
        replyContext: MessageReplyContext? = null
    ): SpaceMessage {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before sending a Ping.")
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val senderName = profile?.displayName ?: session.displayName
        val senderEmoji = profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val resolvedMediaType = mediaTypeFromCategory(mediaCategory)
        val resolvedMessageType = when (resolvedMediaType) {
            MediaType.Gif -> MessageType.Gif
            MediaType.Meme -> MessageType.Meme
            MediaType.Video -> MessageType.Video
            else -> MessageType.Image
        }
        val trimmedCaption = caption?.trim().orEmpty().ifBlank { null }
        val pingKey = ensureEncryptionKey(context, ping.id)
        runMessageEncryptionSelfTestIfNeeded(ping.id, pingKey)
        val encryptedCaption = trimmedCaption?.let { encryptionService.encryptText(it, pingKey) }
        val messageReference = firestore.collection("pings").document(ping.id).collection("messages").document()
        val mediaId = messageReference.id

        if (resolvedMediaType == MediaType.Gif) {
            Log.d("GifSend", "GIF selected scope=ping pingId=${ping.id} mediaId=$mediaId byteCount=${imageBytes.size} mimeType=$mimeType")
            Log.d("GifSend", "upload started scope=ping pingId=${ping.id} mediaId=$mediaId")
        }
        val uploadResult = try {
            when (resolvedMediaType) {
                MediaType.Gif,
                MediaType.Meme -> encryptedMediaService.uploadAnimatedImage(
                    context = context,
                    spaceId = "ping:${ping.id}",
                    mediaId = mediaId,
                    originalBytes = imageBytes,
                    mediaType = resolvedMediaType,
                    mimeType = mimeType,
                    uploadedBy = session.uid
                )
                else -> encryptedMediaService.uploadImage(
                    context = context,
                    spaceId = "ping:${ping.id}",
                    mediaId = mediaId,
                    originalBytes = imageBytes,
                    mediaType = resolvedMediaType,
                    mimeType = mimeType,
                    uploadedBy = session.uid
                )
            }
        } catch (error: Throwable) {
            if (resolvedMediaType == MediaType.Gif) {
                Log.e("GifSend", "upload failed scope=ping pingId=${ping.id} mediaId=$mediaId", error)
            }
            throw error
        }
        if (resolvedMediaType == MediaType.Gif) {
            Log.d("GifSend", "upload succeeded scope=ping pingId=${ping.id} mediaId=$mediaId storagePath=${uploadResult.metadata.storagePath} thumbnailStoragePath=${uploadResult.metadata.thumbnailStoragePath}")
        }
        val metadata = uploadResult.metadata

        val payload = mutableMapOf<String, Any>(
            "id" to mediaId,
            "mediaId" to metadata.mediaId,
            "pingId" to ping.id,
            "senderId" to session.uid,
            "senderName" to senderName,
            "senderEmoji" to senderEmoji,
            "type" to resolvedMessageType.name.lowercase(Locale.US),
            "mediaCategory" to mediaCategory,
            "mediaType" to metadata.mediaType.name.replaceFirstChar { it.lowercase() },
            "storagePath" to metadata.storagePath,
            "mediaStoragePath" to metadata.storagePath,
            "nonce" to metadata.nonce,
            "mediaNonceBase64" to metadata.nonce,
            "encryptionVersion" to metadata.encryptionVersion,
            "mimeType" to metadata.mimeType,
            "fileSize" to metadata.fileSize,
            "uploadedBy" to metadata.uploadedBy,
            "deleted" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "status" to "sent"
        )
        metadata.thumbnailStoragePath?.let { payload["thumbnailStoragePath"] = it }
        metadata.thumbnailNonce?.let {
            payload["thumbnailNonce"] = it
            payload["thumbnailNonceBase64"] = it
        }
        metadata.width?.let { payload["width"] = it }
        metadata.height?.let { payload["height"] = it }
        encryptedCaption?.let {
            payload["captionCiphertextBase64"] = it.ciphertext
            payload["captionNonceBase64"] = it.nonce
        }
        addReplyContext(replyContext, payload)
        if (resolvedMediaType == MediaType.Gif) {
            Log.d("GifSend", "Firestore write started scope=ping pingId=${ping.id} mediaId=$mediaId")
        }
        try {
            setData(messageReference, payload)
            if (resolvedMediaType == MediaType.Gif) {
                Log.d("GifSend", "Firestore write succeeded scope=ping pingId=${ping.id} mediaId=$mediaId")
            }
        } catch (error: Throwable) {
            if (resolvedMediaType == MediaType.Gif) {
                Log.e("GifSend", "Firestore write failed scope=ping pingId=${ping.id} mediaId=$mediaId", error)
            }
            throw error
        }

        val recipientId = notificationRecipientId(ping, session.uid)
        if (recipientId != null) {
            recordPingNotification(
                firestore = firestore,
                recipientId = recipientId,
                actorId = session.uid,
                actorName = senderName,
                actorEmoji = senderEmoji,
                title = "$senderName sent you a Ping",
                pingId = ping.id
            )
        }

        updateData(
            firestore.collection("pings").document(ping.id),
            mapOf(
                "lastMessageAt" to Timestamp.now(),
                "lastMessagePreviewType" to resolvedMessageType.name.lowercase(Locale.US),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        return SpaceMessage(
            id = mediaId,
            spaceId = "ping:${ping.id}",
            senderId = session.uid,
            senderName = senderName,
            senderEmoji = senderEmoji,
            type = resolvedMessageType,
            encryptionVersion = metadata.encryptionVersion,
            deleted = false,
            text = null,
            media = SpaceMedia(
                id = mediaId,
                spaceId = "ping:${ping.id}",
                type = resolvedMessageType,
                mediaCategory = mediaCategory,
                mediaType = metadata.mediaType,
                placeholderIconName = metadata.mediaType.placeholderIconName,
                caption = trimmedCaption,
                senderName = senderName,
                timestamp = messageTimestampFormatter.format(Date()),
                mediaStoragePath = metadata.storagePath,
                thumbnailStoragePath = metadata.thumbnailStoragePath,
                mediaNonceBase64 = metadata.nonce,
                thumbnailNonceBase64 = metadata.thumbnailNonce,
                metadata = metadata
            ),
            createdAt = Date(),
            updatedAt = Date(),
            timestamp = messageTimestampFormatter.format(Date()),
            isOutgoing = true,
            status = "sent",
            deliveryStatus = "Sent",
            replyContext = replyContext
        )
    }

    suspend fun editTextMessage(
        context: Context,
        ping: Ping,
        messageId: String,
        newText: String
    ): SpaceMessage {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before editing a Ping.")
        val trimmed = newText.trim()
        require(trimmed.isNotEmpty()) { "Enter a message before saving." }
        val reference = firestore.collection("pings").document(ping.id).collection("messages").document(messageId)
        val snapshot = getDocument(reference)
        val data = snapshot.data ?: throw IllegalStateException("Message not found.")
        require(data["senderId"] == session.uid) { "You can only edit your own messages." }

        val pingKey = ensureEncryptionKey(context, ping.id)
        val encryptedPayload = encryptionService.encryptText(trimmed, pingKey)
        updateData(
            reference,
            mapOf(
                "ciphertextBase64" to encryptedPayload.ciphertext,
                "nonceBase64" to encryptedPayload.nonce,
                "edited" to true,
                "editedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        return SpaceMessage(
            id = data["id"] as? String ?: messageId,
            senderId = data["senderId"] as? String,
            senderName = data["senderName"] as? String ?: session.displayName,
            senderEmoji = data["senderEmoji"] as? String,
            type = MessageType.Text,
            encryptionVersion = data["encryptionVersion"] as? String ?: generalEncryptionVersion,
            deleted = false,
            text = trimmed,
            createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
            updatedAt = Date(),
            timestamp = messageTimestampFormatter.format((data["createdAt"] as? Timestamp)?.toDate() ?: Date()),
            isOutgoing = true,
            status = data["status"] as? String,
            deliveryStatus = "Sent",
            isEdited = true,
            editedAt = Date(),
            replyContext = mappedReplyContext(data)
        )
    }

    suspend fun deleteMessage(context: Context, ping: Ping, messageId: String) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before deleting a Ping.")
        val reference = firestore.collection("pings").document(ping.id).collection("messages").document(messageId)
        val snapshot = getDocument(reference)
        require(snapshot.data?.get("senderId") == session.uid) { "You can only delete your own messages." }
        updateData(
            reference,
            mapOf(
                "deleted" to true,
                "deletedAt" to FieldValue.serverTimestamp(),
                "deletedBy" to session.uid,
                "text" to "",
                "ciphertextBase64" to "",
                "nonceBase64" to "",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    fun listenToReactions(
        context: Context,
        ping: Ping,
        messageId: String,
        listenerKey: String? = null,
        onUpdate: (Result<List<MessageReaction>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.failure(IllegalStateException("Firestore is not configured yet.")))
            return null
        }
        val currentUserId = currentUserId(context)
        return registerListener(
            listenerKey,
            firestore.collection("pings").document(ping.id).collection("messages").document(messageId).collection("reactions")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }
                val documents = snapshot?.documents.orEmpty()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    onUpdate(Result.success(mapReactions(context, ping, documents, currentUserId)))
                }
            }
        )
    }

    suspend fun toggleReaction(context: Context, ping: Ping, messageId: String, emoji: String) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before reacting.")
        val reference = firestore.collection("pings").document(ping.id).collection("messages").document(messageId).collection("reactions").document(session.uid)
        val snapshot = getDocument(reference)
        val existingEmoji = (snapshot.data?.get("emoji") as? String)?.trim()?.ifEmpty { null }
        if (existingEmoji == emoji) {
            deleteDocument(reference)
        } else {
            val profile = userProfileService.fetchUserProfile(context, session.uid)
            setData(
                reference,
                mapOf(
                    "emoji" to emoji,
                    "userId" to session.uid,
                    "userName" to (profile?.displayName ?: session.displayName),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }
    }

    private suspend fun recordPingNotification(
        firestore: FirebaseFirestore,
        recipientId: String,
        actorId: String,
        actorName: String,
        actorEmoji: String?,
        title: String,
        pingId: String
    ) {
        if (recipientId == actorId) return
        repeat(2) { index ->
            val attempt = index + 1
            val reference = firestore.collection("notifications").document()
            val payload = mutableMapOf<String, Any>(
                "id" to reference.id,
                "recipientId" to recipientId,
                "actorId" to actorId,
                "actorName" to actorName,
                "spaceId" to "",
                "spaceName" to "Ping",
                "spaceEmoji" to "💬",
                "type" to "pingMessage",
                "title" to title,
                "subtitle" to "New encrypted message",
                "targetId" to pingId,
                "targetType" to "ping",
                "createdAt" to FieldValue.serverTimestamp(),
                "read" to false,
                "delivered" to false
            )
            if (!actorEmoji.isNullOrBlank()) payload["actorEmoji"] = actorEmoji

            runCatching { setData(reference, payload) }
                .onSuccess { return }
                .onFailure {
                    android.util.Log.d(
                        "PingService",
                        "[Notifications] Failed to record ping notification. attempt=$attempt pingId=$pingId recipientId=$recipientId actorId=$actorId error=${it.localizedMessage}"
                    )
                    if (attempt == 1) {
                        kotlinx.coroutines.delay(250)
                    }
                }
        }
    }

    private fun notificationRecipientId(ping: Ping, currentUserId: String): String? {
        ping.otherParticipant(currentUserId)?.id
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return ping.participantIds
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != currentUserId }
    }

    private fun mapPing(document: DocumentSnapshot): Ping? {
        val data = document.data ?: return null
        val participantIds = (data["participantIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val participantNames = (data["participantNames"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val participantEmojis = (data["participantEmojis"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        if (participantIds.size != 2) return null
        return Ping(
            id = data["id"] as? String ?: document.id,
            participantIds = participantIds,
            participantNames = participantNames,
            participantEmojis = participantEmojis,
            lastMessageAt = (data["lastMessageAt"] as? Timestamp)?.toDate(),
            lastMessagePreviewType = (data["lastMessagePreviewType"] as? String)?.trim()?.ifEmpty { null },
            createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
            unreadCount = 0
        )
    }

    private fun mapMessage(snapshot: DocumentSnapshot, currentUserId: String?, pingKey: ByteArray): SpaceMessage? {
        val data = snapshot.data ?: return null
        val isGifMessage = isGifPayload(data)
        val type = when ((data["type"] as? String)?.trim()?.lowercase(Locale.US)) {
            "text" -> MessageType.Text
            "image" -> MessageType.Image
            "video" -> MessageType.Video
            "meme" -> MessageType.Meme
            "gif" -> MessageType.Gif
            "screenshot" -> MessageType.Screenshot
            "file" -> MessageType.File
            else -> MessageType.Text
        }
        val createdAt = (data["createdAt"] as? Timestamp)?.toDate()
        val updatedAt = (data["updatedAt"] as? Timestamp)?.toDate()
        val senderId = data["senderId"] as? String
        val isOutgoing = senderId == currentUserId
        val deleted = data["deleted"] as? Boolean ?: false
        val encryptionVersion = (data["encryptionVersion"] as? String)?.trim()?.ifEmpty { null } ?: "none"
        val replyContext = mappedReplyContext(data)
        if (deleted) {
            if (isGifMessage) {
                Log.d(
                    receiveLogTag,
                    "[Mapping] messageId=${snapshot.id} mappedAsDeleted=true type=${type.name} mediaCategory=${data["mediaCategory"]} mediaType=${data["mediaType"]}"
                )
            }
            return SpaceMessage(
                id = data["id"] as? String ?: snapshot.id,
                senderId = senderId,
                senderName = data["senderName"] as? String ?: "User",
                senderEmoji = (data["senderEmoji"] as? String)?.trim()?.ifEmpty { null },
                type = type,
                encryptionVersion = encryptionVersion,
                deleted = true,
                text = null,
                createdAt = createdAt,
                updatedAt = updatedAt,
                timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                isOutgoing = isOutgoing,
                status = data["status"] as? String,
                deliveryStatus = if (isOutgoing) "Sent" else null,
                isEdited = data["edited"] as? Boolean ?: false,
                editedAt = (data["editedAt"] as? Timestamp)?.toDate(),
                replyContext = replyContext
            )
        }
        if (
            type == MessageType.Image ||
            type == MessageType.Video ||
            type == MessageType.Meme ||
            type == MessageType.Gif ||
            type == MessageType.Screenshot
        ) {
            val captionCiphertext = (data["captionCiphertextBase64"] as? String)?.trim()?.ifEmpty { null }
            val captionNonce = (data["captionNonceBase64"] as? String)?.trim()?.ifEmpty { null }
            val caption = if (captionCiphertext != null && captionNonce != null) {
                try {
                    encryptionService.decryptText(captionCiphertext, captionNonce, null, pingKey)
                } catch (error: Throwable) {
                    if (isGifMessage) {
                        Log.e(receiveLogTag, "model mapping failed messageId=${snapshot.id} stage=captionDecrypt", error)
                    }
                    null
                }
            } else {
                null
            }
            val mediaCategory = (data["mediaCategory"] as? String)?.trim()?.ifEmpty { null }
            val resolvedMediaType = data["mediaType"]?.toString()?.let(::mediaTypeFromCategory)
                ?: mediaTypeFromCategory(mediaCategory)
            val pingIdentifier = (data["pingId"] as? String)?.trim()?.ifEmpty { null } ?: snapshot.reference.parent.parent?.id
            val mediaScopeId = pingIdentifier?.let { "ping:$it" }
            val metadata = EncryptedMediaMetadata(
                mediaId = data["mediaId"] as? String ?: (data["id"] as? String ?: snapshot.id),
                mediaType = resolvedMediaType,
                storagePath = data["storagePath"] as? String ?: data["mediaStoragePath"] as? String ?: "",
                thumbnailStoragePath = (data["thumbnailStoragePath"] as? String)?.trim()?.ifEmpty { null },
                encryptionVersion = encryptionVersion,
                nonce = data["nonce"] as? String ?: data["mediaNonceBase64"] as? String ?: "",
                thumbnailNonce = (data["thumbnailNonce"] as? String)?.trim()?.ifEmpty { null }
                    ?: (data["thumbnailNonceBase64"] as? String)?.trim()?.ifEmpty { null },
                mimeType = data["mimeType"] as? String ?: "image/jpeg",
                fileSize = (data["fileSize"] as? Number)?.toLong() ?: 0L,
                width = (data["width"] as? Number)?.toInt(),
                height = (data["height"] as? Number)?.toInt(),
                duration = (data["duration"] as? Number)?.toDouble(),
                createdAt = createdAt,
                uploadedBy = data["uploadedBy"] as? String ?: (senderId ?: "")
            )
            val mediaItems = mappedMediaItems(
                data = data,
                documentId = snapshot.id,
                mediaScopeId = mediaScopeId,
                senderId = senderId,
                senderName = data["senderName"] as? String ?: "User",
                caption = caption,
                createdAt = createdAt,
                fallbackType = type
            )
            val primaryMedia = mediaItems.firstOrNull() ?: SpaceMedia(
                id = data["id"] as? String ?: snapshot.id,
                spaceId = mediaScopeId,
                type = type,
                mediaCategory = mediaCategory,
                mediaType = resolvedMediaType,
                placeholderIconName = resolvedMediaType.placeholderIconName,
                caption = caption,
                senderName = data["senderName"] as? String ?: "User",
                timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                mediaStoragePath = metadata.storagePath,
                thumbnailStoragePath = metadata.thumbnailStoragePath,
                mediaNonceBase64 = metadata.nonce,
                thumbnailNonceBase64 = metadata.thumbnailNonce,
                metadata = metadata
            )

            if (isGifMessage) {
                Log.d(
                    receiveLogTag,
                    "model mapping succeeded messageId=${snapshot.id} type=${type.name} mediaCategory=$mediaCategory mediaType=${resolvedMediaType.name} storagePath=${metadata.storagePath} mediaStoragePath=${data["mediaStoragePath"]} nonce=${data["nonce"]} mediaNonceBase64=${data["mediaNonceBase64"]} thumbnailStoragePath=${metadata.thumbnailStoragePath} thumbnailNonce=${data["thumbnailNonce"]} thumbnailNonceBase64=${data["thumbnailNonceBase64"]} mimeType=${metadata.mimeType} encryptionVersion=${metadata.encryptionVersion} scope=$mediaScopeId"
                )
            }

            return SpaceMessage(
                id = data["id"] as? String ?: snapshot.id,
                spaceId = mediaScopeId,
                senderId = senderId,
                senderName = data["senderName"] as? String ?: "User",
                senderEmoji = (data["senderEmoji"] as? String)?.trim()?.ifEmpty { null },
                type = type,
                encryptionVersion = encryptionVersion,
                deleted = false,
                text = null,
                media = primaryMedia,
                mediaItems = mediaItems,
                createdAt = createdAt,
                updatedAt = updatedAt,
                timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                isOutgoing = isOutgoing,
                status = data["status"] as? String,
                deliveryStatus = if (isOutgoing) "Sent" else null,
                isEdited = data["edited"] as? Boolean ?: false,
                editedAt = (data["editedAt"] as? Timestamp)?.toDate(),
                replyContext = replyContext
            )
        }
        if (isGifMessage) {
            Log.d(
                receiveLogTag,
                "model mapping failed messageId=${snapshot.id} reason=resolved_to_non_media_type type=${type.name} mediaCategory=${data["mediaCategory"]} mediaType=${data["mediaType"]}"
            )
        }
        val resolvedText = when (encryptionVersion) {
            generalEncryptionVersion -> {
                val ciphertext = (data["ciphertextBase64"] as? String)?.trim()?.ifEmpty { null }
                val nonce = (data["nonceBase64"] as? String)?.trim()?.ifEmpty { null }
                if (ciphertext == null || nonce == null) {
                    if (isGifMessage) {
                        Log.d(receiveLogTag, "[Mapping] messageId=${snapshot.id} text decrypt skipped reason=missing ciphertext_or_nonce")
                    }
                    "Unable to decrypt message"
                } else {
                    try {
                        encryptionService.decryptText(ciphertext, nonce, null, pingKey)
                    } catch (error: Throwable) {
                        if (isGifMessage) {
                            Log.e(receiveLogTag, "[Mapping] messageId=${snapshot.id} text decrypt failed", error)
                        }
                        "Unable to decrypt message"
                    }
                }
            }
            else -> {
                if (isGifMessage) {
                    Log.d(receiveLogTag, "[Mapping] messageId=${snapshot.id} dropped=true reason=unsupported_encryption_version version=$encryptionVersion")
                }
                return null
            }
        }
        if (isGifMessage) {
            Log.d(receiveLogTag, "[Mapping] messageId=${snapshot.id} mappedAsText=true type=${type.name}")
        }
        return SpaceMessage(
            id = data["id"] as? String ?: snapshot.id,
            senderId = senderId,
            senderName = data["senderName"] as? String ?: "User",
            senderEmoji = (data["senderEmoji"] as? String)?.trim()?.ifEmpty { null },
            type = type,
            encryptionVersion = encryptionVersion,
            deleted = false,
            text = resolvedText,
            createdAt = createdAt,
            updatedAt = updatedAt,
            timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
            isOutgoing = isOutgoing,
            status = data["status"] as? String,
            deliveryStatus = if (isOutgoing) "Sent" else null,
            isEdited = data["edited"] as? Boolean ?: false,
            editedAt = (data["editedAt"] as? Timestamp)?.toDate(),
            replyContext = replyContext
        )
    }

    private fun mappedMediaItems(
        data: Map<String, Any>,
        documentId: String,
        mediaScopeId: String?,
        senderId: String?,
        senderName: String,
        caption: String?,
        createdAt: Date?,
        fallbackType: MessageType
    ): List<SpaceMedia> {
        val sortedItems = ((data["mediaItems"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?.map { item -> item.entries.associate { (key, value) -> key.toString() to value } }
            ?.sortedBy { (it["order"] as? Number)?.toInt() ?: 0 })
            .orEmpty()
        val itemDictionaries = if (sortedItems.isEmpty()) listOf(data) else sortedItems

        return itemDictionaries.mapNotNull { item ->
            val mediaCategory = item["mediaCategory"]?.toString()?.trim()?.ifEmpty { null }
            val resolvedMediaType = item["mediaType"]?.toString()?.let(::mediaTypeFromCategory)
                ?: mediaTypeFromCategory(mediaCategory)
            val metadata = EncryptedMediaMetadata(
                mediaId = item["mediaId"]?.toString() ?: item["id"]?.toString() ?: documentId,
                mediaType = resolvedMediaType,
                storagePath = item["storagePath"]?.toString() ?: item["mediaStoragePath"]?.toString() ?: "",
                thumbnailStoragePath = item["thumbnailStoragePath"]?.toString()?.trim()?.ifEmpty { null },
                encryptionVersion = item["encryptionVersion"]?.toString()?.trim()?.ifEmpty { null } ?: "aes-gcm-v1",
                nonce = item["nonce"]?.toString() ?: item["mediaNonceBase64"]?.toString() ?: "",
                thumbnailNonce = item["thumbnailNonce"]?.toString()?.trim()?.ifEmpty { null }
                    ?: item["thumbnailNonceBase64"]?.toString()?.trim()?.ifEmpty { null },
                mimeType = item["mimeType"]?.toString() ?: "image/jpeg",
                fileSize = (item["fileSize"] as? Number)?.toLong() ?: 0L,
                width = (item["width"] as? Number)?.toInt(),
                height = (item["height"] as? Number)?.toInt(),
                duration = (item["duration"] as? Number)?.toDouble(),
                createdAt = createdAt,
                uploadedBy = item["uploadedBy"]?.toString() ?: (senderId ?: "")
            )

            if (metadata.storagePath.isBlank() || metadata.nonce.isBlank()) {
                null
            } else {
                val resolvedType = when (resolvedMediaType) {
                    MediaType.Video -> MessageType.Video
                    MediaType.Gif -> MessageType.Gif
                    MediaType.Meme -> MessageType.Meme
                    else -> if (fallbackType == MessageType.Video) MessageType.Video else MessageType.Image
                }
                SpaceMedia(
                    id = item["id"]?.toString() ?: metadata.mediaId,
                    spaceId = mediaScopeId,
                    type = resolvedType,
                    mediaCategory = mediaCategory,
                    mediaType = resolvedMediaType,
                    placeholderIconName = resolvedMediaType.placeholderIconName,
                    caption = caption,
                    senderName = senderName,
                    timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                    mediaStoragePath = metadata.storagePath,
                    thumbnailStoragePath = metadata.thumbnailStoragePath,
                    mediaNonceBase64 = metadata.nonce,
                    thumbnailNonceBase64 = metadata.thumbnailNonce,
                    metadata = metadata
                )
            }
        }
    }

    private fun mappedReplyContext(data: Map<String, Any>): MessageReplyContext? {
        val messageId = (data["replyToMessageId"] as? String)?.trim().orEmpty().ifBlank { return null }
        val senderName = (data["replyToSenderName"] as? String)?.trim().orEmpty().ifBlank { return null }
        val type = (data["replyToType"] as? String)?.trim().orEmpty().ifBlank { return null }
        val preview = (data["replyPreview"] as? String)?.trim().orEmpty().ifBlank { return null }
        return MessageReplyContext(messageId = messageId, senderName = senderName, type = type, preview = preview)
    }

    private fun addReplyContext(replyContext: MessageReplyContext?, payload: MutableMap<String, Any>) {
        if (replyContext == null) return
        payload["replyToMessageId"] = replyContext.messageId
        payload["replyToSenderName"] = replyContext.senderName
        payload["replyToType"] = replyContext.type
        payload["replyPreview"] = replyContext.preview
    }

    private suspend fun ensureEncryptionKey(context: Context, pingId: String): ByteArray {
        encryptionService.cachedSpaceKey("ping:$pingId")?.let { return it }
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before loading messages.")
        val reference = firestore.collection("pings").document(pingId).collection("encryption").document("key")
        val snapshot = runCatching { getDocument(reference) }.getOrNull()
        val existing = snapshot?.data?.get("keyBase64") as? String
        if (!existing.isNullOrBlank()) {
            return encryptionService.decodeSpaceKey(existing).also {
                encryptionService.cacheSpaceKey("ping:$pingId", it)
            }
        }
        val generated = encryptionService.generateSpaceKeyBase64()
        setData(
            reference,
            mapOf(
                "keyVersion" to generalEncryptionVersion,
                "keyBase64" to generated,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "createdBy" to session.uid
            )
        )
        return encryptionService.decodeSpaceKey(generated).also {
            encryptionService.cacheSpaceKey("ping:$pingId", it)
        }
    }

    private fun runMessageEncryptionSelfTestIfNeeded(pingId: String, key: ByteArray) {
        if (verifiedPingEncryptionIds.contains(pingId)) return
        val plaintext = "hello encryption test"
        val encrypted = encryptionService.encryptText(plaintext, key)
        val decrypted = encryptionService.decryptText(encrypted.ciphertext, encrypted.nonce, null, key)
        check(decrypted == plaintext) { "Local encryption self-test failed." }
        verifiedPingEncryptionIds.add(pingId)
    }

    private fun logGifSnapshot(snapshot: DocumentSnapshot) {
        val data = snapshot.data ?: return
        if (!isGifPayload(data)) return
        Log.d(
            receiveLogTag,
            "Firestore document received messageId=${snapshot.id} type=${data["type"]} mediaCategory=${data["mediaCategory"]} mediaType=${data["mediaType"]} storagePath=${data["storagePath"]} mediaStoragePath=${data["mediaStoragePath"]} nonce=${data["nonce"]} mediaNonceBase64=${data["mediaNonceBase64"]} thumbnailStoragePath=${data["thumbnailStoragePath"]} thumbnailNonce=${data["thumbnailNonce"]} thumbnailNonceBase64=${data["thumbnailNonceBase64"]} mimeType=${data["mimeType"]} encryptionVersion=${data["encryptionVersion"]}"
        )
    }

    private fun isGifPayload(data: Map<String, Any>): Boolean {
        val type = data["type"]?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        val mediaCategory = data["mediaCategory"]?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        val mediaType = data["mediaType"]?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        val mimeType = data["mimeType"]?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        return type == "gif" || mediaCategory == "gif" || mediaType == "gif" || mimeType == "image/gif"
    }

    private fun mediaTypeFromCategory(category: String?): MediaType {
        return when (category?.trim()?.lowercase(Locale.US).orEmpty()) {
            "meme" -> MediaType.Meme
            "gif" -> MediaType.Gif
            "video" -> MediaType.Video
            "file" -> MediaType.File
            "voice" -> MediaType.Voice
            "profilephoto" -> MediaType.ProfilePhoto
            "coverphoto" -> MediaType.CoverPhoto
            else -> MediaType.Photo
        }
    }

    private suspend fun mapReactions(
        context: Context,
        ping: Ping,
        documents: List<DocumentSnapshot>,
        currentUserId: String?
    ): List<MessageReaction> {
        val counts = linkedMapOf<String, Int>()
        val namesByEmoji = linkedMapOf<String, MutableList<String>>()
        val reactionRecords = mutableListOf<Triple<String, String, String?>>()
        var selected: String? = null
        val order = listOf("👍", "❤️", "😂", "😮", "😢", "👎")
        for (document in documents) {
            val data = document.data ?: continue
            val emoji = (data["emoji"] as? String)?.trim().orEmpty().ifBlank { continue }
            counts[emoji] = (counts[emoji] ?: 0) + 1
            val userId = (data["userId"] as? String)?.trim().orEmpty().ifBlank { document.id }
            val userName = (data["userName"] as? String)?.trim()?.ifEmpty { null }
            reactionRecords += Triple(emoji, userId, userName)
            if (userId == currentUserId) selected = emoji
        }

        val fallbackNames = ping.participantIds.mapIndexedNotNull { index, userId ->
            ping.participantNames.getOrNull(index)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { userId to it }
        }.toMap().toMutableMap()
        val missingUserIds = reactionRecords
            .filter { it.third == null && it.second !in fallbackNames }
            .map { it.second }
            .distinct()
        missingUserIds.forEach { userId ->
            runCatching { userProfileService.fetchUserProfile(context, userId)?.displayName }
                .getOrNull()
                ?.let { fallbackNames[userId] = it }
            }
        reactionRecords.forEach { (emoji, userId, storedName) ->
            val userName = storedName ?: fallbackNames[userId]
            if (!userName.isNullOrBlank()) {
                val names = namesByEmoji.getOrPut(emoji) { mutableListOf() }
                if (userName !in names) names += userName
            }
        }

        return counts.map { (emoji, count) ->
            MessageReaction(
                emoji = emoji,
                count = count,
                isSelectedByCurrentUser = emoji == selected,
                userNames = namesByEmoji[emoji].orEmpty()
            )
        }.sortedBy { order.indexOf(it.emoji).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
    }

    private fun firestoreOrNull(context: Context): FirebaseFirestore? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    private suspend fun getDocument(reference: DocumentReference): DocumentSnapshot {
        return suspendCancellableCoroutine { continuation ->
            reference.get()
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    private suspend fun getDocuments(query: Query): QuerySnapshot {
        return suspendCancellableCoroutine { continuation ->
            query.get()
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    private suspend fun setData(reference: DocumentReference, data: Map<String, Any>) {
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.set(data)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    private suspend fun updateData(reference: DocumentReference, data: Map<String, Any>) {
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.update(data)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    private suspend fun deleteDocument(reference: DocumentReference) {
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.delete()
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    private val messageTimestampFormatter: SimpleDateFormat
        get() = SimpleDateFormat("h:mm a", Locale.US)
}

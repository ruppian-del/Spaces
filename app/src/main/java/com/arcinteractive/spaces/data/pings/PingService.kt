package com.arcinteractive.spaces.data.pings

import android.content.Context
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.firestore.FirestoreListenerRegistry
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Ping
import com.arcinteractive.spaces.data.model.PingParticipant
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
    private val encryptionService: EncryptionService = EncryptionService()
) {
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
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    runCatching {
                        val pingKey = ensureEncryptionKey(context, ping.id)
                        runMessageEncryptionSelfTestIfNeeded(ping.id, pingKey)
                        snapshot?.documents.orEmpty().mapNotNull {
                            mapMessage(it, session.uid, pingKey)
                        }
                    }.onSuccess {
                        onUpdate(Result.success(it))
                    }.onFailure {
                        onUpdate(Result.failure(it))
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
                onUpdate(Result.success(mapReactions(snapshot?.documents.orEmpty(), currentUserId)))
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
            setData(reference, mapOf("emoji" to emoji, "userId" to session.uid, "createdAt" to FieldValue.serverTimestamp()))
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
        val type = when ((data["type"] as? String)?.trim()?.lowercase(Locale.US)) {
            "text" -> MessageType.Text
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
        val resolvedText = when (encryptionVersion) {
            generalEncryptionVersion -> {
                val ciphertext = (data["ciphertextBase64"] as? String)?.trim()?.ifEmpty { null }
                val nonce = (data["nonceBase64"] as? String)?.trim()?.ifEmpty { null }
                if (ciphertext == null || nonce == null) {
                    "Unable to decrypt message"
                } else {
                    runCatching { encryptionService.decryptText(ciphertext, nonce, null, pingKey) }
                        .getOrElse { "Unable to decrypt message" }
                }
            }
            else -> return null
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

    private fun mapReactions(documents: List<DocumentSnapshot>, currentUserId: String?): List<MessageReaction> {
        val counts = linkedMapOf<String, Int>()
        var selected: String? = null
        val order = listOf("👍", "❤️", "😂", "😮", "😢", "👎")
        for (document in documents) {
            val data = document.data ?: continue
            val emoji = (data["emoji"] as? String)?.trim().orEmpty().ifBlank { continue }
            counts[emoji] = (counts[emoji] ?: 0) + 1
            val userId = (data["userId"] as? String)?.trim().orEmpty().ifBlank { document.id }
            if (userId == currentUserId) selected = emoji
        }
        return counts.map { (emoji, count) ->
            MessageReaction(emoji = emoji, count = count, isSelectedByCurrentUser = emoji == selected)
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

package com.arcinteractive.spaces.data.spaces

import android.content.Context
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.firestore.FirestoreListenerRegistry
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.media.EncryptedMediaService
import com.arcinteractive.spaces.data.model.EncryptedMediaMetadata
import com.arcinteractive.spaces.data.model.ActivityItem
import com.arcinteractive.spaces.data.model.ActivityTargetType
import com.arcinteractive.spaces.data.model.ActivityType
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.MediaType
import com.arcinteractive.spaces.data.mock.MockMembersRepository
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceFileItem
import com.arcinteractive.spaces.data.model.SpaceFolder
import com.arcinteractive.spaces.data.model.SpaceInvite
import com.arcinteractive.spaces.data.model.SpaceEvent
import com.arcinteractive.spaces.data.model.SpaceMember
import com.arcinteractive.spaces.data.model.SpaceMemberRole
import com.arcinteractive.spaces.data.model.SpacePermission
import com.arcinteractive.spaces.data.model.canChangeRole
import com.arcinteractive.spaces.data.model.canRemove
import com.arcinteractive.spaces.data.model.capabilities
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.data.model.SpaceModule
import com.arcinteractive.spaces.data.model.SpaceModules
import com.arcinteractive.spaces.data.model.SpacePoll
import com.arcinteractive.spaces.data.model.SpacePollOption
import com.arcinteractive.spaces.data.model.SpacePollVote
import com.arcinteractive.spaces.data.model.SpaceTemplate
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.webkit.MimeTypeMap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SECURE_ACCESS_NOT_SET_UP_MESSAGE = "Secure access not set up on this device"
private val DEFAULT_REACTION_ORDER = listOf("👍", "❤️", "😂", "😮", "😢", "👎")

private fun String.toActivityTypeOrNull(): ActivityType? = when (trim()) {
    "spaceCreated" -> ActivityType.SpaceCreated
    "memberJoined" -> ActivityType.MemberJoined
    "messageSent" -> ActivityType.MessageSent
    "photoShared" -> ActivityType.PhotoShared
    "videoShared" -> ActivityType.VideoShared
    "fileUploaded" -> ActivityType.FileUploaded
    "pollCreated" -> ActivityType.PollCreated
    "pollVoted" -> ActivityType.PollVoted
    "eventCreated" -> ActivityType.EventCreated
    "eventUpdated" -> ActivityType.EventUpdated
    "reactionAdded" -> ActivityType.ReactionAdded
    "replyAdded" -> ActivityType.ReplyAdded
    else -> null
}

private fun String.toActivityTargetTypeOrNull(): ActivityTargetType? = when (trim()) {
    "space" -> ActivityTargetType.Space
    "general" -> ActivityTargetType.General
    "photos" -> ActivityTargetType.Photos
    "files" -> ActivityTargetType.Files
    "polls" -> ActivityTargetType.Polls
    "events" -> ActivityTargetType.Events
    "members" -> ActivityTargetType.Members
    else -> null
}

class SpaceService(
    private val authService: AuthService = AuthService(),
    private val encryptionService: EncryptionService = EncryptionService(),
    private val userProfileService: UserProfileService = UserProfileService(encryptionService),
    private val encryptedMediaService: EncryptedMediaService = EncryptedMediaService(authService, encryptionService)
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verifiedMessageEncryptionSpaceIds = mutableSetOf<String>()
    private val generalEncryptionVersion = "aes-gcm-v1"

    private fun registerListener(
        listenerKey: String?,
        registration: ListenerRegistration?
    ): ListenerRegistration? {
        val key = listenerKey ?: "spaces.${System.identityHashCode(registration)}"
        return FirestoreListenerRegistry.register(key, registration)
    }

    fun listenToSpacesForCurrentUser(
        context: Context,
        listenerKey: String? = null,
        onUpdate: (Result<List<Space>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }
        val session = authService.currentSession(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .whereArrayContains("memberIds", session.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val spaces = snapshot?.documents.orEmpty()
                    .mapNotNull(::mapSpace)
                    .sortedBy { it.name.lowercase() }
                onUpdate(Result.success(spaces))
            }
        )
    }

    fun listenToMembers(
        context: Context,
        space: Space,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpaceMember>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(MockMembersRepository.membersFor(space)))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("members")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val members = snapshot?.documents.orEmpty()
                    .mapNotNull(::mapMember)
                    .sortedWith(compareBy<SpaceMember> { it.role.sortOrder }.thenBy { it.displayName.lowercase() })
                onUpdate(Result.success(members))
            }
        )
    }

    fun listenToFolders(
        context: Context,
        space: Space,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpaceFolder>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("fileFolders")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val folders = snapshot?.documents.orEmpty().mapNotNull(::mapFolder)
                onUpdate(Result.success(folders))
            }
        )
    }

    fun listenToFiles(
        context: Context,
        space: Space,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpaceFileItem>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("files")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val files = snapshot?.documents.orEmpty().mapNotNull(::mapFile).filterNot { it.deleted }
                onUpdate(Result.success(files))
            }
        )
    }

    fun listenToPolls(
        context: Context,
        space: Space,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpacePoll>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val polls = snapshot?.documents.orEmpty().mapNotNull(::mapPoll).filterNot { it.deleted }
                onUpdate(Result.success(polls))
            }
        )
    }

    fun listenToPollVotes(
        context: Context,
        space: Space,
        pollId: String,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpacePollVote>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .document(pollId)
            .collection("votes")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val votes = snapshot?.documents.orEmpty().mapNotNull(::mapPollVote)
                onUpdate(Result.success(votes))
            }
        )
    }

    fun listenToEvents(
        context: Context,
        space: Space,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpaceEvent>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("events")
            .orderBy("startDate", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val events = snapshot?.documents.orEmpty().mapNotNull(::mapEvent).filterNot { it.deleted }
                onUpdate(Result.success(events))
            }
        )
    }

    fun listenToActivity(
        context: Context,
        spaceIds: List<String>,
        listenerKey: String? = null,
        onUpdate: (Result<List<ActivityItem>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        val uniqueSpaceIds = spaceIds.distinct().sorted()
        if (uniqueSpaceIds.isEmpty()) {
            onUpdate(Result.success(emptyList()))
            return null
        }

        val session = authService.currentSession(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }

        val allowedSpaceIds = uniqueSpaceIds.toSet()
        return registerListener(
            listenerKey,
            firestore.collection("activity")
            .whereArrayContains("visibleTo", session.uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val items = snapshot?.documents.orEmpty().mapNotNull { document ->
                    val spaceId = (document.data?.get("spaceId") as? String)?.trim().orEmpty()
                    if (spaceId !in allowedSpaceIds) {
                        null
                    } else {
                        mapActivity(document)
                    }
                }.sortedByDescending { it.createdAt?.time ?: Long.MIN_VALUE }
                onUpdate(Result.success(items))
            }
        )
    }

    suspend fun markActivityRead(
        context: Context,
        activity: ActivityItem
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before viewing activity.")
        updateData(
            firestore.collection("activity").document(activity.id),
            mapOf("readBy" to FieldValue.arrayUnion(session.uid))
        )
    }

    fun listenToMessages(
        context: Context,
        space: Space,
        listenerKey: String? = null,
        onUpdate: (Result<List<SpaceMessage>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }
        val currentUserId = authService.currentSession(context)?.uid

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                val documents = snapshot?.documents.orEmpty()
                serviceScope.launch {
                    runCatching {
                        val spaceKey = ensureGeneralEncryptionKey(context, space)
                        runMessageEncryptionSelfTestIfNeeded(space.id, spaceKey)
                        documents.mapNotNull { mapMessage(it, currentUserId, spaceKey) }
                    }.onSuccess { messages ->
                        onUpdate(Result.success(messages))
                    }.onFailure { error ->
                        onUpdate(Result.failure(error))
                    }
                }
            }
        )
    }

    suspend fun fetchRecentMessages(
        context: Context,
        space: Space,
        limit: Int = 20
    ): List<SpaceMessage> {
        val firestore = firestoreOrNull(context) ?: return emptyList()
        val currentUserId = authService.currentSession(context)?.uid
        val snapshot = getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
        )
        val spaceKey = ensureGeneralEncryptionKey(context, space)
        runMessageEncryptionSelfTestIfNeeded(space.id, spaceKey)
        return snapshot.documents.mapNotNull { mapMessage(it, currentUserId, spaceKey) }
    }

    fun listenToReactions(
        context: Context,
        space: Space,
        messageId: String,
        listenerKey: String? = null,
        onUpdate: (Result<List<MessageReaction>>) -> Unit
    ): ListenerRegistration? {
        val firestore = firestoreOrNull(context) ?: run {
            onUpdate(Result.success(emptyList()))
            return null
        }
        val currentUserId = authService.currentSession(context)?.uid

        return registerListener(
            listenerKey,
            firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageId)
            .collection("reactions")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }

                onUpdate(Result.success(mapReactions(snapshot?.documents.orEmpty(), currentUserId)))
            }
        )
    }

    suspend fun createSpace(
        context: Context,
        name: String,
        emoji: String,
        colorHex: String,
        description: String,
        template: SpaceTemplate,
        enabledModules: List<SpaceModule>
    ): Space {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before creating a Space.")
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Space Name is required." }

        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val memberDisplayName = profile?.displayName ?: session.displayName
        val memberEmoji = profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val resolvedDescription = description.trim().ifBlank { template.defaultStatus }
        val resolvedEnabledModules = sanitizeEnabledModules(enabledModules, template)
        val spaceReference = firestore.collection("spaces").document()
        val memberReference = spaceReference.collection("members").document(session.uid)
        val generalEncryptionReference = spaceReference.collection("encryption").document("key")
        val generalEncryptionKey = encryptionService.generateSpaceKey()
        val generalEncryptionKeyBase64 = encryptionService.encodeSpaceKey(generalEncryptionKey)

        setData(spaceReference, mapOf(
            "id" to spaceReference.id,
            "name" to trimmedName,
            "emoji" to emoji,
            "color" to colorHex,
            "description" to resolvedDescription,
            "template" to template.title,
            "enabledModules" to resolvedEnabledModules.map { it.id },
            "ownerId" to session.uid,
            "memberIds" to listOf(session.uid),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ))
        setData(memberReference, mapOf(
            "userId" to session.uid,
            "displayName" to memberDisplayName,
            "emojiAvatar" to memberEmoji,
            "role" to SpaceMemberRole.Owner.firestoreValue,
            "joinedAt" to FieldValue.serverTimestamp()
        ))
        setData(generalEncryptionReference, mapOf(
            "keyVersion" to generalEncryptionVersion,
            "keyBase64" to generalEncryptionKeyBase64,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "createdBy" to session.uid
        ))
        encryptionService.cacheSpaceKey(spaceReference.id, generalEncryptionKey)
        val newSpace = Space(
            id = spaceReference.id,
            name = trimmedName,
            emoji = emoji,
            colorHex = colorHex,
            description = resolvedDescription,
            template = template,
            ownerId = session.uid,
            memberIds = listOf(session.uid),
            unreadCount = null,
            enabledModules = resolvedEnabledModules
        )
        recordActivity(
            context = context,
            type = ActivityType.SpaceCreated,
            space = newSpace,
            actorId = session.uid,
            actorName = memberDisplayName,
            actorEmoji = memberEmoji,
            title = "created the Space",
            targetType = ActivityTargetType.Space
        )
        return newSpace
    }

    suspend fun createInvite(
        context: Context,
        space: Space
    ): SpaceInvite {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before creating invites.")
        ensureCurrentUserIsSpaceMember(context, space)

        val code = reserveInviteCode(firestore)
        val createdAt = Date()
        val expiresAt = Calendar.getInstance().apply {
            time = createdAt
            add(Calendar.DAY_OF_YEAR, 7)
        }.time
        val invite = SpaceInvite(
            id = code,
            code = code,
            spaceId = space.id,
            spaceName = space.name,
            spaceEmoji = space.emoji,
            createdBy = session.uid,
            createdAt = createdAt,
            expiresAt = expiresAt,
            maxUses = 25,
            usedCount = 0,
            active = true
        )

        setData(
            firestore.collection("spaceInvites").document(code),
            mapOf(
                "code" to invite.code,
                "spaceId" to invite.spaceId,
                "spaceName" to invite.spaceName,
                "spaceEmoji" to invite.spaceEmoji,
                "createdBy" to invite.createdBy,
                "createdAt" to Timestamp(invite.createdAt),
                "expiresAt" to Timestamp(invite.expiresAt),
                "maxUses" to invite.maxUses,
                "usedCount" to invite.usedCount,
                "active" to invite.active
            )
        )

        return invite
    }

    suspend fun fetchLatestInvite(
        context: Context,
        space: Space
    ): SpaceInvite? {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val snapshot = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { continuation ->
            firestore.collection("spaceInvites")
                .whereEqualTo("spaceId", space.id)
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

        val document = snapshot.documents.firstOrNull() ?: return null
        return mapInvite(document)
    }

    suspend fun updateInviteActiveState(
        context: Context,
        code: String,
        isActive: Boolean
    ): SpaceInvite {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val reference = firestore.collection("spaceInvites").document(normalizeInviteCode(code))
        setData(reference, mapOf("active" to isActive), merge = true)
        val snapshot = getDocument(reference)
        return mapInvite(snapshot) ?: throw IllegalStateException("Unable to load the current invite link.")
    }

    suspend fun regenerateInvite(
        context: Context,
        space: Space,
        existingInvite: SpaceInvite?
    ): SpaceInvite {
        if (existingInvite != null) {
            updateInviteActiveState(context, existingInvite.code, false)
        }
        return createInvite(context, space)
    }

    suspend fun redeemInvite(
        context: Context,
        code: String
    ): Space {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before joining a Space.")
        val normalizedCode = normalizeInviteCode(code)
        require(normalizedCode.isNotEmpty()) { "Enter a valid invite code." }

        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val displayName = profile?.displayName ?: session.displayName
        val emojiAvatar = profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val inviteReference = firestore.collection("spaceInvites").document(normalizedCode)

        val joinedSpace = suspendCancellableCoroutine<Space> { continuation ->
            firestore.runTransaction { transaction ->
                val inviteSnapshot = transaction.get(inviteReference)
                val inviteData = inviteSnapshot.data ?: throw IllegalStateException("That invite code could not be found.")
                val isActive = inviteData["active"] as? Boolean ?: false
                require(isActive) { "That invite is no longer active." }

                val expiresAt = (inviteData["expiresAt"] as? Timestamp)?.toDate() ?: Date(0)
                require(expiresAt.after(Date())) { "That invite has expired." }

                val maxUses = (inviteData["maxUses"] as? Number)?.toInt() ?: 0
                val usedCount = (inviteData["usedCount"] as? Number)?.toInt() ?: 0
                require(usedCount < maxUses) { "That invite has reached its usage limit." }

                val spaceId = inviteData["spaceId"] as? String ?: throw IllegalStateException("That invite is missing a Space.")
                val spaceReference = firestore.collection("spaces").document(spaceId)
                val memberReference = spaceReference.collection("members").document(session.uid)
                val spaceSnapshot = transaction.get(spaceReference)
                val spaceData = spaceSnapshot.data ?: throw IllegalStateException("Unable to load that Space.")

                val memberIds = (spaceData["memberIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                require(!memberIds.contains(session.uid)) { "You are already a member of this Space." }

                transaction.update(spaceReference, mapOf(
                    "memberIds" to memberIds + session.uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                transaction.set(memberReference, mapOf(
                    "userId" to session.uid,
                    "displayName" to displayName,
                    "emojiAvatar" to emojiAvatar,
                    "role" to SpaceMemberRole.Member.firestoreValue,
                    "joinedAt" to FieldValue.serverTimestamp()
                ))
                transaction.update(inviteReference, "usedCount", usedCount + 1)

                val template = SpaceTemplate.entries.firstOrNull { it.title == spaceData["template"] as? String } ?: SpaceTemplate.Custom
                Space(
                    id = spaceData["id"] as? String ?: spaceSnapshot.id,
                    name = spaceData["name"] as? String ?: "Untitled Space",
                    emoji = (spaceData["emoji"] as? String).orEmpty().ifBlank { "\uD83C\uDFE0" },
                    colorHex = spaceData["color"] as? String ?: "#4F46E5",
                    description = spaceData["description"] as? String ?: template.defaultStatus,
                    template = template,
                    ownerId = spaceData["ownerId"] as? String ?: "",
                    memberIds = (spaceData["memberIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                    unreadCount = null,
                    enabledModules = parseEnabledModules(spaceData, template)
                )
            }.addOnSuccessListener { space ->
                continuation.resume(space as Space)
            }.addOnFailureListener { error ->
                continuation.resumeWithException(error)
            }
        }
        recordActivity(
            context = context,
            type = ActivityType.MemberJoined,
            space = joinedSpace,
            actorId = session.uid,
            actorName = displayName,
            actorEmoji = emojiAvatar,
            title = "joined the Space",
            targetType = ActivityTargetType.Members
        )
        return joinedSpace
    }

    suspend fun updateMemberRole(
        context: Context,
        space: Space,
        memberId: String,
        role: SpaceMemberRole
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        ensureCanChangeMemberRole(context, space, memberId, role)

        updateData(
            firestore.collection("spaces").document(space.id).collection("members").document(memberId),
            mapOf("role" to role.firestoreValue)
        )
    }

    suspend fun canManageModules(
        context: Context,
        space: Space
    ): Boolean {
        return runCatching {
            currentUserHasPermission(context, space, SpacePermission.ManageModules)
        }.getOrDefault(false)
    }

    suspend fun canPerform(
        context: Context,
        space: Space,
        permission: SpacePermission
    ): Boolean {
        return runCatching {
            currentUserHasPermission(context, space, permission)
        }.getOrDefault(false)
    }

    suspend fun filesModuleHasContent(
        context: Context,
        space: Space
    ): Boolean {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val filesSnapshot = getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("files")
                .limit(1)
        )
        if (filesSnapshot.documents.isNotEmpty()) {
            return true
        }

        val foldersSnapshot = getDocuments(
            firestore.collection("spaces")
                .document(space.id)
                .collection("fileFolders")
                .limit(1)
        )
        return foldersSnapshot.documents.isNotEmpty()
    }

    suspend fun setFilesEnabled(
        context: Context,
        space: Space,
        isEnabled: Boolean
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        requirePermission(context, space, SpacePermission.ManageModules, "Only members with module permission can manage modules.")

        val mutableModules = latestEnabledModules(context, space).toMutableList()
        if (isEnabled) {
            if (mutableModules.none { it.id == SpaceModules.Files.id }) {
                mutableModules += SpaceModules.Files
            }
        } else {
            mutableModules.removeAll { it.id == SpaceModules.Files.id }
        }

        val resolvedModules = sanitizeEnabledModules(mutableModules, space.template)
        updateData(
            firestore.collection("spaces").document(space.id),
            mapOf(
                "enabledModules" to resolvedModules.map { it.id },
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun setEventsEnabled(
        context: Context,
        space: Space,
        isEnabled: Boolean
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        requirePermission(context, space, SpacePermission.ManageModules, "Only members with module permission can manage modules.")

        val mutableModules = latestEnabledModules(context, space).toMutableList()
        if (isEnabled) {
            if (mutableModules.none { it.id == SpaceModules.Events.id }) {
                mutableModules += SpaceModules.Events
            }
        } else {
            mutableModules.removeAll { it.id == SpaceModules.Events.id }
        }

        val resolvedModules = sanitizeEnabledModules(mutableModules, space.template)
        updateData(
            firestore.collection("spaces").document(space.id),
            mapOf(
                "enabledModules" to resolvedModules.map { it.id },
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun setPollsEnabled(
        context: Context,
        space: Space,
        isEnabled: Boolean
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        requirePermission(context, space, SpacePermission.ManageModules, "Only members with module permission can manage modules.")

        val mutableModules = latestEnabledModules(context, space).toMutableList()
        if (isEnabled) {
            if (mutableModules.none { it.id == SpaceModules.Polls.id }) {
                mutableModules += SpaceModules.Polls
            }
        } else {
            mutableModules.removeAll { it.id == SpaceModules.Polls.id }
        }

        val resolvedModules = sanitizeEnabledModules(mutableModules, space.template)
        updateData(
            firestore.collection("spaces").document(space.id),
            mapOf(
                "enabledModules" to resolvedModules.map { it.id },
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun removeMember(
        context: Context,
        space: Space,
        memberId: String
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        ensureCanRemoveMember(context, space, memberId)

        val spaceReference = firestore.collection("spaces").document(space.id)
        val memberReference = spaceReference.collection("members").document(memberId)

        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(spaceReference)
                val memberIds = (snapshot.data?.get("memberIds") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                transaction.update(spaceReference, mapOf(
                    "memberIds" to memberIds.filterNot { it == memberId },
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                transaction.delete(memberReference)
                true
            }.addOnSuccessListener {
                continuation.resume(Unit)
            }.addOnFailureListener { error ->
                continuation.resumeWithException(error)
            }
        }
    }

    suspend fun sendTextMessage(
        context: Context,
        space: Space,
        text: String,
        replyContext: MessageReplyContext? = null
    ): SpaceMessage {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before sending messages.")
        val trimmedText = text.trim()
        require(trimmedText.isNotEmpty()) { "Enter a message before sending." }
        requirePermission(context, space, SpacePermission.PostPings, "Only members with posting permission can send messages.")

        val spaceKey = ensureGeneralEncryptionKey(context, space)
        runMessageEncryptionSelfTestIfNeeded(space.id, spaceKey)
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val senderName = profile?.displayName ?: session.displayName
        val senderEmoji = profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val encryptedPayload = encryptionService.encryptText(trimmedText, spaceKey)
        val messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document()

        val messageData = mutableMapOf<String, Any>(
                "id" to messageReference.id,
                "spaceId" to space.id,
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
        addReplyContext(replyContext, messageData)
        setData(messageReference, messageData)
        logStoredMessagePayload(
            messageId = messageReference.id,
            senderId = session.uid,
            encryptionVersion = generalEncryptionVersion,
            ciphertext = encryptedPayload.ciphertext,
            nonce = encryptedPayload.nonce
        )
        recordActivity(
            context = context,
            type = if (replyContext == null) ActivityType.MessageSent else ActivityType.ReplyAdded,
            space = space,
            actorId = session.uid,
            actorName = senderName,
            actorEmoji = senderEmoji,
            title = if (replyContext == null) "sent a message" else "added a reply",
            targetId = messageReference.id,
            targetType = ActivityTargetType.General
        )

        return SpaceMessage(
            id = messageReference.id,
            spaceId = space.id,
            senderId = session.uid,
            senderName = senderName,
            senderEmoji = senderEmoji,
            type = com.arcinteractive.spaces.data.model.MessageType.Text,
            encryptionVersion = generalEncryptionVersion,
            deleted = false,
            text = trimmedText,
            media = null,
            createdAt = Date(),
            updatedAt = Date(),
            timestamp = messageTimestampFormatter.format(Date()),
            isOutgoing = true,
            status = "sent",
            deliveryStatus = "Sent",
            isEdited = false,
            replyContext = replyContext
        )
    }

    suspend fun editTextMessage(
        context: Context,
        space: Space,
        messageId: String,
        newText: String
    ): SpaceMessage {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before editing messages.")
        val trimmedText = newText.trim()
        require(trimmedText.isNotEmpty()) { "Enter a message before saving changes." }

        val messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageId)
        val snapshot = getDocument(messageReference)
        val data = snapshot.data ?: throw IllegalStateException("That message could not be found.")
        val senderId = data["senderId"] as? String
        val type = (data["type"] as? String)?.lowercase() ?: "text"
        val deleted = data["deleted"] as? Boolean ?: false
        require(senderId == session.uid && type == "text" && !deleted) {
            "Only your own text messages can be edited."
        }

        val spaceKey = ensureGeneralEncryptionKey(context, space)
        runMessageEncryptionSelfTestIfNeeded(space.id, spaceKey)
        val encryptedPayload = encryptionService.encryptText(trimmedText, spaceKey)

        updateData(
            messageReference,
            mapOf(
                "ciphertextBase64" to encryptedPayload.ciphertext,
                "nonceBase64" to encryptedPayload.nonce,
                "edited" to true,
                "editedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val createdAt = (data["createdAt"] as? Timestamp)?.toDate()
        val status = data["status"] as? String
        return SpaceMessage(
            id = data["id"] as? String ?: messageId,
            spaceId = data["spaceId"] as? String ?: space.id,
            senderId = senderId,
            senderName = data["senderName"] as? String ?: session.displayName,
            senderEmoji = (data["senderEmoji"] as? String).orEmpty().ifBlank { null },
            type = com.arcinteractive.spaces.data.model.MessageType.Text,
            encryptionVersion = (data["encryptionVersion"] as? String)?.trim().orEmpty().ifBlank { generalEncryptionVersion },
            deleted = false,
            text = trimmedText,
            media = null,
            createdAt = createdAt,
            updatedAt = Date(),
            timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
            isOutgoing = true,
            status = status,
            deliveryStatus = deliveryStatus(status, true),
            isEdited = true,
            editedAt = Date(),
            replyContext = mappedReplyContext(data)
        )
    }

    suspend fun sendImageMessage(
        context: Context,
        space: Space,
        imageBytes: ByteArray,
        caption: String?,
        mediaCategory: String = "photo",
        replyContext: MessageReplyContext? = null
    ): SpaceMessage {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before sending messages.")
        requirePermission(context, space, SpacePermission.UploadPhotosVideos, "Only members with media permission can share photos or videos.")
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val senderName = profile?.displayName ?: session.displayName
        val senderEmoji = profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val resolvedMediaType = mediaTypeFromCategory(mediaCategory)
        val trimmedCaption = caption?.trim().orEmpty().ifBlank { null }
        val spaceKey = ensureGeneralEncryptionKey(context, space)
        runMessageEncryptionSelfTestIfNeeded(space.id, spaceKey)
        val encryptedCaption = trimmedCaption?.let { encryptionService.encryptText(it, spaceKey) }

        val messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document()
        val mediaId = messageReference.id
        val uploadResult = encryptedMediaService.uploadImage(
            context = context,
            spaceId = space.id,
            mediaId = mediaId,
            originalBytes = imageBytes,
            mediaType = resolvedMediaType,
            uploadedBy = session.uid
        )
        val metadata = uploadResult.metadata

        android.util.Log.d(
            "SpaceService",
            "[ImageMessage] photoSelected=true imageDataByteCount=${imageBytes.size} thumbnailByteCount=${metadata.fileSize}"
        )
        android.util.Log.d("SpaceService", "[ImageMessage] uploadPath=${metadata.storagePath}")
        metadata.thumbnailStoragePath?.let {
            android.util.Log.d("SpaceService", "[ImageMessage] uploadPath=$it")
        }

        val messageData = mutableMapOf<String, Any>(
            "id" to mediaId,
            "mediaId" to metadata.mediaId,
            "spaceId" to space.id,
            "senderId" to session.uid,
            "senderName" to senderName,
            "senderEmoji" to senderEmoji,
            "type" to "image",
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
        metadata.thumbnailStoragePath?.let {
            messageData["thumbnailStoragePath"] = it
        }
        metadata.thumbnailNonce?.let {
            messageData["thumbnailNonce"] = it
            messageData["thumbnailNonceBase64"] = it
        }
        metadata.width?.let { messageData["width"] = it }
        metadata.height?.let { messageData["height"] = it }
        metadata.duration?.let { messageData["duration"] = it }
        encryptedCaption?.let {
            messageData["captionCiphertextBase64"] = it.ciphertext
            messageData["captionNonceBase64"] = it.nonce
        }
        addReplyContext(replyContext, messageData)
        setData(messageReference, messageData)
        android.util.Log.d("SpaceService", "[ImageMessage] messageDocumentCreated=true messageId=$mediaId")
        recordActivity(
            context = context,
            type = ActivityType.PhotoShared,
            space = space,
            actorId = session.uid,
            actorName = senderName,
            actorEmoji = senderEmoji,
            title = "shared a photo",
            subtitle = trimmedCaption,
            targetId = mediaId,
            targetType = ActivityTargetType.Photos
        )

        return SpaceMessage(
            id = mediaId,
            spaceId = space.id,
            senderId = session.uid,
            senderName = senderName,
            senderEmoji = senderEmoji,
            type = com.arcinteractive.spaces.data.model.MessageType.Image,
            encryptionVersion = metadata.encryptionVersion,
            deleted = false,
            text = null,
            media = com.arcinteractive.spaces.data.model.SpaceMedia(
                id = mediaId,
                spaceId = space.id,
                type = com.arcinteractive.spaces.data.model.MessageType.Image,
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
            isEdited = false,
            replyContext = replyContext
        )
    }

    suspend fun sendVideoMessage(
        context: Context,
        space: Space,
        videoBytes: ByteArray,
        caption: String?,
        mimeType: String,
        replyContext: MessageReplyContext? = null
    ): SpaceMessage {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before sending messages.")
        requirePermission(context, space, SpacePermission.UploadPhotosVideos, "Only members with media permission can share photos or videos.")
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val senderName = profile?.displayName ?: session.displayName
        val senderEmoji = profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }
        val trimmedCaption = caption?.trim().orEmpty().ifBlank { null }
        val spaceKey = ensureGeneralEncryptionKey(context, space)
        runMessageEncryptionSelfTestIfNeeded(space.id, spaceKey)
        val encryptedCaption = trimmedCaption?.let { encryptionService.encryptText(it, spaceKey) }

        val messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document()
        val mediaId = messageReference.id
        val uploadResult = encryptedMediaService.uploadVideo(
            context = context,
            spaceId = space.id,
            mediaId = mediaId,
            originalBytes = videoBytes,
            mimeType = mimeType,
            uploadedBy = session.uid
        )
        val metadata = uploadResult.metadata

        val messageData = mutableMapOf<String, Any>(
            "id" to mediaId,
            "mediaId" to metadata.mediaId,
            "spaceId" to space.id,
            "senderId" to session.uid,
            "senderName" to senderName,
            "senderEmoji" to senderEmoji,
            "type" to "video",
            "mediaCategory" to "video",
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
        metadata.thumbnailStoragePath?.let {
            messageData["thumbnailStoragePath"] = it
        }
        metadata.thumbnailNonce?.let {
            messageData["thumbnailNonce"] = it
            messageData["thumbnailNonceBase64"] = it
        }
        metadata.width?.let { messageData["width"] = it }
        metadata.height?.let { messageData["height"] = it }
        metadata.duration?.let { messageData["duration"] = it }
        encryptedCaption?.let {
            messageData["captionCiphertextBase64"] = it.ciphertext
            messageData["captionNonceBase64"] = it.nonce
        }
        addReplyContext(replyContext, messageData)
        setData(messageReference, messageData)
        recordActivity(
            context = context,
            type = ActivityType.VideoShared,
            space = space,
            actorId = session.uid,
            actorName = senderName,
            actorEmoji = senderEmoji,
            title = "shared a video",
            subtitle = trimmedCaption,
            targetId = mediaId,
            targetType = ActivityTargetType.Photos
        )

        return SpaceMessage(
            id = mediaId,
            spaceId = space.id,
            senderId = session.uid,
            senderName = senderName,
            senderEmoji = senderEmoji,
            type = com.arcinteractive.spaces.data.model.MessageType.Video,
            encryptionVersion = metadata.encryptionVersion,
            deleted = false,
            text = null,
            media = com.arcinteractive.spaces.data.model.SpaceMedia(
                id = mediaId,
                spaceId = space.id,
                type = com.arcinteractive.spaces.data.model.MessageType.Video,
                mediaCategory = "video",
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
            isEdited = false,
            replyContext = replyContext
        )
    }

    suspend fun loadThumbnailBytes(context: Context, media: com.arcinteractive.spaces.data.model.SpaceMedia): ByteArray {
        return encryptedMediaService.loadThumbnailBytes(context, media)
    }

    suspend fun loadFullMediaBytes(context: Context, media: com.arcinteractive.spaces.data.model.SpaceMedia): ByteArray {
        return encryptedMediaService.loadFullMediaBytes(context, media)
    }

    fun currentUserId(context: Context): String? = authService.currentSession(context)?.uid

    suspend fun createPoll(
        context: Context,
        space: Space,
        question: String,
        optionTexts: List<String>,
        closesAt: Date?,
        allowMultipleVotes: Boolean,
        anonymous: Boolean
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before creating polls.")
        val trimmedQuestion = question.trim()
        val trimmedOptions = optionTexts.map { it.trim() }.filter { it.isNotEmpty() }
        require(trimmedQuestion.isNotEmpty()) { "Enter a poll question." }
        require(trimmedOptions.size >= 2) { "Add at least two valid poll options." }

        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val createdByName = profile?.displayName ?: session.displayName
        val pollReference = firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .document()
        val optionsPayload = trimmedOptions.mapIndexed { index, text ->
            mapOf("id" to "option-${index + 1}", "text" to text)
        }
        val payload = mutableMapOf<String, Any>(
            "id" to pollReference.id,
            "spaceId" to space.id,
            "question" to trimmedQuestion,
            "options" to optionsPayload,
            "createdBy" to session.uid,
            "createdByName" to createdByName,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "allowMultipleVotes" to allowMultipleVotes,
            "anonymous" to anonymous,
            "deleted" to false
        )
        if (closesAt != null) {
            payload["closesAt"] = Timestamp(closesAt)
        }
        setData(pollReference, payload)
        recordActivity(
            context = context,
            type = ActivityType.PollCreated,
            space = space,
            actorId = session.uid,
            actorName = createdByName,
            actorEmoji = profile?.emojiAvatar?.trim()?.ifEmpty { null },
            title = "created a poll",
            subtitle = trimmedQuestion,
            targetId = pollReference.id,
            targetType = ActivityTargetType.Polls
        )
    }

    suspend fun submitPollVote(
        context: Context,
        space: Space,
        poll: SpacePoll,
        optionIds: List<String>
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before voting.")
        require(!poll.isClosed) { "This poll is closed." }
        val validOptionIds = poll.options.map { it.id }.toSet()
        val sanitizedOptionIds = optionIds.filter { it in validOptionIds }.distinct().sorted()
        require(sanitizedOptionIds.isNotEmpty()) { "Add at least two valid poll options." }
        require(poll.allowMultipleVotes || sanitizedOptionIds.size == 1) { "Add at least two valid poll options." }

        val voteReference = firestore.collection("spaces")
            .document(space.id)
            .collection("polls")
            .document(poll.id)
            .collection("votes")
            .document(session.uid)

        setData(
            voteReference,
            mapOf(
                "userId" to session.uid,
                "optionIds" to sanitizedOptionIds,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            merge = true
        )
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        recordActivity(
            context = context,
            type = ActivityType.PollVoted,
            space = space,
            actorId = session.uid,
            actorName = profile?.displayName ?: session.displayName,
            actorEmoji = profile?.emojiAvatar?.trim()?.ifEmpty { null },
            title = "voted in a poll",
            subtitle = poll.question,
            targetId = poll.id,
            targetType = ActivityTargetType.Polls
        )
    }

    suspend fun deletePoll(
        context: Context,
        space: Space,
        poll: SpacePoll
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before deleting polls.")
        if (poll.createdBy != session.uid) {
            require(currentUserHasPermission(context, space, SpacePermission.DeleteOthersContent)) {
                "Only the creator or a member with delete permission can delete this poll."
            }
        }

        updateData(
            firestore.collection("spaces").document(space.id).collection("polls").document(poll.id),
            mapOf(
                "deleted" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun createEvent(
        context: Context,
        space: Space,
        title: String,
        description: String,
        location: String,
        startDate: Date,
        endDate: Date,
        allDay: Boolean
    ): SpaceEvent {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before creating events.")
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotEmpty()) { "Enter an event title." }
        require(!endDate.before(startDate)) { "The event end time must be after the start time." }

        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val createdByName = profile?.displayName ?: session.displayName
        val eventReference = firestore.collection("spaces")
            .document(space.id)
            .collection("events")
            .document()
        val timeZoneId = TimeZone.getDefault().id
        val trimmedDescription = description.trim()
        val trimmedLocation = location.trim()

        requirePermission(context, space, SpacePermission.CreateEvents, "Only members with event permission can create events.")

        setData(
            eventReference,
            mapOf(
                "id" to eventReference.id,
                "spaceId" to space.id,
                "title" to trimmedTitle,
                "description" to trimmedDescription,
                "location" to trimmedLocation,
                "startDate" to Timestamp(startDate),
                "endDate" to Timestamp(endDate),
                "allDay" to allDay,
                "timezone" to timeZoneId,
                "createdBy" to session.uid,
                "createdByName" to createdByName,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "deleted" to false
            )
        )
        recordActivity(
            context = context,
            type = ActivityType.EventCreated,
            space = space,
            actorId = session.uid,
            actorName = createdByName,
            actorEmoji = profile?.emojiAvatar?.trim()?.ifEmpty { null },
            title = "created an event",
            subtitle = trimmedTitle,
            targetId = eventReference.id,
            targetType = ActivityTargetType.Events
        )

        return SpaceEvent(
            id = eventReference.id,
            spaceId = space.id,
            title = trimmedTitle,
            description = trimmedDescription,
            location = trimmedLocation,
            startDate = startDate,
            endDate = endDate,
            allDay = allDay,
            timezone = timeZoneId,
            createdBy = session.uid,
            createdByName = createdByName,
            createdAt = Date(),
            updatedAt = Date(),
            deleted = false
        )
    }

    suspend fun updateEvent(
        context: Context,
        space: Space,
        event: SpaceEvent,
        title: String,
        description: String,
        location: String,
        startDate: Date,
        endDate: Date,
        allDay: Boolean
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before editing events.")
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotEmpty()) { "Enter an event title." }
        require(!endDate.before(startDate)) { "The event end time must be after the start time." }

        if (event.createdBy != session.uid) {
            require(currentUserHasPermission(context, space, SpacePermission.DeleteOthersContent)) {
                "Only the creator or a member with delete permission can edit this event."
            }
        }

        updateData(
            firestore.collection("spaces").document(space.id).collection("events").document(event.id),
            mapOf(
                "title" to trimmedTitle,
                "description" to description.trim(),
                "location" to location.trim(),
                "startDate" to Timestamp(startDate),
                "endDate" to Timestamp(endDate),
                "allDay" to allDay,
                "timezone" to TimeZone.getDefault().id,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        recordActivity(
            context = context,
            type = ActivityType.EventUpdated,
            space = space,
            actorId = session.uid,
            actorName = profile?.displayName ?: session.displayName,
            actorEmoji = profile?.emojiAvatar?.trim()?.ifEmpty { null },
            title = "updated an event",
            subtitle = trimmedTitle,
            targetId = event.id,
            targetType = ActivityTargetType.Events
        )
    }

    suspend fun deleteEvent(
        context: Context,
        space: Space,
        event: SpaceEvent
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before deleting events.")
        if (event.createdBy != session.uid) {
            require(currentUserHasPermission(context, space, SpacePermission.DeleteOthersContent)) {
                "Only the creator or a member with delete permission can delete this event."
            }
        }

        updateData(
            firestore.collection("spaces").document(space.id).collection("events").document(event.id),
            mapOf(
                "deleted" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun canManageEvent(
        context: Context,
        space: Space,
        event: SpaceEvent
    ): Boolean {
        val session = authService.currentSession(context) ?: return false
        if (event.createdBy == session.uid) {
            return true
        }
        return runCatching {
            currentUserHasPermission(context, space, SpacePermission.DeleteOthersContent)
        }.getOrDefault(false)
    }

    suspend fun uploadFile(
        context: Context,
        space: Space,
        fileUri: android.net.Uri
    ): SpaceFileItem {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before uploading files.")
        requirePermission(context, space, SpacePermission.UploadFiles, "Only members with file permission can upload files.")
        val fileName = resolveFileName(context, fileUri).trim()
        require(fileName.isNotEmpty()) { "Choose a file before uploading." }
        val fileBytes = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to read the selected file.")
        val mimeType = context.contentResolver.getType(fileUri)
            ?: mimeTypeFromName(fileName)
        val profile = userProfileService.fetchUserProfile(context, session.uid)
        val uploaderName = profile?.displayName ?: session.displayName
        val resolvedFileExtension = fileExtensionFromName(fileName, mimeType)
        val fileReference = firestore.collection("spaces")
            .document(space.id)
            .collection("files")
            .document()
        val storagePath = "spaces/${space.id}/files/${fileReference.id}.enc"
        val uploadResult = encryptedMediaService.uploadFile(
            context = context,
            spaceId = space.id,
            storagePath = storagePath,
            originalBytes = fileBytes,
            mimeType = mimeType,
            uploadedBy = uploaderName
        )

        val metadata = uploadResult.metadata
        val fileData = mutableMapOf<String, Any>(
            "id" to fileReference.id,
            "spaceId" to space.id,
            "name" to fileName,
            "mimeType" to mimeType,
            "fileExtension" to resolvedFileExtension,
            "storagePath" to metadata.storagePath,
            "encryptionVersion" to metadata.encryptionVersion,
            "nonceBase64" to metadata.nonce,
            "uploadedBy" to session.uid,
            "uploadedByName" to uploaderName,
            "fileSize" to metadata.fileSize,
            "deleted" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        setData(fileReference, fileData)
        recordActivity(
            context = context,
            type = ActivityType.FileUploaded,
            space = space,
            actorId = session.uid,
            actorName = uploaderName,
            actorEmoji = profile?.emojiAvatar?.trim()?.ifEmpty { null },
            title = "uploaded a file",
            subtitle = fileName,
            targetId = fileReference.id,
            targetType = ActivityTargetType.Files
        )

        return SpaceFileItem(
            id = fileReference.id,
            spaceId = space.id,
            name = fileName,
            mimeType = mimeType,
            folderId = null,
            storagePath = metadata.storagePath,
            encryptionVersion = metadata.encryptionVersion,
            nonceBase64 = metadata.nonce,
            uploadedBy = session.uid,
            uploadedByName = uploaderName,
            fileExtension = resolvedFileExtension,
            createdAt = Date(),
            updatedAt = Date(),
            sizeBytes = metadata.fileSize,
            deleted = false
        )
    }

    suspend fun downloadFileBytes(
        context: Context,
        space: Space,
        file: SpaceFileItem
    ): ByteArray {
        return encryptedMediaService.loadFileBytes(
            context = context,
            spaceId = space.id,
            storagePath = file.storagePath,
            nonce = file.nonceBase64
        )
    }

    suspend fun renameFile(
        context: Context,
        space: Space,
        file: SpaceFileItem,
        newName: String
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val trimmedName = newName.trim()
        require(trimmedName.isNotEmpty()) { "Enter a file name before saving." }
        ensureFileManagementPermission(context, space, file)
        updateData(
            firestore.collection("spaces").document(space.id).collection("files").document(file.id),
            mapOf(
                "name" to trimmedName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun softDeleteFile(
        context: Context,
        space: Space,
        file: SpaceFileItem
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before deleting files.")
        ensureFileManagementPermission(context, space, file)
        updateData(
            firestore.collection("spaces").document(space.id).collection("files").document(file.id),
            mapOf(
                "deleted" to true,
                "deletedAt" to FieldValue.serverTimestamp(),
                "deletedBy" to session.uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun canManageFile(
        context: Context,
        space: Space,
        file: SpaceFileItem
    ): Boolean {
        return runCatching {
            ensureFileManagementPermission(context, space, file)
            true
        }.getOrDefault(false)
    }

    suspend fun toggleReaction(
        context: Context,
        space: Space,
        messageId: String,
        emoji: String
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before reacting to messages.")
        val reference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageId)
            .collection("reactions")
            .document(session.uid)
        val snapshot = getDocument(reference)
        val existingEmoji = (snapshot.data?.get("emoji") as? String)?.trim()?.ifEmpty { null }

        if (existingEmoji == emoji) {
            deleteDocument(reference)
            return
        }

        setData(
            reference,
            mapOf(
                "emoji" to emoji,
                "userId" to session.uid,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        val profile = runCatching { userProfileService.fetchUserProfile(context, session.uid) }.getOrNull()
        recordActivity(
            context = context,
            type = ActivityType.ReactionAdded,
            space = space,
            actorId = session.uid,
            actorName = profile?.displayName ?: session.displayName,
            actorEmoji = profile?.emojiAvatar?.trim()?.ifEmpty { null },
            title = "reacted to a message",
            targetId = messageId,
            targetType = ActivityTargetType.General
        )
    }

    suspend fun deleteMessage(
        context: Context,
        space: Space,
        messageId: String
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before deleting messages.")
        val messageReference = firestore.collection("spaces")
            .document(space.id)
            .collection("messages")
            .document(messageId)
        val snapshot = getDocument(messageReference)
        val data = snapshot.data ?: throw IllegalStateException("That message could not be found.")
        val senderId = data["senderId"] as? String
        if (senderId != session.uid) {
            require(currentUserHasPermission(context, space, SpacePermission.DeleteOthersContent)) {
                "Only the sender or a member with delete permission can delete this message."
            }
        }

        updateData(
            messageReference,
            mapOf(
                "deleted" to true,
                "deletedAt" to FieldValue.serverTimestamp(),
                "deletedBy" to session.uid,
                "text" to "",
                "ciphertextBase64" to "",
                "nonceBase64" to "",
                "captionCiphertextBase64" to "",
                "captionNonceBase64" to "",
                "mediaStoragePath" to "",
                "thumbnailStoragePath" to "",
                "mediaNonceBase64" to "",
                "thumbnailNonceBase64" to ""
            )
        )
    }

    private suspend fun ensureFileManagementPermission(
        context: Context,
        space: Space,
        file: SpaceFileItem
    ) {
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before managing files.")
        if (file.uploadedBy == session.uid) {
            return
        }

        require(currentUserHasPermission(context, space, SpacePermission.DeleteOthersContent)) {
            "Only the uploader or a member with delete permission can manage this file."
        }
    }

    private fun ensureCurrentUserIsSpaceMember(
        context: Context,
        space: Space
    ) {
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before managing this Space.")
        val isMember = session.uid == space.ownerId || space.memberIds.contains(session.uid)
        require(isMember) { "Join this Space before continuing." }
    }

    private suspend fun fetchCurrentUserRole(
        context: Context,
        space: Space
    ): SpaceMemberRole {
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before managing this Space.")
        if (session.uid == space.ownerId) {
            return SpaceMemberRole.Owner
        }

        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val snapshot = getDocument(
            firestore.collection("spaces").document(space.id).collection("members").document(session.uid)
        )
        val member = mapMember(snapshot) ?: throw IllegalStateException("Unable to resolve the current member for this Space.")
        return member.role
    }

    private suspend fun fetchMemberRole(
        context: Context,
        space: Space,
        memberId: String
    ): SpaceMemberRole {
        if (memberId == space.ownerId) {
            return SpaceMemberRole.Owner
        }

        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val snapshot = getDocument(
            firestore.collection("spaces").document(space.id).collection("members").document(memberId)
        )
        val member = mapMember(snapshot) ?: throw IllegalStateException("Unable to resolve the selected member for this Space.")
        return member.role
    }

    private suspend fun currentUserHasPermission(
        context: Context,
        space: Space,
        permission: SpacePermission
    ): Boolean {
        ensureCurrentUserIsSpaceMember(context, space)
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before managing this Space.")
        if (session.uid == space.ownerId) {
            return true
        }
        return fetchCurrentUserRole(context, space).capabilities.contains(permission)
    }

    private suspend fun requirePermission(
        context: Context,
        space: Space,
        permission: SpacePermission,
        failureMessage: String
    ) {
        require(currentUserHasPermission(context, space, permission)) { failureMessage }
    }

    private suspend fun ensureCanChangeMemberRole(
        context: Context,
        space: Space,
        memberId: String,
        newRole: SpaceMemberRole
    ) {
        val currentRole = fetchCurrentUserRole(context, space)
        val targetRole = fetchMemberRole(context, space, memberId)
        require(currentRole.canChangeRole(targetRole, newRole, memberId == space.ownerId)) {
            if (memberId == space.ownerId || newRole == SpaceMemberRole.Owner) {
                "The Space owner cannot be modified or reassigned."
            } else {
                "Only allowed role transitions can be made for this member."
            }
        }
    }

    private suspend fun ensureCanRemoveMember(
        context: Context,
        space: Space,
        memberId: String
    ) {
        val currentRole = fetchCurrentUserRole(context, space)
        val targetRole = fetchMemberRole(context, space, memberId)
        require(currentRole.canRemove(targetRole, memberId == space.ownerId)) {
            if (memberId == space.ownerId) {
                "The Space owner cannot be removed."
            } else {
                "You do not have permission to remove this member."
            }
        }
    }

    private fun resolveFileName(context: Context, fileUri: android.net.Uri): String {
        val cursor = context.contentResolver.query(fileUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return it.getString(index).orEmpty()
                }
            }
        }
        return fileUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Untitled File" } ?: "Untitled File"
    }

    private fun mimeTypeFromName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun fileExtensionFromName(name: String, mimeType: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isNotBlank()) {
            return extension
        }

        return when (mimeType.lowercase()) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/heic" -> "heic"
            "image/jpeg" -> "jpg"
            "video/quicktime" -> "mov"
            "video/mp4" -> "mp4"
            "text/plain" -> "txt"
            "application/json" -> "json"
            "text/csv" -> "csv"
            else -> "dat"
        }
    }

    private suspend fun reserveInviteCode(firestore: FirebaseFirestore): String {
        repeat(10) {
            val code = randomInviteCode()
            val snapshot = getDocument(firestore.collection("spaceInvites").document(code))
            if (!snapshot.exists()) return code
        }
        throw IllegalStateException("Unable to create an invite right now.")
    }

    private fun sanitizeEnabledModules(
        modules: List<SpaceModule>,
        template: SpaceTemplate
    ): List<SpaceModule> {
        val requestedIds = modules.filter { it.id != SpaceModules.Settings.id }.map { it.id }.toSet()
        val resolvedModules = SpaceModules.required.toMutableList()

        SpaceModules.optional.forEach { module ->
            if (requestedIds.contains(module.id)) {
                resolvedModules += module
            }
        }

        return SpaceModules.configurable.filter { candidate ->
            resolvedModules.any { it.id == candidate.id }
        }
    }

    private fun parseEnabledModules(
        data: Map<String, Any>,
        template: SpaceTemplate
    ): List<SpaceModule> {
        val storedModules = (data["enabledModules"] as? List<*>)?.mapNotNull { value ->
            SpaceModules.fromId(value as? String ?: return@mapNotNull null)
        }.orEmpty()

        return if (storedModules.isNotEmpty()) {
            sanitizeEnabledModules(storedModules, template)
        } else {
            sanitizeEnabledModules(template.defaultEnabledModules, template)
        }
    }

    private suspend fun latestEnabledModules(
        context: Context,
        space: Space
    ): List<SpaceModule> {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val snapshot = getDocument(firestore.collection("spaces").document(space.id))
        val data = snapshot.data ?: return sanitizeEnabledModules(space.enabledModules, space.template)
        return parseEnabledModules(data, space.template)
    }

    private fun mapSpace(snapshot: DocumentSnapshot): Space? {
        val data = snapshot.data ?: return null
        val template = SpaceTemplate.entries.firstOrNull { it.title == data["template"] as? String } ?: SpaceTemplate.Custom
        return Space(
            id = data["id"] as? String ?: snapshot.id,
            name = data["name"] as? String ?: "Untitled Space",
            emoji = (data["emoji"] as? String).orEmpty().ifBlank { "\uD83C\uDFE0" },
            colorHex = data["color"] as? String ?: "#4F46E5",
            description = data["description"] as? String ?: template.defaultStatus,
            template = template,
            ownerId = data["ownerId"] as? String ?: "",
            memberIds = (data["memberIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            unreadCount = null,
            enabledModules = parseEnabledModules(data, template)
        )
    }

    private fun mapMember(snapshot: DocumentSnapshot): SpaceMember? {
        val data = snapshot.data ?: return null
        return SpaceMember(
            id = data["userId"] as? String ?: snapshot.id,
            displayName = data["displayName"] as? String ?: "Member",
            emojiAvatar = (data["emojiAvatar"] as? String).orEmpty().ifBlank { "🙂" },
            role = SpaceMemberRole.fromFirestoreValue(data["role"] as? String ?: "") ?: SpaceMemberRole.Member,
            status = "Active"
        )
    }

    private fun mapFolder(snapshot: DocumentSnapshot): SpaceFolder? {
        val data = snapshot.data ?: return null
        return SpaceFolder(
            id = data["id"] as? String ?: snapshot.id,
            name = data["name"] as? String ?: "Folder",
            createdBy = data["createdBy"] as? String ?: "Member",
            createdAt = (data["createdAt"] as? Timestamp)?.toDate()
        )
    }

    private fun mapFile(snapshot: DocumentSnapshot): SpaceFileItem? {
        val data = snapshot.data ?: return null
        return SpaceFileItem(
            id = data["id"] as? String ?: snapshot.id,
            spaceId = data["spaceId"] as? String ?: snapshot.reference.parent.parent?.id.orEmpty(),
            name = data["name"] as? String ?: "Untitled File",
            mimeType = data["mimeType"] as? String ?: "application/octet-stream",
            folderId = (data["folderId"] as? String)?.trim()?.ifEmpty { null },
            storagePath = data["storagePath"] as? String ?: "spaces/${snapshot.reference.parent.parent?.id.orEmpty()}/files/${snapshot.id}.enc",
            encryptionVersion = data["encryptionVersion"] as? String ?: generalEncryptionVersion,
            nonceBase64 = data["nonceBase64"] as? String ?: "",
            uploadedBy = data["uploadedBy"] as? String ?: "",
            uploadedByName = data["uploadedByName"] as? String ?: "Member",
            fileExtension = data["fileExtension"] as? String
                ?: ((data["name"] as? String)?.substringAfterLast('.', "")?.lowercase()?.ifBlank { "dat" } ?: "dat"),
            createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
            sizeBytes = (data["fileSize"] as? Number)?.toLong() ?: 0L,
            deleted = data["deleted"] as? Boolean ?: false
        )
    }

    private fun mapPoll(snapshot: DocumentSnapshot): SpacePoll? {
        val data = snapshot.data ?: return null
        val options = (data["options"] as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val text = map["text"] as? String ?: return@mapNotNull null
            SpacePollOption(id = id, text = text)
        }.orEmpty()

        return SpacePoll(
            id = data["id"] as? String ?: snapshot.id,
            spaceId = data["spaceId"] as? String ?: snapshot.reference.parent.parent?.id.orEmpty(),
            question = data["question"] as? String ?: "Untitled Poll",
            options = options,
            createdBy = data["createdBy"] as? String ?: "",
            createdByName = data["createdByName"] as? String ?: "Member",
            createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
            closesAt = (data["closesAt"] as? Timestamp)?.toDate(),
            allowMultipleVotes = data["allowMultipleVotes"] as? Boolean ?: false,
            anonymous = data["anonymous"] as? Boolean ?: false,
            deleted = data["deleted"] as? Boolean ?: false
        )
    }

    private fun mapEvent(snapshot: DocumentSnapshot): SpaceEvent? {
        val data = snapshot.data ?: return null
        val startDate = (data["startDate"] as? Timestamp)?.toDate() ?: return null
        val endDate = (data["endDate"] as? Timestamp)?.toDate() ?: return null

        return SpaceEvent(
            id = data["id"] as? String ?: snapshot.id,
            spaceId = data["spaceId"] as? String ?: snapshot.reference.parent.parent?.id.orEmpty(),
            title = data["title"] as? String ?: "Untitled Event",
            description = data["description"] as? String ?: "",
            location = data["location"] as? String ?: "",
            startDate = startDate,
            endDate = endDate,
            allDay = data["allDay"] as? Boolean ?: false,
            timezone = data["timezone"] as? String ?: TimeZone.getDefault().id,
            createdBy = data["createdBy"] as? String ?: "",
            createdByName = data["createdByName"] as? String ?: "Member",
            createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
            deleted = data["deleted"] as? Boolean ?: false
        )
    }

    private fun mapPollVote(snapshot: DocumentSnapshot): SpacePollVote? {
        val data = snapshot.data ?: return null
        return SpacePollVote(
            id = snapshot.id,
            userId = data["userId"] as? String ?: snapshot.id,
            optionIds = data["optionIds"] as? List<String> ?: emptyList(),
            createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate()
        )
    }

    private fun mapInvite(snapshot: DocumentSnapshot): SpaceInvite? {
        val data = snapshot.data ?: return null
        val code = data["code"] as? String ?: snapshot.id
        val createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()
        val expiresAt = (data["expiresAt"] as? Timestamp)?.toDate() ?: Date(createdAt.time + 604800000)
        return SpaceInvite(
            id = code,
            code = code,
            spaceId = data["spaceId"] as? String ?: "",
            spaceName = data["spaceName"] as? String ?: "Space",
            spaceEmoji = (data["spaceEmoji"] as? String).orEmpty().ifBlank { "\uD83C\uDFE0" },
            createdBy = data["createdBy"] as? String ?: "",
            createdAt = createdAt,
            expiresAt = expiresAt,
            maxUses = (data["maxUses"] as? Number)?.toInt() ?: 0,
            usedCount = (data["usedCount"] as? Number)?.toInt() ?: 0,
            active = data["active"] as? Boolean ?: false
        )
    }

    private fun mapActivity(snapshot: DocumentSnapshot): ActivityItem? {
        val data = snapshot.data ?: return null
        val typeRaw = (data["type"] as? String)?.trim()?.ifEmpty { null }
        if (typeRaw == null) {
            android.util.Log.d("SpaceService", "[Activity] Missing type for document ${snapshot.id}: $data")
            return null
        }
        val type = typeRaw.toActivityTypeOrNull()
        if (type == null) {
            android.util.Log.d("SpaceService", "[Activity] Unable to map type: $typeRaw")
            android.util.Log.d("SpaceService", "[Activity] Document data: $data")
            return null
        }
        val targetTypeRaw = (data["targetType"] as? String)?.trim()?.ifEmpty { null }
        val targetType = targetTypeRaw?.toActivityTargetTypeOrNull()
        if (targetTypeRaw != null && targetType == null) {
            android.util.Log.d("SpaceService", "[Activity] Unable to map targetType: $targetTypeRaw")
            android.util.Log.d("SpaceService", "[Activity] Document data: $data")
        }

        return ActivityItem(
            id = data["id"] as? String ?: snapshot.id,
            spaceId = data["spaceId"] as? String ?: "",
            spaceName = data["spaceName"] as? String ?: "Space",
            spaceEmoji = (data["spaceEmoji"] as? String).orEmpty().ifBlank { "\uD83C\uDFE0" },
            actorId = data["actorId"] as? String ?: "",
            actorName = data["actorName"] as? String ?: "Member",
            actorEmoji = (data["actorEmoji"] as? String)?.trim()?.ifEmpty { null },
            type = type,
            title = data["title"] as? String ?: "updated this Space",
            subtitle = (data["subtitle"] as? String)?.trim()?.ifEmpty { null },
            targetId = (data["targetId"] as? String)?.trim()?.ifEmpty { null },
            targetType = targetType,
            createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
            readBy = (data["readBy"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        )
    }

    private suspend fun recordActivity(
        context: Context,
        type: ActivityType,
        space: Space,
        actorId: String,
        actorName: String,
        actorEmoji: String?,
        title: String,
        subtitle: String? = null,
        targetId: String? = null,
        targetType: ActivityTargetType? = null
    ) {
        val firestore = firestoreOrNull(context) ?: return
        val reference = firestore.collection("activity").document()
        val visibleUserIds = resolveVisibleUserIds(context, space)
        if (visibleUserIds.isEmpty()) {
            return
        }
        val payload = mutableMapOf<String, Any>(
            "id" to reference.id,
            "spaceId" to space.id,
            "spaceName" to space.name,
            "spaceEmoji" to space.emoji,
            "actorId" to actorId,
            "actorName" to actorName,
            "type" to type.name.replaceFirstChar { it.lowercase() },
            "title" to title,
            "createdAt" to FieldValue.serverTimestamp(),
            "readBy" to listOf(actorId),
            "visibleTo" to visibleUserIds
        )
        if (!actorEmoji.isNullOrBlank()) payload["actorEmoji"] = actorEmoji
        if (!subtitle.isNullOrBlank()) payload["subtitle"] = subtitle
        if (!targetId.isNullOrBlank()) payload["targetId"] = targetId
        if (targetType != null) payload["targetType"] = targetType.name.replaceFirstChar { it.lowercase() }

        runCatching {
            setData(reference, payload)
            recordNotifications(
                context = context,
                activityType = type,
                space = space,
                actorId = actorId,
                actorName = actorName,
                actorEmoji = actorEmoji,
                title = title,
                subtitle = subtitle,
                targetId = targetId,
                targetType = targetType,
                visibleUserIds = visibleUserIds
            )
        }.onFailure {
            android.util.Log.d("SpaceService", "[Activity] Failed to record activity: ${it.localizedMessage}")
        }
    }

    private suspend fun recordNotifications(
        context: Context,
        activityType: ActivityType,
        space: Space,
        actorId: String,
        actorName: String,
        actorEmoji: String?,
        title: String,
        subtitle: String?,
        targetId: String?,
        targetType: ActivityTargetType?,
        visibleUserIds: List<String>
    ) {
        val firestore = firestoreOrNull(context) ?: return
        val notificationType = notificationTypeFor(activityType) ?: return
        val notificationSubtitle = sanitizedNotificationSubtitle(activityType, subtitle)
        val recipients = visibleUserIds.filter { it != actorId }
        for (recipientId in recipients) {
            val reference = firestore.collection("notifications").document()
            val payload = mutableMapOf<String, Any>(
                "id" to reference.id,
                "recipientId" to recipientId,
                "actorId" to actorId,
                "actorName" to actorName,
                "spaceId" to space.id,
                "spaceName" to space.name,
                "spaceEmoji" to space.emoji,
                "type" to notificationType,
                "title" to title,
                "createdAt" to FieldValue.serverTimestamp(),
                "read" to false,
                "delivered" to false
            )
            if (!actorEmoji.isNullOrBlank()) payload["actorEmoji"] = actorEmoji
            if (!notificationSubtitle.isNullOrBlank()) payload["subtitle"] = notificationSubtitle
            if (!targetId.isNullOrBlank()) payload["targetId"] = targetId
            if (targetType != null) payload["targetType"] = targetType.name.replaceFirstChar { it.lowercase() }
            runCatching { setData(reference, payload) }
        }
    }

    private suspend fun resolveVisibleUserIds(context: Context, space: Space): List<String> {
        val directIds = space.memberIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (directIds.isNotEmpty()) {
            return directIds
        }

        val firestore = firestoreOrNull(context) ?: return emptyList()
        return suspendCancellableCoroutine<QuerySnapshot> { continuation ->
            firestore.collection("spaces").document(space.id).collection("members").get()
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }.documents.mapNotNull { document ->
            ((document.data?.get("userId") as? String) ?: document.id).trim().ifEmpty { null }
        }.distinct().sorted()
    }

    private fun notificationTypeFor(activityType: ActivityType): String? = when (activityType) {
        ActivityType.MessageSent -> "newMessage"
        ActivityType.ReplyAdded -> "reply"
        ActivityType.ReactionAdded -> "reaction"
        ActivityType.PhotoShared -> "photoShared"
        ActivityType.VideoShared -> "videoShared"
        ActivityType.FileUploaded -> "fileUploaded"
        ActivityType.PollCreated -> "pollCreated"
        ActivityType.EventCreated -> "eventCreated"
        ActivityType.EventUpdated -> "eventUpdated"
        ActivityType.MemberJoined -> "memberJoined"
        ActivityType.SpaceCreated, ActivityType.PollVoted -> null
    }

    private fun sanitizedNotificationSubtitle(activityType: ActivityType, subtitle: String?): String? = when (activityType) {
        ActivityType.MessageSent,
        ActivityType.ReplyAdded,
        ActivityType.ReactionAdded,
        ActivityType.PhotoShared,
        ActivityType.VideoShared,
        ActivityType.MemberJoined -> null
        ActivityType.FileUploaded,
        ActivityType.PollCreated,
        ActivityType.EventCreated,
        ActivityType.EventUpdated -> subtitle
        ActivityType.SpaceCreated,
        ActivityType.PollVoted -> null
    }

    private fun mediaTypeFromCategory(category: String?): MediaType {
        return when (category?.trim().orEmpty()) {
            "meme" -> MediaType.Meme
            "gif" -> MediaType.Gif
            "video" -> MediaType.Video
            "file" -> MediaType.File
            "voice" -> MediaType.Voice
            "profilePhoto" -> MediaType.ProfilePhoto
            "coverPhoto" -> MediaType.CoverPhoto
            else -> MediaType.Photo
        }
    }

    private fun mapMessage(snapshot: DocumentSnapshot, currentUserId: String?, spaceKey: ByteArray): SpaceMessage? {
        val data = snapshot.data ?: return null
        val type = when ((data["type"] as? String)?.lowercase()) {
            "image" -> com.arcinteractive.spaces.data.model.MessageType.Image
            "video" -> com.arcinteractive.spaces.data.model.MessageType.Video
            "meme" -> com.arcinteractive.spaces.data.model.MessageType.Meme
            "gif" -> com.arcinteractive.spaces.data.model.MessageType.Gif
            "screenshot" -> com.arcinteractive.spaces.data.model.MessageType.Screenshot
            "file" -> com.arcinteractive.spaces.data.model.MessageType.File
            else -> com.arcinteractive.spaces.data.model.MessageType.Text
        }
        val createdAt = (data["createdAt"] as? Timestamp)?.toDate()
        val senderId = data["senderId"] as? String
        val status = data["status"] as? String
        val isOutgoing = senderId == currentUserId
        val deleted = data["deleted"] as? Boolean ?: false
        val isEdited = data["edited"] as? Boolean ?: false
        val editedAt = (data["editedAt"] as? Timestamp)?.toDate()
        val replyContext = mappedReplyContext(data)
        val encryptionVersion = (data["encryptionVersion"] as? String)?.trim()?.ifEmpty { null } ?: "none"
        if (type == com.arcinteractive.spaces.data.model.MessageType.Image || type == com.arcinteractive.spaces.data.model.MessageType.Video) {
            if (deleted) {
                return SpaceMessage(
                    id = data["id"] as? String ?: snapshot.id,
                    spaceId = data["spaceId"] as? String,
                    senderId = senderId,
                    senderName = data["senderName"] as? String ?: "Member",
                    senderEmoji = (data["senderEmoji"] as? String).orEmpty().ifBlank { null },
                    type = type,
                    encryptionVersion = encryptionVersion,
                    deleted = true,
                    text = null,
                    media = null,
                    createdAt = createdAt,
                    updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
                    timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                    isOutgoing = isOutgoing,
                    status = status,
                    deliveryStatus = deliveryStatus(status, isOutgoing),
                    isEdited = isEdited,
                    editedAt = editedAt,
                    replyContext = replyContext
                )
            }
            val captionCiphertext = (data["captionCiphertextBase64"] as? String)?.trim()?.ifEmpty { null }
            val captionNonce = (data["captionNonceBase64"] as? String)?.trim()?.ifEmpty { null }
            val caption = if (captionCiphertext != null && captionNonce != null) {
                runCatching { encryptionService.decryptText(captionCiphertext, captionNonce, null, spaceKey) }.getOrNull()
            } else {
                null
            }
            val mediaCategory = (data["mediaCategory"] as? String)?.trim()?.ifEmpty { null }
            val resolvedMediaType = data["mediaType"]?.toString()?.let(::mediaTypeFromCategory)
                ?: mediaTypeFromCategory(mediaCategory)
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
            return SpaceMessage(
                id = data["id"] as? String ?: snapshot.id,
                spaceId = data["spaceId"] as? String,
                senderId = senderId,
                senderName = data["senderName"] as? String ?: "Member",
                senderEmoji = (data["senderEmoji"] as? String).orEmpty().ifBlank { null },
                type = type,
                encryptionVersion = encryptionVersion,
                deleted = deleted,
                text = null,
                media = com.arcinteractive.spaces.data.model.SpaceMedia(
                    id = data["id"] as? String ?: snapshot.id,
                    spaceId = data["spaceId"] as? String,
                    type = type,
                    mediaCategory = mediaCategory,
                    mediaType = resolvedMediaType,
                    placeholderIconName = resolvedMediaType.placeholderIconName,
                    caption = caption,
                    senderName = data["senderName"] as? String ?: "Member",
                    timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                    mediaStoragePath = metadata.storagePath,
                    thumbnailStoragePath = metadata.thumbnailStoragePath,
                    mediaNonceBase64 = metadata.nonce,
                    thumbnailNonceBase64 = metadata.thumbnailNonce,
                    metadata = metadata
                ),
                createdAt = createdAt,
                updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
                timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                isOutgoing = isOutgoing,
                status = status,
                deliveryStatus = deliveryStatus(status, isOutgoing),
                isEdited = isEdited,
                editedAt = editedAt,
                replyContext = replyContext
            )
        }
        if (type != com.arcinteractive.spaces.data.model.MessageType.Text) return null
        if (deleted) {
            return SpaceMessage(
                id = data["id"] as? String ?: snapshot.id,
                spaceId = data["spaceId"] as? String,
                senderId = senderId,
                senderName = data["senderName"] as? String ?: "Member",
                senderEmoji = (data["senderEmoji"] as? String).orEmpty().ifBlank { null },
                type = type,
                encryptionVersion = encryptionVersion,
                deleted = true,
                text = null,
                media = null,
                createdAt = createdAt,
                updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
                timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
                isOutgoing = isOutgoing,
                status = status,
                deliveryStatus = deliveryStatus(status, isOutgoing),
                isEdited = isEdited,
                editedAt = editedAt,
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
                    logStoredMessagePayload(
                        messageId = data["id"] as? String ?: snapshot.id,
                        senderId = senderId,
                        encryptionVersion = encryptionVersion,
                        ciphertext = ciphertext,
                        nonce = nonce
                    )
                    runCatching {
                        encryptionService.decryptText(ciphertext, nonce, null, spaceKey)
                    }.getOrElse {
                        android.util.Log.d(
                            "SpaceService",
                            "[DecryptFailure] messageId=${data["id"] as? String ?: snapshot.id} reason=${it.localizedMessage ?: "unknown"}"
                        )
                        "Unable to decrypt message"
                    }
                }
            }
            else -> return null
        }

        return SpaceMessage(
            id = data["id"] as? String ?: snapshot.id,
            spaceId = data["spaceId"] as? String,
            senderId = senderId,
            senderName = data["senderName"] as? String ?: "Member",
            senderEmoji = (data["senderEmoji"] as? String).orEmpty().ifBlank { null },
            type = type,
            encryptionVersion = encryptionVersion,
            deleted = deleted,
            text = resolvedText,
            media = null,
            createdAt = createdAt,
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
            timestamp = messageTimestampFormatter.format(createdAt ?: Date()),
            isOutgoing = isOutgoing,
            status = status,
            deliveryStatus = deliveryStatus(status, isOutgoing),
            isEdited = isEdited,
            editedAt = editedAt,
            replyContext = replyContext
        )
    }

    private fun addReplyContext(replyContext: MessageReplyContext?, messageData: MutableMap<String, Any>) {
        if (replyContext == null) return
        messageData["replyToMessageId"] = replyContext.messageId
        messageData["replyToSenderName"] = replyContext.senderName
        messageData["replyToType"] = replyContext.type
        messageData["replyPreview"] = replyContext.preview
    }

    private fun mappedReplyContext(data: Map<String, Any>): MessageReplyContext? {
        val messageId = (data["replyToMessageId"] as? String)?.trim().orEmpty().ifBlank { return null }
        val senderName = (data["replyToSenderName"] as? String)?.trim().orEmpty().ifBlank { return null }
        val type = (data["replyToType"] as? String)?.trim().orEmpty().ifBlank { return null }
        val preview = (data["replyPreview"] as? String)?.trim().orEmpty().ifBlank { return null }
        return MessageReplyContext(
            messageId = messageId,
            senderName = senderName,
            type = type,
            preview = preview
        )
    }

    private fun deliveryStatus(status: String?, isOutgoing: Boolean): String? {
        return if (isOutgoing) status?.replaceFirstChar { it.titlecase(Locale.getDefault()) } else null
    }

    private suspend fun ensureGeneralEncryptionKey(
        context: Context,
        space: Space
    ): ByteArray {
        return ensureGeneralEncryptionKey(context, space.id)
    }

    private suspend fun ensureGeneralEncryptionKey(
        context: Context,
        spaceId: String
    ): ByteArray {
        encryptionService.cachedSpaceKey(spaceId)?.let { return it }

        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before loading messages.")
        val reference = generalEncryptionKeyReference(spaceId)
        val existingSnapshot = runCatching { getDocument(reference) }.getOrNull()
        val existingKeyBase64 = existingSnapshot?.data?.get("keyBase64") as? String
        if (!existingKeyBase64.isNullOrBlank()) {
            val key = encryptionService.decodeSpaceKey(existingKeyBase64)
            encryptionService.cacheSpaceKey(spaceId, key)
            return key
        }

        val generatedKeyBase64 = encryptionService.generateSpaceKeyBase64()
        runCatching {
            setData(
                reference,
                mapOf(
                    "keyVersion" to generalEncryptionVersion,
                    "keyBase64" to generatedKeyBase64,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "createdBy" to session.uid
                )
            )
        }

        val createdSnapshot = getDocument(reference)
        val createdKeyBase64 = createdSnapshot.data?.get("keyBase64") as? String
            ?: throw IllegalStateException("Unable to load message encryption key.")
        val key = encryptionService.decodeSpaceKey(createdKeyBase64)
        encryptionService.cacheSpaceKey(spaceId, key)
        return key
    }

    private suspend fun ensureSpaceKey(
        context: Context,
        space: Space
    ): ByteArray {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before loading messages.")
        val identity = userProfileService.prepareCurrentDeviceIdentity(context, session)
        val logContext = SecureAccessLogContext(session.uid, space.id, identity.deviceId)
        val reference = memberKeyDeviceReference(space.id, session.uid, identity.deviceId)
        val currentSnapshot = runCatching { getDocument(reference) }.getOrNull()
        val currentExists = currentSnapshot?.exists() == true

        encryptionService.cachedSpaceKey(space.id)?.let { cachedKey ->
            logSecureAccess(
                context = logContext,
                exists = currentExists,
                setupAttempted = !currentExists,
                setupSuccess = currentExists,
                error = null
            )
            if (!currentExists) {
                try {
                    createCurrentDeviceMemberKeyDocument(space, session.uid, identity, cachedKey)
                    logSecureAccess(logContext, exists = false, setupAttempted = true, setupSuccess = true, error = null)
                } catch (error: Exception) {
                    logSecureAccess(logContext, exists = false, setupAttempted = true, setupSuccess = false, error = error)
                    throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
                }
            }
            return cachedKey
        }

        try {
            if (currentExists) {
                val key = decryptSpaceKey(context, requireNotNull(currentSnapshot), session.uid)
                encryptionService.cacheSpaceKey(space.id, key)
                logSecureAccess(logContext, exists = true, setupAttempted = false, setupSuccess = true, error = null)
                return key
            }

            val recoverySnapshots = memberKeyRecoverySnapshots(
                firestore = firestore,
                spaceId = space.id,
                userId = session.uid,
                currentDeviceId = identity.deviceId
            )

            recoverySnapshots.forEach { snapshot ->
                runCatching {
                    val key = decryptSpaceKey(context, snapshot, session.uid)
                    createCurrentDeviceMemberKeyDocument(space, session.uid, identity, key)
                    encryptionService.cacheSpaceKey(space.id, key)
                    logSecureAccess(logContext, exists = false, setupAttempted = true, setupSuccess = true, error = null)
                    return key
                }
            }

            if (session.uid == space.ownerId && recoverySnapshots.isEmpty()) {
                val spaceKey = encryptionService.generateSpaceKey()
                createCurrentDeviceMemberKeyDocument(space, session.uid, identity, spaceKey)
                encryptionService.cacheSpaceKey(space.id, spaceKey)
                logSecureAccess(logContext, exists = false, setupAttempted = true, setupSuccess = true, error = null)
                return spaceKey
            }

            throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
        } catch (error: Exception) {
            logSecureAccess(
                context = logContext,
                exists = currentExists,
                setupAttempted = !currentExists,
                setupSuccess = false,
                error = error
            )
            if (error.message == SECURE_ACCESS_NOT_SET_UP_MESSAGE) {
                throw error
            }
            throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
        }
    }

    private suspend fun shareSpaceKeyIfPossible(
        context: Context,
        space: Space,
        spaceKey: ByteArray
    ) {
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before loading messages.")
        val senderIdentity = userProfileService.prepareCurrentDeviceIdentity(context, session)

        val membersSnapshot = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { continuation ->
            firestore.collection("spaces").document(space.id).collection("members").get()
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        val batch = firestore.batch()
        var didAddWrites = false

        for (memberDocument in membersSnapshot.documents) {
            val userId = memberDocument.data?.get("userId") as? String ?: memberDocument.id
            val identities = userProfileService.fetchEncryptionPublicKeys(context, userId)
            for (identity in identities) {
                val wrappedKey = encryptionService.wrapSpaceKey(spaceKey, identity.publicKey, session.uid)
                val reference = memberKeyDeviceReference(space.id, userId, identity.deviceId)
                batch.set(reference, mapOf(
                    "userId" to userId,
                    "deviceId" to identity.deviceId,
                    "platform" to identity.platform,
                    "publicKey" to identity.publicKey,
                    "encryptedSpaceKeyForDevice" to wrappedKey,
                    "wrappedKey" to wrappedKey,
                    "wrappedByUserId" to session.uid,
                    "wrappedBy" to session.uid,
                    "wrappedByDeviceId" to senderIdentity.deviceId,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "keyVersion" to "v1"
                ))
                didAddWrites = true
            }
        }

        if (didAddWrites) {
            commit(batch)
        }
    }

    private suspend fun syncCurrentDeviceAccessIfPossible(
        context: Context,
        spaces: List<Space>
    ) {
        if (spaces.isEmpty()) return

        spaces.forEach { space ->
            runCatching {
                val spaceKey = ensureSpaceKey(context, space)
                shareSpaceKeyIfPossible(context, space, spaceKey)
            }
        }
    }

    private suspend fun memberKeyRecoverySnapshots(
        firestore: FirebaseFirestore,
        spaceId: String,
        userId: String,
        currentDeviceId: String
    ): List<DocumentSnapshot> {
        val snapshots = mutableListOf<DocumentSnapshot>()

        runCatching {
            getDocuments(
                firestore.collection("spaces")
                    .document(spaceId)
                    .collection("memberKeys")
                    .document(userId)
                    .collection("devices")
            )
        }.getOrNull()?.documents
            ?.filter { it.id != currentDeviceId }
            ?.let(snapshots::addAll)

        runCatching {
            getDocument(
                firestore.collection("spaces")
                    .document(spaceId)
                    .collection("memberKeys")
                    .document(memberKeyDocumentId(userId, currentDeviceId))
            )
        }.getOrNull()?.takeIf { it.exists() }?.let(snapshots::add)

        runCatching {
            getDocument(
                firestore.collection("spaces")
                    .document(spaceId)
                    .collection("memberKeys")
                    .document(userId)
            )
        }.getOrNull()?.takeIf { it.exists() }?.let(snapshots::add)

        return snapshots
    }

    private suspend fun decryptSpaceKey(
        context: Context,
        snapshot: DocumentSnapshot,
        recipientUserId: String
    ): ByteArray {
        val data = snapshot.data ?: throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
        val wrappedKey = (data["encryptedSpaceKeyForDevice"] as? String)
            ?: (data["wrappedKey"] as? String)
            ?: throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
        val wrappedBy = (data["wrappedByUserId"] as? String)
            ?: (data["wrappedBy"] as? String)
            ?: throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
        val wrappedByDeviceId = (data["wrappedByDeviceId"] as? String)?.trim()?.ifEmpty { null }
        val senderPublicKey = userProfileService.fetchEncryptionPublicKey(context, wrappedBy, wrappedByDeviceId)
            ?: throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
        return runCatching {
            encryptionService.unwrapSpaceKey(wrappedKey, senderPublicKey, recipientUserId)
        }.getOrElse {
            throw IllegalStateException(SECURE_ACCESS_NOT_SET_UP_MESSAGE)
        }
    }

    private suspend fun createCurrentDeviceMemberKeyDocument(
        space: Space,
        userId: String,
        identity: com.arcinteractive.spaces.data.auth.DeviceEncryptionIdentity,
        spaceKey: ByteArray
    ) {
        storeMemberKey(
            spaceId = space.id,
            recipientUserId = userId,
            recipientIdentity = identity,
            senderUserId = userId,
            senderDeviceId = identity.deviceId,
            spaceKey = spaceKey
        )
    }

    private fun firestoreOrNull(context: Context): FirebaseFirestore? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    private suspend fun getDocument(reference: DocumentReference): DocumentSnapshot {
        return suspendCancellableCoroutine { continuation ->
            reference.get()
                .addOnSuccessListener { snapshot -> continuation.resume(snapshot) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun getDocuments(query: Query): QuerySnapshot {
        return suspendCancellableCoroutine { continuation ->
            query.get()
                .addOnSuccessListener { snapshot -> continuation.resume(snapshot) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun deleteDocument(reference: DocumentReference) {
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.delete()
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun setData(reference: DocumentReference, data: Map<String, Any>, merge: Boolean = false) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val operation = if (merge) {
                reference.set(data, com.google.firebase.firestore.SetOptions.merge())
            } else {
                reference.set(data)
            }

            operation
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun updateData(reference: DocumentReference, data: Map<String, Any>) {
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.update(data)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun commit(batch: WriteBatch) {
        suspendCancellableCoroutine<Unit> { continuation ->
            batch.commit()
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private fun normalizeInviteCode(code: String): String {
        return code.uppercase().filter { it.isLetterOrDigit() }
    }

    private fun mapReactions(
        documents: List<DocumentSnapshot>,
        currentUserId: String?
    ): List<MessageReaction> {
        val countsByEmoji = linkedMapOf<String, Int>()
        var selectedEmoji: String? = null

        documents.forEach { document ->
            val emoji = (document.data?.get("emoji") as? String)?.trim().orEmpty().ifBlank { return@forEach }
            countsByEmoji[emoji] = (countsByEmoji[emoji] ?: 0) + 1
            val userId = (document.data?.get("userId") as? String)?.trim().orEmpty().ifBlank { document.id }
            if (userId == currentUserId) {
                selectedEmoji = emoji
            }
        }

        val defaultIndices = DEFAULT_REACTION_ORDER.withIndex().associate { it.value to it.index }
        return countsByEmoji.map { (emoji, count) ->
            MessageReaction(
                emoji = emoji,
                count = count,
                isSelectedByCurrentUser = emoji == selectedEmoji
            )
        }.sortedWith(
            compareBy<MessageReaction> { defaultIndices[it.emoji] ?: Int.MAX_VALUE }
                .thenByDescending { it.count }
                .thenBy { it.emoji }
        )
    }

    private fun randomInviteCode(length: Int = 6): String {
        val characters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(length) {
            repeat(length) {
                append(characters.random())
            }
        }
    }

    private fun memberKeyDocumentId(userId: String, deviceId: String): String {
        return if (deviceId == "__legacy__") userId else "${userId}__${deviceId}"
    }

    private fun memberKeyDeviceReference(spaceId: String, userId: String, deviceId: String): DocumentReference {
        return FirebaseFirestore.getInstance()
            .collection("spaces")
            .document(spaceId)
            .collection("memberKeys")
            .document(userId)
            .collection("devices")
            .document(deviceId)
    }

    private fun generalEncryptionKeyReference(spaceId: String): DocumentReference {
        return FirebaseFirestore.getInstance()
            .collection("spaces")
            .document(spaceId)
            .collection("encryption")
            .document("key")
    }

    private suspend fun storeMemberKey(
        spaceId: String,
        recipientUserId: String,
        recipientIdentity: com.arcinteractive.spaces.data.auth.DeviceEncryptionIdentity,
        senderUserId: String,
        senderDeviceId: String,
        spaceKey: ByteArray
    ) {
        val wrappedKey = encryptionService.wrapSpaceKey(spaceKey, recipientIdentity.publicKey, senderUserId)
        val data = mapOf(
            "userId" to recipientUserId,
            "deviceId" to recipientIdentity.deviceId,
            "platform" to recipientIdentity.platform,
            "publicKey" to recipientIdentity.publicKey,
            "encryptedSpaceKeyForDevice" to wrappedKey,
            "wrappedKey" to wrappedKey,
            "wrappedByUserId" to senderUserId,
            "wrappedBy" to senderUserId,
            "wrappedByDeviceId" to senderDeviceId,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "keyVersion" to "v1"
        )
        runCatching {
            setData(
                memberKeyDeviceReference(spaceId, recipientUserId, recipientIdentity.deviceId),
                data,
                merge = true
            )
        }.getOrElse {
            setData(
                FirebaseFirestore.getInstance().collection("spaces").document(spaceId).collection("memberKeys").document(memberKeyDocumentId(recipientUserId, recipientIdentity.deviceId)),
                data,
                merge = true
            )
        }
    }

    private val messageTimestampFormatter: SimpleDateFormat
        get() = SimpleDateFormat("h:mm a", Locale.US)

    private fun logSecureAccess(
        context: SecureAccessLogContext,
        exists: Boolean,
        setupAttempted: Boolean,
        setupSuccess: Boolean,
        error: Throwable?
    ) {
        android.util.Log.d(
            "SpaceService",
            "[SecureAccess] uid=${context.uid} spaceId=${context.spaceId} deviceId=${context.deviceId} " +
                "deviceKeyDocExists=$exists setupAttempted=$setupAttempted setupSuccess=$setupSuccess " +
                "error=${error?.message ?: "none"}"
        )
    }

    private fun runMessageEncryptionSelfTestIfNeeded(spaceId: String, spaceKey: ByteArray) {
        if (verifiedMessageEncryptionSpaceIds.contains(spaceId)) return

        try {
            val plaintext = "hello encryption test"
            val encryptedPayload = encryptionService.encryptText(plaintext, spaceKey)
            val decryptedText = encryptionService.decryptText(
                encryptedPayload.ciphertext,
                encryptedPayload.nonce,
                null,
                spaceKey
            )
            val nonceLengthBytes = android.util.Base64.decode(encryptedPayload.nonce, android.util.Base64.NO_WRAP).size
            val matchesPlaintext = decryptedText == plaintext
            android.util.Log.d(
                "SpaceService",
                "[EncryptionSelfTest] PASS plaintext=$plaintext keyLengthBytes=${spaceKey.size} " +
                    "nonceBase64=${encryptedPayload.nonce} nonceLengthBytes=$nonceLengthBytes " +
                    "ciphertextBase64Length=${encryptedPayload.ciphertext.length} " +
                    "decryptedText=$decryptedText matchesPlaintext=$matchesPlaintext"
            )
            check(matchesPlaintext) { "Local encryption self-test failed." }
            verifiedMessageEncryptionSpaceIds.add(spaceId)
        } catch (error: Exception) {
            android.util.Log.d("SpaceService", "[EncryptionSelfTest] FAIL reason=${error.localizedMessage ?: "unknown"}")
            throw IllegalStateException("Local encryption self-test failed.")
        }
    }

    private fun logStoredMessagePayload(
        messageId: String,
        senderId: String?,
        encryptionVersion: String,
        ciphertext: String,
        nonce: String
    ) {
        val nonceLengthBytes = android.util.Base64.decode(nonce, android.util.Base64.NO_WRAP).size
        android.util.Log.d(
            "SpaceService",
            "[StoredMessage] messageId=$messageId senderId=${senderId ?: "nil"} " +
                "encryptionVersion=$encryptionVersion nonceBase64=$nonce " +
                "nonceLengthBytes=$nonceLengthBytes ciphertextBase64Length=${ciphertext.length}"
        )
    }
}

private data class SecureAccessLogContext(
    val uid: String,
    val spaceId: String,
    val deviceId: String
)

private val SpaceMemberRole.sortOrder: Int
    get() = when (this) {
        SpaceMemberRole.Owner -> 0
        SpaceMemberRole.Admin -> 1
        SpaceMemberRole.Moderator -> 2
        SpaceMemberRole.Member -> 3
        SpaceMemberRole.Guest -> 4
    }

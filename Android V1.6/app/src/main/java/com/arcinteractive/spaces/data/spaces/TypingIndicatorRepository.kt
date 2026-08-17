package com.arcinteractive.spaces.data.spaces

import android.content.Context
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.model.TypingParticipant
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

class TypingIndicatorRepository(
    private val authService: AuthService = AuthService(),
    private val userProfileService: UserProfileService = UserProfileService()
) {
    private data class TypingDocument(
        val userId: String,
        val displayName: String,
        val isTyping: Boolean,
        val lastUpdated: Date
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _participants = MutableStateFlow<List<TypingParticipant>>(emptyList())
    val participants: StateFlow<List<TypingParticipant>> = _participants.asStateFlow()

    private var contextHolder: Context? = null
    private var listener: ListenerRegistration? = null
    private var activeSpaceId: String? = null
    private var currentUserId: String? = null
    private var currentDisplayName: String = "Member"
    private var typingDocuments: Map<String, TypingDocument> = emptyMap()
    private var hasComposerText = false
    private var isPublishingTyping = false
    private var lastPublishedAt: Date? = null
    private var inactivityJob: Job? = null
    private var staleRefreshJob: Job? = null
    private var profileJob: Job? = null
    var onError: ((String) -> Unit)? = null

    fun start(context: Context, spaceId: String) {
        val appContext = context.applicationContext
        contextHolder = appContext
        if (activeSpaceId != null && activeSpaceId != spaceId) {
            publishTypingState(appContext, false)
        }
        if (activeSpaceId == spaceId && listener != null) return

        stopListeningOnly()
        activeSpaceId = spaceId
        currentUserId = authService.currentSession(appContext)?.uid
        loadCurrentProfile(appContext)

        val resolvedFirestore = firestoreOrNull(appContext) ?: return
        listener = resolvedFirestore.collection("spaces")
            .document(spaceId)
            .collection("typing")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val message = "Typing indicator failed to load: ${error.localizedMessage ?: error.message.orEmpty()}"
                    android.util.Log.e("TypingIndicatorRepository", message, error)
                    onError?.invoke(message)
                    return@addSnapshotListener
                }
                val now = Date()
                val documents = snapshot?.documents.orEmpty().mapNotNull { document ->
                    val data = document.data ?: return@mapNotNull null
                    val userId = (data["userId"] as? String)?.trim().orEmpty().ifBlank { document.id }
                    val displayName = (data["displayName"] as? String)?.trim().orEmpty().ifBlank { "Member" }
                    val isTyping = data["isTyping"] as? Boolean ?: false
                    val lastUpdated = (data["lastUpdated"] as? Timestamp)?.toDate() ?: Date(0)
                    TypingDocument(userId, displayName, isTyping, lastUpdated)
                }
                typingDocuments = documents.associateBy { it.userId }
                emitVisibleParticipants(now)
            }

        startStaleRefreshLoop()
    }

    fun updateComposerText(context: Context, text: String) {
        val appContext = context.applicationContext
        contextHolder = appContext
        hasComposerText = text.trim().isNotEmpty()
        if (hasComposerText) {
            publishTypingIfNeeded(appContext, force = !isPublishingTyping)
            scheduleInactiveClear(appContext)
        } else {
            inactivityJob?.cancel()
            inactivityJob = null
            publishTypingState(appContext, false)
        }
    }

    fun messageSent(context: Context) {
        val appContext = context.applicationContext
        contextHolder = appContext
        hasComposerText = false
        inactivityJob?.cancel()
        inactivityJob = null
        publishTypingState(appContext, false)
    }

    fun stop(context: Context? = contextHolder) {
        context?.applicationContext?.let {
            hasComposerText = false
            inactivityJob?.cancel()
            inactivityJob = null
            publishTypingState(it, false)
        }
        stopListeningOnly()
        activeSpaceId = null
        typingDocuments = emptyMap()
        _participants.value = emptyList()
    }

    fun handleAppBackgrounded() {
        contextHolder?.let { publishTypingState(it, false) }
    }

    fun handleAppForegrounded() {
        val context = contextHolder ?: return
        if (hasComposerText) {
            publishTypingIfNeeded(context, force = true)
            scheduleInactiveClear(context)
        }
    }

    fun release() {
        stop()
        profileJob?.cancel()
        staleRefreshJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun loadCurrentProfile(context: Context) {
        profileJob?.cancel()
        profileJob = scope.launch {
            val session = authService.currentSession(context) ?: return@launch
            currentUserId = session.uid
            currentDisplayName = session.displayName.trim().ifBlank { "Member" }
            val profile = runCatching { userProfileService.fetchUserProfile(context, session.uid) }.getOrNull()
            val profileName = profile?.displayName?.trim().orEmpty()
            if (profileName.isNotEmpty()) {
                currentDisplayName = profileName
            }
        }
    }

    private fun publishTypingIfNeeded(context: Context, force: Boolean) {
        if (!hasComposerText) return
        if (force) {
            publishTypingState(context, true)
            return
        }
        val lastPublishedAt = lastPublishedAt
        if (lastPublishedAt != null && Date().time - lastPublishedAt.time < REFRESH_WRITE_INTERVAL_MS) {
            return
        }
        publishTypingState(context, true)
    }

    private fun publishTypingState(context: Context, isTyping: Boolean) {
        val resolvedFirestore = firestoreOrNull(context) ?: return
        val spaceId = activeSpaceId ?: return
        val userId = currentUserId ?: authService.currentSession(context)?.uid ?: return
        currentUserId = userId
        isPublishingTyping = isTyping
        lastPublishedAt = Date()

        resolvedFirestore.collection("spaces")
            .document(spaceId)
            .collection("typing")
            .document(userId)
            .set(
                mapOf(
                    "userId" to userId,
                    "displayName" to currentDisplayName,
                    "isTyping" to isTyping,
                    "lastUpdated" to FieldValue.serverTimestamp()
                )
            )
            .addOnFailureListener { error ->
                val message = "Typing indicator failed to update: ${error.localizedMessage ?: error.message.orEmpty()}"
                android.util.Log.e("TypingIndicatorRepository", message, error)
                onError?.invoke(message)
            }
    }

    private fun scheduleInactiveClear(context: Context) {
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            delay(INACTIVITY_MS)
            hasComposerText = false
            publishTypingState(context, false)
        }
    }

    private fun startStaleRefreshLoop() {
        staleRefreshJob?.cancel()
        staleRefreshJob = scope.launch {
            while (true) {
                delay(STALE_REFRESH_MS)
                emitVisibleParticipants(Date())
            }
        }
    }

    private fun emitVisibleParticipants(now: Date) {
        val selfId = currentUserId
        _participants.value = typingDocuments.values
            .filter { document ->
                document.isTyping &&
                    document.userId != selfId &&
                    now.time - document.lastUpdated.time <= STALE_MS
            }
            .sortedBy { it.displayName.lowercase() }
            .map { document ->
                TypingParticipant(
                    id = document.userId,
                    displayName = document.displayName,
                    isTyping = document.isTyping,
                    lastUpdated = document.lastUpdated
                )
            }
    }

    private fun stopListeningOnly() {
        listener?.remove()
        listener = null
        staleRefreshJob?.cancel()
        staleRefreshJob = null
    }

    private fun firestoreOrNull(context: Context): FirebaseFirestore? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return FirebaseFirestore.getInstance()
    }

    private companion object {
        const val INACTIVITY_MS = 5_000L
        const val STALE_MS = 10_000L
        const val STALE_REFRESH_MS = 1_000L
        const val REFRESH_WRITE_INTERVAL_MS = 3_000L
    }
}

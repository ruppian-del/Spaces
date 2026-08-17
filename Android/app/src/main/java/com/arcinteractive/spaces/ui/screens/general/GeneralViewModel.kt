package com.arcinteractive.spaces.ui.screens.general

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.model.LinkPreviewData
import com.arcinteractive.spaces.data.model.LocalMessageDeliveryState
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.data.model.TypingParticipant
import com.arcinteractive.spaces.data.spaces.QueuedMediaSelection
import com.arcinteractive.spaces.data.spaces.LinkPreviewService
import com.arcinteractive.spaces.data.spaces.SpaceDraftRecord
import com.arcinteractive.spaces.data.spaces.SpaceDraftStore
import com.arcinteractive.spaces.data.spaces.SpaceMessageOutbox
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.arcinteractive.spaces.data.spaces.TypingIndicatorRepository
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ComposerMediaSelection(
    val id: String = UUID.randomUUID().toString(),
    val mediaBytes: ByteArray,
    val previewBytes: ByteArray,
    val mimeType: String,
    val mediaCategory: String,
    val isVideo: Boolean
)

data class GeneralUiState(
    val messages: List<SpaceMessage> = emptyList(),
    val composerText: String = "",
    val selectedMedia: SpaceMedia? = null,
    val selectedComposerMediaItems: List<ComposerMediaSelection> = emptyList(),
    val secureAccessMessage: String? = null,
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val deletingMessageIds: Set<String> = emptySet(),
    val lastErrorMessage: String? = null,
    val replyingToMessage: SpaceMessage? = null,
    val editingMessage: SpaceMessage? = null,
    val isSearchPresented: Boolean = false,
    val searchText: String = "",
    val searchMatchMessageIds: List<String> = emptyList(),
    val selectedSearchMatchIndex: Int? = null,
    val canPostMessages: Boolean = false,
    val canUploadMedia: Boolean = false,
    val canDeleteOthersContent: Boolean = false,
    val typingParticipants: List<TypingParticipant> = emptyList(),
    val hasSavedDraft: Boolean = false,
    val composerLinkPreview: LinkPreviewData? = null,
    val composerSpaceLinks: List<SpaceLinkAttachment> = emptyList(),
    val isLoadingLinkPreview: Boolean = false
) {
    val canSend: Boolean = when {
        isSending -> false
        selectedComposerMediaItems.isNotEmpty() -> canUploadMedia
        composerText.isNotBlank() || composerSpaceLinks.isNotEmpty() -> canPostMessages
        else -> false
    }

    val typingIndicatorText: String? = when (typingParticipants.size) {
        0 -> null
        1 -> "${typingParticipants[0].displayName} is typing…"
        2 -> "${typingParticipants[0].displayName} and ${typingParticipants[1].displayName} are typing…"
        else -> "${typingParticipants.size} people are typing…"
    }
}

class GeneralViewModel(
    space: Space,
    private val spaceService: SpaceService = SpaceService(),
    private val typingIndicatorRepository: TypingIndicatorRepository = TypingIndicatorRepository(),
    private val outbox: SpaceMessageOutbox = SpaceMessageOutbox(spaceService = spaceService),
    private val draftStore: SpaceDraftStore = SpaceDraftStore(),
    private val linkPreviewService: LinkPreviewService = LinkPreviewService()
) : ViewModel() {
    val space: Space = space
    private var listener: ListenerRegistration? = null
    private val reactionListeners = mutableMapOf<String, ListenerRegistration>()
    private var currentUserId: String? = null
    private val localPlaintextByMessageId = mutableMapOf<String, String>()
    private val pendingLocalMessagesById = mutableMapOf<String, SpaceMessage>()
    private var baseMessages: List<SpaceMessage> = emptyList()
    private val reactionsByMessageId = mutableMapOf<String, List<MessageReaction>>()
    private var typingParticipantsJob: Job? = null
    private var draftSaveJob: Job? = null
    private var activeMediaSubmissionId: String? = null
    private var pendingDraftReplyContext: MessageReplyContext? = null
    private var draftRestoreAttempted = false
    private var boundContext: Context? = null
    private var previewFetchJob: Job? = null

    private val _uiState = MutableStateFlow(GeneralUiState())
    val uiState: StateFlow<GeneralUiState> = _uiState.asStateFlow()

    init {
        typingIndicatorRepository.onError = { message ->
            _uiState.update { it.copy(lastErrorMessage = message) }
        }
        outbox.onSendSucceeded = { queuedMessage, localMessage ->
            queuedMessage.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                localPlaintextByMessageId[localMessage.id] = text
            }
            pendingLocalMessagesById[localMessage.id] = localMessage
            if (activeMediaSubmissionId == queuedMessage.id) {
                activeMediaSubmissionId = null
                _uiState.update {
                    it.copy(
                        composerText = "",
                        selectedComposerMediaItems = emptyList(),
                        replyingToMessage = null,
                        isSending = false,
                        composerLinkPreview = null,
                        composerSpaceLinks = emptyList(),
                        isLoadingLinkPreview = false
                    )
                }
                pendingDraftReplyContext = null
            }
            clearDraftIfSubmitted(queuedMessage.id)
            refreshMessages(baseMessages)
        }
    }

    fun loadMessagesIfNeeded(context: Context) {
        if (listener != null) return
        bindTypingContext(context)
        outbox.start(context)
        currentUserId = spaceService.currentUserId(context)
        restoreDraftIfNeeded(context)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    canPostMessages = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.PostPings),
                    canUploadMedia = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.UploadPhotosVideos),
                    canDeleteOthersContent = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.DeleteOthersContent)
                )
            }
        }
        typingIndicatorRepository.start(context, space.id)
        typingParticipantsJob?.cancel()
        typingParticipantsJob = viewModelScope.launch {
            typingIndicatorRepository.participants.collect { participants ->
                _uiState.update { it.copy(typingParticipants = participants) }
            }
        }

        _uiState.update { it.copy(isLoading = true, lastErrorMessage = null) }
        listener = spaceService.listenToMessages(
            context = context,
            space = space,
            listenerKey = "general.${space.id}.messages"
        ) { result ->
            result.onSuccess { messages ->
                val mergedMessages = mergeIncomingMessages(messages)
                baseMessages = mergedMessages
                syncReactionListeners(context, mergedMessages)
                _uiState.update {
                    it.copy(
                        messages = applyReactions(mergedMessages),
                        secureAccessMessage = null,
                        isLoading = false
                    )
                }
                resolvePendingDraftReplyContext(context)
                refreshSearchMatches()
            }.onFailure { error ->
                _uiState.update {
                    val localizedMessage = error.localizedMessage ?: "Unable to load messages for this Space."
                    it.copy(
                        messages = it.messages,
                        secureAccessMessage = null,
                        isLoading = false,
                        lastErrorMessage = localizedMessage
                    )
                }
                resolvePendingDraftReplyContext(context)
            }
        }
    }

    fun updateComposerText(text: String) {
        _uiState.update { it.copy(composerText = text) }
        contextForTypingUpdates()?.let { typingIndicatorRepository.updateComposerText(it, text) }
        scheduleLinkPreviewFetch(text)
        scheduleDraftPersistence()
    }

    fun presentSearch() {
        _uiState.update { it.copy(isSearchPresented = true) }
    }

    fun dismissSearch() {
        _uiState.update {
            it.copy(
                isSearchPresented = false,
                searchText = "",
                searchMatchMessageIds = emptyList(),
                selectedSearchMatchIndex = null
            )
        }
    }

    fun updateSearchText(text: String) {
        val trimmedQuery = text.trim()
        val currentSelectedId = currentSearchMatchMessageId()
        if (trimmedQuery.isEmpty()) {
            _uiState.update {
                it.copy(
                    searchText = text,
                    searchMatchMessageIds = emptyList(),
                    selectedSearchMatchIndex = null
                )
            }
            return
        }

        val matches = _uiState.value.messages
            .filter { !it.deleted && searchableContent(it).contains(trimmedQuery, ignoreCase = true) }
            .map { it.id }

        val selectedIndex = when {
            currentSelectedId != null && matches.contains(currentSelectedId) -> matches.indexOf(currentSelectedId)
            matches.isEmpty() -> null
            else -> 0
        }

        _uiState.update {
            it.copy(
                searchText = text,
                searchMatchMessageIds = matches,
                selectedSearchMatchIndex = selectedIndex
            )
        }
    }

    fun selectNextSearchMatch() {
        val matches = _uiState.value.searchMatchMessageIds
        if (matches.isEmpty()) return
        val nextIndex = ((_uiState.value.selectedSearchMatchIndex ?: -1) + 1) % matches.size
        _uiState.update { it.copy(selectedSearchMatchIndex = nextIndex) }
    }

    fun selectPreviousSearchMatch() {
        val matches = _uiState.value.searchMatchMessageIds
        if (matches.isEmpty()) return
        val previousIndex = ((_uiState.value.selectedSearchMatchIndex ?: matches.size) - 1 + matches.size) % matches.size
        _uiState.update { it.copy(selectedSearchMatchIndex = previousIndex) }
    }

    fun currentSearchMatchMessageId(): String? {
        val state = _uiState.value
        val index = state.selectedSearchMatchIndex ?: return null
        return state.searchMatchMessageIds.getOrNull(index)
    }

    fun sendMessage(context: Context) {
        val trimmed = _uiState.value.composerText.trim()
        if (trimmed.isEmpty() && _uiState.value.composerSpaceLinks.isEmpty()) return
        val queuedId = outbox.enqueueText(
            context = context,
            space = space,
            text = trimmed,
            linkPreview = _uiState.value.composerLinkPreview,
            spaceLinks = _uiState.value.composerSpaceLinks,
            replyContext = activeReplyContext()
        )
        localPlaintextByMessageId[queuedId] = trimmed
        markDraftAsSubmitted(queuedId, _uiState.value.composerText)
        _uiState.update {
            it.copy(
                composerText = "",
                replyingToMessage = null,
                composerLinkPreview = null,
                composerSpaceLinks = emptyList(),
                isLoadingLinkPreview = false
            )
        }
        pendingDraftReplyContext = null
        typingIndicatorRepository.messageSent(context)
        refreshMessages(baseMessages)
    }

    fun selectComposerMedia(
        mediaBytes: ByteArray?,
        previewBytes: ByteArray?,
        mimeType: String?,
        mediaCategory: String,
        isVideo: Boolean
    ) {
        Log.d(
            "GeneralViewModel",
            "[ComposerMedia] selected=${mediaBytes != null} byteCount=${mediaBytes?.size ?: 0} mediaCategory=$mediaCategory isVideo=$isVideo"
        )
        _uiState.update {
            it.copy(
                editingMessage = null,
                composerLinkPreview = null,
                composerSpaceLinks = emptyList(),
                isLoadingLinkPreview = false,
                selectedComposerMediaItems = listOf(
                    ComposerMediaSelection(
                        mediaBytes = mediaBytes ?: ByteArray(0),
                        previewBytes = previewBytes ?: ByteArray(0),
                        mimeType = mimeType ?: if (isVideo) "video/mp4" else "image/jpeg",
                        mediaCategory = mediaCategory,
                        isVideo = isVideo
                    )
                ).filter { selection -> selection.mediaBytes.isNotEmpty() && selection.previewBytes.isNotEmpty() }
            )
        }
        previewFetchJob?.cancel()
        scheduleDraftPersistence()
    }

    fun selectComposerMediaItems(selections: List<ComposerMediaSelection>) {
        _uiState.update {
            it.copy(
                editingMessage = null,
                composerLinkPreview = null,
                composerSpaceLinks = emptyList(),
                isLoadingLinkPreview = false,
                selectedComposerMediaItems = selections.take(10)
            )
        }
        previewFetchJob?.cancel()
        scheduleDraftPersistence()
    }

    fun removeComposerMedia(id: String? = null) {
        Log.d("GeneralViewModel", "[ComposerMedia] selected=false byteCount=0 removed=true")
        _uiState.update {
            it.copy(
                selectedComposerMediaItems = if (id == null) {
                    emptyList()
                } else {
                    it.selectedComposerMediaItems.filterNot { selection -> selection.id == id }
                }
            )
        }
        if (_uiState.value.selectedComposerMediaItems.isEmpty()) {
            scheduleLinkPreviewFetch(_uiState.value.composerText)
        }
        scheduleDraftPersistence()
    }

    fun beginReply(message: SpaceMessage) {
        if (message.deleted) return
        _uiState.update { it.copy(replyingToMessage = message, editingMessage = null) }
        scheduleDraftPersistence()
    }

    fun cancelReply() {
        _uiState.update { it.copy(replyingToMessage = null) }
        pendingDraftReplyContext = null
        scheduleDraftPersistence()
    }

    fun beginEditing(message: SpaceMessage) {
        if (!canEdit(message)) return
        _uiState.update {
            it.copy(
                composerText = message.text.orEmpty(),
                replyingToMessage = null,
                editingMessage = message,
                selectedComposerMediaItems = emptyList(),
                composerLinkPreview = message.linkPreview,
                composerSpaceLinks = message.spaceLinks,
                isLoadingLinkPreview = false
            )
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessage = null, composerText = "") }
        previewFetchJob?.cancel()
        contextForTypingUpdates()?.let { typingIndicatorRepository.updateComposerText(it, "") }
        boundContext?.let { restoreDraftIfNeeded(it, requireReload = true) }
    }

    fun sendComposer(context: Context) {
        if (_uiState.value.isSending) return

        val editingMessage = _uiState.value.editingMessage
        if (editingMessage != null) {
            saveEditedMessage(context, editingMessage)
            return
        }

        val selectedMediaItems = _uiState.value.selectedComposerMediaItems
        if (selectedMediaItems.isNotEmpty()) {
            val caption = _uiState.value.composerText.trim().ifBlank { null }
            if (selectedMediaItems.size == 1 && selectedMediaItems.first().isVideo) {
                sendVideo(
                    context = context,
                    videoBytes = selectedMediaItems.first().mediaBytes,
                    caption = caption,
                    mimeType = selectedMediaItems.first().mimeType
                )
            } else {
                sendImages(
                    context = context,
                    selections = selectedMediaItems,
                    caption = caption
                )
            }
            return
        }

        if (_uiState.value.isLoadingLinkPreview) {
            scheduleLinkPreviewFetch(_uiState.value.composerText, immediate = true) {
                sendMessage(context)
            }
            return
        }

        sendMessage(context)
    }

    fun sendImages(
        context: Context,
        selections: List<ComposerMediaSelection>,
        caption: String?
    ) {
        if (_uiState.value.isSending || selections.isEmpty()) return

        activeMediaSubmissionId = outbox.enqueueMedia(
            context = context,
            space = space,
            attachments = selections.map {
                QueuedMediaSelection(
                    mediaBytes = it.mediaBytes,
                    previewBytes = it.previewBytes,
                    mimeType = it.mimeType,
                    mediaCategory = it.mediaCategory,
                    isVideo = it.isVideo
                )
            },
            caption = caption,
            replyContext = activeReplyContext()
        )
        markDraftAsSubmitted(activeMediaSubmissionId, _uiState.value.composerText)
        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        refreshMessages(baseMessages)
    }

    fun sendVideo(
        context: Context,
        videoBytes: ByteArray,
        caption: String?,
        mimeType: String
    ) {
        if (_uiState.value.isSending) return

        val previewBytes = _uiState.value.selectedComposerMediaItems.firstOrNull()?.previewBytes ?: videoBytes
        activeMediaSubmissionId = outbox.enqueueMedia(
            context = context,
            space = space,
            attachments = listOf(
                QueuedMediaSelection(
                    mediaBytes = videoBytes,
                    previewBytes = previewBytes,
                    mimeType = mimeType,
                    mediaCategory = "video",
                    isVideo = true
                )
            ),
            caption = caption,
            replyContext = activeReplyContext()
        )
        markDraftAsSubmitted(activeMediaSubmissionId, _uiState.value.composerText)
        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        refreshMessages(baseMessages)
    }

    fun openMedia(media: SpaceMedia) {
        _uiState.update { it.copy(selectedMedia = media) }
    }

    fun dismissMedia() {
        _uiState.update { it.copy(selectedMedia = null) }
    }

    fun canDelete(message: SpaceMessage): Boolean {
        return !message.deleted && (
            message.senderId == currentUserId || _uiState.value.canDeleteOthersContent
        )
    }

    fun canEdit(message: SpaceMessage): Boolean {
        return message.senderId == currentUserId &&
            !message.deleted &&
            message.type == MessageType.Text &&
            !message.hasMediaAttachments
    }

    fun isDeleting(message: SpaceMessage): Boolean {
        return _uiState.value.deletingMessageIds.contains(message.id)
    }

    fun deleteMessage(context: Context, message: SpaceMessage) {
        if (!canDelete(message) || isDeleting(message)) return

        _uiState.update {
            it.copy(
                deletingMessageIds = it.deletingMessageIds + message.id,
                lastErrorMessage = null
            )
        }
        viewModelScope.launch {
            runCatching {
                spaceService.deleteMessage(context, space, message.id)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastErrorMessage = error.localizedMessage ?: "Unable to delete this message."
                    )
                }
            }

            _uiState.update {
                it.copy(deletingMessageIds = it.deletingMessageIds - message.id)
            }
        }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    fun reactionOptions(message: SpaceMessage): List<String> {
        if (message.deleted) return emptyList()
        return listOf("👍", "❤️", "😂", "😮", "😢", "👎")
    }

    fun toggleReaction(context: Context, message: SpaceMessage, emoji: String) {
        viewModelScope.launch {
            runCatching {
                spaceService.toggleReaction(context, space, message.id, emoji)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to update this reaction.")
                }
            }
        }
    }

    fun selectedComposerBitmaps() = _uiState.value.selectedComposerMediaItems.mapNotNull {
        BitmapFactory.decodeByteArray(it.previewBytes, 0, it.previewBytes.size)
    }

    fun retryQueuedMessage(context: Context, messageId: String) {
        outbox.retry(context, messageId)
        activeMediaSubmissionId = messageId
        _uiState.update { it.copy(isSending = true) }
        refreshMessages(baseMessages)
    }

    fun deleteQueuedMessage(context: Context, messageId: String) {
        outbox.delete(context, messageId)
        localPlaintextByMessageId.remove(messageId)
        pendingLocalMessagesById.remove(messageId)
        clearDraftIfSubmitted(messageId)
        if (activeMediaSubmissionId == messageId) {
            activeMediaSubmissionId = null
            _uiState.update { it.copy(isSending = false) }
        }
        refreshMessages(baseMessages)
    }

    fun stopTypingIndicators(context: Context) {
        flushDraftPersistence()
        typingIndicatorRepository.stop(context)
        _uiState.update { it.copy(typingParticipants = emptyList()) }
    }

    fun handleAppBackgrounded(context: Context) {
        flushDraftPersistence()
        typingIndicatorRepository.handleAppBackgrounded()
    }

    fun handleAppForegrounded(context: Context) {
        bindTypingContext(context)
        typingIndicatorRepository.handleAppForegrounded()
        restoreDraftIfNeeded(context, requireReload = true)
    }

    fun bindTypingContext(context: Context) {
        boundContext = context.applicationContext
        typingIndicatorRepositoryContext = context.applicationContext
    }

    fun discardDraft() {
        val context = boundContext ?: return
        draftSaveJob?.cancel()
        previewFetchJob?.cancel()
        pendingDraftReplyContext = null
        _uiState.update {
            it.copy(
                composerText = "",
                replyingToMessage = null,
                selectedComposerMediaItems = emptyList(),
                hasSavedDraft = false,
                composerLinkPreview = null,
                composerSpaceLinks = emptyList(),
                isLoadingLinkPreview = false
            )
        }
        currentUserId?.let { draftStore.clearDraft(context, it, space.id) }
        typingIndicatorRepository.updateComposerText(context, "")
    }

    fun flushDraftPersistence() {
        val context = boundContext ?: return
        draftSaveJob?.cancel()
        persistDraftNow(context)
    }

    override fun onCleared() {
        flushDraftPersistence()
        draftSaveJob?.cancel()
        previewFetchJob?.cancel()
        typingParticipantsJob?.cancel()
        typingIndicatorRepository.release()
        listener?.remove()
        listener = null
        reactionListeners.values.forEach { it.remove() }
        reactionListeners.clear()
        super.onCleared()
    }

    private fun refreshMessages(incoming: List<SpaceMessage>) {
        val merged = mergeIncomingMessages(incoming)
        baseMessages = merged
        _uiState.update { it.copy(messages = applyReactions(merged)) }
        boundContext?.let { resolvePendingDraftReplyContext(it) }
        refreshSearchMatches()
    }

    private fun mergeIncomingMessages(incoming: List<SpaceMessage>): List<SpaceMessage> {
        val mergedIncoming = incoming.map { message ->
            val localPlaintextFallback = if (message.senderId == currentUserId) {
                localPlaintextByMessageId[message.id]
            } else {
                null
            }
            val usedLocalPlaintextFallback = !localPlaintextFallback.isNullOrBlank()
            logMessageRendering(
                messageId = message.id,
                senderId = message.senderId,
                encryptionVersion = message.encryptionVersion,
                hasCiphertext = message.encryptionVersion == "aes-gcm-v1",
                hasNonce = message.encryptionVersion == "aes-gcm-v1",
                usedLocalPlaintextFallback = usedLocalPlaintextFallback
            )
            if (!usedLocalPlaintextFallback) {
                message.copy(reactions = reactionsByMessageId[message.id] ?: message.reactions)
            } else {
                message.copy(
                    text = localPlaintextFallback,
                    linkPreview = message.linkPreview,
                    reactions = reactionsByMessageId[message.id] ?: message.reactions
                )
            }
        }

        val incomingIds = mergedIncoming.map { it.id }.toSet()
        pendingLocalMessagesById.keys.removeAll(incomingIds)
        val pendingMessages = pendingLocalMessagesById.values
        val queuedMessages = outbox.itemsForSpace(space.id).map { projectQueuedMessage(it) }

        return (mergedIncoming + pendingMessages + queuedMessages).sortedWith { left, right ->
            val leftCreatedAt = left.createdAt
            val rightCreatedAt = right.createdAt
            when {
                leftCreatedAt != null && rightCreatedAt != null -> leftCreatedAt.compareTo(rightCreatedAt)
                leftCreatedAt != null -> -1
                rightCreatedAt != null -> 1
                else -> left.id.compareTo(right.id)
            }
        }
    }

    private fun projectQueuedMessage(queuedMessage: com.arcinteractive.spaces.data.spaces.QueuedSpaceMessage): SpaceMessage {
        val deliveryStatus = when (queuedMessage.state) {
            LocalMessageDeliveryState.Sending -> "Sending…"
            LocalMessageDeliveryState.Uploading -> "Uploading…"
            LocalMessageDeliveryState.WaitingForConnection -> "Waiting for connection…"
            LocalMessageDeliveryState.Failed -> if (queuedMessage.kind == "media") "Upload failed" else "Failed to send"
        }
        val mediaItems = queuedMessage.attachments.mapIndexed { index, attachment ->
            val previewFile = java.io.File(contextForOutboxFiles(), "space_outbox/attachments/${attachment.previewFileName}")
            val previewBytes = if (previewFile.exists()) previewFile.readBytes() else null
            SpaceMedia(
                id = "${queuedMessage.id}_$index",
                spaceId = space.id,
                type = if (attachment.isVideo) MessageType.Video else if (attachment.mediaCategory.equals("gif", true)) MessageType.Gif else MessageType.Image,
                mediaCategory = attachment.mediaCategory,
                mediaType = when (attachment.mediaCategory.lowercase()) {
                    "gif" -> com.arcinteractive.spaces.data.model.MediaType.Gif
                    "meme" -> com.arcinteractive.spaces.data.model.MediaType.Meme
                    "video" -> com.arcinteractive.spaces.data.model.MediaType.Video
                    else -> com.arcinteractive.spaces.data.model.MediaType.Photo
                },
                placeholderIconName = when (attachment.mediaCategory.lowercase()) {
                    "gif" -> com.arcinteractive.spaces.data.model.MediaType.Gif.placeholderIconName
                    "meme" -> com.arcinteractive.spaces.data.model.MediaType.Meme.placeholderIconName
                    "video" -> com.arcinteractive.spaces.data.model.MediaType.Video.placeholderIconName
                    else -> com.arcinteractive.spaces.data.model.MediaType.Photo.placeholderIconName
                },
                caption = queuedMessage.caption,
                senderName = "You",
                timestamp = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(queuedMessage.createdAt)),
                localPreviewBytes = previewBytes
            )
        }
        return SpaceMessage(
            id = queuedMessage.id,
            spaceId = space.id,
            senderId = currentUserId,
            senderName = "You",
            type = if (queuedMessage.kind == "text") MessageType.Text else mediaItems.firstOrNull()?.type ?: MessageType.Image,
            encryptionVersion = "local-only",
            deleted = false,
            text = queuedMessage.text,
            linkPreview = queuedMessage.linkPreview,
            spaceLinks = queuedMessage.spaceLinks,
            media = mediaItems.firstOrNull(),
            mediaItems = mediaItems,
            createdAt = Date(queuedMessage.createdAt),
            updatedAt = Date(queuedMessage.createdAt),
            timestamp = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(queuedMessage.createdAt)),
            isOutgoing = true,
            deliveryStatus = deliveryStatus,
            replyContext = queuedMessage.replyContext,
            localDeliveryState = queuedMessage.state,
            localFailureMessage = queuedMessage.failureMessage
        )
    }

    private fun contextForOutboxFiles(): java.io.File {
        return typingIndicatorRepositoryContext?.filesDir ?: throw IllegalStateException("Typing context not bound.")
    }

    private fun saveEditedMessage(context: Context, message: SpaceMessage) {
        val trimmed = _uiState.value.composerText.trim()
        if ((trimmed.isEmpty() && _uiState.value.composerSpaceLinks.isEmpty()) || !canEdit(message) || _uiState.value.isSending) return

        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.editTextMessage(
                    context = context,
                    space = space,
                    messageId = message.id,
                    newText = trimmed,
                    linkPreview = _uiState.value.composerLinkPreview,
                    spaceLinks = _uiState.value.composerSpaceLinks
                )
            }.onSuccess { updatedMessage ->
                localPlaintextByMessageId[message.id] = trimmed
                baseMessages = baseMessages.map { existing ->
                    if (existing.id == message.id) updatedMessage else existing
                }
                _uiState.update {
                    it.copy(
                        messages = applyReactions(baseMessages),
                        composerText = "",
                        editingMessage = null,
                        isSending = false,
                        composerLinkPreview = null,
                        composerSpaceLinks = emptyList(),
                        isLoadingLinkPreview = false
                    )
                }
                typingIndicatorRepository.messageSent(context)
                restoreDraftIfNeeded(context, requireReload = true)
                refreshSearchMatches()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to save changes to this message."
                    )
                }
            }
        }
    }

    private fun activeReplyContext(): MessageReplyContext? {
        return currentDraftReplyContext()
    }

    private fun currentDraftReplyContext(): MessageReplyContext? {
        val replyingToMessage = _uiState.value.replyingToMessage
        return if (replyingToMessage != null) {
            MessageReplyContext(
                messageId = replyingToMessage.id,
                senderName = replyingToMessage.senderName,
                type = replyType(replyingToMessage),
                preview = replyPreview(replyingToMessage)
            )
        } else {
            pendingDraftReplyContext
        }
    }

    private fun replyType(message: SpaceMessage): String {
        return when (message.type) {
            MessageType.Video -> "video"
            MessageType.File -> "file"
            MessageType.Image,
            MessageType.Meme,
            MessageType.Gif,
            MessageType.Screenshot -> "image"
            else -> "text"
        }
    }

    private fun replyPreview(message: SpaceMessage): String {
        if (message.deleted) return "Original message unavailable"
        return when (replyType(message)) {
            "video" -> "\uD83C\uDFA5 Video"
            "file" -> "\uD83D\uDCC4 File"
            "image" -> "\uD83D\uDCF7 Photo"
            else -> message.text?.trim().orEmpty().ifBlank { "Message" }.take(80)
        }
    }

    private fun searchableContent(message: SpaceMessage): String {
        return listOf(
            message.senderName,
            message.text.orEmpty(),
            message.replyContext?.preview.orEmpty(),
            message.spaceLinks.joinToString("\n") { it.title + "\n" + (it.subtitle ?: "") }
        ).joinToString("\n")
    }

    private fun refreshSearchMatches() {
        val query = _uiState.value.searchText
        if (query.isBlank()) return
        updateSearchText(query)
    }

    private fun syncReactionListeners(context: Context, messages: List<SpaceMessage>) {
        val validIds = messages.map { it.id }.toSet()

        reactionListeners.keys.filter { it !in validIds }.forEach { staleId ->
            reactionListeners.remove(staleId)?.remove()
            reactionsByMessageId.remove(staleId)
        }

        messages.forEach { message ->
            if (reactionListeners[message.id] == null) {
                val registration = spaceService.listenToReactions(
                    context = context,
                    space = space,
                    messageId = message.id,
                    listenerKey = "general.${space.id}.reactions.${message.id}"
                ) { result ->
                    result.onSuccess { reactions ->
                        reactionsByMessageId[message.id] = reactions
                        _uiState.update { state ->
                            state.copy(messages = applyReactions(baseMessages))
                        }
                    }.onFailure { error ->
                        _uiState.update {
                            it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to load reactions.")
                        }
                    }
                }
                if (registration != null) {
                    reactionListeners[message.id] = registration
                }
            }
        }
    }

    private fun applyReactions(messages: List<SpaceMessage>): List<SpaceMessage> {
        return messages.map { message ->
            message.copy(reactions = reactionsByMessageId[message.id] ?: message.reactions)
        }
    }

    private fun logMessageRendering(
        messageId: String,
        senderId: String?,
        encryptionVersion: String,
        hasCiphertext: Boolean,
        hasNonce: Boolean,
        usedLocalPlaintextFallback: Boolean
    ) {
        Log.d(
            "GeneralViewModel",
            "[Render] messageId=$messageId senderId=${senderId ?: "nil"} currentUserUid=${currentUserId ?: "nil"} " +
                "encryptionVersion=$encryptionVersion hasCiphertext=$hasCiphertext hasNonce=$hasNonce " +
                "usedLocalPlaintextFallback=$usedLocalPlaintextFallback"
        )
    }

    private fun scheduleLinkPreviewFetch(
        text: String,
        immediate: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        previewFetchJob?.cancel()

        if (_uiState.value.selectedComposerMediaItems.isNotEmpty()) {
            _uiState.update { it.copy(composerLinkPreview = null, isLoadingLinkPreview = false) }
            return
        }

        val context = boundContext ?: return
        val trimmed = text.trim()
        val url = linkPreviewService.firstUrl(text)
        if (trimmed.isEmpty() || url == null) {
            _uiState.update { it.copy(composerLinkPreview = null, isLoadingLinkPreview = false) }
            onComplete?.invoke()
            return
        }

        previewFetchJob = viewModelScope.launch {
            if (!immediate) {
                delay(500)
            }

            if (_uiState.value.composerText != text) return@launch

            val cached = linkPreviewService.cachedPreview(context, url)
            if (_uiState.value.composerText != text) return@launch

            if (cached != null) {
                _uiState.update { it.copy(composerLinkPreview = cached, isLoadingLinkPreview = false) }
                scheduleDraftPersistence()
                onComplete?.invoke()
                return@launch
            }

            _uiState.update { it.copy(isLoadingLinkPreview = true) }
            val preview = linkPreviewService.preview(context, url)
            if (_uiState.value.composerText != text) return@launch

            _uiState.update { it.copy(composerLinkPreview = preview, isLoadingLinkPreview = false) }
            scheduleDraftPersistence()
            onComplete?.invoke()
        }
    }

    private fun scheduleDraftPersistence() {
        if (_uiState.value.editingMessage != null) return
        val context = boundContext ?: return
        val userId = currentUserId ?: return
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(400)
            if (currentUserId == userId) {
                persistDraftNow(context)
            }
        }
    }

    private fun persistDraftNow(context: Context) {
        if (_uiState.value.editingMessage != null) return
        val userId = currentUserId ?: return
        val text = _uiState.value.composerText
        val trimmed = text.trim()
        val replyContext = currentDraftReplyContext()

        if (trimmed.isEmpty() && _uiState.value.composerSpaceLinks.isEmpty()) {
            draftStore.clearDraft(context, userId, space.id)
            _uiState.update { it.copy(hasSavedDraft = false) }
            return
        }

        draftStore.saveDraft(
            context = context,
            userId = userId,
            draft = SpaceDraftRecord(
                spaceId = space.id,
                text = text,
                updatedAt = System.currentTimeMillis(),
                spaceLinks = _uiState.value.composerSpaceLinks,
                replyToMessageId = replyContext?.messageId,
                replyToSenderName = replyContext?.senderName,
                replyToType = replyContext?.type,
                replyToPreview = replyContext?.preview,
                submittedQueuedMessageId = existingDraft(context)?.submittedQueuedMessageId
            )
        )
        _uiState.update { it.copy(hasSavedDraft = true) }
    }

    private fun markDraftAsSubmitted(queuedMessageId: String?, text: String) {
        val context = boundContext ?: return
        val userId = currentUserId ?: return
        val validQueuedId = queuedMessageId ?: return
        val replyContext = currentDraftReplyContext()
        val trimmed = text.trim()
        if (trimmed.isEmpty() && replyContext == null && _uiState.value.composerSpaceLinks.isEmpty()) return

        draftStore.saveDraft(
            context = context,
            userId = userId,
            draft = SpaceDraftRecord(
                spaceId = space.id,
                text = text,
                updatedAt = System.currentTimeMillis(),
                spaceLinks = _uiState.value.composerSpaceLinks,
                replyToMessageId = replyContext?.messageId,
                replyToSenderName = replyContext?.senderName,
                replyToType = replyContext?.type,
                replyToPreview = replyContext?.preview,
                submittedQueuedMessageId = validQueuedId
            )
        )
        _uiState.update { it.copy(hasSavedDraft = trimmed.isNotEmpty() || _uiState.value.composerSpaceLinks.isNotEmpty()) }
    }

    private fun clearDraftIfSubmitted(messageId: String) {
        val context = boundContext ?: return
        val userId = currentUserId ?: return
        val existing = draftStore.loadDraft(context, userId, space.id) ?: return
        if (existing.submittedQueuedMessageId == messageId) {
            draftStore.clearDraft(context, userId, space.id)
            _uiState.update { it.copy(hasSavedDraft = false) }
        }
    }

    private fun restoreDraftIfNeeded(context: Context, requireReload: Boolean = false) {
        val userId = currentUserId ?: return
        if (!requireReload && draftRestoreAttempted) return
        draftRestoreAttempted = true

        val draft = draftStore.loadDraft(context, userId, space.id)
        _uiState.update { it.copy(hasSavedDraft = !(draft?.previewText.isNullOrEmpty())) }
        if (draft == null) return

        if (draft.submittedQueuedMessageId != null &&
            outbox.itemsForSpace(space.id).any { it.id == draft.submittedQueuedMessageId }
        ) {
            return
        }

        if (_uiState.value.editingMessage == null) {
            _uiState.update {
                it.copy(
                    composerText = draft.text,
                    composerSpaceLinks = draft.spaceLinks
                )
            }
            scheduleLinkPreviewFetch(draft.text)
        }
        pendingDraftReplyContext = draft.replyToMessageId?.let {
            MessageReplyContext(
                messageId = it,
                senderName = draft.replyToSenderName ?: "Unknown",
                type = draft.replyToType ?: "text",
                preview = draft.replyToPreview ?: "Message"
            )
        }
        resolvePendingDraftReplyContext(context)
    }

    private fun resolvePendingDraftReplyContext(context: Context) {
        val pending = pendingDraftReplyContext ?: return
        val resolved = (baseMessages + _uiState.value.messages)
            .firstOrNull { it.id == pending.messageId && !it.deleted }
        if (resolved != null) {
            pendingDraftReplyContext = null
            _uiState.update { it.copy(replyingToMessage = resolved) }
            scheduleDraftPersistence()
            return
        }
        if (_uiState.value.isLoading) return
        pendingDraftReplyContext = null
        _uiState.update { it.copy(replyingToMessage = null) }
        persistDraftNow(context)
    }

    private fun existingDraft(context: Context): SpaceDraftRecord? {
        val userId = currentUserId ?: return null
        return draftStore.loadDraft(context, userId, space.id)
    }

    private fun contextForTypingUpdates(): Context? = typingIndicatorRepositoryContext

    private var typingIndicatorRepositoryContext: Context? = null

    fun addComposerSpaceLink(link: SpaceLinkAttachment) {
        val currentLinks = _uiState.value.composerSpaceLinks
        if (currentLinks.any { it.id == link.id || (it.moduleType == link.moduleType && it.targetId == link.targetId) }) {
            return
        }
        previewFetchJob?.cancel()
        _uiState.update {
            it.copy(
                composerLinkPreview = null,
                isLoadingLinkPreview = false,
                composerSpaceLinks = currentLinks + link
            )
        }
        scheduleDraftPersistence()
    }

    fun removeComposerSpaceLink(id: String) {
        _uiState.update {
            it.copy(composerSpaceLinks = it.composerSpaceLinks.filterNot { link -> link.id == id })
        }
        scheduleDraftPersistence()
    }
}

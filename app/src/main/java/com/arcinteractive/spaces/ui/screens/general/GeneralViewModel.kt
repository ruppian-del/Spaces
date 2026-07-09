package com.arcinteractive.spaces.ui.screens.general

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeneralUiState(
    val messages: List<SpaceMessage> = emptyList(),
    val composerText: String = "",
    val selectedMedia: SpaceMedia? = null,
    val selectedComposerMediaBytes: ByteArray? = null,
    val selectedComposerPreviewBytes: ByteArray? = null,
    val selectedComposerMimeType: String? = null,
    val selectedComposerIsVideo: Boolean = false,
    val selectedComposerMediaCategory: String = "photo",
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
    val canDeleteOthersContent: Boolean = false
) {
    val canSend: Boolean = when {
        isSending -> false
        selectedComposerMediaBytes != null -> canUploadMedia
        composerText.isNotBlank() -> canPostMessages
        else -> false
    }
}

class GeneralViewModel(
    space: Space,
    private val spaceService: SpaceService = SpaceService()
) : ViewModel() {
    val space: Space = space
    private var listener: ListenerRegistration? = null
    private val reactionListeners = mutableMapOf<String, ListenerRegistration>()
    private var currentUserId: String? = null
    private val localPlaintextByMessageId = mutableMapOf<String, String>()
    private val pendingLocalMessagesById = mutableMapOf<String, SpaceMessage>()
    private var baseMessages: List<SpaceMessage> = emptyList()
    private val reactionsByMessageId = mutableMapOf<String, List<MessageReaction>>()

    private val _uiState = MutableStateFlow(
        GeneralUiState()
    )
    val uiState: StateFlow<GeneralUiState> = _uiState.asStateFlow()

    fun loadMessagesIfNeeded(context: android.content.Context) {
        if (listener != null) return
        currentUserId = spaceService.currentUserId(context)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    canPostMessages = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.PostPings),
                    canUploadMedia = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.UploadPhotosVideos),
                    canDeleteOthersContent = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.DeleteOthersContent)
                )
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
            }
        }

    }

    fun updateComposerText(text: String) {
        _uiState.update { it.copy(composerText = text) }
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

    fun sendMessage(context: android.content.Context) {
        val trimmed = _uiState.value.composerText.trim()
        if (trimmed.isEmpty() || _uiState.value.isSending) return

        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.sendTextMessage(context, space, trimmed, activeReplyContext())
            }.onSuccess { localMessage ->
                localPlaintextByMessageId[localMessage.id] = trimmed
                pendingLocalMessagesById[localMessage.id] = localMessage
                val mergedMessages = mergeIncomingMessages(baseMessages)
                baseMessages = mergedMessages
                syncReactionListeners(context, mergedMessages)
                _uiState.update {
                    it.copy(
                        messages = applyReactions(mergedMessages),
                        composerText = "",
                        replyingToMessage = null,
                        isSending = false
                    )
                }
                refreshSearchMatches()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to send this message."
                    )
                }
            }
        }
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
                selectedComposerMediaBytes = mediaBytes,
                selectedComposerPreviewBytes = previewBytes,
                selectedComposerMimeType = mimeType,
                selectedComposerIsVideo = isVideo,
                selectedComposerMediaCategory = mediaCategory
            )
        }
    }

    fun removeComposerMedia() {
        Log.d("GeneralViewModel", "[ComposerMedia] selected=false byteCount=0 removed=true")
        _uiState.update {
            it.copy(
                selectedComposerMediaBytes = null,
                selectedComposerPreviewBytes = null,
                selectedComposerMimeType = null,
                selectedComposerIsVideo = false
            )
        }
    }

    fun beginReply(message: SpaceMessage) {
        if (message.deleted) return
        _uiState.update { it.copy(replyingToMessage = message, editingMessage = null) }
    }

    fun cancelReply() {
        _uiState.update { it.copy(replyingToMessage = null) }
    }

    fun beginEditing(message: SpaceMessage) {
        if (!canEdit(message)) return
        _uiState.update {
            it.copy(
                composerText = message.text.orEmpty(),
                replyingToMessage = null,
                editingMessage = message,
                selectedComposerMediaBytes = null,
                selectedComposerPreviewBytes = null,
                selectedComposerMimeType = null,
                selectedComposerIsVideo = false
            )
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessage = null, composerText = "") }
    }

    fun sendComposer(context: Context) {
        if (_uiState.value.isSending) return

        val editingMessage = _uiState.value.editingMessage
        if (editingMessage != null) {
            saveEditedMessage(context, editingMessage)
            return
        }

        val selectedMediaBytes = _uiState.value.selectedComposerMediaBytes
        if (selectedMediaBytes != null) {
            val caption = _uiState.value.composerText.trim().ifBlank { null }
            if (_uiState.value.selectedComposerIsVideo) {
                sendVideo(
                    context = context,
                    videoBytes = selectedMediaBytes,
                    caption = caption,
                    mimeType = _uiState.value.selectedComposerMimeType ?: "video/mp4"
                )
            } else {
                sendImage(
                    context = context,
                    imageBytes = selectedMediaBytes,
                    caption = caption,
                    mediaCategory = _uiState.value.selectedComposerMediaCategory
                )
            }
            return
        }

        sendMessage(context)
    }

    fun sendImage(
        context: Context,
        imageBytes: ByteArray,
        caption: String?,
        mediaCategory: String
    ) {
        if (_uiState.value.isSending) return

        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.sendImageMessage(
                    context,
                    space,
                    imageBytes,
                    caption,
                    mediaCategory,
                    activeReplyContext()
                )
            }.onSuccess { localMessage ->
                pendingLocalMessagesById[localMessage.id] = localMessage
                val mergedMessages = mergeIncomingMessages(baseMessages)
                baseMessages = mergedMessages
                syncReactionListeners(context, mergedMessages)
                _uiState.update {
                    it.copy(
                        messages = applyReactions(mergedMessages),
                        composerText = "",
                        selectedComposerMediaBytes = null,
                        selectedComposerPreviewBytes = null,
                        selectedComposerMimeType = null,
                        selectedComposerIsVideo = false,
                        replyingToMessage = null,
                        isSending = false
                    )
                }
                refreshSearchMatches()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to send this image."
                    )
                }
            }
        }
    }

    fun sendVideo(
        context: Context,
        videoBytes: ByteArray,
        caption: String?,
        mimeType: String
    ) {
        if (_uiState.value.isSending) return

        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.sendVideoMessage(
                    context,
                    space,
                    videoBytes,
                    caption,
                    mimeType,
                    activeReplyContext()
                )
            }.onSuccess { localMessage ->
                pendingLocalMessagesById[localMessage.id] = localMessage
                val mergedMessages = mergeIncomingMessages(baseMessages)
                baseMessages = mergedMessages
                syncReactionListeners(context, mergedMessages)
                _uiState.update {
                    it.copy(
                        messages = applyReactions(mergedMessages),
                        composerText = "",
                        selectedComposerMediaBytes = null,
                        selectedComposerPreviewBytes = null,
                        selectedComposerMimeType = null,
                        selectedComposerIsVideo = false,
                        replyingToMessage = null,
                        isSending = false
                    )
                }
                refreshSearchMatches()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to send this video."
                    )
                }
            }
        }
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
            message.type == com.arcinteractive.spaces.data.model.MessageType.Text &&
            message.media == null
    }

    fun isDeleting(message: SpaceMessage): Boolean {
        return _uiState.value.deletingMessageIds.contains(message.id)
    }

    fun deleteMessage(context: android.content.Context, message: SpaceMessage) {
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

    fun selectedComposerBitmap() = _uiState.value.selectedComposerPreviewBytes?.let {
        BitmapFactory.decodeByteArray(it, 0, it.size)
    }

    override fun onCleared() {
        listener?.remove()
        listener = null
        reactionListeners.values.forEach { it.remove() }
        reactionListeners.clear()
        super.onCleared()
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
                    reactions = reactionsByMessageId[message.id] ?: message.reactions
                )
            }
        }

        val incomingIds = mergedIncoming.map { it.id }.toSet()
        pendingLocalMessagesById.keys.removeAll(incomingIds)
        val pendingMessages = pendingLocalMessagesById.values

        return (mergedIncoming + pendingMessages).sortedWith { left, right ->
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

    private fun saveEditedMessage(context: Context, message: SpaceMessage) {
        val trimmed = _uiState.value.composerText.trim()
        if (trimmed.isEmpty() || !canEdit(message) || _uiState.value.isSending) return

        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.editTextMessage(context, space, message.id, trimmed)
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
                        isSending = false
                    )
                }
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
        val replyingToMessage = _uiState.value.replyingToMessage ?: return null
        return MessageReplyContext(
            messageId = replyingToMessage.id,
            senderName = replyingToMessage.senderName,
            type = replyType(replyingToMessage),
            preview = replyPreview(replyingToMessage)
        )
    }

    private fun replyType(message: SpaceMessage): String {
        return when (message.type) {
            com.arcinteractive.spaces.data.model.MessageType.Video -> "video"
            com.arcinteractive.spaces.data.model.MessageType.File -> "file"
            com.arcinteractive.spaces.data.model.MessageType.Image,
            com.arcinteractive.spaces.data.model.MessageType.Meme,
            com.arcinteractive.spaces.data.model.MessageType.Gif,
            com.arcinteractive.spaces.data.model.MessageType.Screenshot -> "image"
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
            message.replyContext?.preview.orEmpty()
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
}

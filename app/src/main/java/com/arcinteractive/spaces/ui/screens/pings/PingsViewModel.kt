package com.arcinteractive.spaces.ui.screens.pings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.Ping
import com.arcinteractive.spaces.data.model.PingParticipant
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.data.pings.PingService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PingsUiState(
    val pings: List<Ping> = emptyList(),
    val selectedPing: Ping? = null,
    val availableParticipants: List<PingParticipant> = emptyList(),
    val messages: List<SpaceMessage> = emptyList(),
    val composerText: String = "",
    val isLoading: Boolean = false,
    val isLoadingParticipants: Boolean = false,
    val isSending: Boolean = false,
    val deletingMessageIds: Set<String> = emptySet(),
    val lastErrorMessage: String? = null,
    val replyingToMessage: SpaceMessage? = null,
    val editingMessage: SpaceMessage? = null
) {
    val canSend: Boolean = composerText.isNotBlank() && !isSending
}

class PingsViewModel(
    private val pingService: PingService = PingService()
) : ViewModel() {
    private val _uiState = MutableStateFlow(PingsUiState())
    val uiState: StateFlow<PingsUiState> = _uiState.asStateFlow()

    private var pingsListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private val reactionListeners = mutableMapOf<String, ListenerRegistration>()
    private var currentUserId: String? = null
    private val localPlaintextByMessageId = mutableMapOf<String, String>()
    private val pendingLocalMessagesById = mutableMapOf<String, SpaceMessage>()
    private var baseMessages: List<SpaceMessage> = emptyList()
    private val reactionsByMessageId = mutableMapOf<String, List<MessageReaction>>()

    fun currentUserId(context: Context): String? = pingService.currentUserId(context)

    fun startListeningIfNeeded(context: Context) {
        if (pingsListener != null) return
        currentUserId = pingService.currentUserId(context)
        _uiState.update { it.copy(isLoading = true) }
        pingsListener = pingService.listenToPingsForCurrentUser(
            context = context,
            listenerKey = "pings.list"
        ) { result ->
            result.onSuccess { pings ->
                _uiState.update { current ->
                    val updatedSelection = current.selectedPing?.let { selected ->
                        pings.firstOrNull { it.id == selected.id } ?: selected
                    }
                    current.copy(pings = pings, selectedPing = updatedSelection, isLoading = false)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, lastErrorMessage = error.localizedMessage ?: "Unable to load Pings.")
                }
            }
        }
    }

    fun loadParticipants(context: Context) {
        if (_uiState.value.isLoadingParticipants) return
        _uiState.update { it.copy(isLoadingParticipants = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                pingService.fetchAvailableParticipants(context)
            }.onSuccess { participants ->
                _uiState.update {
                    it.copy(availableParticipants = participants, isLoadingParticipants = false)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingParticipants = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load people for a new Ping."
                    )
                }
            }
        }
    }

    fun createOrOpenPing(context: Context, participant: PingParticipant, onComplete: (Ping?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                pingService.createOrOpenPing(context, participant)
            }.onSuccess { ping ->
                openPing(context, ping)
                onComplete(ping)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to start this Ping.")
                }
                onComplete(null)
            }
        }
    }

    fun openPing(context: Context, ping: Ping) {
        _uiState.update {
            it.copy(selectedPing = ping, messages = emptyList(), composerText = "", replyingToMessage = null, editingMessage = null)
        }
        messagesListener?.remove()
        messagesListener = null
        reactionListeners.values.forEach { it.remove() }
        reactionListeners.clear()
        reactionsByMessageId.clear()
        localPlaintextByMessageId.clear()
        pendingLocalMessagesById.clear()
        baseMessages = emptyList()

        messagesListener = pingService.listenToMessages(
            context = context,
            ping = ping,
            listenerKey = "pings.${ping.id}.messages"
        ) { result ->
            result.onSuccess { messages ->
                val mergedMessages = mergeIncomingMessages(messages)
                baseMessages = mergedMessages
                syncReactionListeners(context, ping, mergedMessages)
                _uiState.update {
                    it.copy(messages = applyReactions(mergedMessages))
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to load this Ping.")
                }
            }
        }
    }

    fun closePing() {
        messagesListener?.remove()
        messagesListener = null
        reactionListeners.values.forEach { it.remove() }
        reactionListeners.clear()
        reactionsByMessageId.clear()
        pendingLocalMessagesById.clear()
        localPlaintextByMessageId.clear()
        baseMessages = emptyList()
        _uiState.update {
            it.copy(selectedPing = null, messages = emptyList(), composerText = "", replyingToMessage = null, editingMessage = null)
        }
    }

    fun updateComposerText(text: String) {
        _uiState.update { it.copy(composerText = text) }
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
                editingMessage = message
            )
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessage = null, composerText = "") }
    }

    fun sendComposer(context: Context) {
        val ping = _uiState.value.selectedPing ?: return
        val editingMessage = _uiState.value.editingMessage
        if (editingMessage != null) {
            saveEditedMessage(context, ping, editingMessage)
            return
        }
        sendMessage(context, ping)
    }

    private fun sendMessage(context: Context, ping: Ping) {
        val trimmed = _uiState.value.composerText.trim()
        if (trimmed.isEmpty() || _uiState.value.isSending) return
        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                pingService.sendTextMessage(context, ping, trimmed, activeReplyContext())
            }.onSuccess { localMessage ->
                localPlaintextByMessageId[localMessage.id] = trimmed
                pendingLocalMessagesById[localMessage.id] = localMessage
                val mergedMessages = mergeIncomingMessages(baseMessages)
                baseMessages = mergedMessages
                syncReactionListeners(context, ping, mergedMessages)
                _uiState.update {
                    it.copy(
                        messages = applyReactions(mergedMessages),
                        composerText = "",
                        replyingToMessage = null,
                        isSending = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isSending = false, lastErrorMessage = error.localizedMessage ?: "Unable to send this Ping.")
                }
            }
        }
    }

    private fun saveEditedMessage(context: Context, ping: Ping, message: SpaceMessage) {
        val trimmed = _uiState.value.composerText.trim()
        if (trimmed.isEmpty() || !canEdit(message) || _uiState.value.isSending) return
        _uiState.update { it.copy(isSending = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                pingService.editTextMessage(context, ping, message.id, trimmed)
            }.onSuccess { updatedMessage ->
                localPlaintextByMessageId[message.id] = trimmed
                baseMessages = baseMessages.map { if (it.id == message.id) updatedMessage else it }
                _uiState.update {
                    it.copy(messages = applyReactions(baseMessages), composerText = "", editingMessage = null, isSending = false)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isSending = false, lastErrorMessage = error.localizedMessage ?: "Unable to save this message.")
                }
            }
        }
    }

    fun canDelete(message: SpaceMessage): Boolean = message.senderId == currentUserId && !message.deleted

    fun canEdit(message: SpaceMessage): Boolean =
        message.senderId == currentUserId && !message.deleted && message.type == com.arcinteractive.spaces.data.model.MessageType.Text && message.media == null

    fun deleteMessage(context: Context, message: SpaceMessage) {
        val ping = _uiState.value.selectedPing ?: return
        if (!canDelete(message) || _uiState.value.deletingMessageIds.contains(message.id)) return
        _uiState.update { it.copy(deletingMessageIds = it.deletingMessageIds + message.id) }
        viewModelScope.launch {
            runCatching {
                pingService.deleteMessage(context, ping, message.id)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to delete this message.")
                }
            }
            _uiState.update { it.copy(deletingMessageIds = it.deletingMessageIds - message.id) }
        }
    }

    fun reactionOptions(message: SpaceMessage): List<String> {
        if (message.deleted) return emptyList()
        return listOf("👍", "❤️", "😂", "😮", "😢", "👎")
    }

    fun toggleReaction(context: Context, message: SpaceMessage, emoji: String) {
        val ping = _uiState.value.selectedPing ?: return
        viewModelScope.launch {
            runCatching {
                pingService.toggleReaction(context, ping, message.id, emoji)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to update this reaction.")
                }
            }
        }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    private fun activeReplyContext(): MessageReplyContext? {
        val replyingToMessage = _uiState.value.replyingToMessage ?: return null
        return MessageReplyContext(
            messageId = replyingToMessage.id,
            senderName = replyingToMessage.senderName,
            type = "text",
            preview = replyingToMessage.text?.trim().orEmpty().ifBlank { "Message" }.take(80)
        )
    }

    private fun mergeIncomingMessages(incoming: List<SpaceMessage>): List<SpaceMessage> {
        val mergedIncoming = incoming.map { message ->
            val localPlaintextFallback = if (message.senderId == currentUserId) localPlaintextByMessageId[message.id] else null
            if (localPlaintextFallback.isNullOrBlank()) {
                message.copy(reactions = reactionsByMessageId[message.id] ?: message.reactions)
            } else {
                message.copy(text = localPlaintextFallback, reactions = reactionsByMessageId[message.id] ?: message.reactions)
            }
        }
        val incomingIds = mergedIncoming.map { it.id }.toSet()
        pendingLocalMessagesById.keys.removeAll(incomingIds)
        return (mergedIncoming + pendingLocalMessagesById.values).sortedWith { left, right ->
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

    private fun syncReactionListeners(context: Context, ping: Ping, messages: List<SpaceMessage>) {
        val validIds = messages.map { it.id }.toSet()
        reactionListeners.keys.filter { it !in validIds }.forEach { staleId ->
            reactionListeners.remove(staleId)?.remove()
            reactionsByMessageId.remove(staleId)
        }
        messages.forEach { message ->
            if (reactionListeners[message.id] == null) {
                val registration = pingService.listenToReactions(
                    context = context,
                    ping = ping,
                    messageId = message.id,
                    listenerKey = "pings.${ping.id}.reactions.${message.id}"
                ) { result ->
                    result.onSuccess { reactions ->
                        reactionsByMessageId[message.id] = reactions
                        _uiState.update { state -> state.copy(messages = applyReactions(baseMessages)) }
                    }.onFailure { error ->
                        _uiState.update { it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to load reactions.") }
                    }
                }
                if (registration != null) reactionListeners[message.id] = registration
            }
        }
    }

    private fun applyReactions(messages: List<SpaceMessage>): List<SpaceMessage> {
        return messages.map { it.copy(reactions = reactionsByMessageId[it.id] ?: it.reactions) }
    }

    override fun onCleared() {
        pingsListener?.remove()
        messagesListener?.remove()
        reactionListeners.values.forEach { it.remove() }
        reactionListeners.clear()
        super.onCleared()
    }
}

package com.arcinteractive.spaces.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.model.Ping
import com.arcinteractive.spaces.data.model.PingParticipant
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.pings.PingService
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GlobalSearchUiState(
    val query: String = "",
    val currentUserId: String? = null,
    val spaceResults: List<Space> = emptyList(),
    val pingResults: List<Ping> = emptyList(),
    val peopleResults: List<PingParticipant> = emptyList(),
    val messageResults: List<GlobalMessageSearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class GlobalMessageSearchResult(
    val id: String,
    val sourceType: GlobalMessageSourceType,
    val sourceId: String,
    val title: String,
    val subtitle: String,
    val preview: String,
    val createdAt: Date?
)

enum class GlobalMessageSourceType {
    Space,
    Ping
}

class GlobalSearchViewModel(
    private val spaceService: SpaceService = SpaceService(),
    private val pingService: PingService = PingService()
) : ViewModel() {
    private val _uiState = MutableStateFlow(GlobalSearchUiState())
    val uiState: StateFlow<GlobalSearchUiState> = _uiState.asStateFlow()

    private var spaces: List<Space> = emptyList()
    private var pings: List<Ping> = emptyList()
    private var people: List<PingParticipant> = emptyList()
    private var currentUserId: String? = null
    private var pingsListener: ListenerRegistration? = null
    private var searchJob: Job? = null

    fun start(context: Context, spaces: List<Space>) {
        this.spaces = spaces
        this.currentUserId = pingService.currentUserId(context)
        _uiState.update { it.copy(currentUserId = currentUserId) }
        if (pingsListener == null) {
            pingsListener = pingService.listenToPingsForCurrentUser(
                context = context,
                listenerKey = "search.pings"
            ) { result ->
                result.onSuccess { loadedPings ->
                    pings = loadedPings
                    refreshStaticResults()
                    searchMessages(context)
                }.onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Unable to load Pings.") }
                }
            }
            viewModelScope.launch {
                people = runCatching { pingService.fetchAvailableParticipants(context) }.getOrDefault(emptyList())
                refreshStaticResults()
            }
        } else {
            this.spaces = spaces
            refreshStaticResults()
        }
    }

    fun updateQuery(context: Context, query: String) {
        _uiState.update { it.copy(query = query, errorMessage = null) }
        refreshStaticResults()
        searchMessages(context)
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun createOrOpenPing(context: Context, participant: PingParticipant, onComplete: (Ping?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                pingService.createOrOpenPing(context, participant)
            }.onSuccess { ping ->
                onComplete(ping)
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Unable to open this Ping.") }
                onComplete(null)
            }
        }
    }

    private fun refreshStaticResults() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) {
            searchJob?.cancel()
            _uiState.update {
                it.copy(
                    spaceResults = emptyList(),
                    pingResults = emptyList(),
                    peopleResults = emptyList(),
                    messageResults = emptyList(),
                    isLoading = false
                )
            }
            return
        }

        val pingParticipantIds = pings.mapNotNull { it.otherParticipant(currentUserId)?.id }.toSet()
        _uiState.update {
            it.copy(
                spaceResults = spaces.filter { space ->
                    listOf(space.name, space.description, space.emoji).any { value ->
                        value.contains(query, ignoreCase = true)
                    }
                },
                pingResults = pings.filter { ping ->
                    listOf(ping.title(currentUserId), ping.emoji(currentUserId)).any { value ->
                        value.contains(query, ignoreCase = true)
                    }
                },
                peopleResults = people.filter { participant ->
                    participant.id !in pingParticipantIds &&
                        listOf(participant.displayName, participant.emojiAvatar).any { value ->
                            value.contains(query, ignoreCase = true)
                        }
                }
            )
        }
    }

    private fun searchMessages(context: Context) {
        searchJob?.cancel()
        val query = _uiState.value.query.trim()
        if (query.isBlank()) return

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val results = mutableListOf<GlobalMessageSearchResult>()

            spaces.forEach { space ->
                val messages = runCatching {
                    spaceService.fetchRecentMessages(context, space, limit = 25)
                }.getOrDefault(emptyList())
                messages.filter { !it.deleted }.forEach { message ->
                    val searchable = listOf(
                        message.senderName,
                        message.text.orEmpty(),
                        message.replyContext?.preview.orEmpty(),
                        message.spaceLinks.joinToString("\n") { it.title + "\n" + (it.subtitle ?: "") }
                    )
                        .joinToString("\n")
                    if (searchable.contains(query, ignoreCase = true)) {
                        results += GlobalMessageSearchResult(
                            id = "space-${space.id}-${message.id}",
                            sourceType = GlobalMessageSourceType.Space,
                            sourceId = space.id,
                            title = space.name,
                            subtitle = message.senderName,
                            preview = message.text?.trim().orEmpty().ifBlank { message.spaceLinks.firstOrNull()?.title ?: "Message" },
                            createdAt = message.createdAt
                        )
                    }
                }
            }

            pings.forEach { ping ->
                val messages = runCatching {
                    pingService.fetchRecentMessages(context, ping, limit = 25)
                }.getOrDefault(emptyList())
                messages.filter { !it.deleted }.forEach { message ->
                    val searchable = listOf(
                        message.senderName,
                        message.text.orEmpty(),
                        message.replyContext?.preview.orEmpty(),
                        message.spaceLinks.joinToString("\n") { it.title + "\n" + (it.subtitle ?: "") }
                    )
                        .joinToString("\n")
                    if (searchable.contains(query, ignoreCase = true)) {
                        results += GlobalMessageSearchResult(
                            id = "ping-${ping.id}-${message.id}",
                            sourceType = GlobalMessageSourceType.Ping,
                            sourceId = ping.id,
                            title = ping.title(currentUserId),
                            subtitle = message.senderName,
                            preview = message.text?.trim().orEmpty().ifBlank { message.spaceLinks.firstOrNull()?.title ?: "Message" },
                            createdAt = message.createdAt
                        )
                    }
                }
            }

            _uiState.update {
                it.copy(
                    messageResults = results.sortedByDescending { item -> item.createdAt?.time ?: Long.MIN_VALUE },
                    isLoading = false
                )
            }
        }
    }

    override fun onCleared() {
        pingsListener?.remove()
        pingsListener = null
        searchJob?.cancel()
        super.onCleared()
    }
}

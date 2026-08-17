package com.arcinteractive.spaces.ui.screens.polls

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpacePoll
import com.arcinteractive.spaces.data.model.SpacePollVote
import com.arcinteractive.spaces.data.model.SpaceMember
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

data class PollsUiState(
    val polls: List<SpacePoll> = emptyList(),
    val isLoading: Boolean = false,
    val currentUserId: String? = null,
    val canManageAllPolls: Boolean = false,
    val selectedPollId: String? = null,
    val votingPollIds: Set<String> = emptySet(),
    val membersById: Map<String, SpaceMember> = emptyMap(),
    val lastErrorMessage: String? = null
) {
    val selectedPoll: SpacePoll?
        get() = polls.firstOrNull { it.id == selectedPollId }
}

class PollsViewModel(
    private val space: Space,
    private val spaceService: SpaceService = SpaceService()
) : ViewModel() {
    private val _uiState = MutableStateFlow(PollsUiState())
    val uiState: StateFlow<PollsUiState> = _uiState.asStateFlow()

    private var pollListener: ListenerRegistration? = null
    private var memberListener: ListenerRegistration? = null
    private val voteListeners = mutableMapOf<String, ListenerRegistration>()

    fun startListeningIfNeeded(context: Context) {
        if (pollListener != null) return

        _uiState.update {
            it.copy(
                isLoading = true,
                currentUserId = spaceService.currentUserId(context),
                lastErrorMessage = null
            )
        }

        memberListener = spaceService.listenToMembers(
            context = context,
            space = space,
            listenerKey = "polls.${space.id}.members"
        ) { result ->
            result.onSuccess { members ->
                _uiState.update { it.copy(membersById = members.associateBy(SpaceMember::id)) }
            }
        }

        viewModelScope.launch {
            val canManage = spaceService.canManageModules(context, space)
            _uiState.update { it.copy(canManageAllPolls = canManage) }
        }

        pollListener = spaceService.listenToPolls(
            context = context,
            space = space,
            listenerKey = "polls.${space.id}"
        ) { result ->
            result.onSuccess { polls ->
                mergePolls(polls)
                syncVoteListeners(context, polls)
                _uiState.update { state ->
                    val selectedPollStillExists = state.selectedPollId?.let { selectedId ->
                        polls.any { it.id == selectedId }
                    } ?: true
                    state.copy(
                        isLoading = false,
                        selectedPollId = if (selectedPollStillExists) state.selectedPollId else null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load polls."
                    )
                }
            }
        }
    }

    fun openPoll(poll: SpacePoll) {
        _uiState.update { it.copy(selectedPollId = poll.id) }
    }

    fun closePoll() {
        _uiState.update { it.copy(selectedPollId = null) }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    fun canDelete(poll: SpacePoll): Boolean {
        val state = _uiState.value
        return poll.createdBy == state.currentUserId || state.canManageAllPolls
    }

    fun canEdit(poll: SpacePoll): Boolean {
        val state = _uiState.value
        val currentRole = state.currentUserId?.let(state.membersById::get)?.role
        return poll.createdBy == state.currentUserId
            || currentRole == com.arcinteractive.spaces.data.model.SpaceMemberRole.Owner
            || currentRole == com.arcinteractive.spaces.data.model.SpaceMemberRole.Admin
    }

    fun updatePoll(
        context: Context,
        poll: SpacePoll,
        question: String,
        optionTexts: List<String>,
        closesAt: Date?,
        allowMultipleVotes: Boolean,
        anonymous: Boolean,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                spaceService.updatePoll(context, space, poll, question, optionTexts, closesAt, allowMultipleVotes, anonymous)
            }.onSuccess { onComplete() }
                .onFailure { error ->
                    _uiState.update { it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to edit this poll.") }
                }
        }
    }

    fun createPoll(
        context: Context,
        question: String,
        optionTexts: List<String>,
        closesAt: Date?,
        allowMultipleVotes: Boolean,
        anonymous: Boolean,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                spaceService.createPoll(
                    context = context,
                    space = space,
                    question = question,
                    optionTexts = optionTexts,
                    closesAt = closesAt,
                    allowMultipleVotes = allowMultipleVotes,
                    anonymous = anonymous
                )
            }.onSuccess {
                onComplete()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to create this poll.")
                }
            }
        }
    }

    fun toggleVote(context: Context, poll: SpacePoll, optionId: String) {
        if (poll.id in _uiState.value.votingPollIds) return
        val currentUserId = _uiState.value.currentUserId ?: return
        var selectedOptionIds = poll.selectedOptionIds(currentUserId).toMutableSet()

        if (poll.allowMultipleVotes) {
            if (selectedOptionIds.contains(optionId)) {
                selectedOptionIds.remove(optionId)
            } else {
                selectedOptionIds.add(optionId)
            }

        } else {
            selectedOptionIds = if (selectedOptionIds.size == 1 && selectedOptionIds.contains(optionId)) {
                mutableSetOf()
            } else {
                mutableSetOf(optionId)
            }
        }

        _uiState.update { it.copy(votingPollIds = it.votingPollIds + poll.id) }
        viewModelScope.launch {
            runCatching {
                if (selectedOptionIds.isEmpty()) {
                    spaceService.removePollVote(context, space, poll)
                } else {
                    spaceService.submitPollVote(context, space, poll, selectedOptionIds.toList())
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to update this vote.")
                }
            }
            _uiState.update { it.copy(votingPollIds = it.votingPollIds - poll.id) }
        }
    }

    fun deletePoll(context: Context, poll: SpacePoll, onComplete: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                spaceService.deletePoll(context, space, poll)
            }.onSuccess {
                onComplete()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to delete this poll.")
                }
            }
        }
    }

    override fun onCleared() {
        pollListener?.remove()
        memberListener?.remove()
        voteListeners.values.forEach { it.remove() }
        voteListeners.clear()
        super.onCleared()
    }

    private fun mergePolls(incomingPolls: List<SpacePoll>) {
        _uiState.update { state ->
            state.copy(
                polls = incomingPolls.map { incoming ->
                    val existingVotes = state.polls.firstOrNull { it.id == incoming.id }?.votes.orEmpty()
                    incoming.copy(votes = existingVotes)
                }
            )
        }
    }

    private fun syncVoteListeners(context: Context, polls: List<SpacePoll>) {
        val activeIds = polls.map { it.id }.toSet()

        voteListeners.keys.filterNot(activeIds::contains).forEach { pollId ->
            voteListeners.remove(pollId)?.remove()
        }

        polls.forEach { poll ->
            if (voteListeners[poll.id] != null) return@forEach

            val listener = spaceService.listenToPollVotes(
                context = context,
                space = space,
                pollId = poll.id,
                listenerKey = "polls.${space.id}.votes.${poll.id}"
            ) { result ->
                result.onSuccess { votes ->
                    updatePollVotes(poll.id, votes)
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to load poll votes.")
                    }
                }
            }
            if (listener != null) {
                voteListeners[poll.id] = listener
            }
        }
    }

    private fun updatePollVotes(pollId: String, votes: List<SpacePollVote>) {
        _uiState.update { state ->
            state.copy(
                polls = state.polls.map { poll ->
                    if (poll.id == pollId) poll.copy(votes = votes) else poll
                }
            )
        }
    }
}

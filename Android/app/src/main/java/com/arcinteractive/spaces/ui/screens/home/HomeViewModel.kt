package com.arcinteractive.spaces.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceModule
import com.arcinteractive.spaces.data.spaces.SpaceDraftStore
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val spaces: List<Space> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val isJoining: Boolean = false,
    val greetingTitle: String = "Welcome",
    val greetingSubtitle: String = "Your spaces will show up here.",
    val draftPreviews: Map<String, String> = emptyMap(),
    val lastErrorMessage: String? = null,
    val lastSuccessMessage: String? = null
)

class HomeViewModel(
    private val spaceService: SpaceService = SpaceService(),
    private val authService: AuthService = AuthService(),
    private val userProfileService: UserProfileService = UserProfileService(),
    private val draftStore: SpaceDraftStore = SpaceDraftStore()
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var listener: ListenerRegistration? = null
    private var activeSpacesUserId: String? = null
    private var spaceOrder: List<String> = emptyList()

    fun handleAuthState(context: Context, userId: String?) {
        if (userId.isNullOrBlank()) {
            stopSpacesListener()
            activeSpacesUserId = null
            spaceOrder = emptyList()
            _uiState.value = HomeUiState()
            return
        }

        loadGreeting(context)
        loadSpaceOrder(context)
        refreshDraftPreviews(context, userId)
        if (activeSpacesUserId == userId && listener != null) return
        startSpacesListener(context, userId)
    }

    fun startSpacesListener(context: Context, userId: String) {
        stopSpacesListener()
        if (_uiState.value.isLoading) return

        activeSpacesUserId = userId
        _uiState.update { it.copy(isLoading = true, lastErrorMessage = null) }
        listener = spaceService.listenToSpacesForCurrentUser(
            context = context,
            listenerKey = "home.spaces.$userId"
        ) { result ->
            result.onSuccess { spaces ->
                handleIncomingSpaces(context, spaces)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load Spaces."
                    )
                }
            }
        }
    }

    fun stopSpacesListener() {
        listener?.remove()
        listener = null
    }

    private fun loadGreeting(context: Context) {
        viewModelScope.launch {
            val session = authService.currentSession(context)
            val fallbackName = session?.displayName?.trim().orEmpty()
            val profileName = runCatching {
                session?.uid?.let { userProfileService.fetchUserProfile(context, it)?.displayName?.trim() }
            }.getOrNull().orEmpty()
            val resolvedName = listOf(profileName, fallbackName)
                .firstOrNull { it.isNotBlank() }
                ?.substringBefore(" ")

            _uiState.update {
                it.copy(
                    greetingTitle = buildGreetingTitle(resolvedName),
                    greetingSubtitle = if (it.spaces.isEmpty()) {
                        "Create or join a Space to get started."
                    } else {
                        "Your spaces are active tonight."
                    }
                )
            }
        }
    }

    fun createSpace(
        context: Context,
        name: String,
        emoji: String,
        colorHex: String,
        description: String,
        template: com.arcinteractive.spaces.data.model.SpaceTemplate,
        enabledModules: List<SpaceModule>
    ) {
        if (_uiState.value.isCreating) return

        _uiState.update { it.copy(isCreating = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.createSpace(context, name, emoji, colorHex, description, template, enabledModules)
            }.onSuccess { newSpace ->
                appendSpaceToOrder(context, newSpace.id)
                _uiState.update {
                    val updatedSpaces = orderedSpaces(it.spaces + newSpace)
                    it.copy(
                        spaces = updatedSpaces,
                        isCreating = false,
                        greetingSubtitle = "Your spaces are active tonight."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCreating = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to create Space."
                    )
                }
            }
        }
    }

    fun redeemInvite(context: Context, code: String, onJoined: () -> Unit) {
        if (_uiState.value.isJoining) return

        _uiState.update { it.copy(isJoining = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.redeemInvite(context, code)
            }.onSuccess { space ->
                appendSpaceToOrder(context, space.id)
                _uiState.update {
                    it.copy(
                        isJoining = false,
                        lastSuccessMessage = "Joined ${space.name}."
                    )
                }
                onJoined()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isJoining = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to join Space."
                    )
                }
            }
        }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    fun clearLastSuccessMessage() {
        _uiState.update { it.copy(lastSuccessMessage = null) }
    }

    fun refreshDraftPreviews(context: Context, userId: String?) {
        if (userId.isNullOrBlank()) {
            _uiState.update { it.copy(draftPreviews = emptyMap()) }
            return
        }
        _uiState.update {
            it.copy(draftPreviews = draftStore.draftPreviews(context, userId))
        }
    }

    fun moveSpace(context: Context, fromIndex: Int, toIndex: Int) {
        val currentSpaces = _uiState.value.spaces
        if (fromIndex !in currentSpaces.indices || toIndex !in currentSpaces.indices) return

        val mutable = currentSpaces.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        spaceOrder = mutable.map { it.id }
        _uiState.update { it.copy(spaces = mutable) }

        viewModelScope.launch {
            runCatching {
                spaceService.saveSpaceOrderForCurrentUser(context, spaceOrder)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to save Space order.")
                }
            }
        }
    }

    private fun buildGreetingTitle(name: String?): String {
        val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            else -> "Good Evening"
        }
        return if (name.isNullOrBlank()) greeting else "$greeting, $name"
    }

    override fun onCleared() {
        stopSpacesListener()
        super.onCleared()
    }

    private fun loadSpaceOrder(context: Context) {
        viewModelScope.launch {
            spaceOrder = runCatching {
                spaceService.fetchSpaceOrderForCurrentUser(context)
            }.getOrDefault(emptyList())

            val currentSpaces = _uiState.value.spaces
            if (currentSpaces.isNotEmpty()) {
                val ordered = orderedSpaces(currentSpaces)
                _uiState.update { it.copy(spaces = ordered) }
                reconcileSpaceOrder(context, ordered)
            }
        }
    }

    private fun handleIncomingSpaces(context: Context, spaces: List<Space>) {
        val ordered = orderedSpaces(spaces)
        _uiState.update {
            it.copy(
                spaces = ordered,
                isLoading = false
            )
        }
        reconcileSpaceOrder(context, ordered)
    }

    private fun orderedSpaces(spaces: List<Space>): List<Space> {
        val orderIndex = spaceOrder.withIndex().associate { it.value to it.index }
        return spaces.sortedWith(compareBy<Space> { orderIndex[it.id] ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
    }

    private fun reconcileSpaceOrder(context: Context, spaces: List<Space>) {
        viewModelScope.launch {
            val visibleIds = spaces.map { it.id }.toSet()
            val nextOrder = buildList {
                addAll(spaceOrder.filter { visibleIds.contains(it) })
                spaces.map { it.id }.forEach { id ->
                    if (!contains(id)) {
                        add(id)
                    }
                }
            }

            if (nextOrder == spaceOrder) return@launch
            spaceOrder = nextOrder
            runCatching {
                spaceService.saveSpaceOrderForCurrentUser(context, nextOrder)
            }
        }
    }

    private fun appendSpaceToOrder(context: Context, spaceId: String) {
        if (spaceOrder.contains(spaceId)) return
        spaceOrder = spaceOrder + spaceId
        viewModelScope.launch {
            runCatching {
                spaceService.saveSpaceOrderForCurrentUser(context, spaceOrder)
            }
        }
    }
}

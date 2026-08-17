package com.arcinteractive.spaces.ui.screens.activity

import android.content.Context
import androidx.lifecycle.ViewModel
import com.arcinteractive.spaces.data.model.ActivityItem
import com.arcinteractive.spaces.data.model.ActivitySection
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActivityUiState(
    val items: List<ActivityItem> = emptyList(),
    val isLoading: Boolean = false,
    val selectedItem: ActivityItem? = null,
    val lastErrorMessage: String? = null,
    val currentUserId: String? = null
)

class ActivityViewModel(
    private val spaceService: SpaceService = SpaceService()
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    private var activityListener: ListenerRegistration? = null
    private var currentSpaceIds: List<String> = emptyList()

    fun startListening(context: Context, spaces: List<Space>) {
        val spaceIds = spaces.map { it.id }.distinct().sorted()
        if (spaceIds == currentSpaceIds && (activityListener != null || spaceIds.isEmpty())) {
            if (spaceIds.isEmpty()) {
                _uiState.update { it.copy(items = emptyList(), isLoading = false) }
            }
            return
        }

        activityListener?.remove()
        activityListener = null
        currentSpaceIds = spaceIds

        if (spaceIds.isEmpty()) {
            _uiState.update { it.copy(items = emptyList(), isLoading = false) }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                lastErrorMessage = null,
                currentUserId = spaceService.currentUserId(context)
            )
        }
        activityListener = spaceService.listenToActivity(
            context = context,
            spaceIds = spaceIds,
            listenerKey = "activity.${spaceIds.joinToString(",")}"
        ) { result ->
            result.onSuccess { items ->
                _uiState.update {
                    it.copy(
                        items = items.sortedByDescending { item -> item.createdAt?.time ?: Long.MIN_VALUE },
                        isLoading = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load activity."
                    )
                }
            }
        }
    }

    fun openItem(item: ActivityItem) {
        _uiState.update { it.copy(selectedItem = item) }
    }

    fun closeItem() {
        _uiState.update { it.copy(selectedItem = null) }
    }

    fun markRead(context: Context, item: ActivityItem) {
        scope.launch {
            runCatching {
                spaceService.markActivityRead(context, item)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to update activity.")
                }
            }
        }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    val groupedItems: List<Pair<ActivitySection, List<ActivityItem>>>
        get() = ActivitySection.entries.mapNotNull { section ->
            val items = _uiState.value.items.filter { it.section == section }
            if (items.isEmpty()) null else section to items
        }

    override fun onCleared() {
        activityListener?.remove()
        activityListener = null
        super.onCleared()
    }
}

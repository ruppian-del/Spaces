package com.arcinteractive.spaces.ui.screens.events

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.model.EditableSpaceEvent
import com.arcinteractive.spaces.data.model.EventEditorMode
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceEvent
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventsUiState(
    val events: List<SpaceEvent> = emptyList(),
    val isLoading: Boolean = false,
    val canCreateEvents: Boolean = false,
    val selectedEvent: SpaceEvent? = null,
    val canManageSelectedEvent: Boolean = false,
    val editorMode: EventEditorMode? = null,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null
)

class EventsViewModel(
    val space: Space,
    private val spaceService: SpaceService = SpaceService()
) : ViewModel() {
    private val _uiState = MutableStateFlow(EventsUiState(isLoading = true))
    val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

    private var eventsListener: ListenerRegistration? = null

    fun startListening(context: Context) {
        if (eventsListener != null) return

        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            _uiState.update {
                it.copy(canCreateEvents = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.CreateEvents))
            }
        }
        eventsListener = spaceService.listenToEvents(
            context = context,
            space = space,
            listenerKey = "events.${space.id}"
        ) { result ->
            result.onSuccess { events ->
                val sortedEvents = events.sortedBy { it.startDate.time }
                _uiState.update { current ->
                    current.copy(
                        events = sortedEvents,
                        isLoading = false,
                        selectedEvent = current.selectedEvent?.let { selected ->
                            sortedEvents.firstOrNull { it.id == selected.id }
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.localizedMessage ?: "Unable to load events."
                    )
                }
            }
        }
    }

    fun openEvent(context: Context, event: SpaceEvent) {
        _uiState.update { it.copy(selectedEvent = event) }
        refreshSelectedEventPermissions(context, event)
    }

    fun closeEvent() {
        _uiState.update { it.copy(selectedEvent = null, canManageSelectedEvent = false) }
    }

    fun presentCreateEvent() {
        if (!_uiState.value.canCreateEvents) return
        _uiState.update { it.copy(editorMode = EventEditorMode.Create) }
    }

    fun presentEditEvent(event: SpaceEvent) {
        _uiState.update { it.copy(editorMode = EventEditorMode.Edit(event)) }
    }

    fun dismissEditor() {
        _uiState.update { it.copy(editorMode = null) }
    }

    fun saveEvent(context: Context, draft: EditableSpaceEvent, event: SpaceEvent?) {
        if (_uiState.value.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            runCatching {
                if (event == null) {
                    spaceService.createEvent(
                        context = context,
                        space = space,
                        title = draft.title,
                        description = draft.description,
                        location = draft.location,
                        startDate = draft.startDate,
                        endDate = draft.endDate,
                        allDay = draft.allDay
                    )
                } else {
                    spaceService.updateEvent(
                        context = context,
                        space = space,
                        event = event,
                        title = draft.title,
                        description = draft.description,
                        location = draft.location,
                        startDate = draft.startDate,
                        endDate = draft.endDate,
                        allDay = draft.allDay
                    )
                }
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, editorMode = null) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.localizedMessage ?: "Unable to save event."
                    )
                }
            }
        }
    }

    fun deleteEvent(context: Context, event: SpaceEvent) {
        if (_uiState.value.isDeleting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            runCatching {
                spaceService.deleteEvent(context, space, event)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        selectedEvent = null,
                        canManageSelectedEvent = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        errorMessage = error.localizedMessage ?: "Unable to delete event."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun refreshSelectedEventPermissions(context: Context, event: SpaceEvent) {
        viewModelScope.launch {
            val canManage = spaceService.canManageEvent(context, space, event)
            _uiState.update { current ->
                if (current.selectedEvent?.id == event.id) {
                    current.copy(canManageSelectedEvent = canManage)
                } else {
                    current
                }
            }
        }
    }

    override fun onCleared() {
        eventsListener?.remove()
        eventsListener = null
        super.onCleared()
    }
}

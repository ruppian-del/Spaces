package com.arcinteractive.spaces.ui.screens.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PhotosUiState(
    val mediaItems: List<SpaceMedia>,
    val isLoading: Boolean = false,
    val lastErrorMessage: String? = null,
    val selectedMedia: SpaceMedia? = null
)

class PhotosViewModel(
    space: Space,
    private val spaceService: SpaceService = SpaceService()
) : ViewModel() {
    val space: Space = space
    private var listener: ListenerRegistration? = null

    private val _uiState = MutableStateFlow(
        PhotosUiState(mediaItems = emptyList())
    )
    val uiState: StateFlow<PhotosUiState> = _uiState.asStateFlow()

    fun loadPhotosIfNeeded(context: Context) {
        if (listener != null) return
        _uiState.update { it.copy(isLoading = true, lastErrorMessage = null) }
        listener = spaceService.listenToMessages(
            context = context,
            space = space,
            listenerKey = "photos.${space.id}.messages"
        ) { result ->
            result.onSuccess { messages ->
                _uiState.update {
                    it.copy(
                        mediaItems = messages.mapNotNull { message ->
                            val media = message.media ?: return@mapNotNull null
                            if (message.type == MessageType.Image && media.mediaCategory == "photo") media else null
                        },
                        isLoading = false,
                        lastErrorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        mediaItems = emptyList(),
                        isLoading = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load shared photos."
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

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    override fun onCleared() {
        listener?.remove()
        listener = null
        super.onCleared()
    }
}

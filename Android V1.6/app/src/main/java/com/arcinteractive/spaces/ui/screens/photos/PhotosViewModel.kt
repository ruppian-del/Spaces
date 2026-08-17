package com.arcinteractive.spaces.ui.screens.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.SpacePermission
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotosUiState(
    val mediaItems: List<SpaceMedia>,
    val isLoading: Boolean = false,
    val lastErrorMessage: String? = null,
    val selectedMedia: SpaceMedia? = null,
    val canUploadMedia: Boolean = false
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
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    canUploadMedia = spaceService.canPerform(context, space, SpacePermission.UploadPhotosVideos)
                )
            }
        }
        listener = spaceService.listenToMessages(
            context = context,
            space = space,
            listenerKey = "photos.${space.id}.messages"
        ) { result ->
            result.onSuccess { messages ->
                _uiState.update {
                    it.copy(
                        mediaItems = messages.flatMap { message ->
                            message.resolvedMediaItems.mapNotNull { media ->
                                when (media.type) {
                                    MessageType.Image -> if (media.mediaCategory == "photo") media else null
                                    MessageType.Video -> media
                                    else -> null
                                }
                            }
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

    fun uploadMedia(context: Context, mediaBytes: ByteArray, mimeType: String, isVideo: Boolean) {
        if (!_uiState.value.canUploadMedia) return
        viewModelScope.launch {
            runCatching {
                if (isVideo) {
                    spaceService.sendVideoMessage(
                        context = context,
                        space = space,
                        videoBytes = mediaBytes,
                        caption = null,
                        mimeType = mimeType
                    )
                } else {
                    spaceService.sendImageMessage(
                        context = context,
                        space = space,
                        imageBytes = mediaBytes,
                        caption = null,
                        mediaCategory = "photo"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to upload this media.")
                }
            }
        }
    }

    override fun onCleared() {
        listener?.remove()
        listener = null
        super.onCleared()
    }
}

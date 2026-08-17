package com.arcinteractive.spaces.ui.screens.files

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.media.EncryptedMediaService
import com.arcinteractive.spaces.data.model.EncryptedMediaMetadata
import com.arcinteractive.spaces.data.model.MediaType
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceFileItem
import com.arcinteractive.spaces.data.model.SpaceFolder
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat

data class DocumentActionPayload(
    val filePath: String,
    val mimeType: String
)

enum class FilesSortOption(val label: String) {
    Date("Date"),
    Name("Name"),
    Size("Size"),
    Type("Type")
}

data class FilesUiState(
    val folders: List<SpaceFolder> = emptyList(),
    val allFiles: List<SpaceFileItem> = emptyList(),
    val isLoading: Boolean = false,
    val currentUserId: String? = null,
    val canUploadFiles: Boolean = false,
    val canManageAllFiles: Boolean = false,
    val searchText: String = "",
    val sortOption: FilesSortOption = FilesSortOption.Date,
    val selectedMedia: SpaceMedia? = null,
    val previewDocument: DocumentActionPayload? = null,
    val shareDocument: DocumentActionPayload? = null,
    val renameTargetFile: SpaceFileItem? = null,
    val pendingDeleteFile: SpaceFileItem? = null,
    val lastErrorMessage: String? = null,
    val lastInfoMessage: String? = null
) {
    val files: List<SpaceFileItem>
        get() {
            val query = searchText.trim()
            val filtered = allFiles.filter { file ->
                query.isEmpty() || file.name.contains(query, ignoreCase = true)
            }

            return when (sortOption) {
                FilesSortOption.Date -> filtered.sortedByDescending { it.createdAt }
                FilesSortOption.Name -> filtered.sortedBy { it.name.lowercase() }
                FilesSortOption.Size -> filtered.sortedByDescending { it.sizeBytes }
                FilesSortOption.Type -> filtered.sortedWith(
                    compareBy<SpaceFileItem> { it.typeDescription.lowercase() }
                        .thenBy { it.name.lowercase() }
                )
            }
        }
}

class FilesViewModel(
    private val space: Space,
    private val spaceService: SpaceService = SpaceService(),
    private val encryptedMediaService: EncryptedMediaService = EncryptedMediaService()
) : ViewModel() {
    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private var folderListener: ListenerRegistration? = null
    private var fileListener: ListenerRegistration? = null
    private var hasLoadedFolders = false
    private var hasLoadedFiles = false

    fun startListeningIfNeeded(context: Context) {
        if (folderListener != null || fileListener != null) return
        _uiState.update {
            it.copy(
                isLoading = true,
                currentUserId = spaceService.currentUserId(context),
                lastErrorMessage = null
            )
        }

        viewModelScope.launch {
            val canManage = spaceService.canManageModules(context, space)
            val canUpload = spaceService.canPerform(context, space, com.arcinteractive.spaces.data.model.SpacePermission.UploadFiles)
            _uiState.update {
                it.copy(
                    canManageAllFiles = canManage,
                    canUploadFiles = canUpload
                )
            }
        }

        folderListener = spaceService.listenToFolders(
            context = context,
            space = space,
            listenerKey = "files.${space.id}.folders"
        ) { result ->
            result.onSuccess { folders ->
                hasLoadedFolders = true
                _uiState.update { current ->
                    current.copy(
                        folders = folders,
                        isLoading = !(hasLoadedFolders && hasLoadedFiles)
                    )
                }
            }.onFailure { error ->
                hasLoadedFolders = true
                _uiState.update { current ->
                    current.copy(
                        isLoading = !(hasLoadedFolders && hasLoadedFiles),
                        lastErrorMessage = error.localizedMessage ?: "Unable to load folders."
                    )
                }
            }
        }

        fileListener = spaceService.listenToFiles(
            context = context,
            space = space,
            listenerKey = "files.${space.id}.items"
        ) { result ->
            result.onSuccess { files ->
                hasLoadedFiles = true
                _uiState.update { current ->
                    current.copy(
                        allFiles = files,
                        isLoading = !(hasLoadedFolders && hasLoadedFiles)
                    )
                }
            }.onFailure { error ->
                hasLoadedFiles = true
                _uiState.update { current ->
                    current.copy(
                        isLoading = !(hasLoadedFolders && hasLoadedFiles),
                        lastErrorMessage = error.localizedMessage ?: "Unable to load files."
                    )
                }
            }
        }
    }

    fun updateSearchText(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    fun updateSortOption(option: FilesSortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun canManage(file: SpaceFileItem): Boolean {
        val state = _uiState.value
        return file.uploadedBy == state.currentUserId || state.canManageAllFiles
    }

    fun uploadFile(context: Context, fileUri: android.net.Uri) {
        if (!_uiState.value.canUploadFiles) return
        viewModelScope.launch {
            runCatching {
                spaceService.uploadFile(context, space, fileUri)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to upload this file.")
                }
            }
        }
    }

    fun openFile(context: Context, file: SpaceFileItem) {
        viewModelScope.launch {
            runCatching {
                if (file.isImage || file.isVideo) {
                    val metadata = EncryptedMediaMetadata(
                        mediaId = file.id,
                        mediaType = if (file.isVideo) MediaType.Video else MediaType.Photo,
                        storagePath = file.storagePath,
                        thumbnailStoragePath = null,
                        encryptionVersion = file.encryptionVersion,
                        nonce = file.nonceBase64,
                        thumbnailNonce = null,
                        mimeType = file.mimeType,
                        fileSize = file.sizeBytes,
                        createdAt = file.createdAt,
                        uploadedBy = file.uploadedBy
                    )
                    _uiState.update {
                        it.copy(
                            selectedMedia = SpaceMedia(
                                id = file.id,
                                spaceId = space.id,
                                type = if (file.isVideo) MessageType.Video else MessageType.Image,
                                mediaCategory = if (file.isVideo) "video" else "photo",
                                mediaType = if (file.isVideo) MediaType.Video else MediaType.Photo,
                                placeholderIconName = if (file.isVideo) "video" else "photo",
                                caption = file.name,
                                senderName = file.uploadedByName,
                                timestamp = file.createdAt?.let(::formatTimestamp).orEmpty(),
                                metadata = metadata
                            )
                        )
                    }
                } else {
                    val bytes = spaceService.downloadFileBytes(context, space, file)
                    val baseName = file.name.substringBeforeLast('.', file.id).ifBlank { file.id }
                    val tempFile = encryptedMediaService.shareFile(
                        context = context,
                        bytes = bytes,
                        fileName = baseName,
                        extension = file.fileExtension
                    )
                    _uiState.update {
                        it.copy(
                            previewDocument = DocumentActionPayload(tempFile.absolutePath, file.mimeType)
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to open this file.")
                }
            }
        }
    }

    fun shareFile(context: Context, file: SpaceFileItem) {
        viewModelScope.launch {
            runCatching {
                val bytes = spaceService.downloadFileBytes(context, space, file)
                val baseName = file.name.substringBeforeLast('.', file.id).ifBlank { file.id }
                val tempFile = encryptedMediaService.shareFile(
                    context = context,
                    bytes = bytes,
                    fileName = baseName,
                    extension = file.fileExtension
                )
                _uiState.update {
                    it.copy(
                        shareDocument = DocumentActionPayload(tempFile.absolutePath, file.mimeType)
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to share this file.")
                }
            }
        }
    }

    fun downloadFile(context: Context, file: SpaceFileItem) {
        viewModelScope.launch {
            runCatching {
                val bytes = spaceService.downloadFileBytes(context, space, file)
                encryptedMediaService.saveFileToDownloads(context, bytes, file.name, file.mimeType)
            }.onSuccess { message ->
                _uiState.update { it.copy(lastInfoMessage = message) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to download this file.")
                }
            }
        }
    }

    fun beginRename(file: SpaceFileItem) {
        _uiState.update { it.copy(renameTargetFile = file) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(renameTargetFile = null) }
    }

    fun renameFile(context: Context, file: SpaceFileItem, newName: String) {
        viewModelScope.launch {
            runCatching {
                spaceService.renameFile(context, space, file, newName)
            }.onSuccess {
                _uiState.update { it.copy(renameTargetFile = null) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to rename this file.")
                }
            }
        }
    }

    fun requestDelete(file: SpaceFileItem) {
        _uiState.update { it.copy(pendingDeleteFile = file) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(pendingDeleteFile = null) }
    }

    fun deletePendingFile(context: Context) {
        val file = _uiState.value.pendingDeleteFile ?: return
        viewModelScope.launch {
            runCatching {
                spaceService.softDeleteFile(context, space, file)
            }.onSuccess {
                _uiState.update { it.copy(pendingDeleteFile = null) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to delete this file.")
                }
            }
        }
    }

    fun clearSelectedMedia() {
        _uiState.update { it.copy(selectedMedia = null) }
    }

    fun consumePreviewDocument() {
        _uiState.update { it.copy(previewDocument = null) }
    }

    fun consumeShareDocument() {
        _uiState.update { it.copy(shareDocument = null) }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    fun clearLastInfoMessage() {
        _uiState.update { it.copy(lastInfoMessage = null) }
    }

    override fun onCleared() {
        folderListener?.remove()
        fileListener?.remove()
        super.onCleared()
    }
}

private fun formatTimestamp(date: java.util.Date): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
}

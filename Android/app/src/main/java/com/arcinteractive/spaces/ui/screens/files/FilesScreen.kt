package com.arcinteractive.spaces.ui.screens.files

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceFileItem
import com.arcinteractive.spaces.data.model.SpaceFolder
import com.arcinteractive.spaces.ui.components.MediaViewerPlaceholder
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    space: Space,
    onBackPressed: () -> Unit,
    initialFileId: String? = null,
    viewModel: FilesViewModel = viewModel(factory = FilesViewModelFactory(space))
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var isSortMenuExpanded by remember { mutableStateOf(false) }
    var renameDraft by remember(uiState.renameTargetFile?.id) {
        mutableStateOf(uiState.renameTargetFile?.name.orEmpty())
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadFile(context, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startListeningIfNeeded(context)
    }

    LaunchedEffect(uiState.files, initialFileId) {
        val targetId = initialFileId ?: return@LaunchedEffect
        uiState.files.firstOrNull { it.id == targetId }?.let { file ->
            viewModel.openFile(context, file)
        }
    }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    LaunchedEffect(uiState.lastInfoMessage) {
        val message = uiState.lastInfoMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastInfoMessage()
    }

    LaunchedEffect(uiState.previewDocument) {
        val payload = uiState.previewDocument ?: return@LaunchedEffect
        val file = File(payload.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, payload.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, null))
        }.onFailure {
            snackbarHostState.showSnackbar(it.localizedMessage ?: "Unable to open this file.")
        }
        viewModel.consumePreviewDocument()
    }

    LaunchedEffect(uiState.shareDocument) {
        val payload = uiState.shareDocument ?: return@LaunchedEffect
        val file = File(payload.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, null))
        }.onFailure {
            snackbarHostState.showSnackbar(it.localizedMessage ?: "Unable to share this file.")
        }
        viewModel.consumeShareDocument()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { isSortMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Sort,
                                contentDescription = "Sort"
                            )
                        }
                        DropdownMenu(
                            expanded = isSortMenuExpanded,
                            onDismissRequest = { isSortMenuExpanded = false }
                        ) {
                            FilesSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        isSortMenuExpanded = false
                                        viewModel.updateSortOption(option)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.canUploadFiles) {
                FloatingActionButton(onClick = { openDocumentLauncher.launch(arrayOf("*/*")) }) {
                    Icon(
                        imageVector = Icons.Outlined.FileUpload,
                        contentDescription = "Upload File"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = uiState.searchText,
                        onValueChange = viewModel::updateSearchText,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search files") },
                        singleLine = true
                    )
                }

                if (uiState.folders.isEmpty() && uiState.files.isEmpty()) {
                    item {
                        EmptyFilesState(space = space, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    if (uiState.folders.isNotEmpty()) {
                        item {
                            Text(
                                text = "Folders",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        items(uiState.folders, key = { it.id }) { folder ->
                            FolderRow(folder = folder)
                        }
                    }

                    if (uiState.files.isNotEmpty()) {
                        item {
                            Text(
                                text = "Files",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        items(uiState.files, key = { it.id }) { file ->
                            FileRow(
                                file = file,
                                canManage = viewModel.canManage(file),
                                onOpen = { viewModel.openFile(context, file) },
                                onDownload = { viewModel.downloadFile(context, file) },
                                onShare = { viewModel.shareFile(context, file) },
                                onRename = { viewModel.beginRename(file) },
                                onDelete = { viewModel.requestDelete(file) }
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.selectedMedia?.let { media ->
        MediaViewerPlaceholder(
            space = space,
            media = media,
            onDismiss = viewModel::clearSelectedMedia
        )
    }

    uiState.renameTargetFile?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renameFile(context, target, renameDraft) }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) {
                    Text("Cancel")
                }
            }
        )
    }

    if (uiState.pendingDeleteFile != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete this file?") },
            text = { Text("This will hide the file from the Space. The encrypted upload will remain in storage for now.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deletePendingFile(context) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FolderRow(folder: SpaceFolder) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = "Created by ${folder.createdBy}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileRow(
    file: SpaceFileItem,
    canManage: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("${file.iconEmoji} ${file.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${file.uploadedByName} • ${file.typeDescription} • ${file.sizeDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                file.createdAt?.let {
                    Text(
                        text = formatTimestamp(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box {
                IconButton(onClick = { isMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = "More"
                    )
                }
                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        onClick = {
                            isMenuExpanded = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download") },
                        onClick = {
                            isMenuExpanded = false
                            onDownload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            isMenuExpanded = false
                            onShare()
                        }
                    )
                    if (canManage) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                isMenuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                isMenuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(date: Date): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
}

@Composable
private fun EmptyFilesState(space: Space, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Text(
                    text = "No Files Yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Upload PDFs, documents, photos, videos, audio, archives, and other shared files for ${space.name}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private class FilesViewModelFactory(
    private val space: Space
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FilesViewModel(space) as T
    }
}

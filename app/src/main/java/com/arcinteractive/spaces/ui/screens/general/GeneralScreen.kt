package com.arcinteractive.spaces.ui.screens.general

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.ui.components.MediaViewerPlaceholder
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.ui.components.MessageBubble
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralScreen(
    space: Space,
    onBackPressed: () -> Unit,
    viewModel: GeneralViewModel = viewModel(factory = GeneralViewModelFactory(space))
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isAttachmentSheetVisible by remember { mutableStateOf(false) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var isConversationMenuExpanded by remember { mutableStateOf(false) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        val mimeType = context.contentResolver.getType(uri)
        val mediaBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val isVideo = mimeType?.startsWith("video/") == true
        val previewBytes = if (isVideo) {
            videoPreviewBytes(context, uri)
        } else {
            mediaBytes
        }
        viewModel.selectComposerMedia(
            mediaBytes = mediaBytes,
            previewBytes = previewBytes,
            mimeType = mimeType ?: if (isVideo) "video/mp4" else "image/jpeg",
            mediaCategory = if (isVideo) "video" else "photo",
            isVideo = isVideo
        )
    }

    LaunchedEffect(Unit) {
        viewModel.loadMessagesIfNeeded(context)
    }

    LaunchedEffect(uiState.messages.size) {
        val visibleCount = uiState.messages.count { !it.deleted }
        if (!uiState.isSearchPresented && visibleCount > 0) {
            val headerOffset = 1 + if (uiState.secureAccessMessage != null) 1 else 0
            listState.animateScrollToItem(headerOffset + visibleCount - 1)
        }
    }

    LaunchedEffect(uiState.selectedSearchMatchIndex, uiState.searchMatchMessageIds) {
        val targetId = viewModel.currentSearchMatchMessageId() ?: return@LaunchedEffect
        val visibleMessages = uiState.messages.filter { !it.deleted }
        val headerOffset = 1 + if (uiState.secureAccessMessage != null) 1 else 0
        val visibleIndex = visibleMessages.indexOfFirst { it.id == targetId }
        if (visibleIndex >= 0) {
            listState.animateScrollToItem(headerOffset + visibleIndex)
            highlightedMessageId = targetId
            delay(1200)
            if (highlightedMessageId == targetId) {
                highlightedMessageId = null
            }
        }
    }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("General") },
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
                        IconButton(onClick = { isConversationMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = "More"
                            )
                        }
                        DropdownMenu(
                            expanded = isConversationMenuExpanded,
                            onDismissRequest = { isConversationMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Search") },
                                onClick = {
                                    isConversationMenuExpanded = false
                                    viewModel.presentSearch()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            ComposerBar(
                text = uiState.composerText,
                canSend = uiState.canSend,
                isMediaEnabled = !uiState.isSending && uiState.canUploadMedia,
                onTextChange = viewModel::updateComposerText,
                hasAttachment = uiState.selectedComposerPreviewBytes != null,
                replyingToMessage = uiState.replyingToMessage,
                editingMessage = uiState.editingMessage,
                onAttachmentClick = { isAttachmentSheetVisible = true },
                onCancelReply = viewModel::cancelReply,
                onCancelEditing = viewModel::cancelEditing,
                onVoicePlaceholderClick = {
                    scope.launch { snackbarHostState.showSnackbar("Voice messages are not ready yet.") }
                },
                onSend = {
                    viewModel.sendComposer(context)
                },
                selectedImageBitmap = viewModel.selectedComposerBitmap()?.asImageBitmap(),
                selectedMediaIsVideo = uiState.selectedComposerIsVideo,
                onRemoveSelectedImage = viewModel::removeComposerMedia
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(android.graphics.Color.parseColor(space.colorHex)).copy(alpha = 0.18f)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = space.emoji,
                            fontSize = 28.sp
                        )
                        Column {
                            Text(
                                text = space.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = space.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (uiState.isSearchPresented) {
                item {
                    ConversationSearchBar(
                        searchText = uiState.searchText,
                        selectedIndex = uiState.selectedSearchMatchIndex,
                        totalMatches = uiState.searchMatchMessageIds.size,
                        onSearchTextChange = viewModel::updateSearchText,
                        onPrevious = viewModel::selectPreviousSearchMatch,
                        onNext = viewModel::selectNextSearchMatch,
                        onDone = viewModel::dismissSearch
                    )
                }
            }

            uiState.secureAccessMessage?.let { secureAccessMessage ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = secureAccessMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val visibleMessages = uiState.messages.filter { !it.deleted }

            itemsIndexed(visibleMessages) { _, message ->
                val replyContext = message.replyContext
                val resolvedReplyPresentation = if (replyContext != null) {
                    replyContext.copy(
                        preview = if (uiState.messages.firstOrNull { it.id == replyContext.messageId }?.deleted == true) {
                            "Original message unavailable"
                        } else {
                            replyContext.preview
                        }
                    )
                } else {
                    null
                }
                MessageBubble(
                    message = message,
                    onMediaClick = viewModel::openMedia,
                    onReplyClick = { viewModel.beginReply(message) },
                    onEditClick = if (viewModel.canEdit(message)) {
                        { viewModel.beginEditing(message) }
                    } else {
                        null
                    },
                    replyPresentation = resolvedReplyPresentation,
                    onReplyPreviewClick = {
                        val targetId = replyContext?.messageId
                        if (targetId != null) {
                            val targetMessage = uiState.messages.firstOrNull { it.id == targetId }
                            if (targetMessage != null && !targetMessage.deleted) {
                                val headerOffset = 1 + if (uiState.secureAccessMessage != null) 1 else 0
                                val visibleIndex = visibleMessages.indexOfFirst { it.id == targetId }
                                if (visibleIndex >= 0) {
                                    scope.launch {
                                        listState.animateScrollToItem(headerOffset + visibleIndex)
                                        highlightedMessageId = targetId
                                        delay(1200)
                                        if (highlightedMessageId == targetId) {
                                            highlightedMessageId = null
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onDeleteClick = if (viewModel.canDelete(message)) {
                        { viewModel.deleteMessage(context, message) }
                    } else {
                        null
                    },
                    reactionOptions = viewModel.reactionOptions(message),
                    onReactionClick = { emoji ->
                        viewModel.toggleReaction(context, message, emoji)
                    },
                    isHighlighted = highlightedMessageId == message.id,
                    searchQuery = uiState.searchText
                )
            }
        }

        uiState.selectedMedia?.let { media ->
            MediaViewerPlaceholder(
                space = space,
                media = media,
                onDismiss = viewModel::dismissMedia
            )
        }

        if (isAttachmentSheetVisible) {
            AttachmentSheet(
                onDismiss = { isAttachmentSheetVisible = false },
                onSelectCamera = {
                    isAttachmentSheetVisible = false
                    scope.launch { snackbarHostState.showSnackbar("Camera capture will be added in a future update.") }
                },
                onSelectPhotos = {
                    isAttachmentSheetVisible = false
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onSelectMemes = {
                    isAttachmentSheetVisible = false
                    scope.launch { snackbarHostState.showSnackbar("GIF and meme picking will be added in a separate media flow.") }
                },
                onSelectVoice = {
                    isAttachmentSheetVisible = false
                    scope.launch { snackbarHostState.showSnackbar("Voice messages are not ready yet.") }
                },
                onSelectFiles = {
                    isAttachmentSheetVisible = false
                    scope.launch { snackbarHostState.showSnackbar("File attachments are not ready yet.") }
                }
            )
        }
    }
}

@Composable
private fun ComposerBar(
    text: String,
    canSend: Boolean,
    isMediaEnabled: Boolean,
    hasAttachment: Boolean,
    replyingToMessage: SpaceMessage?,
    editingMessage: SpaceMessage?,
    onTextChange: (String) -> Unit,
    onAttachmentClick: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEditing: () -> Unit,
    onVoicePlaceholderClick: () -> Unit,
    onSend: () -> Unit,
    selectedImageBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    selectedMediaIsVideo: Boolean,
    onRemoveSelectedImage: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            editingMessage?.let { message ->
                EditComposerPreview(
                    message = message,
                    onCancelEditing = onCancelEditing
                )
            }

            replyingToMessage?.let { message ->
                ReplyComposerPreview(
                    message = message,
                    onCancelReply = onCancelReply
                )
            }

            selectedImageBitmap?.let { bitmap ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = if (selectedMediaIsVideo) "Selected video" else "Selected photo",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (selectedMediaIsVideo) "Selected video" else "Selected photo",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Add an optional caption below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onRemoveSelectedImage) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                FilledTonalIconButton(onClick = onAttachmentClick, enabled = isMediaEnabled) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Attachments"
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                editingMessage != null -> "Edit message"
                                hasAttachment -> "Add a caption..."
                                else -> "Message"
                            }
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    singleLine = false
                )

                if (canSend) {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = true,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Send"
                        )
                    }
                } else {
                    FilledTonalIconButton(
                        onClick = onVoicePlaceholderClick,
                        enabled = isMediaEnabled,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Mic,
                            contentDescription = "Voice"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditComposerPreview(
    message: com.arcinteractive.spaces.data.model.SpaceMessage,
    onCancelEditing: () -> Unit
) {
    val preview = message.text?.trim().orEmpty().ifBlank { "Message" }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp),
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(999.dp)
        ) {}

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Editing message",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "\"$preview\"",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        IconButton(onClick = onCancelEditing) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Cancel",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ReplyComposerPreview(
    message: com.arcinteractive.spaces.data.model.SpaceMessage,
    onCancelReply: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp),
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(999.dp)
        ) {}

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Replying to ${message.senderName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when (message.type) {
                    com.arcinteractive.spaces.data.model.MessageType.Video -> "\uD83C\uDFA5 Video"
                    com.arcinteractive.spaces.data.model.MessageType.File -> "\uD83D\uDCC4 File"
                    com.arcinteractive.spaces.data.model.MessageType.Image,
                    com.arcinteractive.spaces.data.model.MessageType.Meme,
                    com.arcinteractive.spaces.data.model.MessageType.Gif,
                    com.arcinteractive.spaces.data.model.MessageType.Screenshot -> "\uD83D\uDCF7 Photo"
                    else -> message.text?.trim().orEmpty().ifBlank { "Message" }.take(80)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        IconButton(onClick = onCancelReply) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Cancel",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ConversationSearchBar(
    searchText: String,
    selectedIndex: Int?,
    totalMatches: Int,
    onSearchTextChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search messages") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                TextButton(onClick = onDone) {
                    Text("Done")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        searchText.isBlank() -> "Search this conversation"
                        totalMatches == 0 -> "No matches"
                        selectedIndex != null -> "${selectedIndex + 1} of $totalMatches"
                        else -> "$totalMatches matches"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.weight(1f))

                TextButton(onClick = onPrevious, enabled = totalMatches > 0) {
                    Text("Previous")
                }
                TextButton(onClick = onNext, enabled = totalMatches > 0) {
                    Text("Next")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onSelectCamera: () -> Unit,
    onSelectPhotos: () -> Unit,
    onSelectMemes: () -> Unit,
    onSelectVoice: () -> Unit,
    onSelectFiles: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = "Attachments",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            AttachmentSheetItem(Icons.Outlined.PhotoCamera, "Camera", onSelectCamera)
            AttachmentSheetItem(Icons.Outlined.AddPhotoAlternate, "Photos & Videos", onSelectPhotos)
            AttachmentSheetItem(Icons.Outlined.Gif, "GIFs & Memes", onSelectMemes)
            AttachmentSheetItem(Icons.Outlined.Mic, "Voice Message", onSelectVoice)
            AttachmentSheetItem(Icons.Outlined.Description, "Files", onSelectFiles)
        }
    }
}

@Composable
private fun AttachmentSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { 
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary
            ) 
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        supportingContent = null,
        overlineContent = null,
        trailingContent = null
    )
}

private fun videoPreviewBytes(context: android.content.Context, uri: android.net.Uri): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        bitmap.toJpegBytes(72)
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return stream.toByteArray()
}

private class GeneralViewModelFactory(
    private val space: Space
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return GeneralViewModel(space) as T
    }
}

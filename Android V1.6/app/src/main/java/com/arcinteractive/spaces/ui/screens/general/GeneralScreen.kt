package com.arcinteractive.spaces.ui.screens.general

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.ui.components.MediaViewerPlaceholder
import com.arcinteractive.spaces.ui.components.rememberGifPickerLauncher
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.data.model.SpaceLinkModuleType
import com.arcinteractive.spaces.data.spaces.SpaceLinkModuleDescriptor
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistry
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistryItem
import com.arcinteractive.spaces.ui.navigation.Destination
import com.arcinteractive.spaces.data.model.LinkPreviewData
import com.arcinteractive.spaces.ui.components.MessageBubble
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralScreen(
    space: Space,
    onBackPressed: () -> Unit,
    onNavigateToRoute: ((String) -> Unit)? = null,
    viewModel: GeneralViewModel = viewModel(factory = GeneralViewModelFactory(space))
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isAttachmentSheetVisible by remember { mutableStateOf(false) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var isConversationMenuExpanded by remember { mutableStateOf(false) }
    val linkRegistry = remember { SpaceLinkRegistry() }
    var isModuleLinkPickerVisible by remember { mutableStateOf(false) }
    var activeLinkModule by remember { mutableStateOf<SpaceLinkModuleDescriptor?>(null) }
    var activeLinkItems by remember { mutableStateOf<List<SpaceLinkRegistryItem>>(emptyList()) }
    var isLoadingLinkItems by remember { mutableStateOf(false) }
    val gifPickerLauncher = rememberGifPickerLauncher(
        onGifSelected = { selection ->
            viewModel.selectComposerMedia(
                mediaBytes = selection.gifBytes,
                previewBytes = selection.previewBytes,
                mimeType = selection.mimeType,
                mediaCategory = "gif",
                isVideo = false
            )
        },
        onError = { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    )
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isEmpty()) {
            return@rememberLauncherForActivityResult
        }

        val selections = uris.mapNotNull { uri ->
            val mimeType = context.contentResolver.getType(uri)
            val mediaBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val isVideo = mimeType?.startsWith("video/") == true
            val previewBytes = if (isVideo) {
                videoPreviewBytes(context, uri)
            } else {
                mediaBytes
            }

            if (mediaBytes == null || previewBytes == null) {
                null
            } else {
                ComposerMediaSelection(
                    mediaBytes = mediaBytes,
                    previewBytes = previewBytes,
                    mimeType = mimeType ?: if (isVideo) "video/mp4" else "image/jpeg",
                    mediaCategory = if (isVideo) "video" else "photo",
                    isVideo = isVideo
                )
            }
        }

        if (selections.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("Unable to load the selected media.") }
            return@rememberLauncherForActivityResult
        }

        if (selections.size > 1 && selections.any { it.isVideo }) {
            scope.launch { snackbarHostState.showSnackbar("Select one video or up to 10 photos.") }
            return@rememberLauncherForActivityResult
        }

        if (selections.size == 1) {
            val selection = selections.first()
            viewModel.selectComposerMedia(
                mediaBytes = selection.mediaBytes,
                previewBytes = selection.previewBytes,
                mimeType = selection.mimeType,
                mediaCategory = selection.mediaCategory,
                isVideo = selection.isVideo
            )
        } else {
            viewModel.selectComposerMediaItems(selections)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.bindTypingContext(context)
        viewModel.loadMessagesIfNeeded(context)
    }

    DisposableEffect(space.id) {
        onDispose {
            viewModel.stopTypingIndicators(context)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.handleAppForegrounded(context)
                Lifecycle.Event.ON_STOP -> viewModel.handleAppBackgrounded(context)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
                title = { Text("Space Pings") },
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
                            if (uiState.hasSavedDraft) {
                                DropdownMenuItem(
                                    text = { Text("Discard Draft") },
                                    onClick = {
                                        isConversationMenuExpanded = false
                                        viewModel.discardDraft()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            ComposerBar(
                text = uiState.composerText,
                typingIndicatorText = uiState.typingIndicatorText,
                canSend = uiState.canSend,
                isMediaEnabled = !uiState.isSending && uiState.canUploadMedia,
                onTextChange = { viewModel.updateComposerText(it) },
                hasAttachment = uiState.selectedComposerMediaItems.isNotEmpty(),
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
                composerLinkPreview = uiState.composerLinkPreview,
                isLoadingLinkPreview = uiState.isLoadingLinkPreview,
                composerSpaceLinks = uiState.composerSpaceLinks,
                selectedImageBitmaps = viewModel.selectedComposerBitmaps().map { it.asImageBitmap() },
                selectedMediaItems = uiState.selectedComposerMediaItems,
                onRemoveSelectedImage = { id -> viewModel.removeComposerMedia(id) },
                onRemoveSpaceLink = { id -> viewModel.removeComposerSpaceLink(id) }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }
                },
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
                    onSpaceLinkClick = { link ->
                        val route = routeForSpaceLink(space.id, link)
                        if (route == null) {
                            scope.launch { snackbarHostState.showSnackbar("This item is no longer available.") }
                        } else {
                            onNavigateToRoute?.invoke(route)
                        }
                    },
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
                    onRetryFailedMessage = if (message.localDeliveryState == com.arcinteractive.spaces.data.model.LocalMessageDeliveryState.Failed) {
                        { viewModel.retryQueuedMessage(context, message.id) }
                    } else {
                        null
                    },
                    onDeleteFailedMessage = if (message.localDeliveryState == com.arcinteractive.spaces.data.model.LocalMessageDeliveryState.Failed) {
                        { viewModel.deleteQueuedMessage(context, message.id) }
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
                onSelectGifs = {
                    isAttachmentSheetVisible = false
                    gifPickerLauncher()
                },
                onSelectPhotos = {
                    isAttachmentSheetVisible = false
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onSelectLinks = {
                    isAttachmentSheetVisible = false
                    isModuleLinkPickerVisible = true
                }
            )
        }

        if (isModuleLinkPickerVisible) {
            SpaceLinkModulePickerDialog(
                modules = linkRegistry.availableModules(space),
                onDismiss = { isModuleLinkPickerVisible = false },
                onSelect = { module ->
                    isModuleLinkPickerVisible = false
                    activeLinkModule = module
                    isLoadingLinkItems = true
                    scope.launch {
                        activeLinkItems = runCatching {
                            linkRegistry.fetchItems(context, space, module.moduleType)
                        }.onFailure { error ->
                            snackbarHostState.showSnackbar(error.localizedMessage ?: "Unable to load links.")
                            activeLinkModule = null
                        }.getOrDefault(emptyList())
                        isLoadingLinkItems = false
                    }
                }
            )
        }

        activeLinkModule?.let { module ->
            SpaceLinkItemPickerDialog(
                module = module,
                items = activeLinkItems,
                isLoading = isLoadingLinkItems,
                onDismiss = {
                    activeLinkModule = null
                    activeLinkItems = emptyList()
                    isLoadingLinkItems = false
                },
                onSelect = { item ->
                    viewModel.addComposerSpaceLink(item.attachment)
                    activeLinkModule = null
                    activeLinkItems = emptyList()
                    isLoadingLinkItems = false
                }
            )
        }

    }
}

@Composable
private fun ComposerBar(
    text: String,
    typingIndicatorText: String?,
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
    composerLinkPreview: com.arcinteractive.spaces.data.model.LinkPreviewData?,
    isLoadingLinkPreview: Boolean,
    composerSpaceLinks: List<SpaceLinkAttachment>,
    selectedImageBitmaps: List<androidx.compose.ui.graphics.ImageBitmap>,
    selectedMediaItems: List<ComposerMediaSelection>,
    onRemoveSelectedImage: (String?) -> Unit,
    onRemoveSpaceLink: (String) -> Unit
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                androidx.compose.animation.AnimatedVisibility(visible = !typingIndicatorText.isNullOrBlank()) {
                    Text(
                        text = typingIndicatorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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

            if (isLoadingLinkPreview || composerLinkPreview != null) {
                ComposerLinkPreviewRow(
                    preview = composerLinkPreview,
                    isLoading = isLoadingLinkPreview
                )
            }

            if (composerSpaceLinks.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(composerSpaceLinks, key = { it.id }) { link ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = linkEmoji(link))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = link.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = link.subtitle?.takeIf { it.isNotBlank() } ?: link.moduleType.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveSpaceLink(link.id) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Remove link",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selectedImageBitmaps.isNotEmpty() && selectedMediaItems.isNotEmpty()) {
                if (selectedMediaItems.size == 1) {
                    val mediaItem = selectedMediaItems.first()
                    val bitmap = selectedImageBitmaps.first()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = if (mediaItem.isVideo) "Selected video" else "Selected photo",
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
                                text = if (mediaItem.isVideo) "Selected video" else "Selected photo",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Add an optional caption below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = { onRemoveSelectedImage(mediaItem.id) }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${selectedMediaItems.size} photos selected",
                            style = MaterialTheme.typography.titleSmall
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 4.dp)
                        ) {
                            items(
                                items = selectedMediaItems.zip(selectedImageBitmaps),
                                key = { it.first.id }
                            ) { (mediaItem, bitmap) ->
                                Box {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "Selected photo",
                                        modifier = Modifier
                                            .size(88.dp)
                                            .clip(RoundedCornerShape(16.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp),
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                    ) {
                                        IconButton(
                                            onClick = { onRemoveSelectedImage(mediaItem.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Remove",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            text = "Add an optional caption below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                hasAttachment || composerSpaceLinks.isNotEmpty() -> "Add a caption..."
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
private fun ComposerLinkPreviewRow(
    preview: LinkPreviewData?,
    isLoading: Boolean
) {
    if (isLoading && preview == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = "Loading preview...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    preview ?: return
    val imageBytes = remember(preview.imageDataBase64) {
        preview.imageDataBase64?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }
    }
    val bitmap = remember(imageBytes) {
        imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = preview.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            preview.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Text(
                text = preview.domain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SpaceLinkModulePickerDialog(
    modules: List<SpaceLinkModuleDescriptor>,
    onDismiss: () -> Unit,
    onSelect: (SpaceLinkModuleDescriptor) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (modules.isEmpty()) {
                    Text("No linkable modules are available in this Space yet.")
                } else {
                    modules.forEach { module ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(module) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(module.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    module.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SpaceLinkItemPickerDialog(
    module: SpaceLinkModuleDescriptor,
    items: List<SpaceLinkRegistryItem>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SpaceLinkRegistryItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(module.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isLoading -> CircularProgressIndicator()
                    items.isEmpty() -> Text("No items are available yet.")
                    else -> items.forEach { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun routeForSpaceLink(spaceId: String, link: SpaceLinkAttachment): String? = when (link.moduleType) {
    SpaceLinkModuleType.Announcements -> Destination.Announcements.routeFor(spaceId)
    SpaceLinkModuleType.Polls -> Destination.PollsPlaceholder.routeFor(spaceId, link.targetId)
    SpaceLinkModuleType.Files -> Destination.FilesPlaceholder.routeFor(spaceId, link.targetId)
    SpaceLinkModuleType.Events -> Destination.EventsPlaceholder.routeFor(spaceId, link.targetId)
    SpaceLinkModuleType.Rooms -> Destination.Rooms.routeFor(spaceId, link.targetId)
    SpaceLinkModuleType.Media -> Destination.PhotosPlaceholder.routeFor(spaceId, link.targetId)
    SpaceLinkModuleType.Lists -> Destination.Lists.routeFor(spaceId, link.targetId)
    SpaceLinkModuleType.Notes -> Destination.Notes.routeFor(spaceId, link.targetId)
}

private fun linkEmoji(link: SpaceLinkAttachment): String = when (link.moduleType) {
    SpaceLinkModuleType.Announcements -> "\uD83D\uDCE2"
    SpaceLinkModuleType.Polls -> "\uD83D\uDCCA"
    SpaceLinkModuleType.Files -> "\uD83D\uDCC1"
    SpaceLinkModuleType.Events -> "\uD83D\uDCC5"
    SpaceLinkModuleType.Rooms -> "\uD83D\uDCAC"
    SpaceLinkModuleType.Media -> "\uD83D\uDDBC\uFE0F"
    SpaceLinkModuleType.Lists -> "✅"
    SpaceLinkModuleType.Notes -> "📝"
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
    onSelectGifs: () -> Unit,
    onSelectPhotos: () -> Unit,
    onSelectLinks: () -> Unit
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
            AttachmentSheetItem(Icons.Outlined.Link, "Link", onSelectLinks)
            AttachmentSheetItem(Icons.Outlined.GifBox, "GIFs", onSelectGifs)
            AttachmentSheetItem(Icons.Outlined.AddPhotoAlternate, "Photos & Videos", onSelectPhotos)
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

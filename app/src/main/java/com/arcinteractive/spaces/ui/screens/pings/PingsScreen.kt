package com.arcinteractive.spaces.ui.screens.pings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.Ping
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.ui.components.MessageBubble
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingsScreen(
    initialPingId: String? = null,
    onExitConversation: (() -> Unit)? = null,
    viewModel: PingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showComposerSheet by remember { mutableStateOf(false) }
    var pendingDeleteMessage by remember { mutableStateOf<SpaceMessage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.startListeningIfNeeded(context)
    }

    LaunchedEffect(initialPingId, uiState.pings) {
        val pingId = initialPingId ?: return@LaunchedEffect
        val ping = uiState.pings.firstOrNull { it.id == pingId } ?: return@LaunchedEffect
        if (uiState.selectedPing?.id != pingId) {
            viewModel.openPing(context, ping)
        }
    }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    val selectedPing = uiState.selectedPing

    if (selectedPing == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pings") }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    showComposerSheet = true
                    viewModel.loadParticipants(context)
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "New Ping"
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.pings, key = { it.id }) { ping ->
                    PingRow(
                        ping = ping,
                        currentUserId = viewModel.currentUserId(context),
                        onClick = { viewModel.openPing(context, ping) }
                    )
                }

                if (!uiState.isLoading && uiState.pings.isEmpty()) {
                    item {
                        Text(
                            text = "No Pings yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (showComposerSheet) {
            ModalBottomSheet(onDismissRequest = { showComposerSheet = false }) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text("New Ping", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    items(uiState.availableParticipants, key = { it.id }) { participant ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.createOrOpenPing(context, participant) {
                                        showComposerSheet = false
                                    }
                                },
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(participant.emojiAvatar, fontSize = 24.sp)
                                Text(participant.displayName, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    } else {
        PingConversationScreen(
            ping = selectedPing,
            currentUserId = viewModel.currentUserId(context),
            messages = uiState.messages.filter { !it.deleted },
            composerText = uiState.composerText,
            canSend = uiState.canSend,
            replyingToMessage = uiState.replyingToMessage,
            editingMessage = uiState.editingMessage,
            onBack = {
                viewModel.closePing()
                onExitConversation?.invoke()
            },
            onComposerTextChange = viewModel::updateComposerText,
            onSend = { viewModel.sendComposer(context) },
            onReply = viewModel::beginReply,
            onCancelReply = viewModel::cancelReply,
            onEdit = viewModel::beginEditing,
            onCancelEditing = viewModel::cancelEditing,
            canEdit = viewModel::canEdit,
            canDelete = viewModel::canDelete,
            onDeleteRequest = { pendingDeleteMessage = it },
            reactionOptions = viewModel::reactionOptions,
            onToggleReaction = { message, emoji -> viewModel.toggleReaction(context, message, emoji) }
        )
    }

    if (pendingDeleteMessage != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteMessage = null },
            title = { Text("Delete this message?") },
            text = { Text("This will remove it from the conversation for everyone in this Ping.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteMessage?.let { viewModel.deleteMessage(context, it) }
                        pendingDeleteMessage = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PingRow(
    ping: Ping,
    currentUserId: String?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = ping.emoji(currentUserId), fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = ping.title(currentUserId), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = ping.timestampText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(text = ping.lastMessagePreviewText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PingConversationScreen(
    ping: Ping,
    currentUserId: String?,
    messages: List<SpaceMessage>,
    composerText: String,
    canSend: Boolean,
    replyingToMessage: SpaceMessage?,
    editingMessage: SpaceMessage?,
    onBack: () -> Unit,
    onComposerTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onReply: (SpaceMessage) -> Unit,
    onCancelReply: () -> Unit,
    onEdit: (SpaceMessage) -> Unit,
    onCancelEditing: () -> Unit,
    canEdit: (SpaceMessage) -> Boolean,
    canDelete: (SpaceMessage) -> Boolean,
    onDeleteRequest: (SpaceMessage) -> Unit,
    reactionOptions: (SpaceMessage) -> List<String>,
    onToggleReaction: (SpaceMessage, String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ping.title(currentUserId)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                editingMessage?.let {
                    ComposerStateCard(
                        title = "Editing message",
                        subtitle = it.text.orEmpty(),
                        onClose = onCancelEditing
                    )
                }
                replyingToMessage?.let {
                    ComposerStateCard(
                        title = "Replying to ${it.senderName}",
                        subtitle = it.text.orEmpty().ifBlank { "Message" },
                        onClose = onCancelReply
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Attachments"
                        )
                    }
                    OutlinedTextField(
                        value = composerText,
                        onValueChange = onComposerTextChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (editingMessage != null) "Edit message" else "Message") }
                    )
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = ping.emoji(currentUserId), fontSize = 28.sp)
                        Column {
                            Text(text = ping.title(currentUserId), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = "Private conversation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    onMediaClick = { _ -> },
                    onReplyClick = { onReply(message) },
                    onEditClick = if (canEdit(message)) ({ onEdit(message) }) else null,
                    onDeleteClick = if (canDelete(message)) ({ onDeleteRequest(message) }) else null,
                    reactionOptions = reactionOptions(message),
                    onReactionClick = { emoji -> onToggleReaction(message, emoji) }
                )
            }
        }
    }
}

@Composable
private fun ComposerStateCard(
    title: String,
    subtitle: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

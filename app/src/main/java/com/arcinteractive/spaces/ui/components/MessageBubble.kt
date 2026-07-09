package com.arcinteractive.spaces.ui.components

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcinteractive.spaces.data.media.EncryptedMediaService
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.model.SpaceMessage
import kotlinx.coroutines.launch

@Composable
fun MessageBubble(
    message: SpaceMessage,
    onMediaClick: (SpaceMedia) -> Unit,
    onReplyClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null,
    replyPresentation: MessageReplyContext? = null,
    onReplyPreviewClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    reactionOptions: List<String> = emptyList(),
    onReactionClick: ((String) -> Unit)? = null,
    isHighlighted: Boolean = false,
    searchQuery: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val encryptedMediaService = remember { EncryptedMediaService() }
    val scope = rememberCoroutineScope()
    var isReactionMenuExpanded by remember(message.id) { mutableStateOf(false) }
    var isMoreMenuExpanded by remember(message.id) { mutableStateOf(false) }
    var isDeleteConfirmationPresented by remember(message.id) { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Column(
                horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (message.media != null) {
                            onMediaClick(message.media)
                        }
                    },
                    onLongClick = {
                        if (reactionOptions.isNotEmpty()) {
                            isReactionMenuExpanded = true
                        }
                    }
                )
            ) {
                if (!message.isOutgoing) {
                    Text(
                        text = highlightedAnnotatedString(message.senderName, searchQuery),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
                    )
                }

                replyPresentation?.let { replyContext ->
                    ReplyPreviewCard(
                        replyContext = replyContext,
                        isUnavailable = replyContext.preview == "Original message unavailable",
                        searchQuery = searchQuery,
                        onClick = onReplyPreviewClick,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                when {
                    message.media != null -> MediaMessageBubble(
                        media = message.media,
                        isOutgoing = message.isOutgoing,
                        onClick = { onMediaClick(message.media) },
                        isHighlighted = isHighlighted
                    )

                    !message.text.isNullOrBlank() -> TextMessageBubble(
                        text = message.text,
                        isOutgoing = message.isOutgoing,
                        isHighlighted = isHighlighted,
                        searchQuery = searchQuery
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 2.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = message.timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (message.isEdited) {
                            Text(
                                text = "Edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        message.deliveryStatus?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (canShowMoreActions(message, onEditClick, onDeleteClick)) {
                        IconButton(
                            onClick = { isMoreMenuExpanded = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text(
                                text = "⋯",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 18.sp
                            )
                        }
                    }
                }

                if (message.reactions.isNotEmpty()) {
                    ReactionRow(
                        reactions = message.reactions,
                        onReactionClick = onReactionClick,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (reactionOptions.isNotEmpty()) {
                DropdownMenu(
                    expanded = isReactionMenuExpanded,
                    onDismissRequest = { isReactionMenuExpanded = false }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        reactionOptions.forEach { emoji ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (message.reactions.any { it.isSelectedByCurrentUser && it.emoji == emoji }) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier.clickable {
                                    isReactionMenuExpanded = false
                                    onReactionClick?.invoke(emoji)
                                }
                            ) {
                                Text(
                                    text = emoji,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    fontSize = 20.sp
                                )
                            }
                        }
                    }
                }
            }

            if (canShowMoreActions(message, onEditClick, onDeleteClick)) {
                DropdownMenu(
                    expanded = isMoreMenuExpanded,
                    onDismissRequest = { isMoreMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        onClick = {
                            isMoreMenuExpanded = false
                            onReplyClick?.invoke()
                        }
                    )

                    if (onEditClick != null) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                isMoreMenuExpanded = false
                                onEditClick()
                            }
                        )
                    }

                    if (!message.text.isNullOrBlank()) {
                        DropdownMenuItem(
                            text = { Text("Copy Text") },
                            onClick = {
                                isMoreMenuExpanded = false
                                clipboardManager.setText(AnnotatedString(message.text))
                                Toast.makeText(context, "Message copied.", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    if (message.media != null) {
                        DropdownMenuItem(
                            text = { Text("Save Media") },
                            onClick = {
                                isMoreMenuExpanded = false
                                scope.launch {
                                    runCatching {
                                        val bytes = encryptedMediaService.loadFullMediaBytes(context, message.media)
                                        val result = if (message.media.type == MessageType.Video) {
                                            encryptedMediaService.saveVideoToGallery(
                                                context,
                                                bytes,
                                                message.media.id,
                                                message.media.metadata?.mimeType ?: "video/mp4"
                                            )
                                        } else {
                                            encryptedMediaService.saveImageToGallery(context, bytes, message.media.id)
                                        }
                                        Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                                    }.onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            error.localizedMessage ?: "Unable to save this media.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )
                    }

                    if (onDeleteClick != null) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete Message", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                isMoreMenuExpanded = false
                                isDeleteConfirmationPresented = true
                            }
                        )
                    }
                }
            }

            if (isDeleteConfirmationPresented && onDeleteClick != null) {
                AlertDialog(
                    onDismissRequest = { isDeleteConfirmationPresented = false },
                    title = { Text("Delete this message?") },
                    text = { Text("This will remove it from the conversation for everyone in this Space.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                isDeleteConfirmationPresented = false
                                onDeleteClick()
                            }
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { isDeleteConfirmationPresented = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

private fun canShowMoreActions(
    message: SpaceMessage,
    onEditClick: (() -> Unit)?,
    onDeleteClick: (() -> Unit)?
): Boolean {
    return !message.text.isNullOrBlank()
        || message.media != null
        || onEditClick != null
        || onDeleteClick != null
}

@Composable
private fun ReplyPreviewCard(
    replyContext: MessageReplyContext,
    isUnavailable: Boolean,
    searchQuery: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !isUnavailable && onClick != null) { onClick?.invoke() }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = highlightedAnnotatedString("↪ ${replyContext.senderName}", searchQuery),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = highlightedAnnotatedString(replyContext.preview, searchQuery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ReactionRow(
    reactions: List<MessageReaction>,
    onReactionClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        reactions.forEach { reaction ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (reaction.isSelectedByCurrentUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                tonalElevation = if (reaction.isSelectedByCurrentUser) 2.dp else 0.dp,
                modifier = Modifier.clickable(enabled = onReactionClick != null) {
                    onReactionClick?.invoke(reaction.emoji)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(reaction.emoji)
                    Text(
                        text = reaction.count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun TextMessageBubble(
    text: String,
    isOutgoing: Boolean,
    isHighlighted: Boolean,
    searchQuery: String
) {
    Box(
        modifier = Modifier
            .then(
                if (isHighlighted) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(24.dp)
                    )
                } else {
                    Modifier
                }
            )
            .background(
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                shape = RoundedCornerShape(22.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = highlightedAnnotatedString(text, searchQuery),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isOutgoing) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

private fun highlightedAnnotatedString(text: String, query: String): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    val ranges = mutableListOf<IntRange>()
    val lowerText = text.lowercase()
    val lowerQuery = query.trim().lowercase()
    var startIndex = 0

    while (startIndex < lowerText.length) {
        val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
        if (matchIndex == -1) break
        ranges += matchIndex until (matchIndex + lowerQuery.length)
        startIndex = matchIndex + maxOf(lowerQuery.length, 1)
    }

    if (ranges.isEmpty()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var currentIndex = 0
        ranges.forEach { range ->
            if (currentIndex < range.first) {
                append(text.substring(currentIndex, range.first))
            }
            withStyle(
                SpanStyle(
                    background = androidx.compose.ui.graphics.Color(0xFFFDE68A)
                )
            ) {
                append(text.substring(range.first, range.last + 1))
            }
            currentIndex = range.last + 1
        }
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

@Composable
private fun MediaMessageBubble(
    media: SpaceMedia,
    isOutgoing: Boolean,
    onClick: () -> Unit,
    isHighlighted: Boolean
) {
    Column(
        modifier = Modifier
            .then(
                if (isHighlighted) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(26.dp)
                    )
                } else {
                    Modifier
                }
            )
            .background(
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        EncryptedMediaThumbnail(
            media = media,
            isOutgoing = isOutgoing
        )

        media.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun EncryptedMediaThumbnail(
    media: SpaceMedia,
    isOutgoing: Boolean
) {
    val context = LocalContext.current
    val encryptedMediaService = remember { EncryptedMediaService() }
    var imageBytes by remember(media.id) { mutableStateOf<ByteArray?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }

    LaunchedEffect(media.id) {
        runCatching {
            encryptedMediaService.loadThumbnailBytes(context, media)
        }.onSuccess { bytes ->
            imageBytes = bytes
            isLoading = false
        }.onFailure {
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .width(224.dp)
            .height(156.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        when {
            bitmap != null -> {
                Box(modifier = Modifier.matchParentSize()) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = media.type.displayName,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (media.type == MessageType.Video) {
                        Text(
                            text = "▶",
                            fontSize = 28.sp,
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            isLoading -> {
                CircularProgressIndicator()
            }

            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = media.placeholderIconName.asPlaceholderEmoji(),
                        fontSize = 34.sp
                    )
                    Text(
                        text = media.type.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun String.asPlaceholderEmoji(): String {
    return when (this) {
        "image" -> "🖼️"
        "video" -> "🎬"
        "meme" -> "😂"
        "gif" -> "✨"
        "screenshot" -> "📱"
        "file" -> "📄"
        "text" -> "💬"
        else -> "📎"
    }
}

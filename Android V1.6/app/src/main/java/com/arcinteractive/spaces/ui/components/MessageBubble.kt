package com.arcinteractive.spaces.ui.components

import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Patterns
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arcinteractive.spaces.data.media.EncryptedMediaService
import com.arcinteractive.spaces.data.model.LinkPreviewData
import com.arcinteractive.spaces.data.model.MessageReaction
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.model.SpaceMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val GIF_RECEIVE_LOG_TAG = "GifReceive"

@Composable
fun MessageBubble(
    message: SpaceMessage,
    onMediaClick: (SpaceMedia) -> Unit,
    onSpaceLinkClick: ((SpaceLinkAttachment) -> Unit)? = null,
    onReplyClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null,
    replyPresentation: MessageReplyContext? = null,
    onReplyPreviewClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onRetryFailedMessage: (() -> Unit)? = null,
    onDeleteFailedMessage: (() -> Unit)? = null,
    reactionOptions: List<String> = emptyList(),
    onReactionClick: ((String) -> Unit)? = null,
    isHighlighted: Boolean = false,
    searchQuery: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
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
                        message.primaryMedia?.let {
                            onMediaClick(it)
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
                    message.hasMediaAttachments -> MediaMessageBubble(
                        mediaItems = message.resolvedMediaItems,
                        isOutgoing = message.isOutgoing,
                        onClick = onMediaClick,
                        isHighlighted = isHighlighted
                    )

                    !message.text.isNullOrBlank() -> Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextMessageBubble(
                            text = message.text,
                            isOutgoing = message.isOutgoing,
                            isHighlighted = isHighlighted,
                            searchQuery = searchQuery,
                            onClick = if (message.linkPreview == null) {
                                extractFirstUrl(message.text)?.let { url ->
                                    { uriHandler.openUri(url) }
                                }
                            } else {
                                null
                            }
                        )
                        message.linkPreview?.let { preview ->
                            LinkPreviewCard(
                                preview = preview,
                                isOutgoing = message.isOutgoing,
                                onClick = { uriHandler.openUri(preview.originalUrl) }
                            )
                        }
                        if (message.spaceLinks.isNotEmpty()) {
                            SpaceLinkColumn(
                                links = message.spaceLinks,
                                isOutgoing = message.isOutgoing,
                                onSpaceLinkClick = onSpaceLinkClick
                            )
                        }
                    }
                    message.text.isNullOrBlank() && message.spaceLinks.isNotEmpty() -> {
                        SpaceLinkOnlyBubble(
                            links = message.spaceLinks,
                            isOutgoing = message.isOutgoing,
                            isHighlighted = isHighlighted,
                            onSpaceLinkClick = onSpaceLinkClick
                        )
                    }
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

                if (message.isOutgoing && message.localDeliveryState == com.arcinteractive.spaces.data.model.LocalMessageDeliveryState.Failed) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 4.dp, start = 6.dp)
                    ) {
                        onRetryFailedMessage?.let {
                            TextButton(onClick = it) { Text("Retry") }
                        }
                        onDeleteFailedMessage?.let {
                            TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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

                    message.primaryMedia?.let { primaryMedia ->
                        DropdownMenuItem(
                            text = { Text("Save Media") },
                            onClick = {
                                isMoreMenuExpanded = false
                                scope.launch {
                                    runCatching {
                                        val bytes = encryptedMediaService.loadFullMediaBytes(context, primaryMedia)
                                        val result = if (primaryMedia.type == MessageType.Video) {
                                            encryptedMediaService.saveVideoToGallery(
                                                context,
                                                bytes,
                                                primaryMedia.id,
                                                primaryMedia.metadata?.mimeType ?: "video/mp4"
                                            )
                                        } else {
                                            encryptedMediaService.saveImageToGallery(context, bytes, primaryMedia.id)
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

@Composable
private fun SpaceLinkOnlyBubble(
    links: List<SpaceLinkAttachment>,
    isOutgoing: Boolean,
    isHighlighted: Boolean,
    onSpaceLinkClick: ((SpaceLinkAttachment) -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isOutgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color.Transparent
        )
    ) {
        SpaceLinkColumn(
            links = links,
            isOutgoing = isOutgoing,
            onSpaceLinkClick = onSpaceLinkClick,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun SpaceLinkColumn(
    links: List<SpaceLinkAttachment>,
    isOutgoing: Boolean,
    onSpaceLinkClick: ((SpaceLinkAttachment) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        links.forEach { link ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = onSpaceLinkClick != null) { onSpaceLinkClick?.invoke(link) },
                shape = RoundedCornerShape(14.dp),
                color = if (isOutgoing) MaterialTheme.colorScheme.surface.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isOutgoing) 0.dp else 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = moduleEmoji(link),
                            fontSize = 16.sp
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = link.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                            Text(
                                text = link.subtitle?.takeIf { it.isNotBlank() } ?: link.moduleType.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }

                    Text(
                        text = "Tap to Open",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun moduleEmoji(link: SpaceLinkAttachment): String = when (link.moduleType) {
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Announcements -> "\uD83D\uDCE2"
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Polls -> "\uD83D\uDCCA"
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Files -> "\uD83D\uDCC1"
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Events -> "\uD83D\uDCC5"
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Rooms -> "\uD83D\uDCAC"
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Media -> "\uD83D\uDDBC\uFE0F"
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Lists -> "✅"
    com.arcinteractive.spaces.data.model.SpaceLinkModuleType.Notes -> "📝"
}

private fun canShowMoreActions(
    message: SpaceMessage,
    onEditClick: (() -> Unit)?,
    onDeleteClick: (() -> Unit)?
): Boolean {
    return !message.text.isNullOrBlank()
        || message.hasMediaAttachments
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
    var isReactionDetailsVisible by remember { mutableStateOf(false) }
    val reactionDetails = reactions.flatMap { reaction ->
        reaction.userNames.map { name -> "$name reacted ${reaction.emoji}" }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            reactions.forEach { reaction ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (reaction.isSelectedByCurrentUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    tonalElevation = if (reaction.isSelectedByCurrentUser) 2.dp else 0.dp,
                    modifier = Modifier.combinedClickable(
                        onClick = { isReactionDetailsVisible = true },
                        onLongClick = { isReactionDetailsVisible = true }
                    )
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

    if (isReactionDetailsVisible) {
        AlertDialog(
            onDismissRequest = { isReactionDetailsVisible = false },
            title = { Text("Reactions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (reactionDetails.isEmpty()) {
                        Text("Reaction details are unavailable")
                    } else {
                        reactionDetails.forEach { detail -> Text(detail) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { isReactionDetailsVisible = false }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun TextMessageBubble(
    text: String,
    isOutgoing: Boolean,
    isHighlighted: Boolean,
    searchQuery: String,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
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

private fun extractFirstUrl(text: String?): String? {
    val source = text?.trim().orEmpty()
    if (source.isBlank()) return null

    val matcher = Patterns.WEB_URL.matcher(source)
    while (matcher.find()) {
        val raw = matcher.group().orEmpty().trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        if (raw.startsWith("www.", ignoreCase = true)) {
            return "https://$raw"
        }
    }
    return null
}

@Composable
private fun LinkPreviewCard(
    preview: LinkPreviewData,
    isOutgoing: Boolean,
    onClick: () -> Unit
) {
    val imageBytes = remember(preview.imageDataBase64) {
        preview.imageDataBase64?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }
    }
    val bitmap = remember(imageBytes) {
        imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .background(
                if (isOutgoing) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = preview.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )

            preview.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOutgoing) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3
                )
            }

            Text(
                text = preview.domain,
                style = MaterialTheme.typography.labelMedium,
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
        }
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
    mediaItems: List<SpaceMedia>,
    isOutgoing: Boolean,
    onClick: (SpaceMedia) -> Unit,
    isHighlighted: Boolean
) {
    val primaryMedia = mediaItems.firstOrNull()
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
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MediaGrid(
            mediaItems = mediaItems,
            isOutgoing = isOutgoing,
            onClick = onClick
        )

        primaryMedia?.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MediaGrid(
    mediaItems: List<SpaceMedia>,
    isOutgoing: Boolean,
    onClick: (SpaceMedia) -> Unit
) {
    when (mediaItems.size) {
        0 -> Unit
        1 -> MediaThumbnailCell(
            media = mediaItems[0],
            isOutgoing = isOutgoing,
            width = 224.dp,
            height = 156.dp,
            onClick = { onClick(mediaItems[0]) }
        )
        2 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mediaItems.take(2).forEach { media ->
                MediaThumbnailCell(
                    media = media,
                    isOutgoing = isOutgoing,
                    width = 108.dp,
                    height = 156.dp,
                    onClick = { onClick(media) }
                )
            }
        }
        3 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaThumbnailCell(
                media = mediaItems[0],
                isOutgoing = isOutgoing,
                width = 132.dp,
                height = 180.dp,
                onClick = { onClick(mediaItems[0]) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mediaItems.drop(1).take(2).forEach { media ->
                    MediaThumbnailCell(
                        media = media,
                        isOutgoing = isOutgoing,
                        width = 84.dp,
                        height = 86.dp,
                        onClick = { onClick(media) }
                    )
                }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val visibleItems = mediaItems.take(4)
            visibleItems.chunked(2).forEachIndexed { rowIndex, rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEachIndexed { columnIndex, media ->
                        val absoluteIndex = rowIndex * 2 + columnIndex
                        MediaThumbnailCell(
                            media = media,
                            isOutgoing = isOutgoing,
                            width = 108.dp,
                            height = 108.dp,
                            overlayText = if (absoluteIndex == 3 && mediaItems.size > 4) "+${mediaItems.size - 4}" else null,
                            onClick = { onClick(media) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnailCell(
    media: SpaceMedia,
    isOutgoing: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    overlayText: String? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val encryptedMediaService = remember { EncryptedMediaService() }
    var imageBytes by remember(media.id) { mutableStateOf<ByteArray?>(null) }
    var gifFilePath by remember(media.id) { mutableStateOf<String?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }
    val isGifMessage = media.type == MessageType.Gif
        || media.mediaCategory?.equals("gif", ignoreCase = true) == true
        || media.mediaType == com.arcinteractive.spaces.data.model.MediaType.Gif
        || media.metadata?.mimeType?.equals("image/gif", ignoreCase = true) == true

    LaunchedEffect(media.id) {
        if (isGifMessage) {
            Log.d(
                GIF_RECEIVE_LOG_TAG,
                "GIF decoding/rendering started messageId=${media.id} recognizedAsGif=true type=${media.type.name} mediaCategory=${media.mediaCategory} mediaType=${media.mediaType.name} mimeType=${media.metadata?.mimeType}"
            )
        }
        try {
            if (isGifMessage) {
                val bytes = encryptedMediaService.loadFullMediaBytes(context, media)
                val file = encryptedMediaService.writeTempMediaFile(
                    context = context,
                    bytes = bytes,
                    fileName = media.id,
                    mimeType = media.metadata?.mimeType ?: "image/gif"
                )
                gifFilePath = file.absolutePath
                Log.d(
                    GIF_RECEIVE_LOG_TAG,
                    "GIF decoding/rendering succeeded messageId=${media.id} filePath=${file.absolutePath}"
                )
            } else {
                val bytes = encryptedMediaService.loadThumbnailBytes(context, media)
                imageBytes = bytes
            }
            isLoading = false
        } catch (error: CancellationException) {
            isLoading = false
        } catch (error: Throwable) {
            isLoading = false
            if (isGifMessage) {
                Log.e(GIF_RECEIVE_LOG_TAG, "GIF decoding/rendering failed messageId=${media.id}", error)
            }
        }
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        when {
            isGifMessage && gifFilePath != null -> {
                AnimatedGifThumbnail(filePath = gifFilePath!!)
            }
            media.localPreviewBytes != null -> {
                val localBitmap = BitmapFactory.decodeByteArray(media.localPreviewBytes, 0, media.localPreviewBytes.size)
                if (localBitmap != null) {
                    Image(
                        bitmap = localBitmap.asImageBitmap(),
                        contentDescription = media.type.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            bitmap != null -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = media.type.displayName,
                        modifier = Modifier.fillMaxSize(),
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
                if (isGifMessage) {
                    Log.d(
                        GIF_RECEIVE_LOG_TAG,
                        "GIF decoding/rendering failed messageId=${media.id} reason=no_renderable_content"
                    )
                }
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

        overlayText?.let { text ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.36f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AnimatedGifThumbnail(filePath: String) {
    val file = remember(filePath) { java.io.File(filePath) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp)),
            update = { imageView ->
                val source = ImageDecoder.createSource(file)
                val drawable = ImageDecoder.decodeDrawable(source)
                imageView.setImageDrawable(drawable)
                (drawable as? Animatable)?.start()
            }
        )
    } else {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    isClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    webViewClient = WebViewClient()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp)),
            update = { webView ->
                val html = """
                    <html>
                    <body style="margin:0;padding:0;background:transparent;overflow:hidden;">
                        <img src="${file.name}" style="width:100%;height:100%;object-fit:contain;pointer-events:none;user-select:none;-webkit-user-select:none;" />
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(file.parentFile?.toURI()?.toString(), html, "text/html", "utf-8", null)
            }
        )
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

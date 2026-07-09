package com.arcinteractive.spaces.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arcinteractive.spaces.data.media.EncryptedMediaService
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MediaViewerPlaceholder(
    space: Space,
    media: SpaceMedia,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val encryptedMediaService = remember { EncryptedMediaService() }
    val saveScope = remember { CoroutineScope(Dispatchers.Main) }
    var imageBytes by remember(media.id) { mutableStateOf<ByteArray?>(null) }
    var videoPath by remember(media.id) { mutableStateOf<String?>(null) }
    var sharePath by remember(media.id) { mutableStateOf<String?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }

    val player = remember(videoPath) {
        videoPath?.let { path ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(File(path).toURI().toString()))
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(player, videoPath, sharePath) {
        onDispose {
            player?.release()
            videoPath?.let { path -> runCatching { File(path).delete() } }
            sharePath?.let { path ->
                if (path != videoPath) {
                    runCatching { File(path).delete() }
                }
            }
        }
    }

    LaunchedEffect(media.id) {
        runCatching {
            if (media.type == MessageType.Video) {
                val bytes = encryptedMediaService.loadFullMediaBytes(context, media)
                val file = encryptedMediaService.writeTempMediaFile(
                    context = context,
                    bytes = bytes,
                    fileName = media.id,
                    mimeType = media.metadata?.mimeType ?: "video/mp4"
                )
                imageBytes = bytes
                videoPath = file.absolutePath
            } else {
                imageBytes = encryptedMediaService.loadFullMediaBytes(context, media)
            }
        }.onFailure {
            imageBytes = null
            videoPath = null
        }
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when {
                media.type == MessageType.Video && player != null -> {
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                useController = true
                                this.player = player
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.player = player }
                    )
                }

                imageBytes != null -> {
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes!!.size)
                    if (bitmap != null) {
                        ZoomableImage(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = media.type.displayName
                        )
                    } else {
                        ViewerFallback(media = media, isLoading = isLoading)
                    }
                }

                else -> {
                    ViewerFallback(media = media, isLoading = isLoading)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = space.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ViewerActionButton("Save") {
                            val bytes = imageBytes
                            if (bytes == null) {
                                Toast.makeText(context, "Media is still loading.", Toast.LENGTH_SHORT).show()
                            } else {
                                saveScope.launch {
                                    val result = if (media.type == MessageType.Video) {
                                        encryptedMediaService.saveVideoToGallery(
                                            context,
                                            bytes,
                                            media.id,
                                            media.metadata?.mimeType ?: "video/mp4"
                                        )
                                    } else {
                                        encryptedMediaService.saveImageToGallery(context, bytes, media.id)
                                    }
                                    Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        ViewerActionButton("Share") {
                            val bytes = imageBytes
                            if (bytes == null) {
                                Toast.makeText(context, "Media is still loading.", Toast.LENGTH_SHORT).show()
                            } else {
                                val file = encryptedMediaService.shareFile(
                                    context = context,
                                    bytes = bytes,
                                    fileName = media.id,
                                    extension = mediaFileExtension(media)
                                )
                                sharePath = file.absolutePath
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = media.metadata?.mimeType ?: if (media.type == MessageType.Video) "video/mp4" else "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }

                        ViewerActionButton("Close", onDismiss)
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = media.senderName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = media.timestamp,
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    media.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                        Text(
                            text = caption,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = nextScale
                    offset = if (nextScale > 1f) offset + pan else Offset.Zero
                }
            },
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun ViewerFallback(
    media: SpaceMedia,
    isLoading: Boolean
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Text(
                text = media.placeholderIconName.asViewerEmoji(),
                color = Color.White,
                fontSize = 72.sp
            )
        }
    }
}

@Composable
private fun ViewerActionButton(
    title: String,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

private fun mediaFileExtension(media: SpaceMedia): String {
    return when (media.metadata?.mimeType?.lowercase()) {
        "video/quicktime" -> "mov"
        "video/mp4" -> "mp4"
        "image/png" -> "png"
        "image/heic" -> "heic"
        else -> if (media.type == MessageType.Video) "mp4" else "jpg"
    }
}

private fun String.asViewerEmoji(): String {
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

package com.arcinteractive.spaces.ui.screens.announcements

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcinteractive.spaces.data.model.AnnouncementAttachment
import com.arcinteractive.spaces.data.model.AnnouncementAttachmentKind
import com.arcinteractive.spaces.data.model.AnnouncementComment
import com.arcinteractive.spaces.data.model.AnnouncementReference
import com.arcinteractive.spaces.data.model.AnnouncementReferenceKind
import com.arcinteractive.spaces.data.model.MediaType
import com.arcinteractive.spaces.data.model.EncryptedMediaMetadata
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceAnnouncement
import com.arcinteractive.spaces.data.model.SpacePermission
import com.arcinteractive.spaces.data.spaces.AnnouncementStore
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.auth.FirebaseAuth
import com.arcinteractive.spaces.ui.components.MediaViewerPlaceholder
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch

private sealed interface AnnouncementPage {
    data object ListPage : AnnouncementPage
    data class Detail(val id: String) : AnnouncementPage
    data class Composer(val existingId: String? = null) : AnnouncementPage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsScreen(
    space: Space,
    onBackPressed: () -> Unit,
    onOpenReference: (AnnouncementReference) -> Unit
) {
    val context = LocalContext.current
    val state by AnnouncementStore.announcements.collectAsState()
    val syncError by AnnouncementStore.lastErrorMessage.collectAsState()
    val announcements = remember(state, space.id) { AnnouncementStore.forSpace(space) }
    var page: AnnouncementPage by remember { mutableStateOf(AnnouncementPage.ListPage) }
    val firebaseUser = FirebaseAuth.getInstance().currentUser
    val currentUserId = firebaseUser?.uid.orEmpty()
    val currentUserName = firebaseUser?.displayName?.takeIf { it.isNotBlank() } ?: "Space member"
    var canCreate by remember(space.id, currentUserId) { mutableStateOf(currentUserId == space.ownerId) }

    LaunchedEffect(space.id) {
        AnnouncementStore.startListening(context, space)
        canCreate = SpaceService().canPerform(context, space, SpacePermission.CreateAnnouncements)
    }

    syncError?.let { message ->
        AlertDialog(
            onDismissRequest = AnnouncementStore::clearError,
            confirmButton = {
                TextButton(onClick = AnnouncementStore::clearError) { Text("OK") }
            },
            title = { Text("Announcements") },
            text = { Text(message) }
        )
    }

    BackHandler(enabled = page !is AnnouncementPage.ListPage) {
        page = AnnouncementPage.ListPage
    }

    when (val current = page) {
        AnnouncementPage.ListPage -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Announcements") },
                    navigationIcon = {
                        IconButton(onClick = onBackPressed) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (canCreate) {
                            IconButton(onClick = { page = AnnouncementPage.Composer() }) {
                                Icon(Icons.Outlined.Add, contentDescription = "New announcement")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            if (announcements.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📢", style = MaterialTheme.typography.displaySmall)
                    Text("No Announcements", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Important updates for this Space will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(announcements, key = { it.id }) { announcement ->
                        AnnouncementCard(
                            announcement = announcement,
                            onClick = { page = AnnouncementPage.Detail(announcement.id) }
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }

        is AnnouncementPage.Detail -> {
            val announcement = announcements.firstOrNull { it.id == current.id }
            if (announcement == null) {
                page = AnnouncementPage.ListPage
            } else {
                AnnouncementDetail(
                    announcement = announcement,
                    space = space,
                    currentUserId = currentUserId,
                    canDelete = canCreate,
                    onBack = { page = AnnouncementPage.ListPage },
                    onEdit = { page = AnnouncementPage.Composer(announcement.id) },
                    onDelete = {
                        AnnouncementStore.delete(space.id, announcement.id)
                        page = AnnouncementPage.ListPage
                    },
                    onOpenReference = onOpenReference,
                    onReaction = { emoji ->
                        AnnouncementStore.toggleReaction(space.id, announcement.id, emoji, currentUserId)
                    },
                    onComment = { body ->
                        AnnouncementStore.addComment(
                            space.id,
                            announcement.id,
                            AnnouncementComment(
                                authorId = currentUserId,
                                authorName = currentUserName,
                                body = body
                            )
                        )
                    }
                )
            }
        }

        is AnnouncementPage.Composer -> {
            val existing = announcements.firstOrNull { it.id == current.existingId }
            AnnouncementComposer(
                existing = existing,
                space = space,
                currentUserId = currentUserId,
                currentUserName = currentUserName,
                onCancel = { page = if (existing == null) AnnouncementPage.ListPage else AnnouncementPage.Detail(existing.id) },
                onSave = {
                    AnnouncementStore.save(it)
                    page = AnnouncementPage.Detail(it.id)
                }
            )
        }
    }
}

@Composable
private fun AnnouncementCard(announcement: SpaceAnnouncement, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row {
                if (announcement.isPinned) {
                    Icon(Icons.Outlined.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(announcement.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                announcement.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${announcement.authorName} • ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(announcement.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val reactionCount = announcement.reactions.sumOf { it.userIds.size }
                if (reactionCount > 0) Text("👍 $reactionCount", style = MaterialTheme.typography.labelMedium)
                if (announcement.commentsEnabled) Text("💬 ${announcement.comments.size}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementDetail(
    announcement: SpaceAnnouncement,
    space: Space,
    currentUserId: String,
    canDelete: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenReference: (AnnouncementReference) -> Unit,
    onReaction: (String) -> Unit,
    onComment: (String) -> Unit
) {
    var comment by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var selectedAttachmentMedia by remember { mutableStateOf<SpaceMedia?>(null) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Announcement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (announcement.authorId == currentUserId) {
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                    }
                    if (canDelete) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                if (announcement.isPinned) {
                    Text("📌 Pinned", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Text(announcement.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${announcement.authorName} • ${DateFormat.getDateTimeInstance().format(announcement.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Text(announcement.body, style = MaterialTheme.typography.bodyLarge)
            }
            if (announcement.attachments.isNotEmpty()) {
                item { Text("Attachments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(announcement.attachments, key = { it.id }) {
                    Card(Modifier.fillMaxWidth().clickable {
                        if (it.kind == AnnouncementAttachmentKind.Link && !it.url.isNullOrBlank()) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.url)))
                        } else {
                            selectedAttachmentMedia = it.asSpaceMedia(announcement.spaceId)
                        }
                    }) {
                        Text("${it.kind.emoji}  ${it.title}", Modifier.padding(16.dp))
                    }
                }
            }
            if (announcement.references.isNotEmpty()) {
                item { Text("Related", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(announcement.references, key = { it.id }) {
                    Card(Modifier.fillMaxWidth().clickable { onOpenReference(it) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${it.kind.emoji}  ${it.kind.title}", style = MaterialTheme.typography.labelMedium)
                            Text(it.title, fontWeight = FontWeight.SemiBold)
                            it.subtitle?.let { subtitle -> Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item {
                Text("Reactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("👍", "❤️", "🎉", "👀", "✅").forEach { emoji ->
                        val count = announcement.reactions.firstOrNull { it.emoji == emoji }?.userIds?.size ?: 0
                        AssistChip(onClick = { onReaction(emoji) }, label = { Text(if (count > 0) "$emoji $count" else emoji) })
                    }
                }
            }
            if (announcement.commentsEnabled) {
                item { Text("Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(announcement.comments, key = { it.id }) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(it.authorName, fontWeight = FontWeight.Bold)
                            Text(it.body)
                        }
                    }
                }
                item {
                    Row {
                        OutlinedTextField(
                            value = comment,
                            onValueChange = { comment = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Add a comment") }
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onComment(comment.trim())
                                comment = ""
                            },
                            enabled = comment.isNotBlank()
                        ) { Text("Post") }
                    }
                }
            } else {
                item { Text("Comments are disabled for this announcement.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete announcement?") },
            text = { Text("This removes the announcement from this Space.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }
    selectedAttachmentMedia?.let { media ->
        MediaViewerPlaceholder(
            space = space,
            media = media,
            onDismiss = { selectedAttachmentMedia = null }
        )
    }
}

private fun AnnouncementAttachment.asSpaceMedia(spaceId: String): SpaceMedia? {
    val path = storagePath ?: return null
    val nonceValue = nonce ?: return null
    val type = when (kind) {
        AnnouncementAttachmentKind.Video -> MediaType.Video
        AnnouncementAttachmentKind.Image -> MediaType.Photo
        AnnouncementAttachmentKind.File -> MediaType.File
        AnnouncementAttachmentKind.Link -> return null
    }
    return SpaceMedia(
        id = id,
        spaceId = spaceId,
        type = when (kind) {
            AnnouncementAttachmentKind.Video -> MessageType.Video
            AnnouncementAttachmentKind.File -> MessageType.File
            else -> MessageType.Image
        },
        mediaType = type,
        placeholderIconName = kind.emoji,
        caption = title,
        senderName = "",
        timestamp = "",
        metadata = EncryptedMediaMetadata(
            mediaId = id,
            mediaType = type,
            storagePath = path,
            encryptionVersion = "aes-gcm-v1",
            nonce = nonceValue,
            mimeType = mimeType ?: "application/octet-stream",
            fileSize = fileSize ?: 0,
            uploadedBy = uploadedBy.orEmpty()
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementComposer(
    existing: SpaceAnnouncement?,
    space: Space,
    currentUserId: String,
    currentUserName: String,
    onCancel: () -> Unit,
    onSave: (SpaceAnnouncement) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember { mutableStateOf(existing?.body.orEmpty()) }
    var pinned by remember { mutableStateOf(existing?.isPinned ?: false) }
    var commentsEnabled by remember { mutableStateOf(existing?.commentsEnabled ?: true) }
    var expires by remember { mutableStateOf(existing?.expiresAt != null) }
    var attachments by remember { mutableStateOf(existing?.attachments.orEmpty()) }
    var references by remember { mutableStateOf(existing?.references.orEmpty()) }
    var showAttachment by remember { mutableStateOf(false) }
    var showReference by remember { mutableStateOf(false) }
    var uploadingAttachment by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploadingAttachment = true
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read the selected file.")
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Attachment"
                AnnouncementStore.uploadAttachment(context, space.id, currentUserId, name, mime, bytes)
            }.onSuccess { attachments = attachments + it }
                .onFailure { attachmentError = it.localizedMessage ?: "Unable to upload this attachment." }
            uploadingAttachment = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New Announcement" else "Edit Announcement") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") } },
                actions = {
                    TextButton(
                        enabled = title.isNotBlank() && body.isNotBlank(),
                        onClick = {
                            val expiry = if (expires) Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }.time else null
                            onSave(
                                SpaceAnnouncement(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    spaceId = space.id,
                                    title = title.trim(),
                                    body = body.trim(),
                                    authorId = existing?.authorId ?: currentUserId,
                                    authorName = existing?.authorName ?: currentUserName,
                                    createdAt = existing?.createdAt ?: Date(),
                                    updatedAt = Date(),
                                    isPinned = pinned,
                                    expiresAt = expiry,
                                    commentsEnabled = commentsEnabled,
                                    attachments = attachments,
                                    references = references,
                                    reactions = existing?.reactions.orEmpty(),
                                    comments = existing?.comments.orEmpty()
                                )
                            )
                        }
                    ) { Text("Save") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Title") })
            }
            item {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    label = { Text("Rich text") },
                    supportingText = { Text("Supports Markdown such as **bold**, _italic_, and links.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { body += "**bold**" }, label = { Text("Bold") })
                    AssistChip(onClick = { body += "_italic_" }, label = { Text("Italic") })
                    AssistChip(onClick = { body += "[link](https://)" }, label = { Text("Link") })
                    AssistChip(onClick = { body += "\n- item" }, label = { Text("List") })
                }
            }
            item { SettingSwitch("Pin Announcement", pinned) { pinned = it } }
            item { SettingSwitch("Comments Enabled", commentsEnabled) { commentsEnabled = it } }
            item { SettingSwitch("Expire in 7 days", expires) { expires = it } }
            item {
                HorizontalDivider()
                Text("Attachments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(attachments, key = { it.id }) { attachment ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${attachment.kind.emoji} ${attachment.title}", Modifier.weight(1f))
                    IconButton(onClick = { attachments = attachments.filterNot { it.id == attachment.id } }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove")
                    }
                }
            }
            item {
                OutlinedButton(
                    enabled = !uploadingAttachment,
                    onClick = { attachmentPicker.launch(arrayOf("image/*", "video/*", "application/*")) }
                ) { Text(if (uploadingAttachment) "Uploading…" else "Upload Image, Video, or File") }
                OutlinedButton(onClick = { showAttachment = true }) { Text("Add Web Link") }
            }
            item {
                HorizontalDivider()
                Text("Cross-Module Links", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("References point to original module data; they do not duplicate it.", style = MaterialTheme.typography.bodySmall)
            }
            items(references, key = { it.id }) { reference ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${reference.kind.emoji} ${reference.title}", Modifier.weight(1f))
                    IconButton(onClick = { references = references.filterNot { it.id == reference.id } }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove")
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showReference = true }) { Text("Link Another Module") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAttachment) {
        AttachmentDialog(
            onDismiss = { showAttachment = false },
            onAdd = {
                attachments = attachments + it
                showAttachment = false
            }
        )
    }
    attachmentError?.let { message ->
        AlertDialog(
            onDismissRequest = { attachmentError = null },
            title = { Text("Attachment") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { attachmentError = null }) { Text("OK") } }
        )
    }
    if (showReference) {
        ReferenceDialog(
            space = space,
            onDismiss = { showReference = false },
            onAdd = {
                references = references + it
                showReference = false
            }
        )
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AttachmentDialog(onDismiss: () -> Unit, onAdd: (AnnouncementAttachment) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Attachment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Display name") })
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Web URL") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && url.isNotBlank(),
                onClick = { onAdd(AnnouncementAttachment(kind = AnnouncementAttachmentKind.Link, title = title.trim(), url = url.trim())) }
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReferenceDialog(space: Space, onDismiss: () -> Unit, onAdd: (AnnouncementReference) -> Unit) {
    val context = LocalContext.current
    var references by remember(space.id) { mutableStateOf<List<AnnouncementReference>>(emptyList()) }
    var loading by remember(space.id) { mutableStateOf(true) }
    var error by remember(space.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(space.id) {
        runCatching {
            val service = SpaceService()
            val events = if (space.eventsEnabled) service.fetchEvents(context, space) else emptyList()
            val files = if (space.filesEnabled) service.fetchFiles(context, space) else emptyList()
            val media = service.fetchRecentMessages(context, space, 100)
                .flatMap { it.resolvedMediaItems }
                .filter { it.mediaType == MediaType.Photo || it.mediaType == MediaType.Video }
            buildList {
                addAll(events.map {
                    AnnouncementReference(kind = AnnouncementReferenceKind.Event, targetId = it.id, title = it.title, subtitle = it.dateText)
                })
                addAll(files.map {
                    AnnouncementReference(kind = AnnouncementReferenceKind.File, targetId = it.id, title = it.name, subtitle = it.typeDescription)
                })
                addAll(media.map {
                    AnnouncementReference(
                        kind = AnnouncementReferenceKind.Media,
                        targetId = it.id,
                        title = it.caption?.takeIf(String::isNotBlank) ?: it.mediaType.name,
                        subtitle = it.timestamp
                    )
                })
            }
        }.onSuccess { references = it }
            .onFailure { error = it.localizedMessage ?: "Linked content could not be loaded." }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link Module Content") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) {
                    Text("Loading Space content…")
                } else if (references.isEmpty()) {
                    Text(error ?: "There are no Events, Files, or Media available to link.")
                } else {
                    references.forEach { reference ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onAdd(reference)
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(reference.kind.emoji)
                            Column {
                                Text(reference.title, fontWeight = FontWeight.SemiBold)
                                reference.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

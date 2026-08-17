package com.arcinteractive.spaces.ui.screens.rooms

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arcinteractive.spaces.data.model.RoomMessage
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMember
import com.arcinteractive.spaces.data.model.SpacePermission
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import com.arcinteractive.spaces.data.model.SpaceRoom
import com.arcinteractive.spaces.data.rooms.RoomService
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistry
import com.arcinteractive.spaces.data.spaces.SpaceLinkModuleDescriptor
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistryItem
import com.arcinteractive.spaces.ui.components.rememberGifPickerLauncher
import com.google.firebase.auth.FirebaseAuth
import java.util.Date
import java.util.UUID
import java.io.File
import kotlinx.coroutines.launch

private sealed interface RoomsPage {
    data object ListPage : RoomsPage
    data object CreatePage : RoomsPage
    data class Conversation(val room: SpaceRoom) : RoomsPage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(space: Space, onBackPressed: () -> Unit, onOpenLink: (SpaceLinkAttachment) -> Unit, initialRoomId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roomService = remember { RoomService() }
    val spaceService = remember { SpaceService() }
    var page: RoomsPage by remember(space.id) { mutableStateOf(RoomsPage.ListPage) }
    var rooms by remember(space.id) { mutableStateOf(emptyList<SpaceRoom>()) }
    var members by remember(space.id) { mutableStateOf(emptyList<SpaceMember>()) }
    var canCreate by remember(space.id) { mutableStateOf(false) }
    var canManageMembers by remember(space.id) { mutableStateOf(false) }
    var canEditOwn by remember(space.id) { mutableStateOf(false) }
    var canEditOthers by remember(space.id) { mutableStateOf(false) }
    var canDeleteOwn by remember(space.id) { mutableStateOf(false) }
    var canDeleteOthers by remember(space.id) { mutableStateOf(false) }
    var canPostInRooms by remember(space.id) { mutableStateOf(false) }
    var errorMessage by remember(space.id) { mutableStateOf<String?>(null) }

    DisposableEffect(space.id) {
        val roomListener = roomService.listenToRooms(space.id) { result ->
            result.onSuccess { rooms = it }.onFailure { errorMessage = it.localizedMessage }
        }
        val memberListener = spaceService.listenToMembers(context, space, "rooms-members-${space.id}") { result ->
            result.onSuccess { members = it }
        }
        onDispose {
            roomListener?.remove()
            memberListener?.remove()
        }
    }
    LaunchedEffect(space.id) {
        canCreate = spaceService.canPerform(context, space, SpacePermission.CreateRooms)
        canManageMembers = spaceService.canPerform(context, space, SpacePermission.ManageRoomMembers)
        canEditOwn = spaceService.canPerform(context, space, SpacePermission.EditOwnRooms)
        canEditOthers = spaceService.canPerform(context, space, SpacePermission.EditOthersRooms)
        canDeleteOwn = spaceService.canPerform(context, space, SpacePermission.DeleteOwnRooms)
        canDeleteOthers = spaceService.canPerform(context, space, SpacePermission.DeleteOthersRooms)
        canPostInRooms = spaceService.canPerform(context, space, SpacePermission.PostInRooms)
    }
    LaunchedEffect(initialRoomId, rooms) {
        val target = initialRoomId ?: return@LaunchedEffect
        if (page is RoomsPage.ListPage) {
            rooms.firstOrNull { it.id == target }?.let { page = RoomsPage.Conversation(it) }
        }
    }
    BackHandler(enabled = page !is RoomsPage.ListPage) { page = RoomsPage.ListPage }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
            title = { Text("Rooms") },
            text = { Text(message) }
        )
    }

    when (val currentPage = page) {
        RoomsPage.ListPage -> RoomsList(
            rooms = rooms,
            canCreate = canCreate,
            onBack = onBackPressed,
            onCreate = { page = RoomsPage.CreatePage },
            onOpen = { page = RoomsPage.Conversation(it) }
        )
        RoomsPage.CreatePage -> CreateRoomScreen(
            space = space,
            members = members,
            onBack = { page = RoomsPage.ListPage },
            onCreate = { room ->
                roomService.saveRoom(room) { result ->
                    result.onSuccess {
                        page = RoomsPage.Conversation(room)
                        scope.launch {
                            SpaceService().recordModuleActivity(
                                context, com.arcinteractive.spaces.data.model.ActivityType.RoomCreated,
                                space, "created a Room", room.name, room.id,
                                com.arcinteractive.spaces.data.model.ActivityTargetType.Rooms
                            )
                            val service = SpaceService()
                            val recipients = if (room.isPrivate) room.memberIds.toList() else service.memberIds(context, space)
                            service.recordTargetedModuleNotification(
                                context, recipients, "room", space, "created a Room", room.name,
                                room.id, com.arcinteractive.spaces.data.model.ActivityTargetType.Rooms
                            )
                        }
                    }
                        .onFailure { errorMessage = it.localizedMessage }
                }
            }
        )
        is RoomsPage.Conversation -> RoomConversationScreen(
            space = space,
            room = currentPage.room,
            members = members,
            canManageMembers = canManageMembers,
            canEdit = if (currentPage.room.createdBy == FirebaseAuth.getInstance().currentUser?.uid) canEditOwn else canEditOthers,
            canDelete = if (currentPage.room.createdBy == FirebaseAuth.getInstance().currentUser?.uid) canDeleteOwn else canDeleteOthers,
            canPostInRooms = canPostInRooms,
            canDeleteOthersMessages = canDeleteOthers,
            roomService = roomService,
            onBack = { page = RoomsPage.ListPage },
            onRoomUpdated = { updatedRoom -> page = RoomsPage.Conversation(updatedRoom) },
            onRoomDeleted = { page = RoomsPage.ListPage },
            onOpenLink = onOpenLink,
            onError = { errorMessage = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomsList(
    rooms: List<SpaceRoom>,
    canCreate: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (SpaceRoom) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rooms") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (canCreate) {
                        IconButton(onClick = onCreate) {
                            Icon(Icons.Outlined.Add, contentDescription = "Create Room")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (rooms.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("💬", style = MaterialTheme.typography.displaySmall)
                Text("No Rooms", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Create a Room to organize a discussion.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rooms, key = SpaceRoom::id) { room ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(room) }) {
                        ListItem(
                            headlineContent = { Text(room.name, fontWeight = FontWeight.SemiBold) },
                            supportingContent = room.topic.takeIf(String::isNotBlank)?.let { topic -> { Text(topic) } },
                            leadingContent = {
                                Icon(
                                    if (room.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Tag,
                                    contentDescription = if (room.isPrivate) "Private Room" else "Public Room"
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRoomScreen(
    space: Space,
    members: List<SpaceMember>,
    onBack: () -> Unit,
    onCreate: (SpaceRoom) -> Unit
) {
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var isReadOnly by remember { mutableStateOf(false) }
    var selectedMemberIds by remember { mutableStateOf(emptySet<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Room") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Room name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Private Room", fontWeight = FontWeight.SemiBold)
                        Text("Only selected members can access it.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Only Owners and Admins Can Post", fontWeight = FontWeight.SemiBold)
                        Text("Other members can read but cannot send messages.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = isReadOnly, onCheckedChange = { isReadOnly = it })
                }
            }
            if (isPrivate) {
                item { Text("Room Members", style = MaterialTheme.typography.titleMedium) }
                items(members.filter { it.id != currentUserId }, key = SpaceMember::id) { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedMemberIds = if (member.id in selectedMemberIds) {
                                selectedMemberIds - member.id
                            } else {
                                selectedMemberIds + member.id
                            }
                        }
                    ) {
                        Checkbox(
                            checked = member.id in selectedMemberIds,
                            onCheckedChange = {
                                selectedMemberIds = if (it) selectedMemberIds + member.id else selectedMemberIds - member.id
                            }
                        )
                        Text("${member.emojiAvatar} ${member.displayName}", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val now = Date()
                        onCreate(
                            SpaceRoom(
                                id = UUID.randomUUID().toString(),
                                spaceId = space.id,
                                name = name.trim(),
                                topic = topic.trim(),
                                isPrivate = isPrivate,
                                memberIds = selectedMemberIds + currentUserId,
                                createdBy = currentUserId,
                                createdAt = now,
                                updatedAt = now,
                                postingMemberIds = if (isReadOnly) {
                                    members.filter {
                                        it.role == com.arcinteractive.spaces.data.model.SpaceMemberRole.Owner ||
                                            it.role == com.arcinteractive.spaces.data.model.SpaceMemberRole.Admin
                                    }.map { it.id }.toSet() + space.ownerId
                                } else null
                            )
                        )
                    },
                    enabled = name.isNotBlank() && currentUserId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Room")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomConversationScreen(
    space: Space,
    room: SpaceRoom,
    members: List<SpaceMember>,
    canManageMembers: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    canPostInRooms: Boolean,
    canDeleteOthersMessages: Boolean,
    roomService: RoomService,
    onBack: () -> Unit,
    onRoomUpdated: (SpaceRoom) -> Unit,
    onRoomDeleted: () -> Unit,
    onOpenLink: (SpaceLinkAttachment) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember(room.id) { mutableStateOf(emptyList<RoomMessage>()) }
    var draft by remember(room.id) { mutableStateOf("") }
    var searchText by remember(room.id) { mutableStateOf("") }
    var replyingTo by remember(room.id) { mutableStateOf<RoomMessage?>(null) }
    var composerLinks by remember(room.id) { mutableStateOf(emptyList<SpaceLinkAttachment>()) }
    var isShowingLinkPicker by remember(room.id) { mutableStateOf(false) }
    var isShowingAttachmentMenu by remember(room.id) { mutableStateOf(false) }
    var isInvitingMembers by remember(room.id) { mutableStateOf(false) }
    var isEditingRoom by remember(room.id) { mutableStateOf(false) }
    var isConfirmingDelete by remember(room.id) { mutableStateOf(false) }
    var editingMessage by remember(room.id) { mutableStateOf<RoomMessage?>(null) }
    var editingBody by remember(room.id) { mutableStateOf("") }
    var deletingMessage by remember(room.id) { mutableStateOf<RoomMessage?>(null) }
    val senderName = FirebaseAuth.getInstance().currentUser?.displayName?.takeIf(String::isNotBlank) ?: "Member"
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val currentRole = members.firstOrNull { it.id == currentUserId }?.role
    val canPost = canPostInRooms && (
        room.postingMemberIds == null ||
            currentRole == com.arcinteractive.spaces.data.model.SpaceMemberRole.Owner ||
            currentRole == com.arcinteractive.spaces.data.model.SpaceMemberRole.Admin
        )
    fun recordAttachmentPost() {
        scope.launch {
            val service = SpaceService()
            service.recordModuleActivity(
                context, com.arcinteractive.spaces.data.model.ActivityType.RoomMessageSent,
                space, "shared an attachment in a Room", room.name, room.id,
                com.arcinteractive.spaces.data.model.ActivityTargetType.Rooms, notifyMembers = false
            )
            val recipients = if (room.isPrivate) room.memberIds.toList() else service.memberIds(context, space)
            service.recordTargetedModuleNotification(
                context, recipients, "room", space, "shared an attachment in a Room", room.name,
                room.id, com.arcinteractive.spaces.data.model.ActivityTargetType.Rooms
            )
        }
    }
    var pendingCameraFile by remember(room.id) { mutableStateOf<File?>(null) }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingCameraFile
        if (captured && file != null) {
            runCatching {
                roomService.sendAttachment(
                    space.id, room.id, senderName, file.readBytes(), file.name, "image/jpeg", true
                ) { it.onSuccess { recordAttachmentPost() }.onFailure { error -> onError(error.localizedMessage ?: "Unable to share camera photo.") } }
            }.onFailure { onError(it.localizedMessage ?: "Unable to share camera photo.") }
        }
        pendingCameraFile = null
    }
    val gifPickerLauncher = rememberGifPickerLauncher(
        onGifSelected = { selection ->
            roomService.sendAttachment(
                space.id,
                room.id,
                senderName,
                selection.gifBytes,
                "GIF",
                selection.mimeType,
                true
            ) { result ->
                result.onSuccess { recordAttachmentPost() }.onFailure { error ->
                    onError(error.localizedMessage ?: "Unable to share GIF.")
                }
            }
        },
        onError = onError
    )
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to read selected media.")
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            roomService.sendAttachment(space.id, room.id, senderName, bytes, "Shared Media", mime, true) {
                it.onSuccess { recordAttachmentPost() }.onFailure { error -> onError(error.localizedMessage ?: "Unable to share media.") }
            }
        }.onFailure { onError(it.localizedMessage ?: "Unable to share media.") }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to read selected file.")
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Shared File"
            roomService.sendAttachment(space.id, room.id, senderName, bytes, name, mime, false) {
                it.onSuccess { recordAttachmentPost() }.onFailure { error -> onError(error.localizedMessage ?: "Unable to share file.") }
            }
        }.onFailure { onError(it.localizedMessage ?: "Unable to share file.") }
    }
    val visibleMessages = remember(messages, searchText) {
        val query = searchText.trim()
        if (query.isEmpty()) messages else messages.filter {
            it.body.contains(query, true) ||
                it.senderName.contains(query, true) ||
                it.replyPreview.orEmpty().contains(query, true)
        }
    }

    DisposableEffect(room.id) {
        val messageListener = roomService.listenToMessages(space.id, room.id) { result ->
            result.onSuccess { messages = it }.onFailure { onError(it.localizedMessage ?: "Unable to load messages.") }
        }
        val settingsListener = roomService.listenToRoom(space.id, room.id) { result ->
            result.onSuccess(onRoomUpdated)
                .onFailure { onError(it.localizedMessage ?: "Unable to load Room settings.") }
        }
        onDispose {
            messageListener.remove()
            settingsListener.remove()
        }
    }

    fun send() {
        val body = draft.trim()
        if (body.isEmpty()) return
        draft = ""
        roomService.sendMessage(space.id, room.id, senderName, body, replyingTo, composerLinks) { result ->
            result.onSuccess {
                scope.launch {
                    val service = SpaceService()
                    service.recordModuleActivity(
                        context, com.arcinteractive.spaces.data.model.ActivityType.RoomMessageSent,
                        space, "posted in a Room", room.name, room.id,
                        com.arcinteractive.spaces.data.model.ActivityTargetType.Rooms, notifyMembers = false
                    )
                    val roomRecipients = if (room.isPrivate) room.memberIds.toList() else service.memberIds(context, space)
                    service.recordTargetedModuleNotification(
                        context, roomRecipients, "room", space, "posted in a Room", room.name,
                        room.id, com.arcinteractive.spaces.data.model.ActivityTargetType.Rooms
                    )
                }
            }.onFailure { onError(it.localizedMessage ?: "Unable to send message.") }
        }
        replyingTo = null
        composerLinks = emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(room.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (room.isPrivate && canManageMembers) {
                        IconButton(onClick = { isInvitingMembers = true }) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = "Invite Members")
                        }
                    }
                    if (canEdit) {
                        IconButton(onClick = { isEditingRoom = true }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Room Settings")
                        }
                    }
                    if (canDelete) {
                        IconButton(onClick = { isConfirmingDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete Room")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search messages") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleMessages, key = RoomMessage::id) { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (message.isPinned) {
                                Text("📌 Pinned", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            message.replyPreview?.let {
                                Text("↩ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(message.body)
                            message.attachments.forEach { attachment ->
                                TextButton(onClick = {
                                    roomService.downloadAttachment(space.id, room.id, attachment) { result ->
                                        result.onSuccess { bytes ->
                                            runCatching {
                                                val file = File(context.cacheDir, "shared_room_${attachment.id}_${attachment.name}")
                                                file.writeBytes(bytes)
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, attachment.mimeType)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                })
                                            }.onFailure { onError(it.localizedMessage ?: "Unable to open attachment.") }
                                        }.onFailure { onError(it.localizedMessage ?: "Unable to download attachment.") }
                                    }
                                }) {
                                    Text("${if (attachment.isMedia) "🖼️" else "📎"} ${attachment.name}")
                                }
                            }
                            message.links.forEach { link ->
                                TextButton(onClick = { onOpenLink(link) }) {
                                    Text("${link.moduleType.emoji} ${link.moduleType.title}: ${link.title}")
                                }
                            }
                            if (message.reactions.isNotEmpty()) {
                                Text(
                                    message.reactions.joinToString("  ") { "${it.emoji} ${it.userIds.size}" },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row {
                                TextButton(onClick = { replyingTo = message }) {
                                    Icon(Icons.Outlined.Reply, contentDescription = null)
                                    Text("Reply")
                                }
                                listOf("👍", "❤️", "🎉", "👀", "✅").forEach { emoji ->
                                    TextButton(onClick = {
                                        toggleRoomReaction(roomService, space.id, room.id, message, emoji, currentUserId, onError)
                                    }) { Text(emoji) }
                                }
                                if (canManageMembers) {
                                    IconButton(onClick = {
                                        roomService.updateMessage(
                                            space.id, room.id, message.id,
                                            mapOf("isPinned" to !message.isPinned)
                                        ) { it.onFailure { error -> onError(error.localizedMessage ?: "Unable to pin message.") } }
                                    }) {
                                        Icon(Icons.Outlined.PushPin, contentDescription = if (message.isPinned) "Unpin" else "Pin")
                                    }
                                }
                                if (message.senderId == currentUserId) {
                                    IconButton(onClick = {
                                        editingMessage = message
                                        editingBody = message.body
                                    }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "Edit Message")
                                    }
                                }
                                if (message.senderId == currentUserId || canDeleteOthersMessages) {
                                    IconButton(onClick = { deletingMessage = message }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete Message")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            replyingTo?.let { reply ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text("Replying to ${reply.senderName}: ${reply.body}", modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { replyingTo = null }) { Text("Cancel") }
                }
            }
            if (composerLinks.isNotEmpty()) {
                Text(
                    composerLinks.joinToString(" • ") { "${it.moduleType.emoji} ${it.title}" },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (canPost) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    IconButton(onClick = { isShowingAttachmentMenu = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Attachments")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Message") },
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { send() }, enabled = draft.isNotBlank() || composerLinks.isNotEmpty()) {
                        Icon(Icons.Outlined.Send, contentDescription = "Send")
                    }
                }
            } else {
                Text(
                    "🔒 Only Space Owners and Admins can post in this Room.",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (isInvitingMembers) {
        InviteRoomMembersDialog(
            members = members,
            initialSelectedIds = room.memberIds,
            onDismiss = { isInvitingMembers = false },
            onInvite = { selectedIds ->
                val updatedRoom = room.copy(
                    memberIds = selectedIds + room.createdBy,
                    updatedAt = Date()
                )
                roomService.saveRoom(updatedRoom) { result ->
                    result.onSuccess {
                        isInvitingMembers = false
                        onRoomUpdated(updatedRoom)
                    }.onFailure {
                        onError(it.localizedMessage ?: "Unable to invite members.")
                    }
                }
            }
        )
    }
    editingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editingBody,
                    onValueChange = { editingBody = it },
                    label = { Text("Message") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editingBody.isNotBlank(),
                    onClick = {
                        roomService.editMessage(space.id, room.id, message, editingBody.trim()) { result ->
                            result.onSuccess { editingMessage = null }
                                .onFailure { onError(it.localizedMessage ?: "Unable to edit message.") }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingMessage = null }) { Text("Cancel") } }
        )
    }
    deletingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { deletingMessage = null },
            title = { Text("Delete this message?") },
            text = { Text("This removes it from the Room for everyone.") },
            confirmButton = {
                TextButton(onClick = {
                    roomService.deleteMessage(space.id, room.id, message, canDeleteOthersMessages) { result ->
                        result.onSuccess { deletingMessage = null }
                            .onFailure { onError(it.localizedMessage ?: "Unable to delete message.") }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingMessage = null }) { Text("Cancel") } }
        )
    }
    if (isShowingLinkPicker) {
        RoomLinkPickerDialog(
            space = space,
            onDismiss = { isShowingLinkPicker = false },
            onSelect = { link ->
                if (composerLinks.none { it.moduleType == link.moduleType && it.targetId == link.targetId }) {
                    composerLinks = composerLinks + link
                }
                isShowingLinkPicker = false
            },
            onError = onError
        )
    }
    if (isShowingAttachmentMenu) {
        RoomAttachmentSheet(
            onDismiss = { isShowingAttachmentMenu = false },
            onSelectCamera = {
                isShowingAttachmentMenu = false
                val file = File.createTempFile("room_camera_", ".jpg", context.cacheDir)
                pendingCameraFile = file
                cameraPicker.launch(
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                )
            },
            onSelectLinks = {
                isShowingAttachmentMenu = false
                isShowingLinkPicker = true
            },
            onSelectPhotos = {
                isShowingAttachmentMenu = false
                mediaPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onSelectFiles = {
                isShowingAttachmentMenu = false
                filePicker.launch(arrayOf("*/*"))
            },
            onSelectGifs = {
                isShowingAttachmentMenu = false
                gifPickerLauncher()
            }
        )
    }
    if (isEditingRoom) {
        EditRoomDialog(
            room = room,
            members = members,
            canManageMembers = canManageMembers,
            canDelete = canDelete,
            onDismiss = { isEditingRoom = false },
            onManageMembers = {
                isEditingRoom = false
                isInvitingMembers = true
            },
            onDelete = {
                roomService.deleteRoom(room) { result ->
                    result.onSuccess { onRoomDeleted() }
                        .onFailure { onError(it.localizedMessage ?: "Unable to delete Room.") }
                }
            },
            onSave = { updatedRoom ->
                roomService.saveRoom(updatedRoom) { result ->
                    result.onSuccess {
                        isEditingRoom = false
                        onRoomUpdated(updatedRoom)
                    }.onFailure { onError(it.localizedMessage ?: "Unable to edit Room.") }
                }
            }
        )
    }
    if (isConfirmingDelete) {
        AlertDialog(
            onDismissRequest = { isConfirmingDelete = false },
            title = { Text("Delete Room?") },
            text = { Text("This permanently removes the Room.") },
            confirmButton = {
                TextButton(onClick = {
                    roomService.deleteRoom(room) { result ->
                        result.onSuccess { onRoomDeleted() }
                            .onFailure { onError(it.localizedMessage ?: "Unable to delete Room.") }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { isConfirmingDelete = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomAttachmentSheet(
    onDismiss: () -> Unit,
    onSelectCamera: () -> Unit,
    onSelectLinks: () -> Unit,
    onSelectPhotos: () -> Unit,
    onSelectFiles: () -> Unit,
    onSelectGifs: () -> Unit
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
            RoomAttachmentSheetItem(
                Icons.Outlined.PhotoCamera,
                "Camera",
                "Capture a new photo or video",
                onSelectCamera
            )
            RoomAttachmentSheetItem(
                Icons.Outlined.Link,
                "Link",
                "Reference something in this Space",
                onSelectLinks
            )
            RoomAttachmentSheetItem(
                Icons.Outlined.AddPhotoAlternate,
                "Photos & Videos",
                "Choose from your library",
                onSelectPhotos
            )
            RoomAttachmentSheetItem(
                Icons.Outlined.AttachFile,
                "Files",
                "Choose a file from this device",
                onSelectFiles
            )
            RoomAttachmentSheetItem(
                Icons.Outlined.GifBox,
                "GIFs",
                "Search and send a GIF",
                onSelectGifs
            )
        }
    }
}

@Composable
private fun RoomAttachmentSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

@Composable
private fun RoomLinkPickerDialog(
    space: Space,
    onDismiss: () -> Unit,
    onSelect: (SpaceLinkAttachment) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { SpaceLinkRegistry() }
    var selectedModule by remember { mutableStateOf<SpaceLinkModuleDescriptor?>(null) }
    var items by remember { mutableStateOf(emptyList<SpaceLinkRegistryItem>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(selectedModule?.title ?: "Link Module Item") },
        text = {
            LazyColumn {
                if (selectedModule == null) {
                    items(registry.availableModules(space)) { module ->
                        TextButton(onClick = {
                            selectedModule = module
                            scope.launch {
                                runCatching { registry.fetchItems(context, space, module.moduleType) }
                                    .onSuccess { items = it }
                                    .onFailure { onError(it.localizedMessage ?: "Unable to load module items.") }
                            }
                        }) { Text("${module.moduleType.emoji} ${module.title}") }
                    }
                } else {
                    items(items, key = { it.id }) { item ->
                        TextButton(onClick = { onSelect(item.attachment) }) { Text(item.title) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                if (selectedModule == null) onDismiss() else {
                    selectedModule = null
                    items = emptyList()
                }
            }) { Text(if (selectedModule == null) "Cancel" else "Back") }
        }
    )
}

private fun toggleRoomReaction(
    service: RoomService,
    spaceId: String,
    roomId: String,
    message: RoomMessage,
    emoji: String,
    userId: String,
    onError: (String) -> Unit
) {
    val reactions = message.reactions.toMutableList()
    val index = reactions.indexOfFirst { it.emoji == emoji }
    if (index >= 0) {
        val users = reactions[index].userIds.toMutableSet()
        if (!users.add(userId)) users.remove(userId)
        if (users.isEmpty()) reactions.removeAt(index)
        else reactions[index] = reactions[index].copy(userIds = users)
    } else {
        reactions += com.arcinteractive.spaces.data.model.RoomMessageReaction(emoji, setOf(userId))
    }
    service.updateMessage(
        spaceId,
        roomId,
        message.id,
        mapOf("reactions" to reactions.map { mapOf("emoji" to it.emoji, "userIds" to it.userIds.toList()) })
    ) { it.onFailure { error -> onError(error.localizedMessage ?: "Unable to react.") } }
}

@Composable
private fun EditRoomDialog(
    room: SpaceRoom,
    members: List<SpaceMember>,
    canManageMembers: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onManageMembers: () -> Unit,
    onDelete: () -> Unit,
    onSave: (SpaceRoom) -> Unit
) {
    var name by remember(room.id) { mutableStateOf(room.name) }
    var topic by remember(room.id) { mutableStateOf(room.topic) }
    var isPrivate by remember(room.id) { mutableStateOf(room.isPrivate) }
    var ownersAndAdminsOnly by remember(room.id) { mutableStateOf(room.postingMemberIds != null) }
    var isConfirmingDelete by remember(room.id) { mutableStateOf(false) }
    val creatorName = members.firstOrNull { it.id == room.createdBy }?.displayName ?: "Member"

    if (isConfirmingDelete) {
        AlertDialog(
            onDismissRequest = { isConfirmingDelete = false },
            title = { Text("Delete Room?") },
            text = { Text("This permanently removes the Room and its conversation.") },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingDelete = false }) { Text("Cancel") }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room Settings") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Room Details", style = MaterialTheme.typography.titleSmall)
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Room name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = topic,
                        onValueChange = { topic = it },
                        label = { Text("Topic (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text("Access", style = MaterialTheme.typography.titleSmall)
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Private Room", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Only invited members can view and participate.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = isPrivate,
                            onCheckedChange = { isPrivate = it },
                            enabled = canManageMembers
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Only Owners and Admins Can Post", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Other Room members can read but cannot send messages.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = ownersAndAdminsOnly,
                            onCheckedChange = { ownersAndAdminsOnly = it },
                            enabled = canManageMembers
                        )
                    }
                }
                item {
                    Text("${room.memberIds.size} Room member(s)")
                }
                if (canManageMembers) {
                    item {
                        TextButton(onClick = onManageMembers) {
                            Text("Manage Room Members")
                        }
                    }
                } else {
                    item {
                        Text(
                            "You do not have permission to change Room access or posting controls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    Text("Room Information", style = MaterialTheme.typography.titleSmall)
                }
                item {
                    Text("Created by $creatorName", style = MaterialTheme.typography.bodyMedium)
                }
                if (canDelete) {
                    item {
                        TextButton(onClick = { isConfirmingDelete = true }) {
                            Text("Delete Room", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val postingMembers = if (ownersAndAdminsOnly) {
                        members.filter {
                            it.role == com.arcinteractive.spaces.data.model.SpaceMemberRole.Owner ||
                                it.role == com.arcinteractive.spaces.data.model.SpaceMemberRole.Admin
                        }.map { it.id }.toSet()
                    } else {
                        null
                    }
                    onSave(
                        room.copy(
                            name = name.trim(),
                            topic = topic.trim(),
                            isPrivate = if (canManageMembers) isPrivate else room.isPrivate,
                            memberIds = room.memberIds + room.createdBy,
                            postingMemberIds = if (canManageMembers) postingMembers else room.postingMemberIds,
                            updatedAt = Date()
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun InviteRoomMembersDialog(
    members: List<SpaceMember>,
    initialSelectedIds: Set<String>,
    onDismiss: () -> Unit,
    onInvite: (Set<String>) -> Unit
) {
    var selectedIds by remember(members, initialSelectedIds) { mutableStateOf(initialSelectedIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Members") },
        text = {
            if (members.isEmpty()) {
                Text("There are no Space members to manage.")
            } else {
                LazyColumn {
                    items(members, key = SpaceMember::id) { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedIds = if (member.id in selectedIds) {
                                    selectedIds - member.id
                                } else {
                                    selectedIds + member.id
                                }
                            }
                        ) {
                            Checkbox(
                                checked = member.id in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + member.id else selectedIds - member.id
                                }
                            )
                            Text(
                                "${member.emojiAvatar} ${member.displayName}",
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onInvite(selectedIds) },
                enabled = selectedIds.isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

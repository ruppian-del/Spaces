package com.arcinteractive.spaces.ui.screens.lists

import android.content.Intent
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arcinteractive.spaces.data.lists.ListService
import com.arcinteractive.spaces.data.model.*
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistry
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistryItem
import com.google.firebase.auth.FirebaseAuth
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch

private sealed interface ListsPage {
    data object Index : ListsPage
    data class Detail(val list: SpaceList) : ListsPage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(space: Space, onBackPressed: () -> Unit, onOpenLink: (SpaceLinkAttachment) -> Unit, initialListId: String? = null) {
    val context = LocalContext.current
    val service = remember { ListService() }
    val spaces = remember { SpaceService() }
    val scope = rememberCoroutineScope()
    var page: ListsPage by remember(space.id) { mutableStateOf(ListsPage.Index) }
    var lists by remember(space.id) { mutableStateOf(emptyList<SpaceList>()) }
    var members by remember(space.id) { mutableStateOf(emptyList<SpaceMember>()) }
    var canCreate by remember(space.id) { mutableStateOf(false) }
    var editingList by remember { mutableStateOf<SpaceList?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(space.id) {
        val listListener = service.listenToLists(context, space) {
            it.onSuccess { values -> lists = values }.onFailure { error -> errorMessage = error.localizedMessage }
        }
        val memberListener = spaces.listenToMembers(context, space, "lists-${space.id}") {
            it.onSuccess { values -> members = values }
        }
        onDispose { listListener.remove(); memberListener?.remove() }
    }
    LaunchedEffect(space.id) { canCreate = spaces.canPerform(context, space, SpacePermission.CreateLists) }
    LaunchedEffect(initialListId, lists) { if (page is ListsPage.Index) lists.firstOrNull { it.id == initialListId }?.let { page = ListsPage.Detail(it) } }
    BackHandler(enabled = page !is ListsPage.Index) { page = ListsPage.Index }

    when (val active = page) {
        ListsPage.Index -> {
            var query by remember { mutableStateOf("") }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Lists") },
                        navigationIcon = { IconButton(onClick = onBackPressed) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                        actions = { if (canCreate) IconButton(onClick = { isCreating = true }) { Icon(Icons.Outlined.Add, "New List") } }
                    )
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Search Lists") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), modifier = Modifier.fillMaxWidth().padding(12.dp))
                    LazyColumn {
                        items(lists.filter { query.isBlank() || it.title.contains(query, true) }, key = SpaceList::id) { list ->
                            ListItem(
                                headlineContent = { Text(list.title) },
                                leadingContent = { Icon(Icons.Outlined.Checklist, null) },
                                modifier = Modifier.clickable { page = ListsPage.Detail(list) }
                            )
                        }
                    }
                }
            }
        }
        is ListsPage.Detail -> ListDetailScreen(
            space, active.list, members, service,
            onBack = { page = ListsPage.Index },
            onEditList = { editingList = it },
            onDeleteList = {
                scope.launch {
                    runCatching { service.deleteList(context, space, it) }
                        .onSuccess { page = ListsPage.Index }
                        .onFailure { error -> errorMessage = error.localizedMessage }
                }
            },
            onOpenLink = onOpenLink,
            onError = { errorMessage = it }
        )
    }

    if (isCreating || editingList != null) {
        ListEditorDialog(space, editingList, onDismiss = { isCreating = false; editingList = null }) { list ->
            scope.launch {
                runCatching { service.saveList(context, space, list) }
                    .onSuccess { isCreating = false; editingList = null; page = ListsPage.Detail(list.copy(createdBy = list.createdBy.ifBlank { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() })) }
                    .onFailure { errorMessage = it.localizedMessage }
            }
        }
    }
    errorMessage?.let {
        AlertDialog(onDismissRequest = { errorMessage = null }, title = { Text("Lists") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListDetailScreen(
    space: Space,
    list: SpaceList,
    members: List<SpaceMember>,
    service: ListService,
    onBack: () -> Unit,
    onEditList: (SpaceList) -> Unit,
    onDeleteList: (SpaceList) -> Unit,
    onOpenLink: (SpaceLinkAttachment) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val spaces = remember { SpaceService() }
    val scope = rememberCoroutineScope()
    var items by remember(list.id) { mutableStateOf(emptyList<SpaceListItem>()) }
    var query by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<SpaceListItem?>(null) }
    var isAddingItem by remember { mutableStateOf(false) }
    var canEdit by remember { mutableStateOf(false) }
    var canDelete by remember { mutableStateOf(false) }
    var attachmentTarget by remember { mutableStateOf<SpaceListItem?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = attachmentTarget ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unable to read media.")
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val attachment = service.uploadAttachment(context, space, list.id, bytes, "List Media", mime, true)
                service.saveItem(context, space, list, target.copy(attachments = target.attachments + attachment, updatedAt = Date()))
            }.onFailure { onError(it.localizedMessage ?: "Unable to attach media.") }
            attachmentTarget = null
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = attachmentTarget ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unable to read file.")
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val attachment = service.uploadAttachment(context, space, list.id, bytes, uri.lastPathSegment ?: "List File", mime, false)
                service.saveItem(context, space, list, target.copy(attachments = target.attachments + attachment, updatedAt = Date()))
            }.onFailure { onError(it.localizedMessage ?: "Unable to attach file.") }
            attachmentTarget = null
        }
    }

    DisposableEffect(list.id) {
        val listener = service.listenToItems(context, space, list.id) {
            it.onSuccess { values -> items = values }.onFailure { error -> onError(error.localizedMessage ?: "Unable to load List.") }
        }
        onDispose { listener.remove() }
    }
    LaunchedEffect(list.id) {
        val own = list.createdBy == FirebaseAuth.getInstance().currentUser?.uid
        canEdit = spaces.canPerform(context, space, if (own) SpacePermission.EditOwnLists else SpacePermission.EditAnyLists)
        canDelete = spaces.canPerform(context, space, if (own) SpacePermission.DeleteOwnLists else SpacePermission.DeleteAnyLists)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (canEdit) {
                        IconButton(onClick = { isAddingItem = true }) { Icon(Icons.Outlined.Add, "Add Item") }
                        IconButton(onClick = { onEditList(list) }) { Icon(Icons.Outlined.Settings, "List Settings") }
                    }
                    if (canDelete) IconButton(onClick = { onDeleteList(list) }) { Icon(Icons.Outlined.Delete, "Delete List") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search this List") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), modifier = Modifier.fillMaxWidth().padding(12.dp))
            LazyColumn {
                val visible = items.filter { query.isBlank() || it.title.contains(query, true) || it.notes.contains(query, true) }
                if (list.links.isNotEmpty()) {
                    item { Text("Linked Items", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp)) }
                    items(list.links, key = SpaceLinkAttachment::id) { link ->
                        ListItem(
                            headlineContent = { Text("${link.moduleType.title}: ${link.title}") },
                            leadingContent = { Icon(Icons.Outlined.Link, null) },
                            modifier = Modifier.clickable { onOpenLink(link) }
                        )
                    }
                }
                val groups = listOf(null) + list.sections.map { it.id }
                groups.forEach { sectionId ->
                    val values = visible.filter { it.sectionId == sectionId }
                    if (values.isNotEmpty()) {
                        item {
                            Text(
                                list.sections.firstOrNull { it.id == sectionId }?.title ?: "Items",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
                            )
                        }
                        items(values, key = SpaceListItem::id) { item ->
                            var actionsExpanded by remember(item.id) { mutableStateOf(false) }
                            ListItem(
                                headlineContent = { Text(item.title, fontWeight = if (item.isCompleted) FontWeight.Normal else FontWeight.Medium) },
                                supportingContent = {
                                    Column {
                                        if (item.notes.isNotBlank()) Text(item.notes)
                                        if (item.assignedMemberIds.isNotEmpty()) Text("Assigned: " + members.filter { it.id in item.assignedMemberIds }.joinToString { "@${it.displayName}" })
                                        item.dueDate?.let { Text("Due: $it") }
                                        item.attachments.forEach { attachment ->
                                            TextButton(onClick = {
                                                scope.launch {
                                                    runCatching {
                                                        val bytes = service.downloadAttachment(context, space, attachment)
                                                        val file = File(context.cacheDir, "list_${attachment.id}_${attachment.name}")
                                                        file.writeBytes(bytes)
                                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(uri, attachment.mimeType)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        })
                                                    }.onFailure { onError(it.localizedMessage ?: "Unable to open attachment.") }
                                                }
                                            }) {
                                                Text("${if (attachment.isMedia) "🖼️" else "📎"} ${attachment.name}")
                                            }
                                        }
                                        item.links.forEach { link ->
                                            Surface(
                                                onClick = { onOpenLink(link) },
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = MaterialTheme.shapes.small,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                                    Icon(Icons.Outlined.Link, null, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("${link.moduleType.title}: ${link.title}", Modifier.weight(1f))
                                                    Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                },
                                leadingContent = {
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching { service.saveItem(context, space, list, item.copy(isCompleted = !item.isCompleted, updatedAt = Date())) }
                                                .onFailure { onError(it.localizedMessage ?: "Unable to update item.") }
                                        }
                                    }) { Icon(if (item.isCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null) }
                                },
                                trailingContent = {
                                    if (canEdit) {
                                        Box {
                                            IconButton(onClick = { actionsExpanded = true }) {
                                                Icon(Icons.Outlined.MoreVert, "Item Actions")
                                            }
                                            DropdownMenu(
                                                expanded = actionsExpanded,
                                                onDismissRequest = { actionsExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Edit") },
                                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                                    onClick = {
                                                        actionsExpanded = false
                                                        editingItem = item
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Move up") },
                                                    leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) },
                                                    onClick = {
                                                        actionsExpanded = false
                                                        val sameSection = items.filter { it.sectionId == item.sectionId }.sortedBy { it.order }
                                                        val index = sameSection.indexOfFirst { it.id == item.id }
                                                        if (index > 0) {
                                                            val other = sameSection[index - 1]
                                                            scope.launch {
                                                                service.saveItem(context, space, list, item.copy(order = other.order))
                                                                service.saveItem(context, space, list, other.copy(order = item.order))
                                                            }
                                                        }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Move down") },
                                                    leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                                                    onClick = {
                                                        actionsExpanded = false
                                                        val sameSection = items.filter { it.sectionId == item.sectionId }.sortedBy { it.order }
                                                        val index = sameSection.indexOfFirst { it.id == item.id }
                                                        if (index in 0 until sameSection.lastIndex) {
                                                            val other = sameSection[index + 1]
                                                            scope.launch {
                                                                service.saveItem(context, space, list, item.copy(order = other.order))
                                                                service.saveItem(context, space, list, other.copy(order = item.order))
                                                            }
                                                        }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Attach media") },
                                                    leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                                                    onClick = {
                                                        actionsExpanded = false
                                                        attachmentTarget = item
                                                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Attach file") },
                                                    leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                                                    onClick = {
                                                        actionsExpanded = false
                                                        attachmentTarget = item
                                                        filePicker.launch(arrayOf("*/*"))
                                                    }
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        actionsExpanded = false
                                                        scope.launch {
                                                            runCatching { service.deleteItem(context, space, list, item) }
                                                                .onFailure { onError(it.localizedMessage ?: "Unable to delete item.") }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (isAddingItem || editingItem != null) {
        ListItemEditorDialog(space, list, editingItem, members, onDismiss = { isAddingItem = false; editingItem = null }) { item ->
            scope.launch {
                val orderedItem = if (editingItem == null) item.copy(order = (items.maxOfOrNull { it.order } ?: -1) + 1) else item
                runCatching { service.saveItem(context, space, list, orderedItem) }
                    .onSuccess { isAddingItem = false; editingItem = null }
                    .onFailure { onError(it.localizedMessage ?: "Unable to save item.") }
            }
        }
    }
}

@Composable
private fun ListEditorDialog(space: Space, existing: SpaceList?, onDismiss: () -> Unit, onSave: (SpaceList) -> Unit) {
    val context = LocalContext.current
    val registry = remember { SpaceLinkRegistry() }
    val scope = rememberCoroutineScope()
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var sections by remember(existing?.id) { mutableStateOf(existing?.sections.orEmpty()) }
    var newSection by remember { mutableStateOf("") }
    var links by remember(existing?.id) { mutableStateOf(existing?.links.orEmpty()) }
    var showLinkPicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New List" else "List Settings") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("List name") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                sections.forEachIndexed { index, section ->
                    Row {
                        Text(section.title, Modifier.weight(1f))
                        IconButton(onClick = { if (index > 0) sections = sections.toMutableList().also { val v = it.removeAt(index); it.add(index - 1, v) }.mapIndexed { i, v -> v.copy(order = i) } }) { Icon(Icons.Outlined.KeyboardArrowUp, null) }
                        IconButton(onClick = { sections = sections.filterNot { it.id == section.id }.mapIndexed { i, v -> v.copy(order = i) } }) { Icon(Icons.Outlined.Delete, null) }
                    }
                }
                Row {
                    OutlinedTextField(newSection, { newSection = it }, label = { Text("Optional section") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (newSection.isNotBlank()) {
                            sections = sections + SpaceListSection(title = newSection.trim(), order = sections.size)
                            newSection = ""
                        }
                    }) { Icon(Icons.Outlined.Add, "Add Section") }
                }
                Text("Linked Items", style = MaterialTheme.typography.titleSmall)
                links.forEach { link ->
                    Row {
                        Text("${link.moduleType.title}: ${link.title}", Modifier.weight(1f))
                        IconButton(onClick = { links = links.filterNot { it.id == link.id } }) { Icon(Icons.Outlined.Delete, null) }
                    }
                }
                TextButton(onClick = { showLinkPicker = true }) { Text("Add Linked Item") }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val now = Date()
                onSave(SpaceList(existing?.id ?: UUID.randomUUID().toString(), space.id, title.trim(), sections, links, existing?.createdBy.orEmpty(), existing?.createdAt ?: now, now))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showLinkPicker) {
        ListLinkPickerDialog(
            space = space,
            registry = registry,
            onDismiss = { showLinkPicker = false },
            onSelect = { link ->
                if (links.none { it.moduleType == link.moduleType && it.targetId == link.targetId }) links = links + link
                showLinkPicker = false
            }
        )
    }
}

@Composable
private fun ListLinkPickerDialog(
    space: Space,
    registry: SpaceLinkRegistry,
    onDismiss: () -> Unit,
    onSelect: (SpaceLinkAttachment) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeModule by remember { mutableStateOf<com.arcinteractive.spaces.data.spaces.SpaceLinkModuleDescriptor?>(null) }
    var items by remember { mutableStateOf<List<SpaceLinkRegistryItem>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(activeModule?.title ?: "Link Module") },
        text = {
            LazyColumn {
                if (activeModule == null) {
                    items(registry.availableModules(space), key = { it.moduleType.name }) { module ->
                        ListItem(
                            headlineContent = { Text(module.title) },
                            supportingContent = { Text(module.subtitle) },
                            modifier = Modifier.clickable {
                                activeModule = module
                                scope.launch { items = registry.fetchItems(context, space, module.moduleType) }
                            }
                        )
                    }
                } else {
                    items(items, key = SpaceLinkRegistryItem::id) { item ->
                        ListItem(headlineContent = { Text(item.title) }, supportingContent = { item.subtitle?.let { Text(it) } }, modifier = Modifier.clickable { onSelect(item.attachment) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { if (activeModule == null) onDismiss() else { activeModule = null; items = emptyList() } }) { Text(if (activeModule == null) "Cancel" else "Back") } }
    )
}

@Composable
private fun ListItemEditorDialog(space: Space, list: SpaceList, existing: SpaceListItem?, members: List<SpaceMember>, onDismiss: () -> Unit, onSave: (SpaceListItem) -> Unit) {
    val context = LocalContext.current
    val registry = remember { SpaceLinkRegistry() }
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var assignees by remember(existing?.id) { mutableStateOf(existing?.assignedMemberIds.orEmpty()) }
    var sectionId by remember(existing?.id) { mutableStateOf(existing?.sectionId) }
    var hasDueDate by remember(existing?.id) { mutableStateOf(existing?.dueDate != null) }
    var dueDate by remember(existing?.id) { mutableStateOf(existing?.dueDate ?: Date()) }
    var links by remember(existing?.id) { mutableStateOf(existing?.links.orEmpty()) }
    var showLinkPicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Item" else "Edit Item") },
        text = {
            LazyColumn {
                item { OutlinedTextField(title, { title = it }, label = { Text("Item") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)) }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes or details") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)) }
                item {
                    Text("Section")
                    list.sections.forEach { section ->
                        Row(Modifier.fillMaxWidth().clickable { sectionId = section.id }) {
                            RadioButton(sectionId == section.id, { sectionId = section.id }); Text(section.title, Modifier.padding(top = 12.dp))
                        }
                    }
                }
                item { Text("Assign with @mentions", style = MaterialTheme.typography.titleSmall) }
                items(members, key = SpaceMember::id) { member ->
                    Row(Modifier.fillMaxWidth().clickable { assignees = if (member.id in assignees) assignees - member.id else assignees + member.id }) {
                        Checkbox(member.id in assignees, { checked -> assignees = if (checked) assignees + member.id else assignees - member.id })
                        Text("@${member.displayName}", Modifier.padding(top = 12.dp))
                    }
                }
                item {
                    Row {
                        Checkbox(hasDueDate, { hasDueDate = it })
                        TextButton(onClick = {
                            val calendar = java.util.Calendar.getInstance().apply { time = dueDate }
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    dueDate = java.util.Calendar.getInstance().apply { set(year, month, day) }.time
                                    hasDueDate = true
                                },
                                calendar.get(java.util.Calendar.YEAR),
                                calendar.get(java.util.Calendar.MONTH),
                                calendar.get(java.util.Calendar.DAY_OF_MONTH)
                            ).show()
                        }) { Text(if (hasDueDate) "Due: ${dueDate.toString().take(10)}" else "Add due date") }
                    }
                }
                item { Text("Tagged Modules", style = MaterialTheme.typography.titleSmall) }
                items(links, key = SpaceLinkAttachment::id) { link ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${link.moduleType.title}: ${link.title}", Modifier.weight(1f))
                        IconButton(onClick = { links = links.filterNot { it.id == link.id } }) {
                            Icon(Icons.Outlined.Delete, "Remove Tag")
                        }
                    }
                }
                item {
                    TextButton(onClick = { showLinkPicker = true }) {
                        Icon(Icons.Outlined.Link, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tag Another Module")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val now = Date()
                onSave(SpaceListItem(existing?.id ?: UUID.randomUUID().toString(), list.id, title.trim(), notes, existing?.isCompleted ?: false, assignees, if (hasDueDate) dueDate else null, sectionId, existing?.order ?: Int.MAX_VALUE, existing?.attachments.orEmpty(), links, existing?.createdBy.orEmpty(), existing?.createdAt ?: now, now))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showLinkPicker) {
        ListLinkPickerDialog(
            space = space,
            registry = registry,
            onDismiss = { showLinkPicker = false },
            onSelect = { link ->
                if (links.none { it.moduleType == link.moduleType && it.targetId == link.targetId }) {
                    links = links + link
                }
                showLinkPicker = false
            }
        )
    }
}

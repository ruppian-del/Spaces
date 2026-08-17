package com.arcinteractive.spaces.ui.screens.notes

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arcinteractive.spaces.data.model.*
import com.arcinteractive.spaces.data.notes.NoteService
import com.arcinteractive.spaces.data.notes.NoteViewPreference
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistry
import com.arcinteractive.spaces.data.spaces.SpaceLinkRegistryItem
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.auth.FirebaseAuth
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch

private sealed interface NotesPage { data object Index : NotesPage; data class Detail(val note: SpaceNote) : NotesPage }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(space: Space, onBackPressed: () -> Unit, onOpenLink: (SpaceLinkAttachment) -> Unit, initialNoteId: String? = null) {
    val context = LocalContext.current
    val service = remember { NoteService() }
    val spaces = remember { SpaceService() }
    val scope = rememberCoroutineScope()
    var notes by remember(space.id) { mutableStateOf(emptyList<SpaceNote>()) }
    var page: NotesPage by remember(space.id) { mutableStateOf(NotesPage.Index) }
    var query by remember { mutableStateOf("") }
    var canCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SpaceNote?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf("Recently updated") }
    val preferences = remember { context.getSharedPreferences("note-organization", android.content.Context.MODE_PRIVATE) }
    val orderKey = remember(space.id) { "manual.${FirebaseAuth.getInstance().currentUser?.uid ?: "local"}.${space.id}" }
    var manualOrder by remember(space.id) {
        mutableStateOf(preferences.getString(orderKey, "").orEmpty().split(",").filter(String::isNotBlank))
    }
    var viewPreferences by remember(space.id) { mutableStateOf(emptyMap<String, NoteViewPreference>()) }

    DisposableEffect(space.id) {
        val listener = service.listen(context, space) { it.onSuccess { values -> notes = values }.onFailure { e -> error = e.localizedMessage } }
        val preferenceListener = service.listenViewPreferences(space.id) { viewPreferences = it }
        val orderListener = service.listenManualOrder(space.id) { if (it.isNotEmpty()) manualOrder = it }
        onDispose { listener.remove(); preferenceListener?.remove(); orderListener?.remove() }
    }
    LaunchedEffect(space.id) { canCreate = spaces.canPerform(context, space, SpacePermission.CreateNotes) }
    LaunchedEffect(initialNoteId, notes) { if (page is NotesPage.Index) notes.firstOrNull { it.id == initialNoteId }?.let { page = NotesPage.Detail(it) } }
    BackHandler(page !is NotesPage.Index) { page = NotesPage.Index }

    when (val active = page) {
        NotesPage.Index -> Scaffold(
            topBar = { TopAppBar(title = { Text("Notes") }, navigationIcon = { IconButton(onBackPressed) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }, actions = {
                Box { var expanded by remember { mutableStateOf(false) }; IconButton({ expanded = true }) { Icon(Icons.Outlined.Sort, "Sort") }; DropdownMenu(expanded, { expanded = false }) { listOf("Manual", "Recently viewed", "Most viewed", "Recently updated").forEach { value -> DropdownMenuItem({ Text(value) }, { sort = value; expanded = false }) } } }
                if (canCreate) IconButton({ creating = true }) { Icon(Icons.Outlined.Add, "New Note") }
            }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search Notes") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), modifier = Modifier.fillMaxWidth().padding(12.dp))
                val filtered = notes.filter { query.isBlank() || it.title.contains(query, true) || it.markdown.contains(query, true) }
                val currentOrder = manualOrder.filter { id -> notes.any { it.id == id } } + notes.map(SpaceNote::id).filterNot(manualOrder::contains)
                val ordered = when (sort) {
                    "Manual" -> filtered.sortedBy { currentOrder.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                    "Recently viewed" -> filtered.sortedByDescending { viewPreferences[it.id]?.lastViewedAt ?: Date(0) }
                    "Most viewed" -> filtered.sortedByDescending { viewPreferences[it.id]?.viewCount ?: 0 }
                    else -> filtered.sortedByDescending { it.updatedAt }
                }
                LazyColumn { items(ordered, key = SpaceNote::id) { note ->
                    ListItem(
                        headlineContent = { Text(note.title, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(note.markdown, maxLines = 2) },
                        leadingContent = { Icon(Icons.Outlined.Note, null) },
                        trailingContent = {
                            if (sort == "Manual" && query.isBlank()) Row {
                                IconButton({
                                    val list = currentOrder.toMutableList(); val index = list.indexOf(note.id)
                                    if (index > 0) { java.util.Collections.swap(list, index, index - 1); manualOrder = list; preferences.edit().putString(orderKey, list.joinToString(",")).apply(); scope.launch { service.saveManualOrder(space.id, list) } }
                                }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
                                IconButton({
                                    val list = currentOrder.toMutableList(); val index = list.indexOf(note.id)
                                    if (index in 0 until list.lastIndex) { java.util.Collections.swap(list, index, index + 1); manualOrder = list; preferences.edit().putString(orderKey, list.joinToString(",")).apply(); scope.launch { service.saveManualOrder(space.id, list) } }
                                }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
                            }
                        },
                        modifier = Modifier.clickable { page = NotesPage.Detail(note) }
                    )
                } }
            }
        }
        is NotesPage.Detail -> NoteDetail(space, active.note, onBack = { page = NotesPage.Index }, onEdit = { editing = it }, onDeleted = { page = NotesPage.Index }, onOpenLink = onOpenLink, onError = { error = it })
    }
    if (creating || editing != null) {
        NoteEditorDialog(space, editing, onDismiss = { creating = false; editing = null }) { note ->
            scope.launch { runCatching { service.save(context, space, note) }.onSuccess { creating = false; editing = null }.onFailure { error = it.localizedMessage } }
        }
    }
    error?.let { AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton({ error = null }) { Text("OK") } }, title = { Text("Notes") }, text = { Text(it) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetail(space: Space, initial: SpaceNote, onBack: () -> Unit, onEdit: (SpaceNote) -> Unit, onDeleted: () -> Unit, onOpenLink: (SpaceLinkAttachment) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current; val service = remember { NoteService() }; val spaces = remember { SpaceService() }; val scope = rememberCoroutineScope()
    var note by remember(initial.id, initial.updatedAt) { mutableStateOf(initial) }
    var comments by remember { mutableStateOf(emptyList<SpaceNoteComment>()) }; var comment by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(false) }; var canDelete by remember { mutableStateOf(false) }
    val openAttachment: (SpaceNoteAttachment) -> Unit = { attachment ->
        scope.launch { runCatching {
            val bytes = service.download(context, space, attachment)
            val file = File(context.cacheDir, "note_${attachment.id}_${attachment.name}")
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { onError(it.localizedMessage ?: "Unable to open attachment.") } }
    }
    DisposableEffect(note.id) { val l = service.listenComments(context, space, note.id) { it.onSuccess { comments = it } }; onDispose { l.remove() } }
    LaunchedEffect(note.id) {
        service.recordView(space.id, note.id)
        val preferences = context.getSharedPreferences("note-organization", android.content.Context.MODE_PRIVATE)
        val countKey = "count.${space.id}.${note.id}"
        preferences.edit()
            .putLong("viewed.${space.id}.${note.id}", System.currentTimeMillis())
            .putInt(countKey, preferences.getInt(countKey, 0) + 1)
            .apply()
        val own = note.createdBy == FirebaseAuth.getInstance().currentUser?.uid
        canEdit = spaces.canPerform(context, space, if (own) SpacePermission.EditOwnNotes else SpacePermission.EditAnyNotes)
        canDelete = spaces.canPerform(context, space, if (own) SpacePermission.DeleteOwnNotes else SpacePermission.DeleteAnyNotes)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(note.title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }, actions = {
        if (canEdit) IconButton({ onEdit(note) }) { Icon(Icons.Outlined.Edit, "Edit") }
        if (canDelete) IconButton({ scope.launch { runCatching { service.delete(context, space, note) }.onSuccess { onDeleted() }.onFailure { onError(it.localizedMessage ?: "Unable to delete Note.") } } }) { Icon(Icons.Outlined.Delete, "Delete") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { NoteRichBody(note.markdown, note.attachments, openAttachment) }
            items(note.attachments.filter { !it.isMedia || !note.markdown.contains("{{media:${it.id}}}") }, key = SpaceNoteAttachment::id) { attachment ->
                TextButton(onClick = { openAttachment(attachment) }) { Text("${if (attachment.isMedia) "🖼️" else "📎"} ${attachment.name}") }
            }
            items(note.links, key = SpaceLinkAttachment::id) { link -> Surface(onClick = { onOpenLink(link) }, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) { Row(Modifier.fillMaxWidth().padding(12.dp)) { Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(8.dp)); Text("${link.moduleType.title}: ${link.title}", Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) } } }
            item { HorizontalDivider(); Text("Comments", style = MaterialTheme.typography.titleMedium) }
            items(comments, key = SpaceNoteComment::id) { value -> Column { Text(value.authorName, fontWeight = FontWeight.Bold); Text(value.body); Text(value.createdAt.toString(), style = MaterialTheme.typography.labelSmall) } }
            item { OutlinedTextField(comment, { comment = it }, label = { Text("Add a comment") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), modifier = Modifier.fillMaxWidth()); Button({ val body = comment.trim(); scope.launch { runCatching { service.addComment(context, space, note, body, FirebaseAuth.getInstance().currentUser?.displayName ?: "Member") }.onSuccess { comment = "" }.onFailure { onError(it.localizedMessage ?: "Unable to comment.") } } }, enabled = comment.isNotBlank()) { Text("Post Comment") } }
        }
    }
}

@Composable
private fun NoteRichBody(
    markdown: String,
    attachments: List<SpaceNoteAttachment>,
    openAttachment: (SpaceNoteAttachment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        markdown.lines().forEach { rawLine ->
            val mediaId = rawLine.takeIf { it.startsWith("{{media:") && it.endsWith("}}") }
                ?.removePrefix("{{media:")?.removeSuffix("}}")
            val media = mediaId?.let { id -> attachments.firstOrNull { it.id == id && it.isMedia } }
            when {
                media != null -> Surface(
                    onClick = { openAttachment(media) },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(16.dp), verticalArrangement = Arrangement.Center) {
                        Icon(if (media.mimeType.startsWith("video/")) Icons.Outlined.PlayCircle else Icons.Outlined.Image, null)
                        Spacer(Modifier.height(8.dp))
                        Text(media.name, fontWeight = FontWeight.SemiBold)
                    }
                }
                rawLine.startsWith("# ") -> Text(renderInlineMarkdown(rawLine.removePrefix("# ")), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                rawLine.startsWith("## ") -> Text(renderInlineMarkdown(rawLine.removePrefix("## ")), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                rawLine.startsWith("- [ ] ") -> Row { Icon(Icons.Outlined.CheckBoxOutlineBlank, null); Spacer(Modifier.width(8.dp)); Text(renderInlineMarkdown(rawLine.removePrefix("- [ ] "))) }
                rawLine.startsWith("- [x] ", true) -> Row { Icon(Icons.Outlined.CheckBox, null); Spacer(Modifier.width(8.dp)); Text(renderInlineMarkdown(rawLine.drop(6)), textDecoration = TextDecoration.LineThrough) }
                rawLine.startsWith("- ") -> Row { Text("•"); Spacer(Modifier.width(8.dp)); Text(renderInlineMarkdown(rawLine.removePrefix("- "))) }
                rawLine.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val split = rawLine.indexOf(' ')
                    Row { Text(rawLine.substring(0, split)); Spacer(Modifier.width(8.dp)); Text(renderInlineMarkdown(rawLine.substring(split + 1))) }
                }
                else -> Text(renderInlineMarkdown(rawLine))
            }
        }
    }
}

private fun renderInlineMarkdown(value: String) = buildAnnotatedString {
    val linkPattern = Regex("\\[([^]]+)]\\((https?://[^)]+)\\)")
    var cursor = 0
    linkPattern.findAll(value).forEach { match ->
        append(value.substring(cursor, match.range.first))
        withLink(
            LinkAnnotation.Url(
                match.groupValues[2],
                TextLinkStyles(style = SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF5B5BFF), textDecoration = TextDecoration.Underline))
            )
        ) { append(match.groupValues[1]) }
        cursor = match.range.last + 1
    }
    append(value.substring(cursor))
}

@Composable
private fun NoteEditorDialog(space: Space, existing: SpaceNote?, onDismiss: () -> Unit, onSave: (SpaceNote) -> Unit) {
    val context = LocalContext.current; val service = remember { NoteService() }; val spaces = remember { SpaceService() }; val registry = remember { SpaceLinkRegistry() }; val scope = rememberCoroutineScope()
    val noteId = existing?.id ?: remember { UUID.randomUUID().toString() }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }; var markdown by remember { mutableStateOf(existing?.markdown.orEmpty()) }
    var attachments by remember { mutableStateOf(existing?.attachments.orEmpty()) }; var links by remember { mutableStateOf(existing?.links.orEmpty()) }; var showLinks by remember { mutableStateOf(false) }
    var members by remember(space.id) { mutableStateOf(emptyList<SpaceMember>()) }; var showMentions by remember { mutableStateOf(false) }
    DisposableEffect(space.id) {
        val listener = spaces.listenToMembers(context, space, "note-mentions-${space.id}") { result -> result.onSuccess { members = it } }
        onDispose { listener?.remove() }
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { scope.launch { runCatching { val bytes = context.contentResolver.openInputStream(it)!!.use { s -> s.readBytes() }; val mime = context.contentResolver.getType(it) ?: "application/octet-stream"; service.upload(context, space, noteId, bytes, "Note Media", mime, true) }.onSuccess { a -> attachments = attachments + a; markdown += if (markdown.isEmpty() || markdown.endsWith("\n")) "{{media:${a.id}}}\n" else "\n{{media:${a.id}}}\n" } } } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { scope.launch { runCatching { val bytes = context.contentResolver.openInputStream(it)!!.use { s -> s.readBytes() }; service.upload(context, space, noteId, bytes, it.lastPathSegment ?: "Note File", context.contentResolver.getType(it) ?: "application/octet-stream", false) }.onSuccess { a -> attachments = attachments + a } } } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "New Note" else "Edit Note") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)) }
            item {
                var formatExpanded by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Box {
                        FilledTonalIconButton({ formatExpanded = true }) { Icon(Icons.Outlined.TextFormat, "Format") }
                        DropdownMenu(formatExpanded, { formatExpanded = false }) {
                            listOf(
                                "Heading 1" to "# ", "Heading 2" to "## ", "Bulleted List" to "- ",
                                "Numbered List" to "1. ", "Checklist" to "- [ ] "
                            ).forEach { (label, prefix) ->
                                DropdownMenuItem({ Text(label) }, {
                                    markdown += if (markdown.isEmpty() || markdown.endsWith("\n")) prefix else "\n$prefix"
                                    formatExpanded = false
                                })
                            }
                        }
                    }
                    FilledTonalIconButton({ markdown += " [link](https://)" }) { Icon(Icons.Outlined.Link, "Link") }
                    FilledTonalIconButton({ showMentions = true }) { Icon(Icons.Outlined.AlternateEmail, "Mention") }
                    FilledTonalIconButton({ mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) { Icon(Icons.Outlined.Image, "Media") }
                    FilledTonalIconButton({ filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.AttachFile, "File") }
                    FilledTonalIconButton({ showLinks = true }) { Icon(Icons.Outlined.GridView, "Tag Module") }
                }
            }
            item { OutlinedTextField(markdown, { markdown = it }, label = { Text("Rich text") }, minLines = 8, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)) }
            items(attachments) { Text("${if (it.isMedia) "🖼️" else "📎"} ${it.name}") }; items(links) { Text("${it.moduleType.title}: ${it.title}") }
        }
    }, confirmButton = { TextButton({ val now = Date(); onSave(SpaceNote(noteId, space.id, title.trim(), markdown, attachments, links, existing?.createdBy.orEmpty(), existing?.createdAt ?: now, now)) }, enabled = title.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
    if (showLinks) { NoteLinkDialog(space, registry, { showLinks = false }) { link -> if (links.none { it.moduleType == link.moduleType && it.targetId == link.targetId }) links = links + link; showLinks = false } }
    if (showMentions) {
        AlertDialog(
            onDismissRequest = { showMentions = false },
            title = { Text("Mention Member") },
            text = { LazyColumn { items(members, key = SpaceMember::id) { member ->
                ListItem(headlineContent = { Text(member.displayName) }, modifier = Modifier.clickable {
                    markdown += if (markdown.isEmpty() || markdown.endsWith(" ") || markdown.endsWith("\n")) "@${member.displayName}" else " @${member.displayName}"
                    showMentions = false
                })
            } } },
            confirmButton = {},
            dismissButton = { TextButton({ showMentions = false }) { Text("Cancel") } }
        )
    }
}

@Composable private fun NoteLinkDialog(space: Space, registry: SpaceLinkRegistry, onDismiss: () -> Unit, onSelect: (SpaceLinkAttachment) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); var module by remember { mutableStateOf<com.arcinteractive.spaces.data.spaces.SpaceLinkModuleDescriptor?>(null) }; var values by remember { mutableStateOf(emptyList<SpaceLinkRegistryItem>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(module?.title ?: "Tag Module") }, text = { LazyColumn { if (module == null) items(registry.availableModules(space)) { m -> ListItem(headlineContent = { Text(m.title) }, modifier = Modifier.clickable { module = m; scope.launch { values = registry.fetchItems(context, space, m.moduleType) } }) } else items(values) { item -> ListItem(headlineContent = { Text(item.title) }, modifier = Modifier.clickable { onSelect(item.attachment) }) } } }, confirmButton = {}, dismissButton = { TextButton({ if (module == null) onDismiss() else { module = null; values = emptyList() } }) { Text(if (module == null) "Cancel" else "Back") } })
}

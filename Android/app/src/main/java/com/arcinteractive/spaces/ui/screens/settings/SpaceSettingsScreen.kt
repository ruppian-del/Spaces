package com.arcinteractive.spaces.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMemberRole
import com.arcinteractive.spaces.data.model.SpacePermission
import com.arcinteractive.spaces.data.model.capabilities
import com.arcinteractive.spaces.data.spaces.SpaceService
import kotlinx.coroutines.launch
import com.arcinteractive.spaces.data.model.SpaceModule
import com.arcinteractive.spaces.data.model.SpaceTemplate
import com.arcinteractive.spaces.ui.screens.home.SpaceColorOption
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSettingsScreen(
    space: Space,
    onBackPressed: () -> Unit,
    viewModel: SpaceSettingsViewModel = viewModel(factory = SpaceSettingsViewModelFactory(space))
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var isShowingModuleOrderDialog by remember { mutableStateOf(false) }
    var isShowingRolePermissions by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    LaunchedEffect(Unit) {
        viewModel.loadModuleSettings(context)
    }

    if (isShowingModuleOrderDialog) {
        ReorderModulesDialog(
            modules = uiState.moduleOrder,
            onDismiss = { isShowingModuleOrderDialog = false },
            onSave = { order ->
                viewModel.saveModuleOrder(context, order)
                isShowingModuleOrderDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "Appearance") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlinedTextField(
                            value = uiState.spaceName,
                            onValueChange = viewModel::updateSpaceName,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Space name") },
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = uiState.spaceEmoji,
                            onValueChange = viewModel::updateSpaceEmoji,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Space emoji") },
                            placeholder = { Text(SpaceSettingsViewModel.defaultEmoji) },
                            singleLine = true,
                            leadingIcon = {
                                Text(
                                    text = uiState.displayEmoji,
                                    fontSize = 24.sp
                                )
                            }
                        )

                        Text("Space color", style = MaterialTheme.typography.titleSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(SpaceColorOption.entries) { option ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.clickable { viewModel.updateSpaceColor(option) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(option.color)
                                            .border(
                                                width = if (uiState.spaceColor == option) 3.dp else 0.dp,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                shape = CircleShape
                                            )
                                    )
                                    Text(option.title, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = uiState.spaceDescription,
                            onValueChange = viewModel::updateSpaceDescription,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Space description") }
                        )

                        Text("Template type", style = MaterialTheme.typography.titleSmall)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SpaceTemplate.entries.forEach { template ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.updateTemplateType(template) },
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (uiState.templateType == template) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    tonalElevation = 1.dp
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(template.title, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            template.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Members & Roles") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { isShowingRolePermissions = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.canManageRoles
                        ) {
                            Text("Manage roles")
                        }

                        SettingValueRow(label = "Default access", value = uiState.templateType.title)
                    }
                }
            }

            item {
                SettingsSection(title = "Module Settings") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (uiState.canManageModules) {
                            OutlinedButton(
                                onClick = { isShowingModuleOrderDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Reorder modules")
                            }
                            SettingToggleRow(
                                label = "Announcements",
                                checked = uiState.announcementsEnabled,
                                onCheckedChange = { viewModel.handleAnnouncementsToggle(context, it) },
                                enabled = !uiState.isUpdatingAnnouncementsModule
                            )
                            SettingToggleRow(
                                label = "Rooms",
                                checked = uiState.roomsEnabled,
                                onCheckedChange = { viewModel.handleRoomsToggle(context, it) },
                                enabled = !uiState.isUpdatingRoomsModule
                            )
                            SettingToggleRow(
                                label = "Lists",
                                checked = uiState.listsEnabled,
                                onCheckedChange = { viewModel.handleListsToggle(context, it) },
                                enabled = !uiState.isUpdatingListsModule
                            )
                            SettingToggleRow(
                                label = "Notes",
                                checked = uiState.notesEnabled,
                                onCheckedChange = { viewModel.handleNotesToggle(context, it) },
                                enabled = !uiState.isUpdatingNotesModule
                            )
                            SettingToggleRow(
                                label = "Events",
                                checked = uiState.eventsEnabled,
                                onCheckedChange = { viewModel.handleEventsToggle(context, it) },
                                enabled = !uiState.isUpdatingEventsModule
                            )
                            SettingToggleRow(
                                label = "Files",
                                checked = uiState.filesEnabled,
                                onCheckedChange = { viewModel.handleFilesToggle(context, it) },
                                enabled = !uiState.isUpdatingFilesModule
                            )
                            SettingToggleRow(
                                label = "Polls",
                                checked = uiState.pollsEnabled,
                                onCheckedChange = { viewModel.handlePollsToggle(context, it) },
                                enabled = !uiState.isUpdatingPollsModule
                            )
                        } else {
                            SettingValueRow(
                                label = "Announcements",
                                value = if (uiState.announcementsEnabled) "Enabled" else "Disabled"
                            )
                            SettingValueRow(
                                label = "Lists",
                                value = if (uiState.listsEnabled) "Enabled" else "Disabled"
                            )
                            SettingValueRow(
                                label = "Notes",
                                value = if (uiState.notesEnabled) "Enabled" else "Disabled"
                            )
                            SettingValueRow(
                                label = "Events",
                                value = if (uiState.eventsEnabled) "Enabled" else "Disabled"
                            )
                            SettingValueRow(
                                label = "Files",
                                value = if (uiState.filesEnabled) "Enabled" else "Disabled"
                            )
                            SettingValueRow(
                                label = "Polls",
                                value = if (uiState.pollsEnabled) "Enabled" else "Disabled"
                            )
                        }

                        Text(
                            text = buildString {
                                append(
                                    if (uiState.announcementsEnabled) {
                                        "Announcements can be disabled later. Existing announcements will be hidden, not deleted."
                                    } else {
                                        "Owners and admins can enable Announcements for important Space-wide updates."
                                    }
                                )
                                append("\n\n")
                                append(
                                    if (uiState.eventsEnabled) {
                                        "Events can be disabled later. Existing events will be hidden, not deleted."
                                    } else {
                                        "Owners and admins can enable Events later when this Space needs planning and calendars."
                                    }
                                )
                                append("\n\n")
                                append(
                                    if (uiState.filesEnabled) {
                                        "Files can be disabled later. If files already exist, they will be hidden, not deleted."
                                    } else {
                                        "Owners and admins can enable Files later if this Space needs shared documents."
                                    }
                                )
                                append("\n\n")
                                append(
                                    if (uiState.pollsEnabled) {
                                        "Polls stay available until you turn them off. Existing polls will be hidden, not deleted."
                                    } else {
                                        "Owners and admins can enable Polls later when this Space needs questions and voting."
                                    }
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Invites") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingToggleRow(
                            label = "Allow member invites",
                            checked = uiState.allowMemberInvites,
                            onCheckedChange = viewModel::setAllowMemberInvites,
                            enabled = uiState.canManageInvites
                        )

                        OutlinedButton(
                            onClick = { viewModel.openInviteEditor(context) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.canManageInvites && !uiState.isCreatingInvite && !uiState.isLoadingInvite
                        ) {
                            Text(
                                if (uiState.isLoadingInvite) {
                                    "Loading invite..."
                                } else if (uiState.isCreatingInvite) {
                                    "Creating invite..."
                                } else {
                                    "Edit invite link"
                                }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Privacy & Safety") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingToggleRow(
                            label = "Private space",
                            checked = uiState.isPrivateSpace,
                            onCheckedChange = viewModel::setPrivateSpace
                        )
                        SettingToggleRow(
                            label = "Safe mode",
                            checked = uiState.safeModeEnabled,
                            onCheckedChange = viewModel::setSafeModeEnabled
                        )

                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Report Space")
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Notifications") {
                    SettingToggleRow(
                        label = "Notifications",
                        checked = uiState.notificationsEnabled,
                        onCheckedChange = viewModel::setNotificationsEnabled
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Danger Zone",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )

                        Button(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Leave Space")
                        }

                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete Space")
                        }

                        Text(
                            text = "These are placeholders only. No destructive changes happen yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    uiState.pendingInvite?.let { invite ->
        AlertDialog(
            onDismissRequest = viewModel::dismissInvite,
            title = { Text("Invite Link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = invite.code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("${invite.spaceEmoji} ${invite.spaceName}")
                    Text(
                        text = if (invite.active) "Status: Active" else "Status: Inactive",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Expires ${invite.expiresAt}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Remaining uses: ${invite.remainingUses}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(invite.code))
                    }
                ) {
                    Text("Copy Code")
                }
            },
            dismissButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.setInviteActive(context, !invite.active) },
                        enabled = !uiState.isUpdatingInvite
                    ) {
                        Text(if (invite.active) "Disable Link" else "Enable Link")
                    }
                    OutlinedButton(
                        onClick = { viewModel.regenerateInvite(context) },
                        enabled = !uiState.isUpdatingInvite
                    ) {
                        Text("Regenerate Link")
                    }
                    OutlinedButton(onClick = viewModel::dismissInvite) {
                        Text("Done")
                    }
                }
            }
        )
    }

    if (uiState.shouldConfirmHidingFiles) {
        AlertDialog(
            onDismissRequest = viewModel::cancelHideFiles,
            confirmButton = {
                Button(onClick = { viewModel.confirmHideFiles(context) }) {
                    Text("Hide Files")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = viewModel::cancelHideFiles) {
                    Text("Cancel")
                }
            },
            title = { Text("Hide Files?") },
            text = { Text("Files will be hidden, not deleted.") }
        )
    }

    if (isShowingRolePermissions) {
        RolePermissionsDialog(
            space = space,
            onDismiss = { isShowingRolePermissions = false },
            onError = { message ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        )
    }
}

@Composable
private fun RolePermissionsDialog(space: Space, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { SpaceService() }
    var role by remember { mutableStateOf(SpaceMemberRole.Admin) }
    var permissions by remember(role) {
        mutableStateOf(space.rolePermissionOverrides[role] ?: role.capabilities)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Roles & Permissions") },
        text = {
            Column {
                LazyRow {
                    items(listOf(SpaceMemberRole.Admin, SpaceMemberRole.Moderator, SpaceMemberRole.Member, SpaceMemberRole.Guest)) { option ->
                        FilterChip(
                            selected = role == option,
                            onClick = {
                                role = option
                                permissions = space.rolePermissionOverrides[option] ?: option.capabilities
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(SpacePermission.entries) { permission ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = permission in permissions,
                                onCheckedChange = { checked ->
                                    permissions = if (checked) permissions + permission else permissions - permission
                                }
                            )
                            Text(permission.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { service.setPermissions(context, space, role, permissions) }
                        .onSuccess { onDismiss() }
                        .onFailure { onError(it.localizedMessage ?: "Unable to save permissions.") }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReorderModulesDialog(
    modules: List<SpaceModule>,
    onDismiss: () -> Unit,
    onSave: (List<SpaceModule>) -> Unit
) {
    val displayModules = remember(modules.map { it.id }) { modules.toMutableStateList() }
    val rowHeightPx = with(LocalDensity.current) { 64.dp.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(displayModules.toList()) }) {
                Text("Done")
            }
        },
        title = { Text("Reorder Modules") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(displayModules, key = { _, module -> module.id }) { index, module ->
                    var dragOffset by remember(module.id) { mutableStateOf(0f) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(displayModules.size, index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { _: Offset -> dragOffset = 0f },
                                    onDragEnd = { dragOffset = 0f },
                                    onDragCancel = { dragOffset = 0f }
                                ) { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y

                                    if (dragOffset > rowHeightPx && index < displayModules.lastIndex) {
                                        val moved = displayModules.removeAt(index)
                                        displayModules.add(index + 1, moved)
                                        dragOffset -= rowHeightPx
                                    } else if (dragOffset < -rowHeightPx && index > 0) {
                                        val moved = displayModules.removeAt(index)
                                        displayModules.add(index - 1, moved)
                                        dragOffset += rowHeightPx
                                    }
                                }
                            }
                            .offset { IntOffset(0, dragOffset.roundToInt()) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${module.emoji} ${module.title}", modifier = Modifier.width(180.dp))
                        Text("Long-press and drag", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Box(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingValueRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private class SpaceSettingsViewModelFactory(
    private val space: Space
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SpaceSettingsViewModel(space) as T
    }
}

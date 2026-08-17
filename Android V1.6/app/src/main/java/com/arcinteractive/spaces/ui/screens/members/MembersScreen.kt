package com.arcinteractive.spaces.ui.screens.members

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceInvite
import com.arcinteractive.spaces.data.model.SpaceMember
import com.arcinteractive.spaces.data.model.SpaceMemberRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    space: Space,
    onBackPressed: () -> Unit,
    viewModel: MembersViewModel = viewModel(factory = MembersViewModelFactory(space))
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedMember = uiState.selectedMember
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.loadMembersIfNeeded(context)
    }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedMember == null) "Members" else "Member") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedMember == null) onBackPressed() else viewModel.closeMember()
                        }
                    ) {
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
        if (selectedMember == null) {
            MembersList(
                members = uiState.members,
                canInviteMembers = viewModel.canInviteMembers(context),
                onInviteClick = { viewModel.createInvite(context) },
                isCreatingInvite = uiState.isCreatingInvite,
                onMemberSelected = viewModel::openMember,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            MemberDetailScreen(
                member = selectedMember,
                canManageMember = viewModel.canRemoveMember(context, selectedMember),
                availableRoles = viewModel.availableRoles(context, selectedMember),
                onUpdateRole = { role ->
                    viewModel.updateSelectedMemberRole(context, role)
                },
                onRemove = {
                    viewModel.removeSelectedMember(context) { }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }

    uiState.pendingInvite?.let { invite ->
        InviteCodeDialog(
            invite = invite,
            onCopy = {
                clipboardManager.setText(AnnotatedString(invite.code))
                viewModel.dismissInvite()
            },
            onDismiss = viewModel::dismissInvite
        )
    }
}

@Composable
private fun MembersList(
    members: List<SpaceMember>,
    canInviteMembers: Boolean,
    onInviteClick: () -> Unit,
    isCreatingInvite: Boolean,
    onMemberSelected: (SpaceMember) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(
                onClick = onInviteClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = canInviteMembers && !isCreatingInvite
            ) {
                Text(if (isCreatingInvite) "Creating Invite..." else "Invite Member")
            }
        }

        items(members, key = { it.id }) { member ->
            MemberRow(
                member = member,
                onClick = { onMemberSelected(member) }
            )
        }
    }
}

@Composable
private fun MemberRow(
    member: SpaceMember,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape
                    )
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = member.emojiAvatar,
                    fontSize = 24.sp
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = member.role.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = member.status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MemberDetailScreen(
    member: SpaceMember,
    canManageMember: Boolean,
    availableRoles: List<SpaceMemberRole>,
    onUpdateRole: (SpaceMemberRole) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isShowingRoleDialog by remember(member.id) { mutableStateOf(false) }
    var isShowingRemoveDialog by remember(member.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = CircleShape
                            )
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.emojiAvatar,
                            fontSize = 52.sp
                        )
                    }

                    Text(
                        text = member.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = member.role.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = member.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Actions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(
                        onClick = { },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ping")
                    }
                    OutlinedButton(
                        onClick = { isShowingRoleDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = availableRoles.isNotEmpty()
                    ) {
                        Text("Change Role")
                    }
                    OutlinedButton(
                        onClick = { isShowingRemoveDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canManageMember
                    ) {
                        Text("Remove from Space")
                    }
                }
            }
        }

    }

    if (isShowingRoleDialog) {
        AlertDialog(
            onDismissRequest = { isShowingRoleDialog = false },
            title = { Text("Change Role") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableRoles.forEach { role ->
                        OutlinedButton(
                            onClick = {
                                onUpdateRole(role)
                                isShowingRoleDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(role.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { isShowingRoleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (isShowingRemoveDialog) {
        AlertDialog(
            onDismissRequest = { isShowingRemoveDialog = false },
            title = { Text("Remove Member") },
            text = { Text("Remove ${member.displayName} from this Space?") },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove()
                        isShowingRemoveDialog = false
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { isShowingRemoveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun InviteCodeDialog(
    invite: SpaceInvite,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite Member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = invite.code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${invite.spaceEmoji} ${invite.spaceName}",
                    style = MaterialTheme.typography.bodyLarge
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
            Button(onClick = onCopy) {
                Text("Copy Code")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

private class MembersViewModelFactory(
    private val space: Space
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MembersViewModel(space) as T
    }
}

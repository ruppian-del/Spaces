package com.arcinteractive.spaces.ui.screens.you

import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.MainActivity
import com.arcinteractive.spaces.data.model.AccountDevice
import com.arcinteractive.spaces.data.model.BlockedUser
import com.arcinteractive.spaces.data.model.LinkedProvider
import com.arcinteractive.spaces.data.model.UserProfile
import com.arcinteractive.spaces.ui.auth.AuthViewModel
import java.text.DateFormat

private enum class YouDetailDestination {
    BlockedUsers,
    Support,
    Terms,
    Privacy,
    About
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(
    authViewModel: AuthViewModel,
    viewModel: YouViewModel = viewModel()
) {
    val authUiState by authViewModel.uiState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? MainActivity
    val profile = uiState.profile ?: authUiState.currentUserProfile
    val linkedProviders = authUiState.currentSession?.providers ?: profile?.linkedProviders.orEmpty()
    val snackbarHostState = remember { SnackbarHostState() }

    var showEditProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }
    var activeDetail by rememberSaveable { mutableStateOf<YouDetailDestination?>(null) }
    var devicePendingRemovalId by rememberSaveable { mutableStateOf<String?>(null) }

    val devicePendingRemoval = uiState.devices.firstOrNull { it.id == devicePendingRemovalId }

    LaunchedEffect(authUiState.currentSession?.uid) {
        viewModel.loadIfNeeded(context, authUiState.currentSession, authUiState.currentUserProfile)
    }

    LaunchedEffect(uiState.lastMessage) {
        val message = uiState.lastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(activeDetailTitle(activeDetail))
                },
                navigationIcon = {
                    if (activeDetail != null) {
                        TextButton(onClick = { activeDetail = null }) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (activeDetail == null) {
                        TextButton(onClick = { showEditProfileDialog = true }, enabled = profile != null) {
                            Text("Edit Profile")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (activeDetail == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ProfileHeaderCard(profile = profile)
                }

                item {
                    SettingsSectionCard(title = "Account") {
                        SettingsValueRow("Email", profile?.email ?: "Not available")
                        PhoneRow(
                            phoneNumber = profile?.phoneNumber,
                            onLink = { authViewModel.signInWithPhone() }
                        )
                        SettingsValueRow(
                            "Linked Providers",
                            linkedProviders.joinToString(", ") { it.label }.ifBlank { "Unavailable" }
                        )
                        LinkedProvidersSection(
                            linkedProviders = linkedProviders,
                            activity = activity,
                            authViewModel = authViewModel,
                            authUiState = authUiState,
                            context = context
                        )
                    }
                }

                item {
                    SettingsSectionCard(title = "Devices") {
                        if (uiState.devices.isEmpty()) {
                            EmptySettingsText("No registered devices yet.")
                        } else {
                            uiState.devices.forEachIndexed { index, device ->
                                DeviceRow(
                                    device = device,
                                    friendlyName = friendlyDeviceName(device, device.deviceId == uiState.currentDeviceId),
                                    isCurrent = device.deviceId == uiState.currentDeviceId,
                                    pushStatus = pushStatusText(uiState, device),
                                    isRemoving = viewModel.isRemovingDevice(device),
                                    onRemove = if (device.deviceId == uiState.currentDeviceId) null else {
                                        { devicePendingRemovalId = device.id }
                                    }
                                )
                                if (index != uiState.devices.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "Notifications") {
                        SettingsToggleRow(
                            title = "Notifications on This Device",
                            checked = currentDevicePushEnabled(uiState),
                            enabled = !uiState.isUpdatingNotifications,
                            onCheckedChange = { enabled ->
                                viewModel.setCurrentDeviceNotificationsEnabled(context, authUiState.currentSession, enabled)
                            }
                        )
                        SettingsValueRow("Per-Space Settings", "Coming Soon")
                        SettingsValueRow("Quiet Hours", "Coming Soon")
                    }
                }

                item {
                    SettingsSectionCard(title = "Security & Privacy") {
                        SettingsValueRow("End-to-End Encryption", "Enabled")
                        SettingsNavigationRow(
                            title = "Blocked Users",
                            value = "${profile?.blockedUsers?.size ?: 0}"
                        ) {
                            activeDetail = YouDetailDestination.BlockedUsers
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "Appearance") {
                        SettingsValueRow("Theme", "System")
                    }
                }

                item {
                    SettingsSectionCard(title = "Storage") {
                        SettingsValueRow("Cached Media", formatCacheSize(context, uiState.cacheSizeBytes))
                        SettingsButtonRow(
                            title = if (uiState.isClearingCache) "Clearing Cache..." else "Clear Cache",
                            enabled = !uiState.isClearingCache,
                            onClick = { viewModel.clearCache(context) }
                        )
                    }
                }

                item {
                    SettingsSectionCard(title = "Help & About") {
                        SettingsValueRow("Version", appVersionText(context))
                    SettingsNavigationRow("Support") { activeDetail = YouDetailDestination.Support }
                    SettingsNavigationRow("Terms") { activeDetail = YouDetailDestination.Terms }
                    SettingsNavigationRow("Privacy") { activeDetail = YouDetailDestination.Privacy }
                    SettingsNavigationRow("About") { activeDetail = YouDetailDestination.About }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        tonalElevation = 1.dp
                    ) {
                        Button(
                            onClick = { showSignOutDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        ) {
                            Text("Sign Out")
                        }
                    }
                }
            }
        } else {
            DetailContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                destination = activeDetail,
                profile = profile,
                onUnblock = { blockedUser ->
                    viewModel.unblockUser(context, authUiState.currentSession, blockedUser)
                }
            )
        }

        if (uiState.isLoading && profile == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    if (showEditProfileDialog) {
        EditProfileDialog(
            profile = profile,
            isSaving = uiState.isSavingProfile,
            onDismiss = { showEditProfileDialog = false },
            onSave = { displayName, emojiAvatar, statusMessage ->
                viewModel.saveProfile(
                    context = context,
                    session = authUiState.currentSession,
                    displayName = displayName,
                    emojiAvatar = emojiAvatar,
                    statusMessage = statusMessage
                ) { updatedProfile ->
                    authViewModel.applyUpdatedProfile(updatedProfile)
                    showEditProfileDialog = false
                }
            }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out of Spaces?") },
            text = { Text("This will disable push notifications on this device and sign you out of Firebase.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    authViewModel.signOut(context)
                }) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (devicePendingRemoval != null) {
        val pendingDevice = devicePendingRemoval
        AlertDialog(
            onDismissRequest = { devicePendingRemovalId = null },
            title = { Text("Remove this device?") },
            text = { Text("This device will be removed from your recent devices list.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDevice?.let { viewModel.removeDevice(context, authUiState.currentSession, it) }
                    devicePendingRemovalId = null
                }) {
                    Text("Remove Device")
                }
            },
            dismissButton = {
                TextButton(onClick = { devicePendingRemovalId = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (authUiState.isPhoneDialogOpen) {
        PhoneLinkDialog(
            phoneNumber = authUiState.phoneNumberInput,
            verificationCode = authUiState.verificationCodeInput,
            hasPendingVerification = authUiState.pendingPhoneVerificationId != null,
            isLoading = authUiState.isPhoneAuthLoading,
            onPhoneNumberChange = authViewModel::updatePhoneNumberInput,
            onVerificationCodeChange = authViewModel::updateVerificationCodeInput,
            onDismiss = authViewModel::dismissPhoneDialog,
            onContinue = {
                if (authUiState.pendingPhoneVerificationId == null) {
                    authViewModel.startPhoneSignIn(activity)
                } else {
                    authViewModel.submitPhoneVerificationCode(context)
                }
            }
        )
    }
}

@Composable
private fun ProfileHeaderCard(profile: UserProfile?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
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
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = CircleShape
                    )
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = profile?.emojiAvatar ?: "\uD83E\uDDD1\u200D\uD83D\uDCBB", fontSize = 48.sp)
            }

            Text(
                text = profile?.displayName ?: "Your Account",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            (profile?.email ?: profile?.phoneNumber)?.takeIf { it.isNotBlank() }?.let { contact ->
                Text(
                    text = contact,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            profile?.statusMessage?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsValueRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            value?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Open", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PhoneRow(phoneNumber: String?, onLink: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Phone")
        if (phoneNumber.isNullOrBlank()) {
            TextButton(onClick = onLink) {
                Text("Link")
            }
        } else {
            Text(phoneNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun SettingsButtonRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(title)
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DeviceRow(
    device: AccountDevice,
    friendlyName: String,
    isCurrent: Boolean,
    pushStatus: String,
    isRemoving: Boolean,
    onRemove: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = friendlyName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isCurrent) {
                    Text(
                        text = "Current",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            onRemove?.let { remove ->
                TextButton(onClick = remove, enabled = !isRemoving) {
                    Text(if (isRemoving) "Removing..." else "Remove Device")
                }
            }
        }
        Text(pushStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
        device.lastActiveAt?.let {
            Text(
                text = "Last active ${readableDeviceActivityText(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptySettingsText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EditProfileDialog(
    profile: UserProfile?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var displayName by rememberSaveable(profile?.uid) { mutableStateOf(profile?.displayName ?: "") }
    var emojiAvatar by rememberSaveable(profile?.uid) { mutableStateOf(profile?.emojiAvatar ?: "\uD83E\uDDD1\u200D\uD83D\uDCBB") }
    var statusMessage by rememberSaveable(profile?.uid) { mutableStateOf(profile?.statusMessage ?: "") }
    val originalDisplayName = remember(profile?.uid) { profile?.displayName?.trim().orEmpty() }
    val originalEmojiAvatar = remember(profile?.uid) { profile?.emojiAvatar?.trim().orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }.take(2) }
    val originalStatusMessage = remember(profile?.uid) { profile?.statusMessage?.trim().orEmpty() }
    val trimmedDisplayName = displayName.trim()
    val trimmedStatusMessage = statusMessage.trim()
    val resolvedEmojiPreview = emojiAvatar.trim().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }.take(2)
    val hasChanges = trimmedDisplayName != originalDisplayName ||
        resolvedEmojiPreview != originalEmojiAvatar ||
        trimmedStatusMessage != originalStatusMessage
    val canSave = trimmedDisplayName.isNotEmpty() && hasChanges && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .padding(14.dp)
                        ) {
                            Text(text = resolvedEmojiPreview, fontSize = 28.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = trimmedDisplayName.ifBlank { "Display Name" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = trimmedStatusMessage.ifBlank { "Add a status message" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    supportingText = {
                        Text("${trimmedDisplayName.length} characters")
                    }
                )
                OutlinedTextField(
                    value = emojiAvatar,
                    onValueChange = { emojiAvatar = it },
                    label = { Text("Emoji Avatar") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = statusMessage,
                    onValueChange = { statusMessage = it },
                    label = { Text("Status Message") },
                    singleLine = true,
                    supportingText = {
                        Text("${trimmedStatusMessage.length} characters")
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(displayName, emojiAvatar, statusMessage) },
                enabled = canSave
            ) {
                Text(if (isSaving) "Saving..." else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LinkedProvidersSection(
    linkedProviders: List<LinkedProvider>,
    activity: MainActivity?,
    authViewModel: AuthViewModel,
    authUiState: com.arcinteractive.spaces.ui.auth.AuthUiState,
    context: Context
) {
    if (linkedProviders.contains(LinkedProvider.Apple)) {
        SettingsValueRow("Apple", "Linked")
    } else {
        SettingsButtonRow(
            title = if (authUiState.isLinkingApple) "Linking Apple..." else "Link Apple",
            enabled = !authUiState.isLinkingApple,
            onClick = { authViewModel.linkApple(activity) }
        )
    }

    if (linkedProviders.contains(LinkedProvider.Google)) {
        SettingsValueRow("Google", "Linked")
    } else {
        SettingsButtonRow(
            title = if (authUiState.isLinkingGoogle) "Linking Google..." else "Link Google",
            enabled = !authUiState.isLinkingGoogle,
            onClick = { authViewModel.linkGoogle(context) }
        )
    }
}

@Composable
private fun DetailContent(
    modifier: Modifier,
    destination: YouDetailDestination?,
    profile: UserProfile?,
    onUnblock: (BlockedUser) -> Unit
) {
    when (destination) {
        YouDetailDestination.BlockedUsers -> BlockedUsersDetail(modifier, profile?.blockedUsers.orEmpty(), onUnblock)
        YouDetailDestination.Support -> StaticDetailScreen(modifier, supportBodyText())
        YouDetailDestination.Terms -> StaticDetailScreen(modifier, termsBodyText())
        YouDetailDestination.Privacy -> StaticDetailScreen(modifier, privacyBodyText())
        YouDetailDestination.About -> AboutDetailScreen(modifier, aboutBodyText())
        null -> Box(modifier = modifier)
    }
}

@Composable
private fun BlockedUsersDetail(
    modifier: Modifier,
    blockedUsers: List<BlockedUser>,
    onUnblock: (BlockedUser) -> Unit
) {
    if (blockedUsers.isEmpty()) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No blocked users", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "People you block will appear here, and you can unblock them at any time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(blockedUsers, key = { it.id }) { blockedUser ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(blockedUser.emojiAvatar, fontSize = 28.sp)
                            Column {
                                Text(blockedUser.displayName, fontWeight = FontWeight.SemiBold)
                                blockedUser.blockedAt?.let {
                                    Text(
                                        "Blocked ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { onUnblock(blockedUser) }) {
                            Text("Unblock")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaticDetailScreen(modifier: Modifier, text: String) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun AboutDetailScreen(modifier: Modifier, text: String) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Spaces",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Version 1.0",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneLinkDialog(
    phoneNumber: String,
    verificationCode: String,
    hasPendingVerification: Boolean,
    isLoading: Boolean,
    onPhoneNumberChange: (String) -> Unit,
    onVerificationCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPendingVerification) "Verify Number" else "Link Phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasPendingVerification) {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = onVerificationCodeChange,
                        label = { Text("Verification Code") },
                        singleLine = true
                    )
                    Text(
                        text = "Enter the 6-digit code we sent to your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = onPhoneNumberChange,
                        label = { Text("Phone Number") },
                        singleLine = true
                    )
                    Text(
                        text = "Enter your phone number with country code, like +15551234567.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                enabled = !isLoading && if (hasPendingVerification) verificationCode.trim().length >= 6 else phoneNumber.trim().length >= 8
            ) {
                Text(if (isLoading) "Working..." else if (hasPendingVerification) "Verify" else "Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        }
    )
}

private fun pushStatusText(uiState: YouUiState, device: AccountDevice): String {
    val matchingTokens = uiState.pushTokens.filter { it.deviceId == device.deviceId }
    if (matchingTokens.isEmpty()) return "No push token"
    return if (matchingTokens.any { it.enabled }) "Push enabled" else "Push disabled"
}

private fun currentDevicePushEnabled(uiState: YouUiState): Boolean {
    return uiState.pushTokens.firstOrNull { it.deviceId == uiState.currentDeviceId }?.enabled ?: false
}

private fun formatCacheSize(context: Context, bytes: Long?): String {
    if (bytes == null) return "Unavailable"
    return android.text.format.Formatter.formatShortFileSize(context, bytes)
}

private fun appVersionText(context: Context): String {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = info.versionName ?: "Unknown"
        val versionCode = info.longVersionCode
        "$versionName ($versionCode)"
    }.getOrDefault("Unknown")
}

private fun activeDetailTitle(destination: YouDetailDestination?): String {
    return when (destination) {
        YouDetailDestination.BlockedUsers -> "Blocked Users"
        YouDetailDestination.Support -> "Support"
        YouDetailDestination.Terms -> "Terms"
        YouDetailDestination.Privacy -> "Privacy"
        YouDetailDestination.About -> "About"
        null -> "You"
    }
}

private fun friendlyDeviceName(device: AccountDevice, isCurrent: Boolean): String {
    if (isCurrent) {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        if (manufacturer.isNotEmpty() && model.isNotEmpty()) {
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        }
        if (model.isNotEmpty()) return model
    }

    return when (device.platform.lowercase()) {
        "ios" -> "iPhone or iPad"
        "android" -> "Android device"
        else -> device.platform.replaceFirstChar { it.uppercase() }
    }
}

private fun readableDeviceActivityText(date: java.util.Date): String {
    val relative = DateUtils.getRelativeTimeSpanString(
        date.time,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
    val absolute = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
    return "$relative ($absolute)"
}

private fun supportBodyText(): String {
    return """
        Need Help?

        If you’re having trouble with Spaces, we’re here to help.

        Whether you’ve found a bug, have a question, or want to suggest a feature, we’d love to hear from you.

        Common Issues

        Notifications not working

        • Verify notifications are enabled for Spaces in Android Settings.
        • Make sure “Notifications on This Device” is enabled in Spaces.

        Messages won’t send

        • Check your internet connection.
        • Verify both participants are still connected.

        Problems signing in

        • Confirm you’re using the same sign-in provider originally linked to your account.
        • If you’ve lost access to your account, contact support.

        Contact Support

        Email

        support@arcinteractive.studio

        When contacting support, including your app version and device model, and any screenshots of the issue you are having, it helps us investigate more quickly.

        Response Times

        We typically respond within 1–2 business days.
    """.trimIndent()
}

private fun termsBodyText(): String {
    return """
        Welcome to Spaces

        Spaces is designed for private, meaningful conversations between people. By using Spaces, you agree to use the service responsibly.

        Acceptable Use

        You agree not to:

        • Harass or threaten others
        • Impersonate another person
        • Distribute malware or malicious content
        • Attempt unauthorized access to accounts or systems
        • Use Spaces for illegal activities

        Your Content

        You retain ownership of everything you create in Spaces.

        Because conversations are end-to-end encrypted, we cannot read your messages.

        You are responsible for any content you send.

        Account Security

        You are responsible for maintaining access to your account and keeping your linked authentication providers secure.

        Service Availability

        We strive to keep Spaces available at all times but cannot guarantee uninterrupted service.

        Features may change, improve, or be removed over time.

        Termination

        Accounts that repeatedly violate these Terms may be suspended or permanently removed.
    """.trimIndent()
}

private fun privacyBodyText(): String {
    return """
        Your Privacy Matters

        Privacy isn’t just a feature of Spaces—it’s part of how the app is built.

        Information We Collect

        To provide the service, we store:

        • Your display name
        • Profile emoji
        • Linked sign-in providers
        • Account identifier
        • Device information required for notifications
        • Encrypted conversation metadata needed to deliver messages

        What We Don’t Read

        Messages sent in Spaces are protected using end-to-end encryption.

        We cannot read your conversations.

        We do not use message content for advertising or profiling.

        Notifications

        Push notifications are used only to notify you of activity you’ve chosen to receive.

        Notification settings can be changed at any time.

        Data Security

        We use industry-standard encryption for data in transit and secure cloud infrastructure for account information.

        Account Deletion

        Deleting your account permanently removes your account information from Spaces.

        Some encrypted message records may remain on recipients’ devices until deleted by them.

        Questions

        If you have questions about privacy, contact:

        info@arcinteractive.studio
    """.trimIndent()
}

private fun aboutBodyText(): String {
    return """
        Built for Better Conversations

        Spaces is a communication app focused on intentional conversations—not endless feeds.

        Whether you’re chatting one-on-one or collaborating inside shared Spaces, the goal is simple:

        Give people a place to communicate without unnecessary noise.

        Features

        • End-to-end encrypted messaging
        • Secure device management
        • Private one-to-one Pings
        • Shared Spaces for conversations
        • Cross-device synchronization
        • Privacy-first design

        Built By

        Designed and developed by

        ArcInteractive

        Thank You

        Thank you for helping shape Spaces from the very beginning.

        Every report, suggestion, and conversation helps make the app better.
    """.trimIndent()
}

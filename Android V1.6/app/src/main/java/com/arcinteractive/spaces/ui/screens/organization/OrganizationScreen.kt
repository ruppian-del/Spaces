package com.arcinteractive.spaces.ui.screens.organization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import com.arcinteractive.spaces.data.model.Organization
import com.arcinteractive.spaces.data.model.OrganizationEntitlements
import com.arcinteractive.spaces.data.model.OrganizationOwnedSpace
import com.arcinteractive.spaces.data.model.OrganizationMember
import com.arcinteractive.spaces.data.model.OrganizationRole
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.organization.OrganizationService
import com.arcinteractive.spaces.data.spaces.InviteLink
import kotlinx.coroutines.launch

@Composable
fun OrganizationScreen(initialInviteCode: String? = null, onInviteConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val service = remember { OrganizationService() }
    val authService = remember { AuthService() }
    val userProfileService = remember { UserProfileService() }
    val scope = rememberCoroutineScope()
    var organization by remember { mutableStateOf<Organization?>(null) }
    var name by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var ownedSpaces by remember { mutableStateOf<List<OrganizationOwnedSpace>>(emptyList()) }
    var members by remember { mutableStateOf<List<OrganizationMember>>(emptyList()) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var joinCode by remember { mutableStateOf("") }
    var currentUserId by remember { mutableStateOf<String?>(null) }
    var currentUserProfileName by remember { mutableStateOf<String?>(null) }
    var memberProfileNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var memberPendingRemoval by remember { mutableStateOf<OrganizationMember?>(null) }
    var isManagingPeople by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(context) {
        val session = authService.currentSession(context) ?: return@LaunchedEffect
        currentUserId = session.uid
        currentUserProfileName = runCatching {
            userProfileService.fetchUserProfile(context, session.uid)?.displayName?.trim()
        }.getOrNull()?.takeUnless { it.isNullOrEmpty() }
    }

    androidx.compose.runtime.LaunchedEffect(members) {
        memberProfileNames = members.associate { member ->
            val profileName = runCatching {
                userProfileService.fetchUserProfile(context, member.userId)?.displayName?.trim()
            }.getOrNull()?.takeUnless { it.isNullOrEmpty() }
            member.userId to (profileName ?: member.displayName)
        }
    }

    DisposableEffect(context) {
        var ownedSpacesListener: com.google.firebase.firestore.ListenerRegistration? = null
        var membersListener: com.google.firebase.firestore.ListenerRegistration? = null
        val listener = service.listenToOrganizationsForCurrentUser(context) { result ->
            result.onSuccess { organizations ->
                organization = organizations.sortedBy { it.name.lowercase() }.firstOrNull()
                ownedSpacesListener?.remove()
                membersListener?.remove()
                organization?.let { selected -> ownedSpacesListener = service.listenToOwnedSpaces(context, selected) { ownedSpaces = it.getOrDefault(emptyList()) } }
                organization?.let { selected -> membersListener = service.listenToMembers(context, selected) { members = it.getOrDefault(emptyList()) } }
            }
            result.onFailure { errorMessage = it.localizedMessage ?: "Unable to load organization." }
        }
        onDispose { listener?.remove(); ownedSpacesListener?.remove(); membersListener?.remove() }
    }

    androidx.compose.runtime.LaunchedEffect(initialInviteCode) {
        initialInviteCode?.let { code ->
            runCatching { service.redeemInvite(context, code) }.onFailure { errorMessage = it.localizedMessage }
            onInviteConsumed()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(if (isManagingPeople) "Manage Administrators" else "Organization", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (organization == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.Business, contentDescription = null, modifier = Modifier.padding(8.dp))
                Text("Set Up Your Organization", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Create the founding organization before adding administrators, people, and organization-owned Spaces.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(name, { name = it }, label = { Text("Church or organization name") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        scope.launch {
                            isCreating = true
                            runCatching { service.createFoundingOrganization(context, name) }
                                .onSuccess { organization = it }
                                .onFailure { errorMessage = it.localizedMessage ?: "Unable to create organization." }
                            isCreating = false
                        }
                    },
                    enabled = !isCreating && name.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(if (isCreating) "Creating…" else "Create Organization") }
                OutlinedTextField(joinCode, { joinCode = it }, label = { Text("Organization invite code") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { scope.launch { runCatching { service.redeemInvite(context, joinCode) }.onFailure { errorMessage = it.localizedMessage } } }, enabled = joinCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Join Organization") }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            }
        } else {
            val canManageOrganization = members.firstOrNull { it.userId == currentUserId }?.role?.canManageOrganization == true
            if (isManagingPeople) {
                TextButton(onClick = { isManagingPeople = false }) { Text("‹ Organization") }
                OrganizationAdministratorManagementPage(
                    organization = organization!!,
                    administrators = members.filter { it.role.canManageOrganization }.distinctBy { it.userId },
                    inviteCode = inviteCode,
                    currentUserId = currentUserId,
                    currentUserProfileName = currentUserProfileName,
                    memberProfileNames = memberProfileNames,
                    canManageOrganization = canManageOrganization,
                    onAdd = {
                        scope.launch { runCatching { service.createInvite(context, organization!!, OrganizationRole.Administrator) }.onSuccess {
                            inviteCode = it.id
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, InviteLink.buildOrganization(it.id).toString()) }, "Invite to organization"))
                        }.onFailure { errorMessage = it.localizedMessage } }
                    },
                    onRemove = { member -> memberPendingRemoval = member }
                )
            } else OrganizationDashboard(
                organization = organization!!,
                ownedSpaces = ownedSpaces,
                administratorCount = members.filter { it.role.canManageOrganization }.map { it.userId }.toSet().size,
                uniqueMemberCount = ownedSpaces.flatMap { it.memberIds }.toSet().size,
                onManagePeople = { isManagingPeople = true }
            )
        }
    }

    memberPendingRemoval?.let { member ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { memberPendingRemoval = null },
            title = { Text("Remove Administrator?") },
            text = { Text("${memberProfileNames[member.userId] ?: member.displayName} will keep their existing Space memberships, but will no longer be an organization administrator.") },
            confirmButton = { Button(onClick = {
                scope.launch {
                    runCatching { service.removeMember(context, organization!!, member) }
                        .onFailure { errorMessage = it.localizedMessage ?: "Unable to remove member." }
                    memberPendingRemoval = null
                }
            }) { Text("Remove") } },
            dismissButton = { OutlinedButton(onClick = { memberPendingRemoval = null }) { Text("Cancel") } }
        )
    }

}

@Composable
private fun OrganizationDashboard(organization: Organization, ownedSpaces: List<OrganizationOwnedSpace>, administratorCount: Int, uniqueMemberCount: Int, onManagePeople: () -> Unit) {
    val context = LocalContext.current
    var debugOverrideRevision by remember { mutableStateOf(0) }
    val storedEntitlements = organization.entitlements.let {
        if (it.peopleCapacity == null && it.activeSpaceCapacity == null && it.mediaStorageCapacityBytes == null && it.enabledModuleIds.isEmpty()) OrganizationEntitlements.Foundation else it
    }
    val entitlements = remember(organization, debugOverrideRevision) { OrganizationEntitlements.effective(context, storedEntitlements) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(organization.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("$administratorCount administrator${if (administratorCount == 1) "" else "s"}")
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Capacity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("People — ${entitlements.peopleCapacity?.let { "$uniqueMemberCount of $it" } ?: "Not configured"}")
        Text("Active Spaces — ${entitlements.activeSpaceCapacity?.let { "${ownedSpaces.count { space -> !space.isArchived }} of $it" } ?: "Not configured"}")
        Text("Media storage — ${entitlements.mediaStorageCapacityBytes?.let { "${storageLabel(organization.usage.mediaStorageBytes)} of ${storageLabel(it)}" } ?: "Not configured"}")
    } }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Administration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Organization administrators manage organization-owned Spaces. Space members are managed inside each Space.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onManagePeople, modifier = Modifier.fillMaxWidth()) { Text("Manage Administrators") }
    } }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Organization Spaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (ownedSpaces.isEmpty()) Text("No Spaces have been added to this organization.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ownedSpaces.forEach { space -> Text("${space.emoji}  ${space.name}${if (space.isArchived) " · Archived" else ""} · ${space.memberCount} member${if (space.memberCount == 1) "" else "s"}") }
    } }
    if (com.arcinteractive.spaces.BuildConfig.DEBUG) {
        OrganizationDebugOverridesCard(
            organization = organization,
            storedEntitlements = storedEntitlements,
            onChanged = { debugOverrideRevision += 1 }
        )
    }
}

@Composable
private fun OrganizationDebugOverridesCard(
    organization: Organization,
    storedEntitlements: OrganizationEntitlements,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val service = remember { OrganizationService() }
    val scope = rememberCoroutineScope()
    val initial = remember(organization) { organization.entitlements }
    var people by remember(organization.id) { mutableStateOf(initial.peopleCapacity?.toString().orEmpty()) }
    var spaces by remember(organization.id) { mutableStateOf(initial.activeSpaceCapacity?.toString().orEmpty()) }
    var storageMegabytes by remember(organization.id) { mutableStateOf(initial.mediaStorageCapacityBytes?.div(1024L * 1024L)?.toString().orEmpty()) }
    var modules by remember(organization.id) { mutableStateOf(initial.enabledModuleIds.sorted().joinToString(", ")) }
    var message by remember(organization.id) { mutableStateOf<String?>(null) }
    var isSaving by remember(organization.id) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(organization.id) {
        runCatching { service.effectiveEntitlements(context, organization.id) }.onSuccess { saved ->
            people = saved.peopleCapacity?.toString().orEmpty()
            spaces = saved.activeSpaceCapacity?.toString().orEmpty()
            storageMegabytes = saved.mediaStorageCapacityBytes?.div(1024L * 1024L)?.toString().orEmpty()
            modules = saved.enabledModuleIds.sorted().joinToString(", ")
        }.onFailure { message = it.localizedMessage ?: "Unable to load overrides." }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Debug Testing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("These debug-only values sync with the organization on every debug device. Release builds ignore them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(people, { people = it }, label = { Text("People capacity") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(spaces, { spaces = it }, label = { Text("Active-Space capacity") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(storageMegabytes, { storageMegabytes = it }, label = { Text("Storage capacity (MB)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                modules,
                { modules = it },
                label = { Text("Enabled module IDs") },
                supportingText = { Text("Comma-separated: general, events, polls, members, settings, announcements, rooms, lists, notes, photos, files") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                val peopleValue = people.toIntOrNull()
                val spacesValue = spaces.toIntOrNull()
                val storageValue = storageMegabytes.toLongOrNull()
                if (peopleValue == null || peopleValue < 0 || spacesValue == null || spacesValue < 0 || storageValue == null || storageValue < 0 || storageValue > Long.MAX_VALUE / (1024L * 1024L)) {
                    message = "Enter zero or a positive whole number for every capacity."
                } else {
                    scope.launch {
                        isSaving = true
                        runCatching {
                            service.setDebugEntitlementOverrides(context, organization.id, OrganizationEntitlements(
                                peopleValue, spacesValue,
                                modules.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
                                storageValue * 1024L * 1024L
                            ))
                        }.onSuccess { message = "Debug overrides saved for every debug device."; onChanged() }
                            .onFailure { message = it.localizedMessage ?: "Unable to save overrides." }
                        isSaving = false
                    }
                }
            }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) { Text("Apply Overrides") }
            OutlinedButton(onClick = {
                scope.launch {
                    isSaving = true
                    runCatching { service.clearDebugEntitlementOverrides(context, organization.id) }
                        .onSuccess { message = "Overrides cleared."; onChanged() }
                        .onFailure { message = it.localizedMessage ?: "Unable to clear overrides." }
                    isSaving = false
                }
            }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) { Text("Reset to Organization Values") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun OrganizationAdministratorManagementPage(organization: Organization, administrators: List<OrganizationMember>, inviteCode: String?, currentUserId: String?, currentUserProfileName: String?, memberProfileNames: Map<String, String>, canManageOrganization: Boolean, onAdd: () -> Unit, onRemove: (OrganizationMember) -> Unit) {
    var selectedMember by remember { mutableStateOf<OrganizationMember?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Administrators", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (canManageOrganization) {
            Text("Invite another administrator to manage organization-owned Spaces.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAdd) { Text("Invite Administrator") }
            inviteCode?.let { Text("Invite link ready to share", color = MaterialTheme.colorScheme.primary) }
        } else Text("Only organization administrators can invite people.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Current Administrators", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        administrators.forEach { member ->
            val displayName = memberProfileNames[member.userId] ?: if (member.userId == currentUserId && !currentUserProfileName.isNullOrBlank()) currentUserProfileName else member.displayName
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                    Text(displayName, fontWeight = FontWeight.Medium)
                    Text(roleLabel(member.role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canManageOrganization && member.role != OrganizationRole.PrimaryAdministrator) {
                    TextButton(onClick = { selectedMember = member }) { Text("Manage") }
                }
            }
        }
    } }
    selectedMember?.let { member ->
        val displayName = memberProfileNames[member.userId] ?: member.displayName
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selectedMember = null },
            title = { Text("Manage $displayName") },
            text = { Text("This does not change their membership in any Space.") },
            confirmButton = { TextButton(onClick = { onRemove(member); selectedMember = null }) { Text("Remove Administrator") } },
            dismissButton = { TextButton(onClick = { selectedMember = null }) { Text("Cancel") } }
        )
    }
}

private fun roleLabel(role: OrganizationRole): String = when (role) {
    OrganizationRole.PrimaryAdministrator -> "Primary Administrator"
    OrganizationRole.Administrator -> "Administrator"
    OrganizationRole.Member -> "Member"
}

private fun storageLabel(bytes: Long): String {
    val gigabyte = 1024L * 1024L * 1024L
    val value = bytes.toDouble() / gigabyte.toDouble()
    return if (value % 1.0 == 0.0) "${value.toInt()} GB" else "%.1f GB".format(value)
}

package com.arcinteractive.spaces.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.SpaceModules
import com.arcinteractive.spaces.data.model.SpaceTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSpaceSheet(
    onDismiss: () -> Unit,
    isCreating: Boolean,
    onCreateSpace: (name: String, emoji: String, colorHex: String, description: String, template: SpaceTemplate, enabledModules: List<com.arcinteractive.spaces.data.model.SpaceModule>) -> Unit,
    viewModel: CreateSpaceViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val emojiFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showInviteInfoDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isCreating) onDismiss()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "Create Space",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::updateName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Space Name") },
                    singleLine = true
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Space Icon", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                emojiFocusRequester.requestFocus()
                                keyboardController?.show()
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = uiState.displayEmoji,
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit Emoji",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        OutlinedTextField(
                            value = uiState.emoji,
                            onValueChange = viewModel::updateEmoji,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(emojiFocusRequester),
                            label = { Text("Emoji") },
                            placeholder = { Text(CreateSpaceViewModel.DEFAULT_EMOJI) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Space Color", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(SpaceColorOption.entries) { option ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.clickable { viewModel.updateColor(option) }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(option.color)
                                        .border(
                                            width = if (uiState.color == option) 3.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            shape = CircleShape
                                        )
                                )
                                Text(option.title, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 4
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Template", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpaceTemplate.entries.forEach { template ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateTemplate(template) },
                                shape = RoundedCornerShape(18.dp),
                                color = if (uiState.template == template) {
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

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Modules", style = MaterialTheme.typography.titleMedium)
                    if (uiState.template == SpaceTemplate.Custom) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SpaceModules.configurable.forEach { module ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 1.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("${module.emoji} ${module.title}", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                module.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        androidx.compose.material3.Switch(
                                            checked = viewModel.isModuleEnabled(module),
                                            onCheckedChange = { viewModel.setModuleEnabled(module, it) },
                                            enabled = module.id != SpaceModules.General.id
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "General stays on by default. You can enable Files later from Space Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            uiState.enabledModules.joinToString(" • ") { it.title },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Invite Members", style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = { showInviteInfoDialog = true },
                        label = { Text("Invite Members") }
                    )
                    Text(
                        "Optional for now. You can invite people later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !isCreating
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        onCreateSpace(
                            uiState.name,
                            uiState.displayEmoji,
                            uiState.color.hex,
                            uiState.description,
                            uiState.template,
                            uiState.enabledModules
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = uiState.isCreateEnabled && !isCreating
                ) {
                    Text(if (isCreating) "Creating..." else "Create Space")
                }
            }
        }
    }

    if (showInviteInfoDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInviteInfoDialog = false },
            confirmButton = {
                Button(onClick = { showInviteInfoDialog = false }) {
                    Text("OK")
                }
            },
            title = { Text("Invite Members") },
            text = {
                Text("Create the Space first, then invite members from the Members screen.")
            }
        )
    }
}

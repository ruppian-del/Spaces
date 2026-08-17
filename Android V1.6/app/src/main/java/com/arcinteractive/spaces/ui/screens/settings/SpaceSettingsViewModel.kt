package com.arcinteractive.spaces.ui.screens.settings

import android.icu.text.BreakIterator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceModule
import com.arcinteractive.spaces.data.model.SpacePermission
import com.arcinteractive.spaces.data.model.SpaceInvite
import com.arcinteractive.spaces.data.model.SpaceTemplate
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.arcinteractive.spaces.ui.screens.home.SpaceColorOption
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class SpaceSettingsUiState(
    val spaceName: String,
    val spaceEmoji: String,
    val spaceColor: SpaceColorOption,
    val spaceDescription: String,
    val templateType: SpaceTemplate,
    val announcementsEnabled: Boolean,
    val roomsEnabled: Boolean,
    val eventsEnabled: Boolean,
    val filesEnabled: Boolean,
    val pollsEnabled: Boolean,
    val listsEnabled: Boolean,
    val notesEnabled: Boolean,
    val canManageModules: Boolean = false,
    val canManageRoles: Boolean = false,
    val canManageInvites: Boolean = false,
    val isUpdatingAnnouncementsModule: Boolean = false,
    val isUpdatingRoomsModule: Boolean = false,
    val isUpdatingEventsModule: Boolean = false,
    val isUpdatingFilesModule: Boolean = false,
    val isUpdatingPollsModule: Boolean = false,
    val isUpdatingListsModule: Boolean = false,
    val isUpdatingNotesModule: Boolean = false,
    val shouldConfirmHidingFiles: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val allowMemberInvites: Boolean = true,
    val isPrivateSpace: Boolean = true,
    val safeModeEnabled: Boolean = true,
    val isLoadingInvite: Boolean = false,
    val isCreatingInvite: Boolean = false,
    val isUpdatingInvite: Boolean = false,
    val pendingInvite: SpaceInvite? = null,
    val moduleOrder: List<SpaceModule> = emptyList(),
    val lastErrorMessage: String? = null
) {
    val displayEmoji: String = spaceEmoji.ifBlank { SpaceSettingsViewModel.defaultEmoji }
}

class SpaceSettingsViewModel(
    private val space: Space,
    private val spaceService: SpaceService = SpaceService()
) : ViewModel() {
    companion object {
        const val defaultEmoji = "\uD83C\uDFE0"
    }

    private val _uiState = MutableStateFlow(
        SpaceSettingsUiState(
            spaceName = space.name,
            spaceEmoji = space.emoji.ifBlank { defaultEmoji },
            spaceColor = SpaceColorOption.entries.firstOrNull { it.hex == space.colorHex } ?: SpaceColorOption.Indigo,
            spaceDescription = space.subtitle,
            templateType = space.template,
            announcementsEnabled = space.announcementsEnabled,
            roomsEnabled = space.roomsEnabled,
            eventsEnabled = space.eventsEnabled,
            filesEnabled = space.filesEnabled,
            pollsEnabled = space.pollsEnabled,
            listsEnabled = space.listsEnabled,
            notesEnabled = space.notesEnabled,
            moduleOrder = space.moduleOrder
        )
    )
    val uiState: StateFlow<SpaceSettingsUiState> = _uiState.asStateFlow()

    fun updateSpaceName(name: String) {
        _uiState.update { it.copy(spaceName = name) }
    }

    fun updateSpaceEmoji(emoji: String) {
        val compact = emoji.trim()
        val sanitized = if (compact.isEmpty()) {
            ""
        } else {
            val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
            iterator.setText(compact)
            val end = iterator.next()
            if (end == BreakIterator.DONE) compact else compact.substring(0, end)
        }

        _uiState.update { it.copy(spaceEmoji = sanitized) }
    }

    fun updateSpaceColor(color: SpaceColorOption) {
        _uiState.update { it.copy(spaceColor = color) }
    }

    fun updateSpaceDescription(description: String) {
        _uiState.update { it.copy(spaceDescription = description) }
    }

    fun updateTemplateType(template: SpaceTemplate) {
        _uiState.update { it.copy(templateType = template) }
    }

    fun loadModuleSettings(context: android.content.Context) {
        viewModelScope.launch {
            val canManage = spaceService.canManageModules(context, space)
            val canManageRoles = spaceService.canPerform(context, space, SpacePermission.ManageRoles)
            val canManageInvites = spaceService.canPerform(context, space, SpacePermission.InviteMembers)
            val moduleOrder = runCatching {
                spaceService.fetchModuleOrder(context, space)
            }.getOrDefault(_uiState.value.moduleOrder)
            _uiState.update {
                it.copy(
                    canManageModules = canManage,
                    canManageRoles = canManageRoles,
                    canManageInvites = canManageInvites,
                    moduleOrder = moduleOrder
                )
            }
        }
    }

    fun addToOrganization(context: android.content.Context, organizationId: String) {
        viewModelScope.launch {
            runCatching { spaceService.addToOrganization(context, space, organizationId) }
                .onFailure { error -> _uiState.update { it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to add this Space to the organization.") } }
        }
    }

    fun moveModule(context: android.content.Context, fromIndex: Int, toIndex: Int) {
        val currentOrder = _uiState.value.moduleOrder
        if (fromIndex !in currentOrder.indices || toIndex !in currentOrder.indices) return

        val mutable = currentOrder.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        _uiState.update { it.copy(moduleOrder = mutable) }

        viewModelScope.launch {
            runCatching {
                spaceService.updateModuleOrder(context, space, mutable)
            }.onFailure { error ->
                val latestOrder = runCatching {
                    spaceService.fetchModuleOrder(context, space)
                }.getOrDefault(currentOrder)
                _uiState.update {
                    it.copy(
                        moduleOrder = latestOrder,
                        lastErrorMessage = error.localizedMessage ?: "Unable to save module order."
                    )
                }
            }
        }
    }

    fun saveModuleOrder(context: android.content.Context, order: List<SpaceModule>) {
        val previousOrder = _uiState.value.moduleOrder
        _uiState.update { it.copy(moduleOrder = order) }
        viewModelScope.launch {
            runCatching {
                spaceService.updateModuleOrder(context, space, order)
                spaceService.fetchModuleOrder(context, space)
            }.onSuccess { savedOrder ->
                _uiState.update { it.copy(moduleOrder = savedOrder) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        moduleOrder = previousOrder,
                        lastErrorMessage = error.localizedMessage ?: "Unable to save module order."
                    )
                }
            }
        }
    }

    fun handleFilesToggle(context: android.content.Context, isEnabled: Boolean) {
        val currentState = _uiState.value
        if (!currentState.canManageModules || currentState.filesEnabled == isEnabled) return

        viewModelScope.launch {
            if (!isEnabled) {
                runCatching {
                    spaceService.filesModuleHasContent(context, space)
                }.onSuccess { hasContent ->
                    if (hasContent) {
                        _uiState.update { it.copy(shouldConfirmHidingFiles = true) }
                    } else {
                        applyFilesToggle(context, false)
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to inspect Files right now.")
                    }
                }
            } else {
                applyFilesToggle(context, true)
            }
        }
    }

    fun handleEventsToggle(context: android.content.Context, isEnabled: Boolean) {
        val currentState = _uiState.value
        if (!currentState.canManageModules || currentState.eventsEnabled == isEnabled) return

        viewModelScope.launch {
            applyEventsToggle(context, isEnabled)
        }
    }

    fun handleAnnouncementsToggle(context: android.content.Context, isEnabled: Boolean) {
        if (!_uiState.value.canManageModules) {
            _uiState.update { it.copy(announcementsEnabled = space.announcementsEnabled) }
            return
        }
        if (isEnabled == _uiState.value.announcementsEnabled || _uiState.value.isUpdatingAnnouncementsModule) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingAnnouncementsModule = true, lastErrorMessage = null) }
            runCatching {
                spaceService.setAnnouncementsEnabled(context, space, isEnabled)
            }.onSuccess {
                _uiState.update {
                    it.copy(isUpdatingAnnouncementsModule = false, announcementsEnabled = isEnabled)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdatingAnnouncementsModule = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to update Announcements right now."
                    )
                }
            }
        }
    }

    fun handleRoomsToggle(context: android.content.Context, isEnabled: Boolean) {
        if (!_uiState.value.canManageModules) {
            _uiState.update { it.copy(roomsEnabled = space.roomsEnabled) }
            return
        }
        if (isEnabled == _uiState.value.roomsEnabled || _uiState.value.isUpdatingRoomsModule) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingRoomsModule = true, lastErrorMessage = null) }
            runCatching { spaceService.setRoomsEnabled(context, space, isEnabled) }
                .onSuccess { _uiState.update { it.copy(isUpdatingRoomsModule = false, roomsEnabled = isEnabled) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isUpdatingRoomsModule = false, lastErrorMessage = error.localizedMessage ?: "Unable to update Rooms.")
                    }
                }
        }
    }

    fun handlePollsToggle(context: android.content.Context, isEnabled: Boolean) {
        val currentState = _uiState.value
        if (!currentState.canManageModules || currentState.pollsEnabled == isEnabled) return

        viewModelScope.launch {
            applyPollsToggle(context, isEnabled)
        }
    }

    fun handleListsToggle(context: android.content.Context, isEnabled: Boolean) {
        val state = _uiState.value
        if (!state.canManageModules || state.isUpdatingListsModule || state.listsEnabled == isEnabled) return
        _uiState.update { it.copy(isUpdatingListsModule = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching { spaceService.setListsEnabled(context, space, isEnabled) }
                .onSuccess { _uiState.update { it.copy(listsEnabled = isEnabled, isUpdatingListsModule = false) } }
                .onFailure { error ->
                    _uiState.update { it.copy(isUpdatingListsModule = false, lastErrorMessage = error.localizedMessage ?: "Unable to update Lists.") }
                }
        }
    }
    fun handleNotesToggle(context: android.content.Context, isEnabled: Boolean) {
        val state = _uiState.value
        if (!state.canManageModules || state.isUpdatingNotesModule || state.notesEnabled == isEnabled) return
        _uiState.update { it.copy(isUpdatingNotesModule = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching { spaceService.setNotesEnabled(context, space, isEnabled) }
                .onSuccess { _uiState.update { it.copy(notesEnabled = isEnabled, isUpdatingNotesModule = false) } }
                .onFailure { e -> _uiState.update { it.copy(isUpdatingNotesModule = false, lastErrorMessage = e.localizedMessage ?: "Unable to update Notes.") } }
        }
    }

    fun confirmHideFiles(context: android.content.Context) {
        _uiState.update { it.copy(shouldConfirmHidingFiles = false) }
        viewModelScope.launch {
            applyFilesToggle(context, false)
        }
    }

    fun cancelHideFiles() {
        _uiState.update { it.copy(shouldConfirmHidingFiles = false) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }

    fun setAllowMemberInvites(enabled: Boolean) {
        _uiState.update { it.copy(allowMemberInvites = enabled) }
    }

    fun setPrivateSpace(enabled: Boolean) {
        _uiState.update { it.copy(isPrivateSpace = enabled) }
    }

    fun setSafeModeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(safeModeEnabled = enabled) }
    }

    fun createInvite(context: android.content.Context) {
        if (_uiState.value.isCreatingInvite) return

        _uiState.update { it.copy(isCreatingInvite = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.createInvite(context, space)
            }.onSuccess { invite ->
                _uiState.update {
                    it.copy(
                        isCreatingInvite = false,
                        pendingInvite = invite
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCreatingInvite = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to create invite."
                    )
                }
            }
        }
    }

    fun dismissInvite() {
        _uiState.update { it.copy(pendingInvite = null) }
    }

    fun openInviteEditor(context: android.content.Context) {
        if (_uiState.value.isLoadingInvite) return

        _uiState.update { it.copy(isLoadingInvite = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.fetchLatestInvite(context, space)
            }.onSuccess { invite ->
                if (invite != null) {
                    _uiState.update {
                        it.copy(
                            isLoadingInvite = false,
                            pendingInvite = invite
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoadingInvite = false) }
                    createInvite(context)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingInvite = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load the current invite link."
                    )
                }
            }
        }
    }

    fun setInviteActive(context: android.content.Context, isActive: Boolean) {
        val invite = _uiState.value.pendingInvite ?: return
        if (_uiState.value.isUpdatingInvite) return

        _uiState.update { it.copy(isUpdatingInvite = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.updateInviteActiveState(context, invite.code, isActive)
            }.onSuccess { updatedInvite ->
                _uiState.update {
                    it.copy(
                        isUpdatingInvite = false,
                        pendingInvite = updatedInvite
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdatingInvite = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to update the invite link."
                    )
                }
            }
        }
    }

    fun regenerateInvite(context: android.content.Context) {
        val invite = _uiState.value.pendingInvite
        if (_uiState.value.isUpdatingInvite) return

        _uiState.update { it.copy(isUpdatingInvite = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.regenerateInvite(context, space, invite)
            }.onSuccess { updatedInvite ->
                _uiState.update {
                    it.copy(
                        isUpdatingInvite = false,
                        pendingInvite = updatedInvite
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdatingInvite = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to regenerate the invite link."
                    )
                }
            }
        }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    private suspend fun applyFilesToggle(context: android.content.Context, isEnabled: Boolean) {
        if (_uiState.value.isUpdatingFilesModule) return

        _uiState.update { it.copy(isUpdatingFilesModule = true, lastErrorMessage = null) }
        runCatching {
            spaceService.setFilesEnabled(context, space, isEnabled)
        }.onSuccess {
            _uiState.update {
                it.copy(
                    isUpdatingFilesModule = false,
                    filesEnabled = isEnabled
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isUpdatingFilesModule = false,
                    lastErrorMessage = error.localizedMessage ?: "Unable to update Files right now."
                )
            }
        }
    }

    private suspend fun applyEventsToggle(context: android.content.Context, isEnabled: Boolean) {
        _uiState.update { it.copy(isUpdatingEventsModule = true, lastErrorMessage = null) }
        runCatching {
            spaceService.setEventsEnabled(context, space, isEnabled)
        }.onSuccess {
            _uiState.update {
                it.copy(
                    isUpdatingEventsModule = false,
                    eventsEnabled = isEnabled
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isUpdatingEventsModule = false,
                    lastErrorMessage = error.localizedMessage ?: "Unable to update Events right now."
                )
            }
        }
    }

    private suspend fun applyPollsToggle(context: android.content.Context, isEnabled: Boolean) {
        if (_uiState.value.isUpdatingPollsModule) return

        _uiState.update { it.copy(isUpdatingPollsModule = true, lastErrorMessage = null) }
        runCatching {
            spaceService.setPollsEnabled(context, space, isEnabled)
        }.onSuccess {
            _uiState.update {
                it.copy(
                    isUpdatingPollsModule = false,
                    pollsEnabled = isEnabled
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isUpdatingPollsModule = false,
                    lastErrorMessage = error.localizedMessage ?: "Unable to update Polls right now."
                )
            }
        }
    }
}

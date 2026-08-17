package com.arcinteractive.spaces.ui.screens.members

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.mock.MockMembersRepository
import com.arcinteractive.spaces.data.model.canChangeRole
import com.arcinteractive.spaces.data.model.canRemove
import com.arcinteractive.spaces.data.model.hasCapability
import com.arcinteractive.spaces.data.model.roleFor
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceInvite
import com.arcinteractive.spaces.data.model.SpaceMember
import com.arcinteractive.spaces.data.model.SpaceMemberRole
import com.arcinteractive.spaces.data.model.SpacePermission
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MembersUiState(
    val members: List<SpaceMember>,
    val currentUserId: String? = null,
    val selectedMember: SpaceMember? = null,
    val isLoading: Boolean = false,
    val isCreatingInvite: Boolean = false,
    val pendingInvite: SpaceInvite? = null,
    val lastErrorMessage: String? = null
)

class MembersViewModel(
    space: Space,
    private val spaceService: SpaceService = SpaceService(),
    private val authService: AuthService = AuthService()
) : ViewModel() {
    val space: Space = space
    private var listener: ListenerRegistration? = null

    private val _uiState = MutableStateFlow(
        MembersUiState(members = MockMembersRepository.membersFor(space))
    )
    val uiState: StateFlow<MembersUiState> = _uiState.asStateFlow()

    fun loadMembersIfNeeded(context: Context) {
        if (listener != null) return
        _uiState.update {
            it.copy(
                isLoading = true,
                currentUserId = spaceService.currentUserId(context)
            )
        }
        listener = spaceService.listenToMembers(
            context = context,
            space = space,
            listenerKey = "members.${space.id}"
        ) { result ->
            result.onSuccess { members ->
                _uiState.update {
                    it.copy(
                        members = members,
                        currentUserId = spaceService.currentUserId(context),
                        isLoading = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        currentUserId = spaceService.currentUserId(context),
                        isLoading = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load members."
                    )
                }
            }
        }
    }

    fun openMember(member: SpaceMember) {
        _uiState.update { it.copy(selectedMember = member) }
    }

    fun closeMember() {
        _uiState.update { it.copy(selectedMember = null) }
    }

    fun createInvite(context: Context) {
        openInviteEditor(context)
    }

    fun openInviteEditor(context: Context) {
        if (_uiState.value.isLoading || _uiState.value.isCreatingInvite) return

        _uiState.update { it.copy(isLoading = true, lastErrorMessage = null) }
        viewModelScope.launch {
            runCatching {
                spaceService.fetchLatestInvite(context, space)
            }.onSuccess { invite ->
                if (invite != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            pendingInvite = invite
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    createFreshInvite(context)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastErrorMessage = error.localizedMessage ?: "Unable to load the current invite link."
                    )
                }
            }
        }
    }

    private fun createFreshInvite(context: Context) {
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
                        lastErrorMessage = error.localizedMessage ?: "Unable to create an invite."
                    )
                }
            }
        }
    }

    fun dismissInvite() {
        _uiState.update { it.copy(pendingInvite = null) }
    }

    fun updateSelectedMemberRole(context: Context, role: SpaceMemberRole) {
        val selectedMember = _uiState.value.selectedMember ?: return

        viewModelScope.launch {
            runCatching {
                spaceService.updateMemberRole(context, space, selectedMember.id, role)
            }.onSuccess {
                val updatedMember = selectedMember.copy(role = role)
                _uiState.update { state ->
                    state.copy(
                        members = state.members.map { member ->
                            if (member.id == updatedMember.id) updatedMember else member
                        },
                        selectedMember = updatedMember
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to update this member.")
                }
            }
        }
    }

    fun removeSelectedMember(context: Context, onRemoved: () -> Unit) {
        val selectedMember = _uiState.value.selectedMember ?: return

        viewModelScope.launch {
            runCatching {
                spaceService.removeMember(context, space, selectedMember.id)
            }.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        members = state.members.filterNot { it.id == selectedMember.id },
                        selectedMember = null
                    )
                }
                onRemoved()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastErrorMessage = error.localizedMessage ?: "Unable to remove this member.")
                }
            }
        }
    }

    fun canInviteMembers(context: Context): Boolean {
        return space.hasCapability(_uiState.value.currentUserId, _uiState.value.members, SpacePermission.InviteMembers)
    }

    fun canManageRoles(context: Context): Boolean {
        return _uiState.value.members.any { canManageRoles(context, it) }
    }

    fun canManageMembers(context: Context): Boolean {
        return _uiState.value.members.any { canRemoveMember(context, it) }
    }

    fun canManageRoles(context: Context, member: SpaceMember): Boolean {
        return availableRoles(context, member).isNotEmpty()
    }

    fun canRemoveMember(context: Context, member: SpaceMember): Boolean {
        val currentRole = currentUserRole(context) ?: return false
        return currentRole.canRemove(member.role, member.id == space.ownerId)
    }

    fun availableRoles(context: Context, member: SpaceMember): List<SpaceMemberRole> {
        val currentRole = currentUserRole(context) ?: return emptyList()
        return SpaceMemberRole.entries.filter {
            currentRole.canChangeRole(member.role, it, member.id == space.ownerId)
        }
    }

    fun clearLastErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    override fun onCleared() {
        listener?.remove()
        listener = null
        super.onCleared()
    }

    private fun currentUserRole(context: Context): SpaceMemberRole? {
        return space.roleFor(_uiState.value.currentUserId, _uiState.value.members)
    }
}

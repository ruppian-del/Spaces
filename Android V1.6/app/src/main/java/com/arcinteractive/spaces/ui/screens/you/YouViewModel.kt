package com.arcinteractive.spaces.ui.screens.you

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.auth.AuthSession
import com.arcinteractive.spaces.data.auth.DeviceIdentityService
import com.arcinteractive.spaces.data.auth.PushTokenService
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.model.AccountDevice
import com.arcinteractive.spaces.data.model.BlockedUser
import com.arcinteractive.spaces.data.model.PushTokenRecord
import com.arcinteractive.spaces.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class YouUiState(
    val profile: UserProfile? = null,
    val devices: List<AccountDevice> = emptyList(),
    val pushTokens: List<PushTokenRecord> = emptyList(),
    val currentDeviceId: String = "",
    val isLoading: Boolean = false,
    val isSavingProfile: Boolean = false,
    val isUpdatingNotifications: Boolean = false,
    val isClearingCache: Boolean = false,
    val removingDeviceIds: Set<String> = emptySet(),
    val cacheSizeBytes: Long? = null,
    val lastMessage: String? = null
)

class YouViewModel(
    private val userProfileService: UserProfileService = UserProfileService(),
    private val pushTokenService: PushTokenService = PushTokenService(),
    private val deviceIdentityService: DeviceIdentityService = DeviceIdentityService()
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        YouUiState(currentDeviceId = "")
    )
    val uiState: StateFlow<YouUiState> = _uiState.asStateFlow()
    private var lastLoadedUserId: String? = null

    fun loadIfNeeded(context: Context, session: AuthSession?, cachedProfile: UserProfile?) {
        val resolvedSession = session ?: return
        val uid = resolvedSession.uid
        if (lastLoadedUserId == uid && _uiState.value.profile != null) return
        load(context, resolvedSession, cachedProfile)
    }

    fun refresh(context: Context, session: AuthSession?, cachedProfile: UserProfile?) {
        val resolvedSession = session ?: return
        load(context, resolvedSession, cachedProfile)
    }

    fun saveProfile(
        context: Context,
        session: AuthSession?,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String,
        onSaved: (UserProfile) -> Unit
    ) {
        val resolvedSession = session ?: run {
            _uiState.update { it.copy(lastMessage = "Sign in before updating your profile.") }
            return
        }

        _uiState.update { it.copy(isSavingProfile = true, lastMessage = null) }
        viewModelScope.launch {
            runCatching {
                userProfileService.updateUserProfile(
                    context = context,
                    session = resolvedSession,
                    displayName = displayName,
                    emojiAvatar = emojiAvatar,
                    statusMessage = statusMessage
                )
            }.onSuccess { profile ->
                onSaved(profile)
                _uiState.update {
                    it.copy(
                        profile = profile,
                        isSavingProfile = false,
                        lastMessage = "Profile updated."
                    )
                }
                load(context, resolvedSession, profile)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSavingProfile = false,
                        lastMessage = error.localizedMessage ?: "Unable to update your profile."
                    )
                }
            }
        }
    }

    fun setCurrentDeviceNotificationsEnabled(context: Context, session: AuthSession?, enabled: Boolean) {
        if (session == null) {
            _uiState.update { it.copy(lastMessage = "Sign in before updating notifications.") }
            return
        }

        val existingTokens = _uiState.value.pushTokens
        _uiState.update { state ->
            state.copy(
                isUpdatingNotifications = true,
                lastMessage = null,
                pushTokens = state.pushTokens.map { token ->
                    if (token.deviceId == state.currentDeviceId) token.copy(enabled = enabled) else token
                }
            )
        }
        viewModelScope.launch {
            runCatching {
                pushTokenService.setCurrentTokenEnabled(context, enabled)
            }.onSuccess {
                load(context, session, _uiState.value.profile)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pushTokens = existingTokens,
                        isUpdatingNotifications = false,
                        lastMessage = error.localizedMessage ?: "Unable to update notifications."
                    )
                }
            }
        }
    }

    fun clearCache(context: Context) {
        _uiState.update { it.copy(isClearingCache = true, lastMessage = null) }
        viewModelScope.launch {
            runCatching {
                clearDirectory(context.cacheDir)
                clearDirectory(context.externalCacheDir)
                clearDirectory(context.filesDir.resolve("tmp"))
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isClearingCache = false,
                        cacheSizeBytes = computeCacheSizeBytes(context),
                        lastMessage = "Cached files cleared."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isClearingCache = false,
                        lastMessage = error.localizedMessage ?: "Unable to clear cached files."
                    )
                }
            }
        }
    }

    fun clearLastMessage() {
        _uiState.update { it.copy(lastMessage = null) }
    }

    fun isRemovingDevice(device: AccountDevice): Boolean {
        return _uiState.value.removingDeviceIds.contains(device.id)
    }

    fun removeDevice(context: Context, session: AuthSession?, device: AccountDevice) {
        val resolvedSession = session ?: run {
            _uiState.update { it.copy(lastMessage = "Sign in before removing a device.") }
            return
        }

        val relatedPushTokens = _uiState.value.pushTokens.filter { it.deviceId == device.deviceId }
        val existingDevices = _uiState.value.devices
        val existingPushTokens = _uiState.value.pushTokens

        _uiState.update { state ->
            state.copy(
                devices = state.devices.filterNot { it.id == device.id },
                pushTokens = state.pushTokens.filterNot { token -> relatedPushTokens.any { it.id == token.id } },
                removingDeviceIds = state.removingDeviceIds + device.id,
                lastMessage = null
            )
        }

        viewModelScope.launch {
            runCatching {
                userProfileService.removeDevice(context, resolvedSession.uid, device, relatedPushTokens)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        devices = existingDevices.filterNot { saved -> saved.id == device.id },
                        pushTokens = existingPushTokens.filterNot { token -> relatedPushTokens.any { related -> related.id == token.id } },
                        lastMessage = "Removed from this view. Firestore cleanup could not be completed: ${error.localizedMessage ?: "Unknown error."}"
                    )
                }
            }

            _uiState.update { it.copy(removingDeviceIds = it.removingDeviceIds - device.id) }
        }
    }

    fun unblockUser(context: Context, session: AuthSession?, blockedUser: BlockedUser) {
        val resolvedSession = session ?: run {
            _uiState.update { it.copy(lastMessage = "Sign in before updating blocked users.") }
            return
        }
        val currentProfile = _uiState.value.profile ?: return
        val updatedBlockedUsers = currentProfile.blockedUsers.filterNot { it.id == blockedUser.id }

        viewModelScope.launch {
            runCatching {
                userProfileService.updateBlockedUsers(context, resolvedSession.uid, updatedBlockedUsers)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        profile = currentProfile.copy(blockedUsers = updatedBlockedUsers),
                        lastMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(lastMessage = error.localizedMessage ?: "Unable to update blocked users.")
                }
            }
        }
    }

    private fun load(context: Context, session: AuthSession, cachedProfile: UserProfile?) {
        _uiState.update {
            it.copy(
                isLoading = true,
                currentDeviceId = deviceIdentityService.currentDeviceId(context),
                profile = it.profile ?: cachedProfile,
                lastMessage = null
            )
        }
        lastLoadedUserId = session.uid

        viewModelScope.launch {
            val currentDeviceId = deviceIdentityService.currentDeviceId(context)
            runCatching {
                Triple(
                    userProfileService.fetchUserProfile(context, session.uid),
                    userProfileService.fetchDevices(context, session.uid),
                    userProfileService.fetchPushTokens(context, session.uid)
                )
            }.onSuccess { (profile, devices, pushTokens) ->
                _uiState.update {
                    it.copy(
                        profile = profile ?: cachedProfile,
                        devices = devices,
                        pushTokens = pushTokens,
                        currentDeviceId = currentDeviceId,
                        isLoading = false,
                        isUpdatingNotifications = false,
                        cacheSizeBytes = computeCacheSizeBytes(context)
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isUpdatingNotifications = false,
                        currentDeviceId = currentDeviceId,
                        lastMessage = error.localizedMessage ?: "Unable to load your account."
                    )
                }
            }
        }
    }

    private fun computeCacheSizeBytes(context: Context): Long {
        return sequenceOf(context.cacheDir, context.externalCacheDir, context.filesDir.resolve("tmp"))
            .filterNotNull()
            .sumOf { directorySize(it) }
    }

    private fun clearDirectory(directory: File?) {
        if (directory == null || !directory.exists()) return
        directory.listFiles()?.forEach { child ->
            child.deleteRecursively()
        }
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { child -> directorySize(child) } ?: 0L
    }
}

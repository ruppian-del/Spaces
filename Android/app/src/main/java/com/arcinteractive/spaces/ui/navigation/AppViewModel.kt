package com.arcinteractive.spaces.ui.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppUiState(
    val hasCompletedOnboarding: Boolean = false,
    val pendingInviteCode: String? = null,
    val pendingNotificationNavigation: PendingNotificationNavigation? = null
)

data class PendingNotificationNavigation(
    val notificationId: String?,
    val type: String?,
    val spaceId: String?,
    val targetId: String?,
    val targetType: String?
)

class AppViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    fun completeOnboarding() {
        _uiState.update { it.copy(hasCompletedOnboarding = true) }
    }

    fun handleInviteCode(code: String?) {
        val normalized = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return
        _uiState.update { it.copy(pendingInviteCode = normalized) }
    }

    fun clearPendingInviteCode() {
        _uiState.update { it.copy(pendingInviteCode = null) }
    }

    fun handleNotificationNavigation(
        notificationId: String?,
        type: String?,
        spaceId: String?,
        targetId: String?,
        targetType: String?
    ) {
        val navigation = PendingNotificationNavigation(
            notificationId = notificationId?.trim()?.ifEmpty { null },
            type = type?.trim()?.ifEmpty { null },
            spaceId = spaceId?.trim()?.ifEmpty { null },
            targetId = targetId?.trim()?.ifEmpty { null },
            targetType = targetType?.trim()?.ifEmpty { null }
        )

        if (navigation.notificationId == null &&
            navigation.type == null &&
            navigation.spaceId == null &&
            navigation.targetId == null &&
            navigation.targetType == null
        ) {
            return
        }

        _uiState.update { it.copy(pendingNotificationNavigation = navigation) }
    }

    fun clearPendingNotificationNavigation() {
        _uiState.update { it.copy(pendingNotificationNavigation = null) }
    }

    fun resetForSignedOutUser() {
        _uiState.update {
            it.copy(
                pendingInviteCode = null,
                pendingNotificationNavigation = null
            )
        }
    }
}

package com.arcinteractive.spaces.ui.screens.onboarding

import android.icu.text.BreakIterator
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class OnboardingPage {
    Welcome,
    Spaces,
    Pings,
    Privacy,
    Authentication,
    Profile
}

data class OnboardingUiState(
    val isShowingSplash: Boolean = true,
    val currentPage: OnboardingPage = OnboardingPage.Welcome,
    val displayName: String = "",
    val emojiAvatar: String = OnboardingViewModel.DEFAULT_EMOJI,
    val statusMessage: String = ""
) {
    val displayEmoji: String = emojiAvatar.ifBlank { OnboardingViewModel.DEFAULT_EMOJI }
    val canContinueProfile: Boolean = displayName.isNotBlank()
}

class OnboardingViewModel : ViewModel() {
    companion object {
        const val DEFAULT_EMOJI = "\uD83E\uDDD1\u200D\uD83D\uDCBB"
    }

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun advanceFromSplash() {
        _uiState.update { it.copy(isShowingSplash = false) }
    }

    fun setCurrentPage(page: OnboardingPage) {
        _uiState.update { it.copy(currentPage = page) }
    }

    fun updateDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name) }
    }

    fun updateEmojiAvatar(emoji: String) {
        val compact = emoji.trim()
        val sanitized = if (compact.isEmpty()) {
            ""
        } else {
            val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
            iterator.setText(compact)
            val end = iterator.next()
            if (end == BreakIterator.DONE) compact else compact.substring(0, end)
        }

        _uiState.update { it.copy(emojiAvatar = sanitized) }
    }

    fun updateStatusMessage(status: String) {
        _uiState.update { it.copy(statusMessage = status) }
    }

    fun applyAuthenticatedProfileDefaults(displayName: String?) {
        _uiState.update { current ->
            current.copy(
                displayName = if (current.displayName.isBlank()) displayName.orEmpty() else current.displayName
            )
        }
    }

    fun prepareForRequiredProfileCreation(displayName: String?) {
        _uiState.update { current ->
            current.copy(
                isShowingSplash = false,
                currentPage = OnboardingPage.Profile,
                displayName = if (current.displayName.isBlank()) displayName.orEmpty() else current.displayName
            )
        }
    }
}

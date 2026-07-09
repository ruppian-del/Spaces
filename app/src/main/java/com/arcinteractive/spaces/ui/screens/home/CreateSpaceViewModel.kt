package com.arcinteractive.spaces.ui.screens.home

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import android.icu.text.BreakIterator
import com.arcinteractive.spaces.data.model.SpaceModule
import com.arcinteractive.spaces.data.model.SpaceModules
import com.arcinteractive.spaces.data.model.SpaceTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class CreateSpaceUiState(
    val name: String = "",
    val emoji: String = CreateSpaceViewModel.DEFAULT_EMOJI,
    val color: SpaceColorOption = SpaceColorOption.Indigo,
    val description: String = SpaceTemplate.Family.defaultStatus,
    val template: SpaceTemplate = SpaceTemplate.Family,
    val enabledModules: List<SpaceModule> = SpaceTemplate.Family.defaultEnabledModules
) {
    val isCreateEnabled: Boolean = name.isNotBlank()
    val displayEmoji: String = emoji.ifBlank { CreateSpaceViewModel.DEFAULT_EMOJI }
}

enum class SpaceColorOption(val title: String, val color: Color, val hex: String) {
    Indigo("Indigo", Color(0xFF4F46E5), "#4F46E5"),
    Blue("Blue", Color(0xFF3A6EA5), "#3A6EA5"),
    Green("Green", Color(0xFF2F855A), "#2F855A"),
    Orange("Orange", Color(0xFFD97706), "#D97706"),
    Violet("Violet", Color(0xFF7C3AED), "#7C3AED")
}

class CreateSpaceViewModel : ViewModel() {
    companion object {
        const val DEFAULT_EMOJI = "\uD83C\uDFE0"
    }

    private val _uiState = MutableStateFlow(CreateSpaceUiState())
    val uiState: StateFlow<CreateSpaceUiState> = _uiState.asStateFlow()

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun updateEmoji(emoji: String) {
        val compact = emoji.trim()
        val sanitized = if (compact.isEmpty()) {
            ""
        } else {
            val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
            iterator.setText(compact)
            val end = iterator.next()
            if (end == BreakIterator.DONE) compact else compact.substring(0, end)
        }

        _uiState.update { it.copy(emoji = sanitized) }
    }

    fun updateColor(color: SpaceColorOption) {
        _uiState.update { it.copy(color = color) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    fun updateTemplate(template: SpaceTemplate) {
        _uiState.update { current ->
            val previousDefault = current.template.defaultStatus
            val resolvedDescription = if (current.description.isBlank() || current.description == previousDefault) {
                template.defaultStatus
            } else {
                current.description
            }
            current.copy(
                template = template,
                description = resolvedDescription,
                enabledModules = template.defaultEnabledModules
            )
        }
    }

    fun isModuleEnabled(module: SpaceModule): Boolean {
        return _uiState.value.enabledModules.any { it.id == module.id }
    }

    fun setModuleEnabled(module: SpaceModule, isEnabled: Boolean) {
        _uiState.update { current ->
            if (current.template != SpaceTemplate.Custom || SpaceModules.optional.none { it.id == module.id }) {
                return@update current
            }

            val mutableModules = current.enabledModules.toMutableList()
            if (isEnabled) {
                if (mutableModules.none { it.id == module.id }) {
                    mutableModules += module
                }
            } else {
                mutableModules.removeAll { it.id == module.id }
            }

            val orderedModules = SpaceModules.configurable.filter { candidate ->
                mutableModules.any { it.id == candidate.id }
            }
            current.copy(enabledModules = orderedModules)
        }
    }
}

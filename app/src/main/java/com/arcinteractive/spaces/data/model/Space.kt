package com.arcinteractive.spaces.data.model

import androidx.compose.ui.graphics.Color

data class Space(
    val id: String,
    val name: String,
    val emoji: String,
    val colorHex: String,
    val description: String,
    val template: SpaceTemplate,
    val ownerId: String,
    val memberIds: List<String>,
    val unreadCount: Int?,
    val enabledModules: List<SpaceModule>
) {
    val subtitle: String
        get() = description

    val modules: List<SpaceModule>
        get() = (enabledModules + SpaceModules.Settings).distinctBy { it.id }

    val filesEnabled: Boolean
        get() = enabledModules.any { it.id == SpaceModules.Files.id }

    val pollsEnabled: Boolean
        get() = enabledModules.any { it.id == SpaceModules.Polls.id }
}

enum class SpaceTemplate(val title: String, val subtitle: String, val suggestedEmoji: String) {
    Family("Family", "Shared updates and planning", "\uD83C\uDFE1"),
    Friends("Friends", "Chats, plans, and photos", "\uD83E\uDEE6"),
    Business("Business", "Projects and team coordination", "\uD83D\uDCBC"),
    Community("Community", "Events and announcements", "\uD83C\uDF0E"),
    Custom("Custom", "Start with a blank space", "✨");

    val defaultStatus: String
        get() = when (this) {
            Family -> "Family updates and plans"
            Friends -> "Conversations and hangouts"
            Business -> "Team communication hub"
            Community -> "Events and announcements"
            Custom -> "A new shared space"
        }

    val defaultEnabledModules: List<SpaceModule>
        get() = when (this) {
            Family -> listOf(SpaceModules.General, SpaceModules.Photos, SpaceModules.Events, SpaceModules.Members)
            Friends -> listOf(SpaceModules.General, SpaceModules.Photos, SpaceModules.Events, SpaceModules.Members)
            Business -> listOf(SpaceModules.General, SpaceModules.Files, SpaceModules.Events, SpaceModules.Members)
            Community -> listOf(SpaceModules.General, SpaceModules.Files, SpaceModules.Events, SpaceModules.Members)
            Custom -> listOf(SpaceModules.General, SpaceModules.Members)
        }
}

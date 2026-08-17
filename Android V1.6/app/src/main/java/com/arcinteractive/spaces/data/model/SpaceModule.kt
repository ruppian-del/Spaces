package com.arcinteractive.spaces.data.model

data class SpaceModule(
    val id: String,
    val title: String,
    val description: String,
    val emoji: String
)

object SpaceModules {
    val General = SpaceModule("general", "Space Pings", "Private conversations and quick check-ins", "\uD83D\uDCAC")
    val Announcements = SpaceModule("announcements", "Announcements", "Important updates that stay easy to find", "\uD83D\uDCE2")
    val Rooms = SpaceModule("rooms", "Rooms", "Topic-based discussions for this Space", "\uD83D\uDCAC")
    val Photos = SpaceModule("photos", "Media", "Shared photos and videos", "\uD83D\uDCF7")
    val Files = SpaceModule("files", "Files", "Shared documents and uploads", "\uD83D\uDCC1")
    val Polls = SpaceModule("polls", "Polls", "Questions and voting", "\uD83D\uDCCA")
    val Events = SpaceModule("events", "Events", "Plans and calendar", "\uD83D\uDCC5")
    val Lists = SpaceModule("lists", "Lists", "Shared checklists and lightweight planning", "✅")
    val Notes = SpaceModule("notes", "Notes", "Shared documentation and long-form knowledge", "📝")
    val Members = SpaceModule("members", "Members", "People in this Space", "\uD83D\uDC65")
    val Settings = SpaceModule("settings", "Settings", "Space preferences", "⚙️")

    val required = listOf(General, Photos, Members)
    val optional = listOf(Announcements, Rooms, Events, Lists, Notes, Files, Polls)
    val configurable = required + optional
    val all = configurable + Settings

    fun fromId(id: String): SpaceModule? = when (id) {
        General.id -> General
        Announcements.id -> Announcements
        Rooms.id -> Rooms
        Photos.id -> Photos
        Files.id -> Files
        Polls.id -> Polls
        Events.id -> Events
        Lists.id -> Lists
        Notes.id -> Notes
        Members.id -> Members
        Settings.id -> Settings
        else -> null
    }
}

data class SpaceModuleCategory(
    val title: String,
    val modules: List<SpaceModule>
)

object SpaceModuleCategories {
    val Content = SpaceModuleCategory("Content", listOf(SpaceModules.Photos, SpaceModules.Files))
    val Agenda = SpaceModuleCategory("Agenda", listOf(SpaceModules.Lists, SpaceModules.Notes))
}

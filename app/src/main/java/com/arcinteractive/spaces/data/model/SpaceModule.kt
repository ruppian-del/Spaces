package com.arcinteractive.spaces.data.model

data class SpaceModule(
    val id: String,
    val title: String,
    val description: String,
    val emoji: String
)

object SpaceModules {
    val General = SpaceModule("general", "Space Pings", "Private conversations and quick check-ins", "\uD83D\uDCAC")
    val Photos = SpaceModule("photos", "Photos", "Shared photos and memes", "\uD83D\uDCF7")
    val Files = SpaceModule("files", "Files", "Shared documents and uploads", "\uD83D\uDCC1")
    val Polls = SpaceModule("polls", "Polls", "Questions and voting", "\uD83D\uDCCA")
    val Events = SpaceModule("events", "Events", "Plans and calendar", "\uD83D\uDCC5")
    val Members = SpaceModule("members", "Members", "People in this Space", "\uD83D\uDC65")
    val Settings = SpaceModule("settings", "Settings", "Space preferences", "⚙️")

    val required = listOf(General, Photos, Members)
    val optional = listOf(Events, Files, Polls)
    val configurable = required + optional

    fun fromId(id: String): SpaceModule? = when (id) {
        General.id -> General
        Photos.id -> Photos
        Files.id -> Files
        Polls.id -> Polls
        Events.id -> Events
        Members.id -> Members
        Settings.id -> Settings
        else -> null
    }
}

package com.arcinteractive.spaces.ui.navigation

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object GlobalSearch : Destination("search")
    data object Pings : Destination("pings")
    data object PingConversation : Destination("pings/{pingId}") {
        fun routeFor(pingId: String) = "pings/$pingId"
    }
    data object Activity : Destination("activity")
    data object You : Destination("you")
    data object SpaceDetail : Destination("space/{spaceId}") {
        fun routeFor(spaceId: String) = "space/$spaceId"
    }
    data object GeneralPlaceholder : Destination("space/{spaceId}/general") {
        fun routeFor(spaceId: String) = "space/$spaceId/general"
    }
    data object Announcements : Destination("space/{spaceId}/announcements") {
        fun routeFor(spaceId: String) = "space/$spaceId/announcements"
    }
    data object Rooms : Destination("space/{spaceId}/rooms") {
        fun routeFor(spaceId: String) = "space/$spaceId/rooms"
        fun routeFor(spaceId: String, targetId: String?) =
            if (targetId.isNullOrBlank()) routeFor(spaceId) else "space/$spaceId/rooms?targetId=$targetId"
    }
    data object Lists : Destination("space/{spaceId}/lists") {
        fun routeFor(spaceId: String) = "space/$spaceId/lists"
        fun routeFor(spaceId: String, targetId: String?) =
            if (targetId.isNullOrBlank()) routeFor(spaceId) else "space/$spaceId/lists?targetId=$targetId"
    }
    data object Notes : Destination("space/{spaceId}/notes") {
        fun routeFor(spaceId: String) = "space/$spaceId/notes"
        fun routeFor(spaceId: String, targetId: String?) =
            if (targetId.isNullOrBlank()) routeFor(spaceId) else "space/$spaceId/notes?targetId=$targetId"
    }
    data object PhotosPlaceholder : Destination("space/{spaceId}/photos") {
        fun routeFor(spaceId: String) = "space/$spaceId/photos"
        fun routeFor(spaceId: String, targetId: String?) =
            if (targetId.isNullOrBlank()) routeFor(spaceId) else "space/$spaceId/photos?targetId=$targetId"
    }
    data object FilesPlaceholder : Destination("space/{spaceId}/files") {
        fun routeFor(spaceId: String) = "space/$spaceId/files"
        fun routeFor(spaceId: String, targetId: String?) =
            if (targetId.isNullOrBlank()) routeFor(spaceId) else "space/$spaceId/files?targetId=$targetId"
    }
    data object PollsPlaceholder : Destination("space/{spaceId}/polls") {
        fun routeFor(spaceId: String) = "space/$spaceId/polls"
        fun routeFor(spaceId: String, targetId: String?) =
            if (targetId.isNullOrBlank()) routeFor(spaceId) else "space/$spaceId/polls?targetId=$targetId"
    }
    data object EventsPlaceholder : Destination("space/{spaceId}/events") {
        fun routeFor(spaceId: String) = "space/$spaceId/events"
        fun routeFor(spaceId: String, targetId: String?) =
            if (targetId.isNullOrBlank()) routeFor(spaceId) else "space/$spaceId/events?targetId=$targetId"
    }
    data object MembersPlaceholder : Destination("space/{spaceId}/members") {
        fun routeFor(spaceId: String) = "space/$spaceId/members"
    }
    data object SpaceSettingsPlaceholder : Destination("space/{spaceId}/settings") {
        fun routeFor(spaceId: String) = "space/$spaceId/settings"
    }
}

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
    data object PhotosPlaceholder : Destination("space/{spaceId}/photos") {
        fun routeFor(spaceId: String) = "space/$spaceId/photos"
    }
    data object FilesPlaceholder : Destination("space/{spaceId}/files") {
        fun routeFor(spaceId: String) = "space/$spaceId/files"
    }
    data object PollsPlaceholder : Destination("space/{spaceId}/polls") {
        fun routeFor(spaceId: String) = "space/$spaceId/polls"
    }
    data object EventsPlaceholder : Destination("space/{spaceId}/events") {
        fun routeFor(spaceId: String) = "space/$spaceId/events"
    }
    data object MembersPlaceholder : Destination("space/{spaceId}/members") {
        fun routeFor(spaceId: String) = "space/$spaceId/members"
    }
    data object SpaceSettingsPlaceholder : Destination("space/{spaceId}/settings") {
        fun routeFor(spaceId: String) = "space/$spaceId/settings"
    }
}

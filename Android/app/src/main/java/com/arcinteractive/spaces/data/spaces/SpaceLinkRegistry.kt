package com.arcinteractive.spaces.data.spaces

import android.content.Context
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import com.arcinteractive.spaces.data.model.SpaceLinkModuleType
import com.arcinteractive.spaces.data.model.SpaceList
import com.arcinteractive.spaces.data.model.SpaceNote
import com.arcinteractive.spaces.data.rooms.RoomService
import com.arcinteractive.spaces.data.lists.ListService
import com.arcinteractive.spaces.data.notes.NoteService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class SpaceLinkModuleDescriptor(
    val moduleType: SpaceLinkModuleType,
    val title: String,
    val subtitle: String
)

data class SpaceLinkRegistryItem(
    val attachment: SpaceLinkAttachment
) {
    val id: String get() = attachment.id
    val title: String get() = attachment.title
    val subtitle: String? get() = attachment.subtitle
}

class SpaceLinkRegistry(
    private val spaceService: SpaceService = SpaceService()
) {
    fun availableModules(space: Space): List<SpaceLinkModuleDescriptor> = buildList {
        if (space.announcementsEnabled) {
            add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Announcements, "Announcements", "Link an announcement"))
        }
        if (space.pollsEnabled) {
            add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Polls, "Polls", "Link an existing poll"))
        }
        if (space.filesEnabled) {
            add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Files, "Files", "Link a shared file"))
        }
        if (space.eventsEnabled) {
            add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Events, "Events", "Link an event"))
        }
        if (space.roomsEnabled) add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Rooms, "Rooms", "Link a Room"))
        if (space.enabledModules.any { it.id == com.arcinteractive.spaces.data.model.SpaceModules.Photos.id }) {
            add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Media, "Media", "Link shared Media"))
        }
        if (space.listsEnabled) add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Lists, "Lists", "Link a shared List"))
        if (space.notesEnabled) add(SpaceLinkModuleDescriptor(SpaceLinkModuleType.Notes, "Notes", "Link a shared Note"))
    }

    suspend fun fetchItems(
        context: Context,
        space: Space,
        moduleType: SpaceLinkModuleType
    ): List<SpaceLinkRegistryItem> {
        return when (moduleType) {
            SpaceLinkModuleType.Announcements -> {
                if (!space.announcementsEnabled) return emptyList()
                AnnouncementStore.forSpace(space).map { announcement ->
                    SpaceLinkRegistryItem(
                        attachment = SpaceLinkAttachment(
                            id = "announcement-${announcement.id}",
                            moduleType = SpaceLinkModuleType.Announcements,
                            targetId = announcement.id,
                            title = announcement.title,
                            subtitle = "Announcement",
                            icon = "megaphone.fill"
                        )
                    )
                }
            }
            SpaceLinkModuleType.Polls -> {
                if (!space.pollsEnabled) return emptyList()
                spaceService.fetchPolls(context, space).map { poll ->
                    SpaceLinkRegistryItem(
                        attachment = SpaceLinkAttachment(
                            id = "poll-\${poll.id}",
                            moduleType = SpaceLinkModuleType.Polls,
                            targetId = poll.id,
                            title = poll.question,
                            subtitle = "Poll",
                            icon = "chart.bar.xaxis"
                        )
                    )
                }
            }
            SpaceLinkModuleType.Files -> {
                if (!space.filesEnabled) return emptyList()
                spaceService.fetchFiles(context, space).map { file ->
                    SpaceLinkRegistryItem(
                        attachment = SpaceLinkAttachment(
                            id = "file-\${file.id}",
                            moduleType = SpaceLinkModuleType.Files,
                            targetId = file.id,
                            title = file.name,
                            subtitle = "File",
                            icon = "folder.fill"
                        )
                    )
                }
            }
            SpaceLinkModuleType.Events -> {
                if (!space.eventsEnabled) return emptyList()
                spaceService.fetchEvents(context, space).map { event ->
                    SpaceLinkRegistryItem(
                        attachment = SpaceLinkAttachment(
                            id = "event-\${event.id}",
                            moduleType = SpaceLinkModuleType.Events,
                            targetId = event.id,
                            title = event.title,
                            subtitle = event.dateText,
                            icon = "calendar"
                        )
                    )
                }
            }
            SpaceLinkModuleType.Rooms -> fetchRooms(space.id).map { room ->
                SpaceLinkRegistryItem(
                    SpaceLinkAttachment(
                        id = "room-${room.id}", moduleType = SpaceLinkModuleType.Rooms,
                        targetId = room.id, title = room.name,
                        subtitle = room.topic.ifBlank { "Room" }, icon = SpaceLinkModuleType.Rooms.icon
                    )
                )
            }
            SpaceLinkModuleType.Media -> fetchMessages(context, space).flatMap { it.resolvedMediaItems }
                .filter { it.type == com.arcinteractive.spaces.data.model.MessageType.Image || it.type == com.arcinteractive.spaces.data.model.MessageType.Video }
                .map { media ->
                    SpaceLinkRegistryItem(
                        SpaceLinkAttachment(
                            id = "media-${media.id}", moduleType = SpaceLinkModuleType.Media,
                            targetId = media.id, title = media.caption?.takeIf(String::isNotBlank) ?: "Media from ${media.senderName}",
                            subtitle = media.timestamp, icon = SpaceLinkModuleType.Media.icon
                        )
                    )
                }
            SpaceLinkModuleType.Lists -> fetchLists(context, space).map { value ->
                SpaceLinkRegistryItem(SpaceLinkAttachment("list-${value.id}", SpaceLinkModuleType.Lists, value.id, value.title, "List", SpaceLinkModuleType.Lists.icon))
            }
            SpaceLinkModuleType.Notes -> fetchNotes(context, space).map { value ->
                SpaceLinkRegistryItem(SpaceLinkAttachment("note-${value.id}", SpaceLinkModuleType.Notes, value.id, value.title, "Note", SpaceLinkModuleType.Notes.icon))
            }
        }
    }

    private suspend fun fetchRooms(spaceId: String) = suspendCancellableCoroutine<List<com.arcinteractive.spaces.data.model.SpaceRoom>> { continuation ->
        var listener: com.google.firebase.firestore.ListenerRegistration? = null
        listener = RoomService().listenToRooms(spaceId) { result ->
            if (!continuation.isActive) return@listenToRooms
            listener?.remove()
            result.onSuccess { continuation.resume(it) {} }
                .onFailure(continuation::resumeWithException)
        }
        continuation.invokeOnCancellation { listener?.remove() }
    }

    private suspend fun fetchMessages(context: Context, space: Space) =
        suspendCancellableCoroutine<List<com.arcinteractive.spaces.data.model.SpaceMessage>> { continuation ->
            val key = "link-registry-media-${space.id}-${System.nanoTime()}"
            val listener = spaceService.listenToMessages(context, space, key) { result ->
                if (!continuation.isActive) return@listenToMessages
                result.onSuccess { continuation.resume(it) {} }
                    .onFailure(continuation::resumeWithException)
            }
            continuation.invokeOnCancellation { listener?.remove() }
        }
    private suspend fun fetchLists(context: Context, space: Space) = suspendCancellableCoroutine<List<SpaceList>> { c ->
        val listener = ListService().listenToLists(context, space) { result ->
            if (!c.isActive) return@listenToLists
            result.onSuccess { c.resume(it) {} }.onFailure(c::resumeWithException)
        }
        c.invokeOnCancellation { listener.remove() }
    }
    private suspend fun fetchNotes(context: Context, space: Space) = suspendCancellableCoroutine<List<SpaceNote>> { c ->
        val listener = NoteService().listen(context, space) { result ->
            if (!c.isActive) return@listen
            result.onSuccess { c.resume(it) {} }.onFailure(c::resumeWithException)
        }
        c.invokeOnCancellation { listener.remove() }
    }
}

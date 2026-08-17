import Foundation
import FirebaseFirestore

struct SpaceLinkModuleDescriptor: Identifiable, Hashable {
    let moduleType: SpaceLinkModuleType

    var id: String { moduleType.rawValue }
    var title: String { moduleType.title }
    var subtitle: String {
        switch moduleType {
        case .announcements: "Link an announcement"
        case .polls: "Link an existing poll"
        case .files: "Link a shared file"
        case .events: "Link an event"
        case .rooms: "Link a Room"
        case .media: "Link shared Media"
        case .lists: "Link a shared List"
        case .notes: "Link a shared Note"
        }
    }
}

enum SpaceLinkRegistryError: LocalizedError {
    case unavailableModule

    var errorDescription: String? {
        switch self {
        case .unavailableModule:
            "This module is not available in the current Space."
        }
    }
}

struct SpaceLinkRegistryItem: Identifiable, Hashable {
    let attachment: SpaceLinkAttachment

    var id: String { attachment.id }
    var title: String { attachment.title }
    var subtitle: String? { attachment.subtitle }
}

@MainActor
final class SpaceLinkRegistry {
    private let spaceService: SpaceService

    init(spaceService: SpaceService? = nil) {
        self.spaceService = spaceService ?? SpaceService()
    }

    func availableModules(in space: Space) -> [SpaceLinkModuleDescriptor] {
        var modules: [SpaceLinkModuleDescriptor] = []
        if space.announcementsEnabled {
            modules.append(.init(moduleType: .announcements))
        }
        if space.pollsEnabled {
            modules.append(.init(moduleType: .polls))
        }
        if space.filesEnabled {
            modules.append(.init(moduleType: .files))
        }
        if space.eventsEnabled {
            modules.append(.init(moduleType: .events))
        }
        if space.roomsEnabled { modules.append(.init(moduleType: .rooms)) }
        if space.enabledModules.contains(.photos) { modules.append(.init(moduleType: .media)) }
        if space.listsEnabled { modules.append(.init(moduleType: .lists)) }
        if space.notesEnabled { modules.append(.init(moduleType: .notes)) }
        return modules
    }

    func fetchItems(for moduleType: SpaceLinkModuleType, in space: Space) async throws -> [SpaceLinkRegistryItem] {
        switch moduleType {
        case .announcements:
            guard space.announcementsEnabled else { throw SpaceLinkRegistryError.unavailableModule }
            AnnouncementStore.shared.startListening(in: space)
            return AnnouncementStore.shared.announcements(in: space).map {
                SpaceLinkRegistryItem(attachment: SpaceLinkAttachment(
                    moduleType: .announcements,
                    targetId: $0.id,
                    title: $0.title,
                    subtitle: "Announcement",
                    icon: "megaphone.fill"
                ))
            }
        case .polls:
            guard space.pollsEnabled else { throw SpaceLinkRegistryError.unavailableModule }
            return try await spaceService.fetchPolls(in: space)
                .map {
                    SpaceLinkRegistryItem(
                        attachment: SpaceLinkAttachment(
                            moduleType: .polls,
                            targetId: $0.id,
                            title: $0.question,
                            subtitle: "Poll",
                            icon: "chart.bar.xaxis"
                        )
                    )
                }
        case .files:
            guard space.filesEnabled else { throw SpaceLinkRegistryError.unavailableModule }
            return try await spaceService.fetchFiles(in: space)
                .map {
                    SpaceLinkRegistryItem(
                        attachment: SpaceLinkAttachment(
                            moduleType: .files,
                            targetId: $0.id,
                            title: $0.name,
                            subtitle: "File",
                            icon: "folder.fill"
                        )
                    )
                }
        case .events:
            guard space.eventsEnabled else { throw SpaceLinkRegistryError.unavailableModule }
            return try await spaceService.fetchEvents(in: space)
                .map {
                    SpaceLinkRegistryItem(
                        attachment: SpaceLinkAttachment(
                            moduleType: .events,
                            targetId: $0.id,
                            title: $0.title,
                            subtitle: $0.dateText,
                            icon: "calendar"
                        )
                    )
                }
        case .rooms:
            guard space.roomsEnabled else { throw SpaceLinkRegistryError.unavailableModule }
            let rooms = try await fetchRooms(in: space)
            return rooms.map { room in
                let topic = room.topic.trimmingCharacters(in: .whitespacesAndNewlines)
                let attachment = SpaceLinkAttachment(
                    moduleType: .rooms, targetId: room.id, title: room.name,
                    subtitle: topic.isEmpty ? "Room" : topic,
                    icon: "bubble.left.and.text.bubble.right.fill"
                )
                return SpaceLinkRegistryItem(attachment: attachment)
            }
        case .media:
            guard space.enabledModules.contains(.photos) else { throw SpaceLinkRegistryError.unavailableModule }
            let messages = try await fetchMessages(in: space)
            let mediaItems = messages.flatMap { $0.resolvedMediaItems }
            let supportedMedia = mediaItems.filter { $0.mediaType == .photo || $0.mediaType == .video }
            return supportedMedia.map { media in
                let caption = media.caption?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let attachment = SpaceLinkAttachment(
                    moduleType: .media, targetId: media.id,
                    title: caption.isEmpty ? "Media from \(media.senderName)" : caption,
                    subtitle: media.timestamp, icon: "photo.on.rectangle"
                )
                return SpaceLinkRegistryItem(attachment: attachment)
            }
        case .lists:
            guard space.listsEnabled else { throw SpaceLinkRegistryError.unavailableModule }
            return try await fetchLists(in: space).map { value in
                .init(attachment: .init(moduleType: .lists, targetId: value.id, title: value.title, subtitle: "List", icon: "checklist"))
            }
        case .notes:
            guard space.notesEnabled else { throw SpaceLinkRegistryError.unavailableModule }
            return try await fetchNotes(in: space).map { value in
                .init(attachment: .init(moduleType: .notes, targetId: value.id, title: value.title, subtitle: "Note", icon: "note.text"))
            }
        }
    }

    private func fetchRooms(in space: Space) async throws -> [SpaceRoom] {
        try await withCheckedThrowingContinuation { continuation in
            var listener: ListenerRegistration?
            var completed = false
            listener = RoomService().listenToRooms(in: space) { result in
                guard !completed else { return }
                completed = true
                listener?.remove()
                continuation.resume(with: result)
            }
        }
    }

    private func fetchMessages(in space: Space) async throws -> [SpaceMessage] {
        try await withCheckedThrowingContinuation { continuation in
            var listener: ListenerRegistration?
            var completed = false
            listener = spaceService.listenToMessages(in: space) { result in
                guard !completed else { return }
                completed = true
                listener?.remove()
                continuation.resume(with: result)
            }
        }
    }
    private func fetchLists(in space: Space) async throws -> [SpaceList] {
        try await withCheckedThrowingContinuation { continuation in
            var listener: ListenerRegistration?; var completed = false
            listener = ListService().listenToLists(in: space) { result in guard !completed else { return }; completed = true; listener?.remove(); continuation.resume(with: result) }
        }
    }
    private func fetchNotes(in space: Space) async throws -> [SpaceNote] {
        try await withCheckedThrowingContinuation { continuation in
            var listener: ListenerRegistration?; var completed = false
            listener = NoteService().listen(in: space) { result in guard !completed else { return }; completed = true; listener?.remove(); continuation.resume(with: result) }
        }
    }
}

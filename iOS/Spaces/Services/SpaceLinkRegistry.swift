import Foundation

struct SpaceLinkModuleDescriptor: Identifiable, Hashable {
    let moduleType: SpaceLinkModuleType

    var id: String { moduleType.rawValue }
    var title: String { moduleType.title }
    var subtitle: String {
        switch moduleType {
        case .polls: "Link an existing poll"
        case .files: "Link a shared file"
        case .events: "Link an event"
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
        if space.pollsEnabled {
            modules.append(.init(moduleType: .polls))
        }
        if space.filesEnabled {
            modules.append(.init(moduleType: .files))
        }
        if space.eventsEnabled {
            modules.append(.init(moduleType: .events))
        }
        return modules
    }

    func fetchItems(for moduleType: SpaceLinkModuleType, in space: Space) async throws -> [SpaceLinkRegistryItem] {
        switch moduleType {
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
        }
    }
}

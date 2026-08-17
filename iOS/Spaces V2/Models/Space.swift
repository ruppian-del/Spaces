import Foundation

struct Space: Identifiable, Hashable {
    let id: String
    let name: String
    let emoji: String
    let tintHex: String
    let description: String
    let template: SpaceTemplate
    let ownerId: String
    let memberIds: [String]
    let unreadCount: Int?
    let enabledModules: [SpaceModule]
    let moduleOrder: [SpaceModule]

    var subtitle: String {
        description
    }

    var modules: [SpaceModule] {
        let enabledSet = Set(enabledModules + [.settings])
        return moduleOrder.filter { enabledSet.contains($0) }
    }

    var eventsEnabled: Bool {
        enabledModules.contains(.events)
    }

    var filesEnabled: Bool {
        enabledModules.contains(.files)
    }

    var pollsEnabled: Bool {
        enabledModules.contains(.polls)
    }
}

enum SpaceModule: String, CaseIterable, Identifiable, Hashable {
    case general = "general"
    case photos = "photos"
    case files = "files"
    case polls = "polls"
    case events = "events"
    case members = "members"
    case settings = "settings"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: "Space Pings"
        case .photos: "Photos"
        case .files: "Files"
        case .polls: "Polls"
        case .events: "Events"
        case .members: "Members"
        case .settings: "Settings"
        }
    }

    var icon: String {
        switch self {
        case .general: "bubble.left.and.bubble.right.fill"
        case .photos: "photo.on.rectangle.angled"
        case .files: "folder.fill"
        case .polls: "chart.bar.xaxis"
        case .events: "calendar"
        case .members: "person.2.fill"
        case .settings: "gearshape.fill"
        }
    }

    var emoji: String {
        switch self {
        case .general: "💬"
        case .photos: "📷"
        case .files: "📁"
        case .polls: "📊"
        case .events: "📅"
        case .members: "👥"
        case .settings: "⚙️"
        }
    }

    var description: String {
        switch self {
        case .general: "Private conversations and quick check-ins"
        case .photos: "Shared photos and memes"
        case .files: "Shared documents and uploads"
        case .polls: "Questions and voting"
        case .events: "Plans and calendar"
        case .members: "People in this Space"
        case .settings: "Space preferences"
        }
    }

    static var requiredModules: [SpaceModule] {
        [.general, .photos, .members]
    }

    static var optionalModules: [SpaceModule] {
        [.events, .files, .polls]
    }

    static var configurableModules: [SpaceModule] {
        requiredModules + optionalModules
    }

    static var allModules: [SpaceModule] {
        configurableModules + [.settings]
    }
}

enum SpaceTemplate: String, CaseIterable, Identifiable {
    case family = "Family"
    case friends = "Friends"
    case business = "Business"
    case community = "Community"
    case custom = "Custom"

    var id: String { rawValue }

    var subtitle: String {
        switch self {
        case .family: return "Shared updates and planning"
        case .friends: return "Chats, photos, and plans"
        case .business: return "Projects and coordination"
        case .community: return "Announcements and events"
        case .custom: return "Start from a blank space"
        }
    }

    var suggestedEmoji: String {
        switch self {
        case .family: return "🏡"
        case .friends: return "🫶"
        case .business: return "💼"
        case .community: return "🌎"
        case .custom: return "✨"
        }
    }

    var defaultSubtitle: String {
        switch self {
        case .family: return "Family updates and plans"
        case .friends: return "Conversations and hangouts"
        case .business: return "Team communication hub"
        case .community: return "Events and announcements"
        case .custom: return "A new shared space"
        }
    }

    var defaultEnabledModules: [SpaceModule] {
        switch self {
        case .family:
            return SpaceModule.requiredModules + [.events]
        case .friends:
            return SpaceModule.requiredModules + [.events]
        case .business:
            return SpaceModule.requiredModules + [.events, .files]
        case .community:
            return SpaceModule.requiredModules + [.events, .files]
        case .custom:
            return SpaceModule.requiredModules
        }
    }

    var defaultModuleOrder: [SpaceModule] {
        let enabled = defaultEnabledModules
        let disabledOptional = SpaceModule.optionalModules.filter { !enabled.contains($0) }
        return enabled + disabledOptional + [.settings]
    }
}

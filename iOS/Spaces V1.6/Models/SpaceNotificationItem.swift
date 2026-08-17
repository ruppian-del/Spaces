import Foundation

struct SpaceNotificationItem: Identifiable, Hashable {
    let id: String
    let recipientId: String
    let actorId: String
    let actorName: String
    let actorEmoji: String?
    let spaceId: String
    let spaceName: String
    let spaceEmoji: String
    let type: SpaceNotificationType
    let title: String
    let subtitle: String?
    let targetId: String?
    let targetType: ActivityTargetType?
    let createdAt: Date?
    let read: Bool
    let readAt: Date?
    let delivered: Bool
    let deliveredAt: Date?
}

extension SpaceNotificationItem {
    var isUnread: Bool {
        !read
    }

    var section: NotificationSection {
        NotificationSection.resolve(for: createdAt ?? Date())
    }

    var timestampText: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: createdAt ?? Date(), relativeTo: Date())
    }

    var primaryText: String {
        let name = actorName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return title }
        let titleAlreadyIncludesActor = title.range(
            of: name,
            options: [.caseInsensitive, .anchored]
        ) != nil
        return titleAlreadyIncludesActor ? title : "\(name) \(title)"
    }
}

enum SpaceNotificationType: String, CaseIterable, Identifiable, Hashable {
    case newMessage
    case reply
    case reaction
    case photoShared
    case videoShared
    case fileUploaded
    case pollCreated
    case pollClosed
    case eventCreated
    case eventUpdated
    case eventReminder
    case memberJoined
    case pingMessage
    case ping
    case announcement
    case room
    case list
    case note

    var id: String { rawValue }

    var systemImageName: String {
        switch self {
        case .newMessage, .reply:
            return "bubble.left.and.bubble.right"
        case .reaction:
            return "face.smiling"
        case .photoShared:
            return "photo"
        case .videoShared:
            return "video"
        case .fileUploaded:
            return "doc"
        case .pollCreated, .pollClosed:
            return "chart.bar.xaxis"
        case .eventCreated, .eventUpdated, .eventReminder:
            return "calendar"
        case .memberJoined:
            return "person.badge.plus"
        case .pingMessage, .ping:
            return "bell"
        case .announcement:
            return "megaphone.fill"
        case .room:
            return "bubble.left.and.text.bubble.right.fill"
        case .list:
            return "checklist"
        case .note:
            return "note.text"
        }
    }
}

enum NotificationSection: String, CaseIterable, Identifiable, Hashable {
    case today = "Today"
    case yesterday = "Yesterday"
    case earlier = "Earlier"

    var id: String { rawValue }

    static func resolve(for date: Date) -> NotificationSection {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return .today
        }
        if calendar.isDateInYesterday(date) {
            return .yesterday
        }
        return .earlier
    }
}

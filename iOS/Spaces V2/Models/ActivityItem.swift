import Foundation

struct ActivityItem: Identifiable, Hashable {
    let id: String
    let spaceID: String
    let spaceName: String
    let spaceEmoji: String
    let actorID: String
    let actorName: String
    let actorEmoji: String?
    let type: ActivityItemType
    let title: String
    let subtitle: String?
    let targetID: String?
    let targetType: ActivityTargetType?
    let createdAt: Date?
    let readBy: [String]
    let hiddenBy: [String]
}

extension ActivityItem {
    func isUnread(for userID: String?) -> Bool {
        guard let userID else { return false }
        return !readBy.contains(userID)
    }

    var section: ActivitySection {
        ActivitySection.resolve(for: createdAt ?? Date())
    }

    var timestampText: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: createdAt ?? Date(), relativeTo: Date())
    }

    var primaryText: String {
        "\(actorName) \(title)"
    }
}

enum ActivityItemType: String, CaseIterable, Identifiable, Hashable {
    case spaceCreated
    case memberJoined
    case messageSent
    case photoShared
    case videoShared
    case fileUploaded
    case pollCreated
    case pollVoted
    case eventCreated
    case eventUpdated
    case reactionAdded
    case replyAdded

    var id: String { rawValue }

    var systemImageName: String {
        switch self {
        case .spaceCreated:
            return "sparkles.rectangle.stack"
        case .memberJoined:
            return "person.badge.plus"
        case .messageSent, .replyAdded:
            return "bubble.left.and.bubble.right"
        case .photoShared:
            return "photo"
        case .videoShared:
            return "video"
        case .fileUploaded:
            return "doc"
        case .pollCreated, .pollVoted:
            return "chart.bar.xaxis"
        case .eventCreated, .eventUpdated:
            return "calendar"
        case .reactionAdded:
            return "face.smiling"
        }
    }
}

enum ActivityTargetType: String, CaseIterable, Identifiable, Hashable {
    case space
    case general
    case photos
    case files
    case polls
    case events
    case members

    var id: String { rawValue }
}

enum ActivitySection: String, CaseIterable, Identifiable, Hashable {
    case today = "Today"
    case yesterday = "Yesterday"
    case thisWeek = "This Week"
    case older = "Older"

    var id: String { rawValue }

    static func resolve(for date: Date) -> ActivitySection {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return .today
        }
        if calendar.isDateInYesterday(date) {
            return .yesterday
        }
        if let weekAgo = calendar.date(byAdding: .day, value: -7, to: Date()),
           date >= weekAgo {
            return .thisWeek
        }
        return .older
    }
}

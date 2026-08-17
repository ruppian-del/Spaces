import Foundation

struct PingParticipant: Identifiable, Hashable {
    let id: String
    let displayName: String
    let emojiAvatar: String
}

struct Ping: Identifiable, Hashable {
    let id: String
    let participantIds: [String]
    let participantNames: [String]
    let participantEmojis: [String]
    let lastMessageAt: Date?
    let lastMessagePreviewType: String?
    let createdAt: Date?
    let updatedAt: Date?
    let unreadCount: Int

    func otherParticipant(for currentUserID: String?) -> PingParticipant? {
        guard let currentUserID else { return nil }
        guard let index = participantIds.firstIndex(where: { $0 != currentUserID }) else { return nil }
        return PingParticipant(
            id: participantIds[index],
            displayName: participantNames[safe: index] ?? "User",
            emojiAvatar: participantEmojis[safe: index] ?? "🙂"
        )
    }

    func title(for currentUserID: String?) -> String {
        otherParticipant(for: currentUserID)?.displayName ?? participantNames.first ?? "Ping"
    }

    func emoji(for currentUserID: String?) -> String {
        otherParticipant(for: currentUserID)?.emojiAvatar ?? participantEmojis.first ?? "💬"
    }

    var timestampText: String {
        guard let referenceDate = lastMessageAt ?? updatedAt ?? createdAt else { return "" }
        return Self.relativeTimestampFormatter.localizedString(for: referenceDate, relativeTo: Date())
    }

    var lastMessagePreviewText: String {
        switch lastMessagePreviewType {
        case MessageType.video.rawValue:
            return "Video"
        case MessageType.image.rawValue:
            return "Photo"
        case MessageType.file.rawValue:
            return "File"
        case MessageType.text.rawValue:
            return "Message"
        default:
            return "No messages yet"
        }
    }

    private static let relativeTimestampFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

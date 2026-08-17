import Foundation

struct SpaceRoom: Identifiable, Hashable {
    let id: String
    let spaceID: String
    var name: String
    var topic: String
    var isPrivate: Bool
    var memberIDs: Set<String>
    let createdBy: String
    let createdAt: Date
    var updatedAt: Date
    var postingMemberIDs: Set<String>?

    func isVisible(to userID: String) -> Bool {
        !isPrivate || memberIDs.contains(userID) || createdBy == userID
    }
}

struct RoomMessageReaction: Identifiable, Hashable {
    var id: String { emoji }
    let emoji: String
    var userIDs: Set<String>
}

struct RoomMessageAttachment: Identifiable, Hashable {
    let id: String
    let name: String
    let mimeType: String
    let storagePath: String
    let nonce: String
    let isMedia: Bool
}

struct RoomMessage: Identifiable, Hashable {
    let id: String
    let roomID: String
    let senderID: String
    let senderName: String
    let body: String
    let createdAt: Date
    let replyToID: String?
    let replyPreview: String?
    var reactions: [RoomMessageReaction]
    var isPinned: Bool
    let links: [SpaceLinkAttachment]
    let attachments: [RoomMessageAttachment]
}

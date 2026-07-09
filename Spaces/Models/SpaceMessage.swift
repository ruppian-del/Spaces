import Foundation

struct MessageReplyContext: Hashable {
    let messageId: String
    let senderName: String
    let type: String
    let preview: String
}

struct SpaceMessage: Identifiable, Hashable {
    let id: String
    let spaceId: String?
    let senderId: String?
    let senderName: String
    let senderEmoji: String?
    let type: MessageType
    let encryptionVersion: String
    let deleted: Bool
    let text: String?
    let media: SpaceMedia?
    let createdAt: Date?
    let updatedAt: Date?
    let timestamp: String
    let isOutgoing: Bool
    let status: String?
    let deliveryStatus: String?
    let isEdited: Bool
    let editedAt: Date?
    let replyContext: MessageReplyContext?
    let reactions: [MessageReaction]

    init(
        id: String,
        spaceId: String? = nil,
        senderId: String? = nil,
        senderName: String,
        senderEmoji: String? = nil,
        type: MessageType = .text,
        encryptionVersion: String = "none",
        deleted: Bool = false,
        text: String? = nil,
        media: SpaceMedia? = nil,
        createdAt: Date? = nil,
        updatedAt: Date? = nil,
        timestamp: String,
        isOutgoing: Bool,
        status: String? = nil,
        deliveryStatus: String?,
        isEdited: Bool = false,
        editedAt: Date? = nil,
        replyContext: MessageReplyContext? = nil,
        reactions: [MessageReaction] = []
    ) {
        self.id = id
        self.spaceId = spaceId
        self.senderId = senderId
        self.senderName = senderName
        self.senderEmoji = senderEmoji
        self.type = type
        self.encryptionVersion = encryptionVersion
        self.deleted = deleted
        self.text = text
        self.media = media
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.timestamp = timestamp
        self.isOutgoing = isOutgoing
        self.status = status
        self.deliveryStatus = deliveryStatus
        self.isEdited = isEdited
        self.editedAt = editedAt
        self.replyContext = replyContext
        self.reactions = reactions
    }

    init(
        id: UUID,
        spaceId: String? = nil,
        senderId: String? = nil,
        senderName: String,
        senderEmoji: String? = nil,
        type: MessageType = .text,
        encryptionVersion: String = "none",
        deleted: Bool = false,
        text: String? = nil,
        media: SpaceMedia? = nil,
        createdAt: Date? = nil,
        updatedAt: Date? = nil,
        timestamp: String,
        isOutgoing: Bool,
        status: String? = nil,
        deliveryStatus: String?,
        isEdited: Bool = false,
        editedAt: Date? = nil,
        replyContext: MessageReplyContext? = nil,
        reactions: [MessageReaction] = []
    ) {
        self.init(
            id: id.uuidString,
            spaceId: spaceId,
            senderId: senderId,
            senderName: senderName,
            senderEmoji: senderEmoji,
            type: type,
            encryptionVersion: encryptionVersion,
            deleted: deleted,
            text: text,
            media: media,
            createdAt: createdAt,
            updatedAt: updatedAt,
            timestamp: timestamp,
            isOutgoing: isOutgoing,
            status: status,
            deliveryStatus: deliveryStatus,
            isEdited: isEdited,
            editedAt: editedAt,
            replyContext: replyContext,
            reactions: reactions
        )
    }
}

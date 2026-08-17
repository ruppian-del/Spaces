import Foundation

enum LocalMessageDeliveryState: String, Codable, Hashable {
    case sending
    case uploading
    case waitingForConnection
    case failed
}

struct MessageReplyContext: Codable, Hashable {
    let messageId: String
    let senderName: String
    let type: String
    let preview: String
}

struct LinkPreviewData: Codable, Hashable {
    let originalURL: String
    let canonicalURL: String?
    let domain: String
    let title: String
    let summary: String?
    let siteName: String?
    let imageDataBase64: String?
    let imageMimeType: String?

    var displayURL: String {
        canonicalURL ?? originalURL
    }

    var imageData: Data? {
        guard let imageDataBase64 else { return nil }
        return Data(base64Encoded: imageDataBase64)
    }
}

enum SpaceLinkModuleType: String, Codable, CaseIterable, Hashable {
    case announcements
    case polls
    case files
    case events
    case rooms
    case media
    case lists
    case notes

    var title: String {
        switch self {
        case .announcements: return "Announcement"
        case .polls: return "Poll"
        case .files: return "File"
        case .events: return "Event"
        case .rooms: return "Room"
        case .media: return "Media"
        case .lists: return "List"
        case .notes: return "Note"
        }
    }

    var icon: String {
        switch self {
        case .announcements: return "megaphone.fill"
        case .polls: return "chart.bar.xaxis"
        case .files: return "folder.fill"
        case .events: return "calendar"
        case .rooms: return "bubble.left.and.text.bubble.right.fill"
        case .media: return "photo.on.rectangle"
        case .lists: return "checklist"
        case .notes: return "note.text"
        }
    }

    var emoji: String {
        switch self {
        case .announcements: return "📢"
        case .polls: return "📊"
        case .files: return "📁"
        case .events: return "📅"
        case .rooms: return "💬"
        case .media: return "🖼️"
        case .lists: return "✅"
        case .notes: return "📝"
        }
    }
}

struct SpaceLinkAttachment: Identifiable, Codable, Hashable {
    let id: String
    let moduleType: SpaceLinkModuleType
    let targetId: String
    let title: String
    let subtitle: String?
    let icon: String
    let version: Int

    init(
        id: String = UUID().uuidString,
        moduleType: SpaceLinkModuleType,
        targetId: String,
        title: String,
        subtitle: String? = nil,
        icon: String? = nil,
        version: Int = 1
    ) {
        self.id = id
        self.moduleType = moduleType
        self.targetId = targetId
        self.title = title
        self.subtitle = subtitle
        self.icon = icon ?? moduleType.icon
        self.version = version
    }

    var searchableText: String {
        [title, subtitle ?? "", moduleType.title].joined(separator: "\n")
    }
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
    let mediaItems: [SpaceMedia]
    let createdAt: Date?
    let updatedAt: Date?
    let timestamp: String
    let isOutgoing: Bool
    let status: String?
    let deliveryStatus: String?
    let isEdited: Bool
    let editedAt: Date?
    let replyContext: MessageReplyContext?
    let linkPreview: LinkPreviewData?
    let spaceLinks: [SpaceLinkAttachment]
    let reactions: [MessageReaction]
    let localDeliveryState: LocalMessageDeliveryState?
    let localFailureMessage: String?

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
        mediaItems: [SpaceMedia] = [],
        createdAt: Date? = nil,
        updatedAt: Date? = nil,
        timestamp: String,
        isOutgoing: Bool,
        status: String? = nil,
        deliveryStatus: String?,
        isEdited: Bool = false,
        editedAt: Date? = nil,
        replyContext: MessageReplyContext? = nil,
        linkPreview: LinkPreviewData? = nil,
        spaceLinks: [SpaceLinkAttachment] = [],
        reactions: [MessageReaction] = [],
        localDeliveryState: LocalMessageDeliveryState? = nil,
        localFailureMessage: String? = nil
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
        self.mediaItems = mediaItems
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.timestamp = timestamp
        self.isOutgoing = isOutgoing
        self.status = status
        self.deliveryStatus = deliveryStatus
        self.isEdited = isEdited
        self.editedAt = editedAt
        self.replyContext = replyContext
        self.linkPreview = linkPreview
        self.spaceLinks = spaceLinks
        self.reactions = reactions
        self.localDeliveryState = localDeliveryState
        self.localFailureMessage = localFailureMessage
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
        mediaItems: [SpaceMedia] = [],
        createdAt: Date? = nil,
        updatedAt: Date? = nil,
        timestamp: String,
        isOutgoing: Bool,
        status: String? = nil,
        deliveryStatus: String?,
        isEdited: Bool = false,
        editedAt: Date? = nil,
        replyContext: MessageReplyContext? = nil,
        linkPreview: LinkPreviewData? = nil,
        spaceLinks: [SpaceLinkAttachment] = [],
        reactions: [MessageReaction] = [],
        localDeliveryState: LocalMessageDeliveryState? = nil,
        localFailureMessage: String? = nil
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
            mediaItems: mediaItems,
            createdAt: createdAt,
            updatedAt: updatedAt,
            timestamp: timestamp,
            isOutgoing: isOutgoing,
            status: status,
            deliveryStatus: deliveryStatus,
            isEdited: isEdited,
            editedAt: editedAt,
            replyContext: replyContext,
            linkPreview: linkPreview,
            spaceLinks: spaceLinks,
            reactions: reactions,
            localDeliveryState: localDeliveryState,
            localFailureMessage: localFailureMessage
        )
    }

    var resolvedMediaItems: [SpaceMedia] {
        if !mediaItems.isEmpty {
            return mediaItems
        }
        if let media {
            return [media]
        }
        return []
    }

    var primaryMedia: SpaceMedia? {
        resolvedMediaItems.first
    }

    var hasMediaAttachments: Bool {
        !resolvedMediaItems.isEmpty
    }
}

import Foundation

enum AnnouncementAttachmentKind: String, Codable, CaseIterable, Hashable {
    case image
    case video
    case file
    case link

    var title: String {
        switch self {
        case .image: "Image"
        case .video: "Video"
        case .file: "File"
        case .link: "Link"
        }
    }

    var icon: String {
        switch self {
        case .image: "photo.fill"
        case .video: "video.fill"
        case .file: "doc.fill"
        case .link: "link"
        }
    }
}

struct AnnouncementAttachment: Identifiable, Codable, Hashable {
    let id: String
    let kind: AnnouncementAttachmentKind
    let title: String
    let urlString: String?
    let storagePath: String?
    let nonce: String?
    let mimeType: String?
    let fileSize: Int?
    let uploadedBy: String?

    init(
        id: String = UUID().uuidString,
        kind: AnnouncementAttachmentKind,
        title: String,
        urlString: String? = nil,
        storagePath: String? = nil,
        nonce: String? = nil,
        mimeType: String? = nil,
        fileSize: Int? = nil,
        uploadedBy: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.urlString = urlString
        self.storagePath = storagePath
        self.nonce = nonce
        self.mimeType = mimeType
        self.fileSize = fileSize
        self.uploadedBy = uploadedBy
    }
}

enum AnnouncementReferenceKind: String, Codable, CaseIterable, Hashable {
    case event
    case note
    case list
    case media
    case file

    var title: String {
        switch self {
        case .event: "Event"
        case .note: "Note"
        case .list: "List"
        case .media: "Media"
        case .file: "File"
        }
    }

    var icon: String {
        switch self {
        case .event: "calendar"
        case .note: "note.text"
        case .list: "checklist"
        case .media: "photo.on.rectangle"
        case .file: "folder.fill"
        }
    }

    var emoji: String {
        switch self {
        case .event: "📅"
        case .note: "📝"
        case .list: "✅"
        case .media: "📷"
        case .file: "📁"
        }
    }
}

struct AnnouncementReference: Identifiable, Codable, Hashable {
    let id: String
    let kind: AnnouncementReferenceKind
    let targetID: String
    let title: String
    let subtitle: String?

    init(
        id: String = UUID().uuidString,
        kind: AnnouncementReferenceKind,
        targetID: String,
        title: String,
        subtitle: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.targetID = targetID
        self.title = title
        self.subtitle = subtitle
    }
}

struct AnnouncementReaction: Identifiable, Codable, Hashable {
    var id: String { emoji }
    let emoji: String
    var userIDs: Set<String>

    var count: Int { userIDs.count }
}

struct AnnouncementComment: Identifiable, Codable, Hashable {
    let id: String
    let authorID: String
    let authorName: String
    let body: String
    let createdAt: Date

    init(
        id: String = UUID().uuidString,
        authorID: String,
        authorName: String,
        body: String,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.authorID = authorID
        self.authorName = authorName
        self.body = body
        self.createdAt = createdAt
    }
}

struct SpaceAnnouncement: Identifiable, Codable, Hashable {
    let id: String
    let spaceID: String
    var title: String
    var body: String
    let authorID: String
    let authorName: String
    let createdAt: Date
    var updatedAt: Date
    var isPinned: Bool
    var expiresAt: Date?
    var commentsEnabled: Bool
    var attachments: [AnnouncementAttachment]
    var references: [AnnouncementReference]
    var reactions: [AnnouncementReaction]
    var comments: [AnnouncementComment]

    var isExpired: Bool {
        guard let expiresAt else { return false }
        return expiresAt <= Date()
    }
}

import Foundation

struct SpaceNoteAttachment: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let mimeType: String
    let storagePath: String
    let nonce: String
    let isMedia: Bool
}

struct SpaceNote: Identifiable, Hashable {
    let id: String
    let spaceID: String
    var title: String
    var markdown: String
    var attachments: [SpaceNoteAttachment]
    var links: [SpaceLinkAttachment]
    let createdBy: String
    let createdAt: Date
    var updatedAt: Date
}

struct SpaceNoteComment: Identifiable, Hashable {
    let id: String
    let noteID: String
    let authorID: String
    let authorName: String
    let body: String
    let createdAt: Date
}

struct SpaceNotePayload: Codable {
    let title: String
    let markdown: String
    let attachments: [SpaceNoteAttachment]
    let links: [SpaceLinkAttachment]
}

struct SpaceNoteCommentPayload: Codable {
    let authorName: String
    let body: String
}

import Foundation

struct SpaceListSection: Codable, Identifiable, Hashable {
    var id: String = UUID().uuidString
    var title: String
    var order: Int
}

struct SpaceList: Identifiable, Hashable {
    let id: String
    let spaceID: String
    var title: String
    var sections: [SpaceListSection]
    var links: [SpaceLinkAttachment]
    let createdBy: String
    let createdAt: Date
    var updatedAt: Date
}

struct SpaceListItemAttachment: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let mimeType: String
    let storagePath: String
    let nonce: String
    let isMedia: Bool
}

struct SpaceListItem: Identifiable, Hashable {
    let id: String
    let listID: String
    var title: String
    var notes: String
    var isCompleted: Bool
    var assignedMemberIDs: Set<String>
    var dueDate: Date?
    var sectionID: String?
    var order: Int
    var attachments: [SpaceListItemAttachment]
    var links: [SpaceLinkAttachment]
    let createdBy: String
    let createdAt: Date
    var updatedAt: Date
}

struct SpaceListPayload: Codable {
    let title: String
    let sections: [SpaceListSection]
    let links: [SpaceLinkAttachment]
}

struct SpaceListItemPayload: Codable {
    let title: String
    let notes: String
    let assignedMemberIDs: [String]
    let attachments: [SpaceListItemAttachment]
    let links: [SpaceLinkAttachment]?
}

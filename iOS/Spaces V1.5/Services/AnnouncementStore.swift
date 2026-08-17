import Combine
import FirebaseCore
import FirebaseFirestore
import Foundation

@MainActor
final class AnnouncementStore: ObservableObject {
    static let shared = AnnouncementStore()

    @Published private var announcementsBySpace: [String: [SpaceAnnouncement]] = [:]
    @Published private(set) var lastErrorMessage: String?

    private var listeners: [String: ListenerRegistration] = [:]
    private var spacesByID: [String: Space] = [:]

    private init() {}

    func startListening(in space: Space) {
        spacesByID[space.id] = space
        guard listeners[space.id] == nil else { return }
        guard FirebaseApp.app() != nil else {
            ensureSeeded(space)
            return
        }

        let collection = Firestore.firestore()
            .collection("spaces")
            .document(space.id)
            .collection("announcements")

        listeners[space.id] = collection.addSnapshotListener { [weak self] snapshot, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let error {
                    self.lastErrorMessage = error.localizedDescription
                    return
                }

                self.announcementsBySpace[space.id] = snapshot?.documents.compactMap {
                    self.mapAnnouncement(document: $0, spaceID: space.id)
                } ?? []
                self.lastErrorMessage = nil
            }
        }
    }

    func announcements(in space: Space) -> [SpaceAnnouncement] {
        ensureSeeded(space)
        return (announcementsBySpace[space.id] ?? [])
            .filter { !$0.isExpired }
            .sorted {
                if $0.isPinned != $1.isPinned {
                    return $0.isPinned
                }
                return $0.createdAt > $1.createdAt
            }
    }

    func announcement(id: String, in space: Space) -> SpaceAnnouncement? {
        ensureSeeded(space)
        return announcementsBySpace[space.id]?.first(where: { $0.id == id })
    }

    func save(_ announcement: SpaceAnnouncement) {
        var values = announcementsBySpace[announcement.spaceID] ?? []
        let isNew = !values.contains { $0.id == announcement.id }
        if let index = values.firstIndex(where: { $0.id == announcement.id }) {
            values[index] = announcement
        } else {
            values.append(announcement)
        }
        announcementsBySpace[announcement.spaceID] = values
        persist(announcement)
        if let space = spacesByID[announcement.spaceID] {
            Task {
                let service = SpaceService()
                if isNew {
                    await service.recordModuleActivity(
                        type: .announcementCreated,
                        in: space,
                        title: "posted an Announcement",
                        subtitle: announcement.title,
                        targetID: announcement.id,
                        targetType: .announcements
                    )
                }
                if !isNew {
                    let mentionedIDs = await service.mentionedMemberIDs(in: announcement.body, space: space)
                    await service.recordTargetedModuleNotification(
                        recipientIDs: mentionedIDs,
                        type: .announcement,
                        in: space,
                        title: "mentioned you in an Announcement",
                        subtitle: announcement.title,
                        targetID: announcement.id,
                        targetType: .announcements
                    )
                }
            }
        }
    }

    func delete(id: String, in space: Space) {
        announcementsBySpace[space.id]?.removeAll { $0.id == id }
        guard FirebaseApp.app() != nil else { return }
        Firestore.firestore()
            .collection("spaces")
            .document(space.id)
            .collection("announcements")
            .document(id)
            .delete { [weak self] error in
                guard let error else { return }
                Task { @MainActor [weak self] in
                    self?.lastErrorMessage = error.localizedDescription
                }
            }
    }

    func toggleReaction(emoji: String, announcementID: String, space: Space, userID: String) {
        update(announcementID: announcementID, space: space) { announcement in
            if let index = announcement.reactions.firstIndex(where: { $0.emoji == emoji }) {
                if announcement.reactions[index].userIDs.contains(userID) {
                    announcement.reactions[index].userIDs.remove(userID)
                } else {
                    announcement.reactions[index].userIDs.insert(userID)
                }
                announcement.reactions.removeAll { $0.userIDs.isEmpty }
            } else {
                announcement.reactions.append(.init(emoji: emoji, userIDs: [userID]))
            }
            announcement.updatedAt = Date()
        }
        persistCurrent(announcementID: announcementID, space: space)
    }

    func addComment(_ comment: AnnouncementComment, announcementID: String, space: Space) {
        update(announcementID: announcementID, space: space) { announcement in
            guard announcement.commentsEnabled else { return }
            announcement.comments.append(comment)
            announcement.updatedAt = Date()
        }
        persistCurrent(announcementID: announcementID, space: space)
        Task {
            let service = SpaceService()
            let mentionedIDs = await service.mentionedMemberIDs(in: comment.body, space: space)
            await service.recordTargetedModuleNotification(
                recipientIDs: Array(Set(mentionedIDs + (announcement(id: announcementID, in: space).map { [$0.authorID] } ?? []))),
                type: .announcement,
                in: space,
                title: "commented on your Announcement",
                subtitle: announcement(id: announcementID, in: space)?.title,
                targetID: announcementID,
                targetType: .announcements
            )
        }
    }

    private func update(
        announcementID: String,
        space: Space,
        mutation: (inout SpaceAnnouncement) -> Void
    ) {
        ensureSeeded(space)
        guard var values = announcementsBySpace[space.id],
              let index = values.firstIndex(where: { $0.id == announcementID }) else {
            return
        }
        mutation(&values[index])
        announcementsBySpace[space.id] = values
    }

    private func ensureSeeded(_ space: Space) {
        guard announcementsBySpace[space.id] == nil else { return }
        announcementsBySpace[space.id] = []
    }

    private func persistCurrent(announcementID: String, space: Space) {
        guard let announcement = announcementsBySpace[space.id]?.first(where: { $0.id == announcementID }) else {
            return
        }
        persist(announcement)
    }

    private func persist(_ announcement: SpaceAnnouncement) {
        guard FirebaseApp.app() != nil else { return }

        Firestore.firestore()
            .collection("spaces")
            .document(announcement.spaceID)
            .collection("announcements")
            .document(announcement.id)
            .setData(documentData(for: announcement), merge: true) { [weak self] error in
                guard let error else { return }
                Task { @MainActor [weak self] in
                    self?.lastErrorMessage = error.localizedDescription
                }
            }
    }

    private func documentData(for announcement: SpaceAnnouncement) -> [String: Any] {
        [
            "spaceId": announcement.spaceID,
            "title": announcement.title,
            "body": announcement.body,
            "authorId": announcement.authorID,
            "authorName": announcement.authorName,
            "createdAt": Timestamp(date: announcement.createdAt),
            "updatedAt": Timestamp(date: announcement.updatedAt),
            "isPinned": announcement.isPinned,
            "expiresAt": announcement.expiresAt.map(Timestamp.init(date:)) ?? NSNull(),
            "commentsEnabled": announcement.commentsEnabled,
            "attachments": announcement.attachments.map {
                [
                    "id": $0.id,
                    "kind": $0.kind.rawValue,
                    "title": $0.title,
                    "url": $0.urlString.map { $0 as Any } ?? NSNull(),
                    "storagePath": $0.storagePath.map { $0 as Any } ?? NSNull(),
                    "nonce": $0.nonce.map { $0 as Any } ?? NSNull(),
                    "mimeType": $0.mimeType.map { $0 as Any } ?? NSNull(),
                    "fileSize": $0.fileSize.map { $0 as Any } ?? NSNull(),
                    "uploadedBy": $0.uploadedBy.map { $0 as Any } ?? NSNull()
                ]
            },
            "references": announcement.references.map {
                [
                    "id": $0.id,
                    "kind": $0.kind.rawValue,
                    "targetId": $0.targetID,
                    "title": $0.title,
                    "subtitle": $0.subtitle.map { $0 as Any } ?? NSNull()
                ]
            },
            "reactions": announcement.reactions.map {
                [
                    "emoji": $0.emoji,
                    "userIds": Array($0.userIDs)
                ]
            },
            "comments": announcement.comments.map {
                [
                    "id": $0.id,
                    "authorId": $0.authorID,
                    "authorName": $0.authorName,
                    "body": $0.body,
                    "createdAt": Timestamp(date: $0.createdAt)
                ]
            }
        ]
    }

    private func mapAnnouncement(document: QueryDocumentSnapshot, spaceID: String) -> SpaceAnnouncement? {
        let data = document.data()
        guard
            let title = data["title"] as? String,
            let body = data["body"] as? String,
            let authorID = data["authorId"] as? String,
            let authorName = data["authorName"] as? String
        else {
            return nil
        }

        let attachments = (data["attachments"] as? [[String: Any]] ?? []).compactMap { value -> AnnouncementAttachment? in
            guard
                let kindValue = value["kind"] as? String,
                let kind = AnnouncementAttachmentKind(rawValue: kindValue),
                let title = value["title"] as? String
            else {
                return nil
            }
            return AnnouncementAttachment(
                id: value["id"] as? String ?? UUID().uuidString,
                kind: kind,
                title: title,
                urlString: value["url"] as? String,
                storagePath: value["storagePath"] as? String,
                nonce: value["nonce"] as? String,
                mimeType: value["mimeType"] as? String,
                fileSize: value["fileSize"] as? Int,
                uploadedBy: value["uploadedBy"] as? String
            )
        }.uniquedByID()

        let references = (data["references"] as? [[String: Any]] ?? []).compactMap { value -> AnnouncementReference? in
            guard
                let kindValue = value["kind"] as? String,
                let kind = AnnouncementReferenceKind(rawValue: kindValue),
                let targetID = value["targetId"] as? String,
                let title = value["title"] as? String
            else {
                return nil
            }
            return AnnouncementReference(
                id: value["id"] as? String ?? UUID().uuidString,
                kind: kind,
                targetID: targetID,
                title: title,
                subtitle: value["subtitle"] as? String
            )
        }.uniquedByID()

        let reactions = (data["reactions"] as? [[String: Any]] ?? []).compactMap { value -> AnnouncementReaction? in
            guard let emoji = value["emoji"] as? String else { return nil }
            return AnnouncementReaction(
                emoji: emoji,
                userIDs: Set(value["userIds"] as? [String] ?? [])
            )
        }.uniquedByID()

        let comments = (data["comments"] as? [[String: Any]] ?? []).compactMap { value -> AnnouncementComment? in
            guard
                let authorID = value["authorId"] as? String,
                let authorName = value["authorName"] as? String,
                let body = value["body"] as? String
            else {
                return nil
            }
            return AnnouncementComment(
                id: value["id"] as? String ?? UUID().uuidString,
                authorID: authorID,
                authorName: authorName,
                body: body,
                createdAt: (value["createdAt"] as? Timestamp)?.dateValue() ?? Date()
            )
        }.uniquedByID()

        return SpaceAnnouncement(
            id: document.documentID,
            spaceID: data["spaceId"] as? String ?? spaceID,
            title: title,
            body: body,
            authorID: authorID,
            authorName: authorName,
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date(),
            updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue() ?? Date(),
            isPinned: data["isPinned"] as? Bool ?? false,
            expiresAt: (data["expiresAt"] as? Timestamp)?.dateValue(),
            commentsEnabled: data["commentsEnabled"] as? Bool ?? true,
            attachments: attachments,
            references: references,
            reactions: reactions,
            comments: comments
        )
    }

}

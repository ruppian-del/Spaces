import Combine
import Foundation

@MainActor
final class AnnouncementsViewModel: ObservableObject {
    @Published private(set) var announcements: [SpaceAnnouncement] = []
    @Published private(set) var canCreate = false
    @Published var errorMessage: String?

    let space: Space
    let currentUserID: String
    let currentUserName: String

    private let store: AnnouncementStore
    private let spaceService: SpaceService
    private let authService: AuthService
    private let encryptedMediaService = EncryptedMediaService()
    private var storeSubscription: AnyCancellable?

    init(
        space: Space,
        store: AnnouncementStore? = nil,
        spaceService: SpaceService? = nil,
        authService: AuthService? = nil
    ) {
        self.space = space
        self.store = store ?? .shared
        self.spaceService = spaceService ?? SpaceService()
        self.authService = authService ?? AuthService()
        let session = self.authService.currentSession()
        self.currentUserID = session?.uid ?? space.ownerId
        self.currentUserName = session?.displayName.nonEmpty ?? "Space Owner"

        storeSubscription = self.store.objectWillChange.sink { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.reload()
                self?.errorMessage = self?.store.lastErrorMessage
            }
        }
        reload()
    }

    func start() async {
        store.startListening(in: space)
        if currentUserID == space.ownerId || space.ownerId.hasPrefix("mock-") {
            canCreate = true
        } else {
            canCreate = await spaceService.canPerform(.createAnnouncements, in: space)
        }
        reload()
    }

    func reload() {
        announcements = store.announcements(in: space)
    }

    func announcement(id: String) -> SpaceAnnouncement? {
        store.announcement(id: id, in: space)
    }

    func save(
        existing: SpaceAnnouncement?,
        title: String,
        body: String,
        isPinned: Bool,
        expiresAt: Date?,
        commentsEnabled: Bool,
        attachments: [AnnouncementAttachment],
        references: [AnnouncementReference]
    ) {
        let now = Date()
        let announcement = SpaceAnnouncement(
            id: existing?.id ?? UUID().uuidString,
            spaceID: space.id,
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            body: body.trimmingCharacters(in: .whitespacesAndNewlines),
            authorID: existing?.authorID ?? currentUserID,
            authorName: existing?.authorName ?? currentUserName,
            createdAt: existing?.createdAt ?? now,
            updatedAt: now,
            isPinned: isPinned,
            expiresAt: expiresAt,
            commentsEnabled: commentsEnabled,
            attachments: attachments,
            references: references,
            reactions: existing?.reactions ?? [],
            comments: existing?.comments ?? []
        )
        store.save(announcement)
        reload()
    }

    func canEdit(_ announcement: SpaceAnnouncement) -> Bool {
        announcement.authorID == currentUserID && canCreate
    }

    func canDelete(_ announcement: SpaceAnnouncement) -> Bool {
        canCreate || announcement.authorID == currentUserID
    }

    func delete(_ announcement: SpaceAnnouncement) {
        guard canDelete(announcement) else { return }
        store.delete(id: announcement.id, in: space)
        reload()
    }

    func toggleReaction(_ emoji: String, announcementID: String) {
        store.toggleReaction(
            emoji: emoji,
            announcementID: announcementID,
            space: space,
            userID: currentUserID
        )
        reload()
    }

    func addComment(_ body: String, announcementID: String) {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store.addComment(
            AnnouncementComment(
                authorID: currentUserID,
                authorName: currentUserName,
                body: trimmed
            ),
            announcementID: announcementID,
            space: space
        )
        reload()
    }

    func uploadAttachment(data: Data, fileName: String, mimeType: String) async throws -> AnnouncementAttachment {
        let id = UUID().uuidString
        let result = try await encryptedMediaService.uploadFile(
            spaceID: space.id,
            storagePath: "spaces/\(space.id)/announcements/\(id)",
            originalData: data,
            mimeType: mimeType,
            uploadedBy: currentUserID
        )
        let metadata = result.metadata
        let kind: AnnouncementAttachmentKind = mimeType.hasPrefix("image/")
            ? .image
            : (mimeType.hasPrefix("video/") ? .video : .file)
        return AnnouncementAttachment(
            id: id,
            kind: kind,
            title: fileName,
            storagePath: metadata.storagePath,
            nonce: metadata.nonce,
            mimeType: metadata.mimeType,
            fileSize: metadata.fileSize,
            uploadedBy: metadata.uploadedBy
        )
    }
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

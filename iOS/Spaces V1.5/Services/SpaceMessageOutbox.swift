import Foundation

struct QueuedMessageAttachment: Codable, Hashable, Identifiable {
    let id: String
    let dataFileName: String
    let previewFileName: String
    let mimeType: String
    let mediaCategory: String
    let isVideo: Bool
}

struct QueuedSpaceMessage: Codable, Hashable, Identifiable {
    enum PayloadKind: String, Codable {
        case text
        case media
    }

    let id: String
    let space: Space
    let kind: PayloadKind
    let createdAt: Date
    let text: String?
    let linkPreview: LinkPreviewData?
    let spaceLinks: [SpaceLinkAttachment]
    let caption: String?
    let replyContext: MessageReplyContext?
    let attachments: [QueuedMessageAttachment]
    var state: LocalMessageDeliveryState
    var failureMessage: String?
}

@MainActor
final class SpaceMessageOutbox {
    private let spaceService: SpaceService
    private let connectivityService: ConnectivityService
    private let fileManager: FileManager
    private let persistenceURL: URL
    private let mediaDirectoryURL: URL

    private(set) var items: [QueuedSpaceMessage] = [] {
        didSet {
            persist()
            onItemsChanged?(items)
        }
    }

    var onItemsChanged: (([QueuedSpaceMessage]) -> Void)?
    var onSendSucceeded: ((QueuedSpaceMessage, SpaceMessage) -> Void)?

    private var processingTask: Task<Void, Never>?

    init(
        spaceService: SpaceService? = nil,
        connectivityService: ConnectivityService = .shared,
        fileManager: FileManager = .default
    ) {
        self.spaceService = spaceService ?? SpaceService()
        self.connectivityService = connectivityService
        self.fileManager = fileManager

        let supportURL = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let baseURL = supportURL.appendingPathComponent("SpacesOutbox", isDirectory: true)
        persistenceURL = baseURL.appendingPathComponent("space-outbox.json")
        mediaDirectoryURL = baseURL.appendingPathComponent("attachments", isDirectory: true)
        try? fileManager.createDirectory(at: mediaDirectoryURL, withIntermediateDirectories: true)
        load()

        connectivityService.onConnectivityChanged = { [weak self] isConnected in
            guard let self else { return }
            if isConnected {
                self.processQueueIfNeeded()
            } else {
                self.markActiveItemsWaitingForConnection()
            }
        }
    }

    func items(for spaceID: String) -> [QueuedSpaceMessage] {
        items
            .filter { $0.space.id == spaceID }
            .sorted { $0.createdAt < $1.createdAt }
    }

    @discardableResult
    func enqueueText(
        space: Space,
        text: String,
        linkPreview: LinkPreviewData?,
        spaceLinks: [SpaceLinkAttachment],
        replyContext: MessageReplyContext?
    ) -> String {
        let messageID = UUID().uuidString
        let item = QueuedSpaceMessage(
            id: messageID,
            space: space,
            kind: .text,
            createdAt: Date(),
            text: text,
            linkPreview: linkPreview,
            spaceLinks: spaceLinks,
            caption: nil,
            replyContext: replyContext,
            attachments: [],
            state: connectivityService.isConnected ? .sending : .waitingForConnection,
            failureMessage: nil
        )
        items.append(item)
        processQueueIfNeeded()
        return messageID
    }

    @discardableResult
    func enqueueMedia(
        space: Space,
        selections: [ComposerMediaSelection],
        caption: String?,
        replyContext: MessageReplyContext?
    ) -> String {
        let messageID = UUID().uuidString
        let attachments = selections.map { selection in
            persistAttachment(selection, messageID: messageID)
        }
        let item = QueuedSpaceMessage(
            id: messageID,
            space: space,
            kind: .media,
            createdAt: Date(),
            text: nil,
            linkPreview: nil,
            spaceLinks: [],
            caption: caption,
            replyContext: replyContext,
            attachments: attachments,
            state: connectivityService.isConnected ? .uploading : .waitingForConnection,
            failureMessage: nil
        )
        items.append(item)
        processQueueIfNeeded()
        return messageID
    }

    func retry(_ messageID: String) {
        guard let index = items.firstIndex(where: { $0.id == messageID }) else { return }
        items[index].failureMessage = nil
        items[index].state = connectivityService.isConnected
            ? (items[index].kind == .media ? .uploading : .sending)
            : .waitingForConnection
        processQueueIfNeeded()
    }

    func delete(_ messageID: String) {
        guard let index = items.firstIndex(where: { $0.id == messageID }) else { return }
        let item = items.remove(at: index)
        cleanupAttachments(for: item)
    }

    private func processQueueIfNeeded() {
        guard processingTask == nil else { return }
        processingTask = Task { [weak self] in
            guard let self else { return }
            await self.processQueue()
            self.processingTask = nil
        }
    }

    private func processQueue() async {
        while true {
            guard connectivityService.isConnected else {
                markActiveItemsWaitingForConnection()
                return
            }

            guard let nextItem = nextProcessableItem() else {
                return
            }

            do {
                let sentMessage = try await send(nextItem)
                items.removeAll { $0.id == nextItem.id }
                cleanupAttachments(for: nextItem)
                onSendSucceeded?(nextItem, sentMessage)
            } catch {
                let message = error.localizedDescription
                if isConnectivityError(error) {
                    updateState(for: nextItem.id, state: .waitingForConnection, failureMessage: nil)
                    return
                } else {
                    updateState(
                        for: nextItem.id,
                        state: .failed,
                        failureMessage: nextItem.kind == .media ? "Upload failed" : "Failed to send"
                    )
                }
            }
        }
    }

    private func nextProcessableItem() -> QueuedSpaceMessage? {
        items
            .sorted { $0.createdAt < $1.createdAt }
            .first {
                $0.state == .sending || $0.state == .uploading || $0.state == .waitingForConnection
            }
    }

    private func send(_ item: QueuedSpaceMessage) async throws -> SpaceMessage {
        switch item.kind {
        case .text:
            updateState(for: item.id, state: .sending, failureMessage: nil)
            return try await spaceService.sendTextMessage(
                in: item.space,
                text: item.text ?? "",
                linkPreview: item.linkPreview,
                spaceLinks: item.spaceLinks,
                replyContext: item.replyContext,
                messageID: item.id
            )
        case .media:
            updateState(for: item.id, state: .uploading, failureMessage: nil)
            let uploads = try item.attachments.map { attachment -> SpaceService.ImageAttachmentUpload in
                SpaceService.ImageAttachmentUpload(
                    data: try Data(contentsOf: mediaDirectoryURL.appendingPathComponent(attachment.dataFileName)),
                    previewImageData: try Data(contentsOf: mediaDirectoryURL.appendingPathComponent(attachment.previewFileName)),
                    mimeType: attachment.mimeType,
                    mediaCategory: attachment.mediaCategory
                )
            }
            if item.attachments.count == 1, let attachment = item.attachments.first, attachment.isVideo {
                let videoData = try Data(contentsOf: mediaDirectoryURL.appendingPathComponent(attachment.dataFileName))
                return try await spaceService.sendVideoMessage(
                    in: item.space,
                    videoData: videoData,
                    caption: item.caption,
                    mimeType: attachment.mimeType,
                    replyContext: item.replyContext,
                    messageID: item.id
                )
            }
            return try await spaceService.sendImageMessage(
                in: item.space,
                imageAttachments: uploads,
                caption: item.caption,
                replyContext: item.replyContext,
                messageID: item.id
            )
        }
    }

    private func updateState(for messageID: String, state: LocalMessageDeliveryState, failureMessage: String?) {
        guard let index = items.firstIndex(where: { $0.id == messageID }) else { return }
        items[index].state = state
        items[index].failureMessage = failureMessage
    }

    private func markActiveItemsWaitingForConnection() {
        var updatedItems = items
        for index in updatedItems.indices {
            if updatedItems[index].state == .sending || updatedItems[index].state == .uploading {
                updatedItems[index].state = .waitingForConnection
            }
        }
        items = updatedItems
    }

    private func persistAttachment(_ selection: ComposerMediaSelection, messageID: String) -> QueuedMessageAttachment {
        let attachmentID = UUID().uuidString
        let dataFileName = "\(messageID)-\(attachmentID)-data"
        let previewFileName = "\(messageID)-\(attachmentID)-preview"
        let dataURL = mediaDirectoryURL.appendingPathComponent(dataFileName)
        let previewURL = mediaDirectoryURL.appendingPathComponent(previewFileName)
        try? selection.data.write(to: dataURL, options: .atomic)
        try? selection.previewImageData.write(to: previewURL, options: .atomic)
        return QueuedMessageAttachment(
            id: attachmentID,
            dataFileName: dataFileName,
            previewFileName: previewFileName,
            mimeType: selection.mimeType,
            mediaCategory: selection.mediaCategory,
            isVideo: selection.isVideo
        )
    }

    private func cleanupAttachments(for item: QueuedSpaceMessage) {
        guard item.kind == .media else { return }
        for attachment in item.attachments {
            try? fileManager.removeItem(at: mediaDirectoryURL.appendingPathComponent(attachment.dataFileName))
            try? fileManager.removeItem(at: mediaDirectoryURL.appendingPathComponent(attachment.previewFileName))
        }
    }

    private func load() {
        guard
            let data = try? Data(contentsOf: persistenceURL),
            let decoded = try? JSONDecoder().decode([QueuedSpaceMessage].self, from: data)
        else {
            items = []
            return
        }
        items = decoded.map { item in
            var mutable = item
            if mutable.state == .sending || mutable.state == .uploading {
                mutable.state = .waitingForConnection
            }
            return mutable
        }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? fileManager.createDirectory(at: persistenceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: persistenceURL, options: .atomic)
    }

    private func isConnectivityError(_ error: Error) -> Bool {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            return true
        }
        let message = error.localizedDescription.lowercased()
        return message.contains("network") || message.contains("offline") || message.contains("connection")
    }
}

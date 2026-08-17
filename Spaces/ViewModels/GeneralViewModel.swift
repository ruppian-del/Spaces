import Combine
import FirebaseFirestore
import Foundation
import UIKit

struct ComposerMediaSelection {
    let id = UUID()
    let data: Data
    let previewImageData: Data
    let mimeType: String
    let mediaCategory: String
    let isVideo: Bool
}

@MainActor
final class GeneralViewModel: ObservableObject {
    @Published private(set) var messages: [SpaceMessage]
    @Published var composerText: String = ""
    @Published private(set) var isLoading = false
    @Published private(set) var isSending = false
    @Published private(set) var isDeletingMessageIDs: Set<String> = []
    @Published var errorMessage: String?
    @Published private(set) var secureAccessMessage: String?
    @Published var pendingMediaCategory: String = "photo"
    @Published private(set) var selectedComposerMediaItems: [ComposerMediaSelection] = []
    @Published private(set) var replyingToMessage: SpaceMessage?
    @Published private(set) var editingMessage: SpaceMessage?
    @Published var isSearchPresented = false
    @Published var searchText: String = "" {
        didSet {
            updateSearchMatches()
        }
    }
    @Published private(set) var searchMatchMessageIDs: [String] = []
    @Published private(set) var selectedSearchMatchIndex: Int?
    @Published private(set) var canPostMessages = false
    @Published private(set) var canUploadMedia = false
    @Published private(set) var canDeleteOthersContent = false
    @Published private(set) var typingParticipants: [TypingParticipant] = []
    @Published private(set) var hasSavedDraft = false
    @Published private(set) var composerLinkPreview: LinkPreviewData?
    @Published private(set) var composerSpaceLinks: [SpaceLinkAttachment] = []
    @Published private(set) var isLoadingLinkPreview = false

    let space: Space
    private let spaceService: SpaceService
    private let typingIndicatorService: TypingIndicatorService
    private let outbox: SpaceMessageOutbox
    private let draftStore: SpaceDraftStore
    private let linkPreviewService: LinkPreviewService
    private var listener: ListenerRegistration?
    private var reactionListeners: [String: ListenerRegistration] = [:]
    private var currentUserID: String?
    private var localPlaintextByMessageID: [String: String] = [:]
    private var pendingLocalMessagesByID: [String: SpaceMessage] = [:]
    private var baseMessages: [SpaceMessage] = []
    private var reactionsByMessageID: [String: [MessageReaction]] = [:]
    private var activeMediaSubmissionID: String?
    private var draftSaveTask: Task<Void, Never>?
    private var previewFetchTask: Task<Void, Never>?
    private var draftRestoreAttempted = false
    private var pendingDraftReplyContext: MessageReplyContext?
    private static let queuedMessageTimestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter
    }()

    init(
        space: Space,
        messages: [SpaceMessage]? = nil,
        spaceService: SpaceService? = nil,
        typingIndicatorService: TypingIndicatorService? = nil,
        outbox: SpaceMessageOutbox? = nil,
        draftStore: SpaceDraftStore? = nil,
        linkPreviewService: LinkPreviewService = .shared
    ) {
        self.space = space
        self.spaceService = spaceService ?? SpaceService()
        self.typingIndicatorService = typingIndicatorService ?? TypingIndicatorService()
        self.outbox = outbox ?? SpaceMessageOutbox(spaceService: self.spaceService)
        self.draftStore = draftStore ?? SpaceDraftStore()
        self.linkPreviewService = linkPreviewService
        self.messages = messages ?? []
        self.typingIndicatorService.onParticipantsChanged = { [weak self] participants in
            self?.typingParticipants = participants
        }
        self.typingIndicatorService.onError = { [weak self] message in
            self?.errorMessage = message
        }
        self.outbox.onItemsChanged = { [weak self] _ in
            self?.refreshMessages(self?.baseMessages ?? [])
            self?.handleOutboxStateChanges()
        }
        self.outbox.onSendSucceeded = { [weak self] queuedMessage, localMessage in
            guard let self else { return }
            let trimmedText = queuedMessage.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !trimmedText.isEmpty {
                let text = trimmedText
                self.localPlaintextByMessageID[localMessage.id] = text
            }
            self.pendingLocalMessagesByID[localMessage.id] = localMessage
            if self.activeMediaSubmissionID == queuedMessage.id {
                self.composerText = ""
                self.selectedComposerMediaItems = []
                self.composerLinkPreview = nil
                self.composerSpaceLinks = []
                self.isLoadingLinkPreview = false
                self.replyingToMessage = nil
                self.pendingDraftReplyContext = nil
                self.activeMediaSubmissionID = nil
                self.isSending = false
                self.typingIndicatorService.messageSent()
            }
            self.clearDraftIfSubmitted(queueMessageID: queuedMessage.id)
            self.refreshMessages(self.baseMessages)
        }
    }

    deinit {
        let listener = listener
        let reactionListeners = reactionListeners.values
        let typingIndicatorService = typingIndicatorService
        let draftSaveTask = draftSaveTask
        let previewFetchTask = previewFetchTask
        Task { @MainActor in
            draftSaveTask?.cancel()
            previewFetchTask?.cancel()
            listener?.remove()
            reactionListeners.forEach { $0.remove() }
            typingIndicatorService.stop()
        }
    }

    var canSend: Bool {
        let hasText = !composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasMedia = !selectedComposerMediaItems.isEmpty
        let hasLinks = !composerSpaceLinks.isEmpty
        guard !isSending else { return false }
        if hasMedia {
            return canUploadMedia
        }
        return (hasText || hasLinks) && canPostMessages
    }

    var selectedComposerUIImages: [UIImage] {
        selectedComposerMediaItems.compactMap { UIImage(data: $0.previewImageData) }
    }

    var hasSelectedComposerMedia: Bool {
        !selectedComposerMediaItems.isEmpty
    }

    var selectedComposerIsVideo: Bool {
        selectedComposerMediaItems.count == 1 && selectedComposerMediaItems.first?.isVideo == true
    }

    var selectedComposerMediaCategory: String {
        selectedComposerMediaItems.first?.mediaCategory ?? pendingMediaCategory
    }

    var hasReplyContext: Bool {
        replyingToMessage != nil
    }

    var isEditing: Bool {
        editingMessage != nil
    }

    var typingIndicatorText: String? {
        let names = typingParticipants.map(\.displayName)
        switch names.count {
        case 0:
            return nil
        case 1:
            return "\(names[0]) is typing…"
        case 2:
            return "\(names[0]) and \(names[1]) are typing…"
        default:
            return "\(names.count) people are typing…"
        }
    }

    func startListeningIfNeeded() {
        guard listener == nil else { return }
        currentUserID = spaceService.currentUserID()
        restoreDraftIfNeeded()
        isLoading = true
        typingIndicatorService.start(spaceID: space.id)
        Task {
            canPostMessages = await spaceService.canPerform(.postPings, in: space)
            canUploadMedia = await spaceService.canPerform(.uploadPhotosVideos, in: space)
            canDeleteOthersContent = await spaceService.canPerform(.deleteOthersContent, in: space)
        }
        listener = spaceService.listenToMessages(in: space) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let messages):
                self.refreshMessages(messages)
                self.secureAccessMessage = nil
                self.isLoading = false
            case .failure(let error):
                self.secureAccessMessage = nil
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }

    }

    func stopTypingIndicators() {
        flushDraftPersistence()
        typingIndicatorService.stop()
    }

    func sendMessage() async {
        let trimmed = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || !composerSpaceLinks.isEmpty else { return }
        let queuedID = outbox.enqueueText(
            space: space,
            text: trimmed,
            linkPreview: composerLinkPreview,
            spaceLinks: composerSpaceLinks,
            replyContext: activeReplyContext()
        )
        localPlaintextByMessageID[queuedID] = trimmed
        markDraftAsSubmitted(queuedMessageID: queuedID, text: composerText)
        composerText = ""
        composerLinkPreview = nil
        composerSpaceLinks = []
        isLoadingLinkPreview = false
        replyingToMessage = nil
        pendingDraftReplyContext = nil
        typingIndicatorService.messageSent()
        refreshMessages(baseMessages)
    }

    func selectComposerMedia(
        data: Data?,
        previewImageData: Data?,
        mimeType: String?,
        mediaCategory: String,
        isVideo: Bool
    ) {
        let hasMedia = data != nil
        let byteCount = data?.count ?? 0
        print("[GeneralViewModel][ComposerMedia] selected=\(hasMedia) byteCount=\(byteCount) mediaCategory=\(mediaCategory) isVideo=\(isVideo)")
        if mediaCategory == "gif", hasMedia {
            print("[GIF] Selected")
        }
        pendingMediaCategory = mediaCategory
        guard
            let data,
            let previewImageData,
            let mimeType
        else {
            selectedComposerMediaItems = []
            composerLinkPreview = nil
            composerSpaceLinks = []
            isLoadingLinkPreview = false
            return
        }
        editingMessage = nil
        composerLinkPreview = nil
        composerSpaceLinks = []
        isLoadingLinkPreview = false
        previewFetchTask?.cancel()
        selectedComposerMediaItems = [ComposerMediaSelection(
            data: data,
            previewImageData: previewImageData,
            mimeType: mimeType,
            mediaCategory: mediaCategory,
            isVideo: isVideo
        )]
        scheduleDraftPersistence()
    }

    func selectComposerMediaItems(_ selections: [ComposerMediaSelection]) {
        pendingMediaCategory = selections.first?.mediaCategory ?? pendingMediaCategory
        editingMessage = nil
        composerLinkPreview = nil
        composerSpaceLinks = []
        isLoadingLinkPreview = false
        previewFetchTask?.cancel()
        selectedComposerMediaItems = Array(selections.prefix(10))
        scheduleDraftPersistence()
    }

    func removeComposerMedia(id: UUID? = nil) {
        if let id {
            selectedComposerMediaItems.removeAll { $0.id == id }
        } else {
            selectedComposerMediaItems = []
        }
        if selectedComposerMediaItems.isEmpty {
            scheduleLinkPreviewFetch(for: composerText)
        }
        print("[GeneralViewModel][ComposerMedia] selected=\(!selectedComposerMediaItems.isEmpty) count=\(selectedComposerMediaItems.count) removed=true")
        scheduleDraftPersistence()
    }

    func beginReply(to message: SpaceMessage) {
        guard !message.deleted else { return }
        editingMessage = nil
        replyingToMessage = message
        scheduleDraftPersistence()
    }

    func cancelReply() {
        replyingToMessage = nil
        pendingDraftReplyContext = nil
        scheduleDraftPersistence()
    }

    func beginEditing(_ message: SpaceMessage) {
        guard canEdit(message), let text = message.text, !text.isEmpty else { return }
        replyingToMessage = nil
        selectedComposerMediaItems = []
        editingMessage = message
        composerText = text
        composerLinkPreview = message.linkPreview
        composerSpaceLinks = message.spaceLinks
        isLoadingLinkPreview = false
        typingIndicatorService.updateComposerText(text)
    }

    func cancelEditing() {
        editingMessage = nil
        composerText = ""
        typingIndicatorService.updateComposerText("")
        restoreDraftIfNeeded(force: true)
    }

    func presentSearch() {
        isSearchPresented = true
    }

    func dismissSearch() {
        isSearchPresented = false
        searchText = ""
    }

    func currentSearchMatchMessageID() -> String? {
        guard let selectedSearchMatchIndex, searchMatchMessageIDs.indices.contains(selectedSearchMatchIndex) else {
            return nil
        }
        return searchMatchMessageIDs[selectedSearchMatchIndex]
    }

    func selectNextSearchMatch() {
        guard !searchMatchMessageIDs.isEmpty else { return }
        let nextIndex = ((selectedSearchMatchIndex ?? -1) + 1) % searchMatchMessageIDs.count
        selectedSearchMatchIndex = nextIndex
    }

    func selectPreviousSearchMatch() {
        guard !searchMatchMessageIDs.isEmpty else { return }
        let previousIndex = ((selectedSearchMatchIndex ?? searchMatchMessageIDs.count) - 1 + searchMatchMessageIDs.count) % searchMatchMessageIDs.count
        selectedSearchMatchIndex = previousIndex
    }

    func sendComposer() async {
        guard !isSending else { return }

        if let editingMessage {
            await saveEditedMessage(editingMessage)
            return
        }

        if let selectedComposerMedia = selectedComposerMediaItems.first, !selectedComposerMediaItems.isEmpty {
            let trimmedCaption = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
            let caption = trimmedCaption.isEmpty ? nil : trimmedCaption
            if selectedComposerMediaItems.count == 1, selectedComposerMedia.isVideo {
                await sendVideo(
                    selectedComposerMedia.data,
                    caption: caption,
                    mimeType: selectedComposerMedia.mimeType
                )
            } else {
                await sendImages(
                    selectedComposerMediaItems,
                    caption: caption,
                )
            }
            return
        }

        if isLoadingLinkPreview {
            await previewFetchTask?.value
        }
        await sendMessage()
    }

    func sendImages(_ selections: [ComposerMediaSelection], caption: String?) async {
        guard !isSending else { return }
        guard !selections.isEmpty else { return }

        isSending = true
        let queuedID = outbox.enqueueMedia(
            space: space,
            selections: selections,
            caption: caption,
            replyContext: activeReplyContext()
        )
        activeMediaSubmissionID = queuedID
        markDraftAsSubmitted(queuedMessageID: queuedID, text: composerText)
        refreshMessages(baseMessages)
    }

    func sendVideo(_ videoData: Data, caption: String?, mimeType: String) async {
        guard !isSending else { return }

        isSending = true
        let selection = ComposerMediaSelection(
            data: videoData,
            previewImageData: selectedComposerMediaItems.first?.previewImageData ?? videoData,
            mimeType: mimeType,
            mediaCategory: "video",
            isVideo: true
        )
        let queuedID = outbox.enqueueMedia(
            space: space,
            selections: [selection],
            caption: caption,
            replyContext: activeReplyContext()
        )
        activeMediaSubmissionID = queuedID
        markDraftAsSubmitted(queuedMessageID: queuedID, text: composerText)
        refreshMessages(baseMessages)
    }

    func composerTextDidChange(_ text: String) {
        composerText = text
        typingIndicatorService.updateComposerText(text)
        scheduleLinkPreviewFetch(for: text)
        scheduleDraftPersistence()
    }

    func canDelete(_ message: SpaceMessage) -> Bool {
        guard !message.deleted else { return false }
        if message.senderId == currentUserID {
            return true
        }
        return canDeleteOthersContent
    }

    func canEdit(_ message: SpaceMessage) -> Bool {
        message.senderId == currentUserID && !message.deleted && message.type == .text && !message.hasMediaAttachments
    }

    func isDeleting(_ message: SpaceMessage) -> Bool {
        isDeletingMessageIDs.contains(message.id)
    }

    func deleteMessage(_ message: SpaceMessage) async {
        guard canDelete(message), !isDeleting(message) else { return }

        isDeletingMessageIDs.insert(message.id)
        defer { isDeletingMessageIDs.remove(message.id) }

        do {
            try await spaceService.deleteMessage(in: space, messageID: message.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func toggleReaction(for message: SpaceMessage, emoji: String) async {
        do {
            try await spaceService.toggleReaction(emoji, in: space, messageID: message.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func reactionOptions(for message: SpaceMessage) -> [String] {
        guard !message.deleted else { return [] }
        return ["👍", "❤️", "😂", "😮", "😢", "👎"]
    }

    private func refreshMessages(_ incoming: [SpaceMessage]) {
        let merged = mergeIncomingMessages(incoming)
        baseMessages = merged
        syncReactionListeners(for: merged)
        messages = applyReactions(to: merged)
        resolvePendingDraftReplyContext()
        updateSearchMatches()
    }

    private func mergeIncomingMessages(_ incoming: [SpaceMessage]) -> [SpaceMessage] {
        let mergedIncoming = incoming.map { message in
            let localPlaintextFallback = message.senderId == currentUserID ? localPlaintextByMessageID[message.id] : nil
            let usedLocalPlaintextFallback = localPlaintextFallback?.isEmpty == false
            logMessageRendering(
                messageID: message.id,
                senderID: message.senderId,
                encryptionVersion: message.encryptionVersion,
                hasCiphertext: message.encryptionVersion == "aes-gcm-v1",
                hasNonce: message.encryptionVersion == "aes-gcm-v1",
                usedLocalPlaintextFallback: usedLocalPlaintextFallback
            )
            guard let localPlaintextFallback, usedLocalPlaintextFallback else {
                return message
            }
            return SpaceMessage(
                id: message.id,
                spaceId: message.spaceId,
                senderId: message.senderId,
                senderName: message.senderName,
                senderEmoji: message.senderEmoji,
                type: message.type,
                encryptionVersion: message.encryptionVersion,
                deleted: message.deleted,
                text: localPlaintextFallback,
                media: message.media,
                mediaItems: message.mediaItems,
                createdAt: message.createdAt,
                updatedAt: message.updatedAt,
                timestamp: message.timestamp,
                isOutgoing: message.isOutgoing,
                status: message.status,
                deliveryStatus: message.deliveryStatus,
                isEdited: message.isEdited,
                editedAt: message.editedAt,
                replyContext: message.replyContext,
                linkPreview: message.linkPreview,
                spaceLinks: message.spaceLinks,
                reactions: reactionsByMessageID[message.id] ?? message.reactions
            )
        }

        let incomingIDs = Set(mergedIncoming.map(\.id))
        pendingLocalMessagesByID = pendingLocalMessagesByID.filter { !incomingIDs.contains($0.key) }
        let pendingMessages = Array(pendingLocalMessagesByID.values)
        let queuedMessages = outbox.items(for: space.id).map { projectQueuedMessage($0) }

        return (mergedIncoming + pendingMessages + queuedMessages).sorted { lhs, rhs in
            switch (lhs.createdAt, rhs.createdAt) {
            case let (left?, right?):
                return left < right
            case (.some, .none):
                return true
            case (.none, .some):
                return false
            case (.none, .none):
                return lhs.id < rhs.id
            }
        }
    }

    func retryQueuedMessage(_ messageID: String) {
        outbox.retry(messageID)
        if let queuedMessage = outbox.items(for: space.id).first(where: { $0.id == messageID }),
           queuedMessage.kind == .media {
            activeMediaSubmissionID = messageID
            isSending = true
        }
    }

    func deleteQueuedMessage(_ messageID: String) {
        outbox.delete(messageID)
        localPlaintextByMessageID[messageID] = nil
        pendingLocalMessagesByID[messageID] = nil
        clearDraftIfSubmitted(queueMessageID: messageID)
        if activeMediaSubmissionID == messageID {
            activeMediaSubmissionID = nil
            isSending = false
        }
        refreshMessages(baseMessages)
    }

    private func handleOutboxStateChanges() {
        guard let activeMediaSubmissionID,
              let queuedItem = outbox.items(for: space.id).first(where: { $0.id == activeMediaSubmissionID }) else {
            return
        }
        switch queuedItem.state {
        case .failed, .waitingForConnection:
            isSending = false
        case .sending, .uploading:
            isSending = true
        }
    }

    private func projectQueuedMessage(_ queuedMessage: QueuedSpaceMessage) -> SpaceMessage {
        let deliveryStatus: String = switch queuedMessage.state {
        case .sending:
            "Sending…"
        case .uploading:
            "Uploading…"
        case .waitingForConnection:
            "Waiting for connection…"
        case .failed:
            queuedMessage.kind == .media ? "Upload failed" : "Failed to send"
        }

        let mediaItems = queuedMessage.attachments.enumerated().map { index, attachment in
            let previewURL = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
                .appendingPathComponent("SpacesOutbox/attachments/\(attachment.previewFileName)")
            let previewData = previewURL.flatMap { try? Data(contentsOf: $0) }
            return SpaceMedia(
                id: "\(queuedMessage.id)_\(index)",
                spaceID: space.id,
                type: attachment.isVideo ? .video : ((attachment.mediaCategory == "gif") ? .gif : .image),
                mediaCategory: attachment.mediaCategory,
                mediaType: MediaType(rawValue: attachment.mediaCategory) ?? .photo,
                placeholderImageName: (MediaType(rawValue: attachment.mediaCategory) ?? .photo).defaultPlaceholderImageName,
                caption: queuedMessage.caption,
                senderName: "You",
                timestamp: Self.queuedMessageTimestampFormatter.string(from: queuedMessage.createdAt),
                localPreviewImageData: previewData
            )
        }

        return SpaceMessage(
            id: queuedMessage.id,
            spaceId: space.id,
            senderId: currentUserID,
            senderName: "You",
            senderEmoji: nil,
            type: queuedMessage.kind == .text ? .text : (mediaItems.first?.type ?? .image),
            encryptionVersion: "local-only",
            deleted: false,
            text: queuedMessage.text,
            media: mediaItems.first,
            mediaItems: mediaItems,
            createdAt: queuedMessage.createdAt,
            updatedAt: queuedMessage.createdAt,
            timestamp: Self.queuedMessageTimestampFormatter.string(from: queuedMessage.createdAt),
            isOutgoing: true,
            status: nil,
            deliveryStatus: deliveryStatus,
            isEdited: false,
            replyContext: queuedMessage.replyContext,
            linkPreview: queuedMessage.linkPreview,
            spaceLinks: queuedMessage.spaceLinks,
            reactions: [],
            localDeliveryState: queuedMessage.state,
            localFailureMessage: queuedMessage.failureMessage
        )
    }

    private func syncReactionListeners(for messages: [SpaceMessage]) {
        let validIDs = Set(messages.map(\.id))

        for staleID in Array(reactionListeners.keys) where !validIDs.contains(staleID) {
            reactionListeners[staleID]?.remove()
            reactionListeners[staleID] = nil
            reactionsByMessageID[staleID] = nil
        }

        for message in messages where reactionListeners[message.id] == nil {
            reactionListeners[message.id] = spaceService.listenToReactions(for: message.id, in: space) { [weak self] result in
                guard let self else { return }
                switch result {
                case .success(let reactions):
                    self.reactionsByMessageID[message.id] = reactions
                    self.messages = self.applyReactions(to: self.baseMessages)
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func applyReactions(to messages: [SpaceMessage]) -> [SpaceMessage] {
        messages.map { message in
            SpaceMessage(
                id: message.id,
                spaceId: message.spaceId,
                senderId: message.senderId,
                senderName: message.senderName,
                senderEmoji: message.senderEmoji,
                type: message.type,
                encryptionVersion: message.encryptionVersion,
                deleted: message.deleted,
                text: message.text,
                media: message.media,
                mediaItems: message.mediaItems,
                createdAt: message.createdAt,
                updatedAt: message.updatedAt,
                timestamp: message.timestamp,
                isOutgoing: message.isOutgoing,
                status: message.status,
                deliveryStatus: message.deliveryStatus,
                isEdited: message.isEdited,
                editedAt: message.editedAt,
                replyContext: message.replyContext,
                linkPreview: message.linkPreview,
                spaceLinks: message.spaceLinks,
                reactions: reactionsByMessageID[message.id] ?? message.reactions
            )
        }
    }

    private func saveEditedMessage(_ message: SpaceMessage) async {
        let trimmed = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (!trimmed.isEmpty || !composerSpaceLinks.isEmpty), canEdit(message) else { return }

        isSending = true
        defer { isSending = false }

        do {
            if isLoadingLinkPreview {
                await previewFetchTask?.value
            }
            let updatedMessage = try await spaceService.editTextMessage(
                in: space,
                messageID: message.id,
                newText: trimmed,
                linkPreview: composerLinkPreview,
                spaceLinks: composerSpaceLinks
            )
            localPlaintextByMessageID[message.id] = trimmed
            baseMessages = baseMessages.map { existing in
                existing.id == message.id ? updatedMessage : existing
            }
            messages = applyReactions(to: baseMessages)
            updateSearchMatches()
            composerText = ""
            composerLinkPreview = nil
            composerSpaceLinks = []
            isLoadingLinkPreview = false
            editingMessage = nil
            typingIndicatorService.messageSent()
            restoreDraftIfNeeded(force: true)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func activeReplyContext() -> MessageReplyContext? {
        guard let replyingToMessage else { return nil }
        return MessageReplyContext(
            messageId: replyingToMessage.id,
            senderName: replyingToMessage.senderName,
            type: replyType(for: replyingToMessage),
            preview: replyPreview(for: replyingToMessage)
        )
    }

    private func replyType(for message: SpaceMessage) -> String {
        switch message.type {
        case .video:
            return "video"
        case .file:
            return "file"
        case .image, .meme, .gif, .screenshot:
            return "image"
        default:
            return "text"
        }
    }

    private func replyPreview(for message: SpaceMessage) -> String {
        if message.deleted {
            return "Original message unavailable"
        }
        switch replyType(for: message) {
        case "video":
            return "🎥 Video"
        case "file":
            return "📄 File"
        case "image":
            return "📷 Photo"
        default:
            let preview = (message.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return preview.isEmpty ? "Message" : String(preview.prefix(80))
        }
    }

    private func logMessageRendering(
        messageID: String,
        senderID: String?,
        encryptionVersion: String,
        hasCiphertext: Bool,
        hasNonce: Bool,
        usedLocalPlaintextFallback: Bool
    ) {
        print(
            "[GeneralViewModel][Render] " +
            "messageId=\(messageID) " +
            "senderId=\(senderID ?? "nil") " +
            "currentUserUid=\(currentUserID ?? "nil") " +
            "encryptionVersion=\(encryptionVersion) " +
            "hasCiphertext=\(hasCiphertext) " +
            "hasNonce=\(hasNonce) " +
            "usedLocalPlaintextFallback=\(usedLocalPlaintextFallback)"
        )
    }

    private func updateSearchMatches() {
        let trimmedQuery = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let currentSelectedID = currentSearchMatchMessageID()
        guard !trimmedQuery.isEmpty else {
            searchMatchMessageIDs = []
            selectedSearchMatchIndex = nil
            return
        }

        let matches = messages.filter { message in
            !message.deleted && searchableContent(for: message).localizedCaseInsensitiveContains(trimmedQuery)
        }
        .map(\.id)

        searchMatchMessageIDs = matches

        if let currentSelectedID, let newIndex = matches.firstIndex(of: currentSelectedID) {
            selectedSearchMatchIndex = newIndex
        } else {
            selectedSearchMatchIndex = matches.isEmpty ? nil : 0
        }
    }

    private func searchableContent(for message: SpaceMessage) -> String {
        [
            message.senderName,
            message.text ?? "",
            message.replyContext?.preview ?? "",
            message.spaceLinks.map(\.searchableText).joined(separator: "\n")
        ]
        .joined(separator: "\n")
    }

    func discardDraft() {
        draftSaveTask?.cancel()
        previewFetchTask?.cancel()
        composerText = ""
        composerLinkPreview = nil
        composerSpaceLinks = []
        isLoadingLinkPreview = false
        replyingToMessage = nil
        pendingDraftReplyContext = nil
        selectedComposerMediaItems = []
        if let currentUserID {
            draftStore.clearDraft(for: currentUserID, spaceID: space.id)
        }
        hasSavedDraft = false
        typingIndicatorService.updateComposerText("")
    }

    func flushDraftPersistence() {
        draftSaveTask?.cancel()
        persistDraftNow()
    }

    private func scheduleLinkPreviewFetch(for text: String) {
        previewFetchTask?.cancel()

        guard selectedComposerMediaItems.isEmpty else {
            composerLinkPreview = nil
            isLoadingLinkPreview = false
            return
        }

        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = LinkPreviewService.firstURL(in: text) else {
            composerLinkPreview = nil
            isLoadingLinkPreview = false
            return
        }

        previewFetchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled else { return }

            if let cached = await self.linkPreviewService.cachedPreview(for: url) {
                guard self.composerText == text else { return }
                self.composerLinkPreview = cached
                self.isLoadingLinkPreview = false
                return
            }

            guard self.composerText == text else { return }
            self.isLoadingLinkPreview = true
            let preview = await self.linkPreviewService.preview(for: url)
            guard !Task.isCancelled, self.composerText == text else { return }
            self.composerLinkPreview = preview
            self.isLoadingLinkPreview = false
            self.scheduleDraftPersistence()
        }
    }

    private func scheduleDraftPersistence() {
        guard editingMessage == nil else { return }
        guard currentUserID != nil else { return }
        draftSaveTask?.cancel()
        draftSaveTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard let self else { return }
            self.persistDraftNow()
        }
    }

    private func persistDraftNow() {
        guard editingMessage == nil else { return }
        guard let currentUserID else { return }
        let text = composerText
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let replyContext = currentDraftReplyContext()

        guard !trimmed.isEmpty || !composerSpaceLinks.isEmpty else {
            draftStore.clearDraft(for: currentUserID, spaceID: space.id)
            hasSavedDraft = false
            return
        }

        let draft = SpaceDraft(
            spaceID: space.id,
            text: text,
            updatedAt: Date(),
            spaceLinks: composerSpaceLinks,
            replyToMessageID: replyContext?.messageId,
            replyToSenderName: replyContext?.senderName,
            replyToType: replyContext?.type,
            replyToPreview: replyContext?.preview,
            submittedQueuedMessageID: existingDraft()?.submittedQueuedMessageID
        )
        draftStore.save(draft, for: currentUserID)
        hasSavedDraft = true
    }

    private func existingDraft() -> SpaceDraft? {
        guard let currentUserID else { return nil }
        return draftStore.draft(for: currentUserID, spaceID: space.id)
    }

    private func markDraftAsSubmitted(queuedMessageID: String, text: String) {
        guard let currentUserID else { return }
        let replyContext = currentDraftReplyContext()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || replyContext != nil || !composerSpaceLinks.isEmpty else { return }

        let draft = SpaceDraft(
            spaceID: space.id,
            text: text,
            updatedAt: Date(),
            spaceLinks: composerSpaceLinks,
            replyToMessageID: replyContext?.messageId,
            replyToSenderName: replyContext?.senderName,
            replyToType: replyContext?.type,
            replyToPreview: replyContext?.preview,
            submittedQueuedMessageID: queuedMessageID
        )
        draftStore.save(draft, for: currentUserID)
        hasSavedDraft = !trimmed.isEmpty
    }

    private func clearDraftIfSubmitted(queueMessageID: String) {
        guard let currentUserID else { return }
        guard let draft = existingDraft(), draft.submittedQueuedMessageID == queueMessageID else { return }
        draftStore.clearDraft(for: currentUserID, spaceID: space.id)
        hasSavedDraft = false
    }

    private func restoreDraftIfNeeded(force: Bool = false) {
        guard let currentUserID else { return }
        guard force || !draftRestoreAttempted else { return }
        draftRestoreAttempted = true

        guard let draft = draftStore.draft(for: currentUserID, spaceID: space.id) else {
            hasSavedDraft = false
            return
        }

        hasSavedDraft = !(draft.previewText?.isEmpty ?? true)

        if let queuedMessageID = draft.submittedQueuedMessageID,
           outbox.items(for: space.id).contains(where: { $0.id == queuedMessageID }) {
            return
        }

        if editingMessage == nil {
            composerText = draft.text
            composerSpaceLinks = draft.spaceLinks
            scheduleLinkPreviewFetch(for: draft.text)
        }

        pendingDraftReplyContext = draft.replyToMessageID.map {
            MessageReplyContext(
                messageId: $0,
                senderName: draft.replyToSenderName ?? "Unknown",
                type: draft.replyToType ?? "text",
                preview: draft.replyToPreview ?? "Message"
            )
        }
        resolvePendingDraftReplyContext()
    }

    private func resolvePendingDraftReplyContext() {
        guard let pendingDraftReplyContext else { return }

        if let message = baseMessages.first(where: { $0.id == pendingDraftReplyContext.messageId && !$0.deleted }) ??
            messages.first(where: { $0.id == pendingDraftReplyContext.messageId && !$0.deleted }) {
            replyingToMessage = message
            self.pendingDraftReplyContext = nil
            scheduleDraftPersistence()
            return
        }

        guard !isLoading else { return }
        self.pendingDraftReplyContext = nil
        replyingToMessage = nil
        persistDraftNow()
    }

    private func currentDraftReplyContext() -> MessageReplyContext? {
        if let replyingToMessage {
            return MessageReplyContext(
                messageId: replyingToMessage.id,
                senderName: replyingToMessage.senderName,
                type: replyType(for: replyingToMessage),
                preview: replyPreview(for: replyingToMessage)
            )
        }
        return pendingDraftReplyContext
    }

    func addComposerSpaceLink(_ link: SpaceLinkAttachment) {
        guard !composerSpaceLinks.contains(where: { $0.id == link.id || ($0.moduleType == link.moduleType && $0.targetId == link.targetId) }) else {
            return
        }
        composerSpaceLinks.append(link)
        composerLinkPreview = nil
        isLoadingLinkPreview = false
        previewFetchTask?.cancel()
        scheduleDraftPersistence()
    }

    func removeComposerSpaceLink(id: String) {
        composerSpaceLinks.removeAll { $0.id == id }
        scheduleDraftPersistence()
    }

    var hasComposerAttachments: Bool {
        hasSelectedComposerMedia || !composerSpaceLinks.isEmpty
    }
}

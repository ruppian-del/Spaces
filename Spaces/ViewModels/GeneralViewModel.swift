import Combine
import FirebaseFirestore
import Foundation
import UIKit

struct ComposerMediaSelection {
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
    @Published private(set) var selectedComposerMedia: ComposerMediaSelection?
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

    let space: Space
    private let spaceService: SpaceService
    private var listener: ListenerRegistration?
    private var reactionListeners: [String: ListenerRegistration] = [:]
    private var currentUserID: String?
    private var localPlaintextByMessageID: [String: String] = [:]
    private var pendingLocalMessagesByID: [String: SpaceMessage] = [:]
    private var baseMessages: [SpaceMessage] = []
    private var reactionsByMessageID: [String: [MessageReaction]] = [:]

    init(space: Space, messages: [SpaceMessage]? = nil, spaceService: SpaceService? = nil) {
        self.space = space
        self.spaceService = spaceService ?? SpaceService()
        self.messages = messages ?? []
    }

    deinit {
        listener?.remove()
        reactionListeners.values.forEach { $0.remove() }
    }

    var canSend: Bool {
        let hasText = !composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasMedia = selectedComposerMedia != nil
        guard !isSending else { return false }
        if hasMedia {
            return canUploadMedia
        }
        return hasText && canPostMessages
    }

    var selectedComposerUIImage: UIImage? {
        guard let selectedComposerMedia else { return nil }
        return UIImage(data: selectedComposerMedia.previewImageData)
    }

    var selectedComposerIsVideo: Bool {
        selectedComposerMedia?.isVideo == true
    }

    var hasReplyContext: Bool {
        replyingToMessage != nil
    }

    var isEditing: Bool {
        editingMessage != nil
    }

    func startListeningIfNeeded() {
        guard listener == nil else { return }
        currentUserID = spaceService.currentUserID()
        isLoading = true
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

    func sendMessage() async {
        let trimmed = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard !isSending else { return }

        isSending = true
        defer { isSending = false }

        do {
            let localMessage = try await spaceService.sendTextMessage(
                in: space,
                text: trimmed,
                replyContext: activeReplyContext()
            )
            localPlaintextByMessageID[localMessage.id] = trimmed
            pendingLocalMessagesByID[localMessage.id] = localMessage
            refreshMessages(baseMessages)
            composerText = ""
            replyingToMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
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
        pendingMediaCategory = mediaCategory
        guard
            let data,
            let previewImageData,
            let mimeType
        else {
            selectedComposerMedia = nil
            return
        }
        editingMessage = nil
        selectedComposerMedia = ComposerMediaSelection(
            data: data,
            previewImageData: previewImageData,
            mimeType: mimeType,
            mediaCategory: mediaCategory,
            isVideo: isVideo
        )
    }

    func removeComposerMedia() {
        selectedComposerMedia = nil
        print("[GeneralViewModel][ComposerMedia] selected=false byteCount=0 removed=true")
    }

    func beginReply(to message: SpaceMessage) {
        guard !message.deleted else { return }
        editingMessage = nil
        replyingToMessage = message
    }

    func cancelReply() {
        replyingToMessage = nil
    }

    func beginEditing(_ message: SpaceMessage) {
        guard canEdit(message), let text = message.text, !text.isEmpty else { return }
        replyingToMessage = nil
        selectedComposerMedia = nil
        editingMessage = message
        composerText = text
    }

    func cancelEditing() {
        editingMessage = nil
        composerText = ""
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

        if let selectedComposerMedia {
            let trimmedCaption = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
            let caption = trimmedCaption.isEmpty ? nil : trimmedCaption
            if selectedComposerMedia.isVideo {
                await sendVideo(
                    selectedComposerMedia.data,
                    caption: caption,
                    mimeType: selectedComposerMedia.mimeType
                )
            } else {
                await sendImage(
                    selectedComposerMedia.data,
                    caption: caption,
                    mediaCategory: selectedComposerMedia.mediaCategory
                )
            }
            return
        }

        await sendMessage()
    }

    func sendImage(_ imageData: Data, caption: String?, mediaCategory: String) async {
        guard !isSending else { return }

        isSending = true
        defer { isSending = false }

        do {
            let localMessage = try await spaceService.sendImageMessage(
                in: space,
                imageData: imageData,
                caption: caption,
                mediaCategory: mediaCategory,
                replyContext: activeReplyContext()
            )
            pendingLocalMessagesByID[localMessage.id] = localMessage
            refreshMessages(baseMessages)
            composerText = ""
            selectedComposerMedia = nil
            replyingToMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func sendVideo(_ videoData: Data, caption: String?, mimeType: String) async {
        guard !isSending else { return }

        isSending = true
        defer { isSending = false }

        do {
            let localMessage = try await spaceService.sendVideoMessage(
                in: space,
                videoData: videoData,
                caption: caption,
                mimeType: mimeType,
                replyContext: activeReplyContext()
            )
            pendingLocalMessagesByID[localMessage.id] = localMessage
            refreshMessages(baseMessages)
            composerText = ""
            selectedComposerMedia = nil
            replyingToMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func canDelete(_ message: SpaceMessage) -> Bool {
        guard !message.deleted else { return false }
        if message.senderId == currentUserID {
            return true
        }
        return canDeleteOthersContent
    }

    func canEdit(_ message: SpaceMessage) -> Bool {
        message.senderId == currentUserID && !message.deleted && message.type == .text && message.media == nil
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
                createdAt: message.createdAt,
                updatedAt: message.updatedAt,
                timestamp: message.timestamp,
                isOutgoing: message.isOutgoing,
                status: message.status,
                deliveryStatus: message.deliveryStatus,
                isEdited: message.isEdited,
                editedAt: message.editedAt,
                replyContext: message.replyContext,
                reactions: reactionsByMessageID[message.id] ?? message.reactions
            )
        }

        let incomingIDs = Set(mergedIncoming.map(\.id))
        pendingLocalMessagesByID = pendingLocalMessagesByID.filter { !incomingIDs.contains($0.key) }
        let pendingMessages = Array(pendingLocalMessagesByID.values)

        return (mergedIncoming + pendingMessages).sorted { lhs, rhs in
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
                createdAt: message.createdAt,
                updatedAt: message.updatedAt,
                timestamp: message.timestamp,
                isOutgoing: message.isOutgoing,
                status: message.status,
                deliveryStatus: message.deliveryStatus,
                isEdited: message.isEdited,
                editedAt: message.editedAt,
                replyContext: message.replyContext,
                reactions: reactionsByMessageID[message.id] ?? message.reactions
            )
        }
    }

    private func saveEditedMessage(_ message: SpaceMessage) async {
        let trimmed = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, canEdit(message) else { return }

        isSending = true
        defer { isSending = false }

        do {
            let updatedMessage = try await spaceService.editTextMessage(in: space, messageID: message.id, newText: trimmed)
            localPlaintextByMessageID[message.id] = trimmed
            baseMessages = baseMessages.map { existing in
                existing.id == message.id ? updatedMessage : existing
            }
            messages = applyReactions(to: baseMessages)
            updateSearchMatches()
            composerText = ""
            editingMessage = nil
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
            message.replyContext?.preview ?? ""
        ]
        .joined(separator: "\n")
    }
}

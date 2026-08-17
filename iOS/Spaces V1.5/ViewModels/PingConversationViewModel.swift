import Combine
import FirebaseFirestore
import Foundation
import UIKit

@MainActor
final class PingConversationViewModel: ObservableObject {
    @Published private(set) var messages: [SpaceMessage] = []
    @Published var composerText: String = ""
    @Published private(set) var isLoading = false
    @Published private(set) var isSending = false
    @Published private(set) var isDeletingMessageIDs: Set<String> = []
    @Published var errorMessage: String?
    @Published private(set) var selectedComposerMedia: ComposerMediaSelection?
    @Published private(set) var replyingToMessage: SpaceMessage?
    @Published private(set) var editingMessage: SpaceMessage?

    let ping: Ping

    private let pingService: PingService
    private var listener: ListenerRegistration?
    private var reactionListeners: [String: ListenerRegistration] = [:]
    private var currentUserID: String?
    private var localPlaintextByMessageID: [String: String] = [:]
    private var pendingLocalMessagesByID: [String: SpaceMessage] = [:]
    private var baseMessages: [SpaceMessage] = []
    private var reactionsByMessageID: [String: [MessageReaction]] = [:]

    init(ping: Ping, pingService: PingService? = nil) {
        self.ping = ping
        self.pingService = pingService ?? PingService()
    }

    deinit {
        listener?.remove()
        reactionListeners.values.forEach { $0.remove() }
    }

    var canSend: Bool {
        let hasText = !composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasMedia = selectedComposerMedia != nil
        return (hasText || hasMedia) && !isSending
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

    var resolvedCurrentUserID: String? {
        currentUserID
    }

    func startListeningIfNeeded() {
        guard listener == nil else { return }
        currentUserID = pingService.currentUserID()
        isLoading = true
        listener = pingService.listenToMessages(in: ping) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let messages):
                self.refreshMessages(messages)
                self.isLoading = false
            case .failure(let error):
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }
    }

    func sendComposer() async {
        guard !isSending else { return }
        if let editingMessage {
            await saveEditedMessage(editingMessage)
        } else if let selectedComposerMedia {
            let trimmedCaption = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
            let caption = trimmedCaption.isEmpty ? nil : trimmedCaption
            if selectedComposerMedia.isVideo {
                await sendVideo(selectedComposerMedia.data, caption: caption, mimeType: selectedComposerMedia.mimeType)
            } else {
                await sendImage(
                    selectedComposerMedia.data,
                    caption: caption,
                    mediaCategory: selectedComposerMedia.mediaCategory,
                    previewImageData: selectedComposerMedia.previewImageData,
                    mimeType: selectedComposerMedia.mimeType
                )
            }
        } else {
            await sendMessage()
        }
    }

    func sendMessage() async {
        let trimmed = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard !isSending else { return }

        isSending = true
        defer { isSending = false }

        do {
            let localMessage = try await pingService.sendTextMessage(
                in: ping,
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

    func beginReply(to message: SpaceMessage) {
        guard !message.deleted else { return }
        editingMessage = nil
        replyingToMessage = message
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
        print("[PingConversationViewModel][ComposerMedia] selected=\(hasMedia) byteCount=\(byteCount) mediaCategory=\(mediaCategory) isVideo=\(isVideo)")
        if mediaCategory == "gif", hasMedia {
            print("[GIF] Selected")
        }
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
        print("[PingConversationViewModel][ComposerMedia] selected=false byteCount=0 removed=true")
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

    func canDelete(_ message: SpaceMessage) -> Bool {
        message.senderId == currentUserID && !message.deleted
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
            try await pingService.deleteMessage(in: ping, messageID: message.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func toggleReaction(for message: SpaceMessage, emoji: String) async {
        do {
            try await pingService.toggleReaction(emoji, in: ping, messageID: message.id)
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
    }

    private func mergeIncomingMessages(_ incoming: [SpaceMessage]) -> [SpaceMessage] {
        let mergedIncoming = incoming.map { message in
            guard
                let localPlaintextFallback = message.senderId == currentUserID ? localPlaintextByMessageID[message.id] : nil,
                !localPlaintextFallback.isEmpty
            else {
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
            reactionListeners[message.id] = pingService.listenToReactions(for: message.id, in: ping) { [weak self] result in
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
            let updatedMessage = try await pingService.editTextMessage(in: ping, messageID: message.id, newText: trimmed)
            localPlaintextByMessageID[message.id] = trimmed
            baseMessages = baseMessages.map { existing in
                existing.id == message.id ? updatedMessage : existing
            }
            messages = applyReactions(to: baseMessages)
            composerText = ""
            editingMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func sendImage(
        _ imageData: Data,
        caption: String?,
        mediaCategory: String,
        previewImageData: Data,
        mimeType: String
    ) async {
        guard !isSending else { return }

        isSending = true
        defer { isSending = false }

        do {
            let localMessage = try await pingService.sendImageMessage(
                in: ping,
                imageData: imageData,
                caption: caption,
                mediaCategory: mediaCategory,
                previewImageData: previewImageData,
                mimeType: mimeType,
                replyContext: activeReplyContext()
            )
            pendingLocalMessagesByID[localMessage.id] = localMessage
            refreshMessages(baseMessages)
            if mediaCategory == "gif" {
                print("[GIF] Message visible locally")
            }
            composerText = ""
            selectedComposerMedia = nil
            replyingToMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func sendVideo(_ videoData: Data, caption: String?, mimeType: String) async {
        guard !isSending else { return }

        isSending = true
        defer { isSending = false }

        do {
            let localMessage = try await pingService.sendVideoMessage(
                in: ping,
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

    private func activeReplyContext() -> MessageReplyContext? {
        guard let replyingToMessage else { return nil }
        return MessageReplyContext(
            messageId: replyingToMessage.id,
            senderName: replyingToMessage.senderName,
            type: "text",
            preview: replyPreview(for: replyingToMessage)
        )
    }

    private func replyPreview(for message: SpaceMessage) -> String {
        if message.deleted {
            return "Original message unavailable"
        }
        let preview = (message.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return preview.isEmpty ? "Message" : String(preview.prefix(80))
    }
}

import SwiftUI
@preconcurrency import PhotosUI
import UniformTypeIdentifiers
import AVFoundation

fileprivate enum GeneralAttachmentAction {
    case camera
    case link
    case photos
    case gifs
}

fileprivate enum GeneralLinkedDestination: Hashable, Identifiable {
    case announcements(String)
    case polls(String)
    case files(String)
    case events(String)
    case rooms(String)
    case media(String)
    case lists(String)
    case notes(String)

    var id: String {
        switch self {
        case .announcements(let id): "announcements:\(id)"
        case .polls(let id): "polls:\(id)"
        case .files(let id): "files:\(id)"
        case .events(let id): "events:\(id)"
        case .rooms(let id): "rooms:\(id)"
        case .media(let id): "media:\(id)"
        case .lists(let id): "lists:\(id)"
        case .notes(let id): "notes:\(id)"
        }
    }
}

fileprivate struct GeneralMediaPickerConfiguration: Identifiable {
    enum FilterKind {
        case imagesOnly
        case imagesAndVideos
    }

    let id: String
    let filterKind: FilterKind
    let mediaCategory: String
    let allowsVideos: Bool
    let selectionLimit: Int

    static let photosAndVideos = GeneralMediaPickerConfiguration(
        id: "photos-and-videos",
        filterKind: .imagesAndVideos,
        mediaCategory: "photo",
        allowsVideos: true,
        selectionLimit: 10
    )
}

struct GeneralView: View {
    @StateObject private var viewModel: GeneralViewModel
    @FocusState private var isComposerFocused: Bool
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedMedia: SpaceMedia?
    @State private var activeMediaPickerConfiguration: GeneralMediaPickerConfiguration?
    @State private var isGiphyPickerPresented = false
    @State private var isAttachmentMenuPresented = false
    @State private var attachmentPlaceholderMessage: String?
    @State private var pendingAttachmentAction: GeneralAttachmentAction?
    @State private var pendingDeleteMessage: SpaceMessage?
    @State private var highlightedMessageID: String?
    @State private var isSpaceLinkModulePickerPresented = false
    @State private var activeSpaceLinkModule: SpaceLinkModuleDescriptor?
    @State private var activeLinkedDestination: GeneralLinkedDestination?
    private let encryptedMediaService = EncryptedMediaService()
    private let spaceLinkRegistry = SpaceLinkRegistry()

    init(space: Space) {
        _viewModel = StateObject(wrappedValue: GeneralViewModel(space: space))
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    spaceHeader

                    if let secureAccessMessage = viewModel.secureAccessMessage {
                        Text(secureAccessMessage)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(
                                RoundedRectangle(cornerRadius: 20, style: .continuous)
                                    .fill(Color(.secondarySystemBackground))
                            )
                    }

                    if viewModel.isSearchPresented {
                        conversationSearchBar
                    }

                    LazyVStack(spacing: 0) {
                        ForEach(Array(visibleMessages.enumerated()), id: \.element.id) { index, message in
                            messageRow(message, at: index, proxy: proxy)
                                .padding(.bottom, spacingAfterMessage(at: index))
                                .id(message.id)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)
            }
            .simultaneousGesture(
                TapGesture().onEnded {
                    isComposerFocused = false
                }
            )
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Space Pings")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                composerBar
                    .background(.ultraThinMaterial)
            }
            .task {
                viewModel.startListeningIfNeeded()
                scrollToBottom(proxy: proxy, animated: false)
            }
            .onDisappear {
                viewModel.flushDraftPersistence()
                viewModel.stopTypingIndicators()
            }
            .onChange(of: scenePhase) { phase in
                if phase == .background {
                    viewModel.flushDraftPersistence()
                }
            }
            .onChange(of: viewModel.messages) { _ in
                if viewModel.isSearchPresented, let matchID = viewModel.currentSearchMatchMessageID() {
                    highlightAndScroll(to: matchID, proxy: proxy)
                } else {
                    scrollToBottom(proxy: proxy, animated: true)
                }
            }
            .onChange(of: viewModel.selectedSearchMatchIndex) { _ in
                guard let matchID = viewModel.currentSearchMatchMessageID() else { return }
                highlightAndScroll(to: matchID, proxy: proxy)
            }
            .fullScreenCover(item: $selectedMedia) { media in
                MediaViewerPlaceholderView(space: viewModel.space, media: media)
            }
            .alert("Attachment", isPresented: Binding(
                get: { attachmentPlaceholderMessage != nil },
                set: { if !$0 { attachmentPlaceholderMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(attachmentPlaceholderMessage ?? "")
            }
            .alert("Delete this message?", isPresented: Binding(
                get: { pendingDeleteMessage != nil },
                set: { if !$0 { pendingDeleteMessage = nil } }
            )) {
                Button("Cancel", role: .cancel) {
                    pendingDeleteMessage = nil
                }
                Button("Delete", role: .destructive) {
                    guard let message = pendingDeleteMessage else { return }
                    pendingDeleteMessage = nil
                    Task {
                        await viewModel.deleteMessage(message)
                    }
                }
            } message: {
                Text("This will remove it from the conversation for everyone in this Space.")
            }
            .sheet(isPresented: $isAttachmentMenuPresented) {
                if #available(iOS 16.0, *) {
                    AttachmentMenuSheet { action in
                        dismissAttachmentSheet(andPerform: action)
                    }
                    .presentationDetents([.medium])
                    .presentationDragIndicator(.visible)
                } else {
                    AttachmentMenuSheet { action in
                        dismissAttachmentSheet(andPerform: action)
                    }
                }
            }
            .sheet(item: $activeMediaPickerConfiguration) { configuration in
                LegacyMediaPicker(configuration: configuration) { selections in
                    if selections.isEmpty {
                        viewModel.selectComposerMedia(
                            data: nil,
                            previewImageData: nil,
                            mimeType: nil,
                            mediaCategory: configuration.mediaCategory,
                            isVideo: false
                        )
                    } else if selections.count == 1, let selection = selections.first {
                        viewModel.selectComposerMedia(
                            data: selection.data,
                            previewImageData: selection.previewImageData,
                            mimeType: selection.mimeType,
                            mediaCategory: selection.mediaCategory,
                            isVideo: selection.isVideo
                        )
                    } else {
                        viewModel.selectComposerMediaItems(
                            selections.map { selection in
                                ComposerMediaSelection(
                                    data: selection.data,
                                    previewImageData: selection.previewImageData,
                                    mimeType: selection.mimeType,
                                    mediaCategory: selection.mediaCategory,
                                    isVideo: selection.isVideo
                                )
                            }
                        )
                    }
                }
            }
            .sheet(isPresented: $isGiphyPickerPresented) {
                GiphyPickerView { selection in
                    viewModel.selectComposerMedia(
                        data: selection?.data,
                        previewImageData: selection?.previewImageData,
                        mimeType: selection?.mimeType,
                        mediaCategory: selection?.mediaCategory ?? "gif",
                        isVideo: selection?.isVideo == true
                    )
                }
            }
            .sheet(isPresented: $isSpaceLinkModulePickerPresented) {
                SpaceLinkModulePickerSheet(
                    modules: spaceLinkRegistry.availableModules(in: viewModel.space),
                    onSelect: { module in
                        isSpaceLinkModulePickerPresented = false
                        activeSpaceLinkModule = module
                    }
                )
            }
            .sheet(item: $activeSpaceLinkModule) { module in
                SpaceLinkItemPickerSheet(
                    space: viewModel.space,
                    module: module,
                    registry: spaceLinkRegistry,
                    onSelect: { item in
                        viewModel.addComposerSpaceLink(item.attachment)
                        activeSpaceLinkModule = nil
                        isComposerFocused = true
                    },
                    onError: { message in
                        attachmentPlaceholderMessage = message
                    }
                )
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button {
                            viewModel.presentSearch()
                        } label: {
                            Label("Search", systemImage: "magnifyingglass")
                        }

                        if viewModel.hasSavedDraft {
                            Button(role: .destructive) {
                                viewModel.discardDraft()
                            } label: {
                                Label("Discard Draft", systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .background(linkNavigationLinks)
        }
    }

    private var visibleMessages: [SpaceMessage] {
        viewModel.messages.filter { !$0.deleted }
    }

    private var spaceHeader: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(hex: viewModel.space.tintHex).opacity(0.16))
                    .frame(width: 52, height: 52)

                SpaceIconView(
                    emoji: viewModel.space.emoji,
                    tintHex: viewModel.space.tintHex,
                    font: .system(size: 24, weight: .semibold)
                )
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(viewModel.space.name)
                    .font(.headline)
                Text(viewModel.space.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
    }

    private var composerBar: some View {
        VStack(alignment: .leading, spacing: 10) {
            typingIndicatorRow

            if let editingMessage = viewModel.editingMessage {
                editComposerPreview(for: editingMessage)
            }

            if let replyingToMessage = viewModel.replyingToMessage {
                replyComposerPreview(for: replyingToMessage)
            }

            if viewModel.isLoadingLinkPreview || viewModel.composerLinkPreview != nil {
                composerLinkPreviewRow
            }

            if !viewModel.composerSpaceLinks.isEmpty {
                composerSpaceLinksRow
            }

            if viewModel.hasSelectedComposerMedia {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(Array(zip(viewModel.selectedComposerMediaItems, viewModel.selectedComposerUIImages)), id: \.0.id) { item, image in
                            ZStack(alignment: .topTrailing) {
                                ZStack {
                                    Image(uiImage: image)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 72, height: 72)
                                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                                    if item.isVideo {
                                        Image(systemName: "play.circle.fill")
                                            .font(.system(size: 28))
                                            .foregroundStyle(.white)
                                            .shadow(radius: 3)
                                    }
                                }

                                Button {
                                    viewModel.removeComposerMedia(id: item.id)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.title3)
                                        .foregroundStyle(.white, .black.opacity(0.35))
                                        .padding(4)
                                }
                                .buttonStyle(.plain)
                            }
                        }

                        VStack(alignment: .leading, spacing: 4) {
                            Text(selectedComposerTitle)
                                .font(.subheadline.weight(.semibold))
                            Text(selectedComposerSubtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .frame(width: 150, alignment: .leading)
                    }
                    .padding(.horizontal, 14)
                }
            }

            HStack(alignment: .bottom, spacing: 10) {
                Button {
                    isAttachmentMenuPresented = true
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 36, height: 36)
                        .background(
                            Circle()
                                .fill(Color(.secondarySystemBackground))
                        )
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .disabled(viewModel.isSending || (!viewModel.canUploadMedia && spaceLinkRegistry.availableModules(in: viewModel.space).isEmpty))

                TextField(
                    viewModel.isEditing
                        ? "Edit message"
                        : (!viewModel.hasComposerAttachments ? "Message" : "Add a caption..."),
                    text: Binding(
                        get: { viewModel.composerText },
                        set: { viewModel.composerTextDidChange($0) }
                    )
                )
                    .focused($isComposerFocused)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .fill(Color(.secondarySystemBackground))
                    )

                Group {
                    if viewModel.canSend {
                        Button {
                            Task {
                                await viewModel.sendComposer()
                            }
                        } label: {
                            Image(systemName: "arrow.up.circle.fill")
                                .font(.system(size: 30))
                                .foregroundStyle(Color.accentColor)
                        }
                        .buttonStyle(.plain)
                    } else {
                        Button {
                            attachmentPlaceholderMessage = "Voice messages are not ready yet."
                        } label: {
                            Image(systemName: "mic.fill")
                                .font(.system(size: 18, weight: .semibold))
                                .frame(width: 36, height: 36)
                                .background(
                                    Circle()
                                        .fill(Color(.secondarySystemBackground))
                                )
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.isSending)
                    }
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var typingIndicatorRow: some View {
        ZStack(alignment: .leading) {
            if let typingIndicatorText = viewModel.typingIndicatorText {
                Text(typingIndicatorText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .frame(maxWidth: .infinity, minHeight: 18, alignment: .leading)
        .animation(.easeInOut(duration: 0.18), value: viewModel.typingIndicatorText)
    }

    @ViewBuilder
    private var composerLinkPreviewRow: some View {
        if viewModel.isLoadingLinkPreview && viewModel.composerLinkPreview == nil {
            HStack(spacing: 10) {
                ProgressView()
                    .controlSize(.small)
                Text("Loading preview...")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
        } else if let preview = viewModel.composerLinkPreview {
            HStack(alignment: .top, spacing: 10) {
                if let imageData = preview.imageData, let image = UIImage(data: imageData) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(preview.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    if let summary = preview.summary, !summary.isEmpty {
                        Text(summary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }

                    Text(preview.domain)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(.secondarySystemBackground))
            )
            .padding(.horizontal, 14)
        }
    }

    @ViewBuilder
    private func messageRow(_ message: SpaceMessage, at index: Int, proxy: ScrollViewProxy) -> some View {
        MessageBubbleView(
            message: message,
            onTapMedia: handleTapMedia,
            onTapSpaceLink: handleTapSpaceLink,
            onDelete: deleteAction(for: message),
            onReply: replyAction(for: message),
            onEdit: editAction(for: message),
            replyPresentation: replyPresentation(for: message),
            onTapReplyPreview: tapReplyPreviewAction(for: message, proxy: proxy),
            onCopyText: copyTextAction(for: message),
            onSaveMedia: saveMediaAction(for: message),
            onRetryFailedMessage: retryFailedMessageAction(for: message),
            onDeleteFailedMessage: deleteFailedMessageAction(for: message),
            reactionOptions: viewModel.reactionOptions(for: message),
            onToggleReaction: toggleReactionAction(for: message),
            showsSenderName: shouldShowSenderName(for: message, at: index),
            isHighlighted: highlightedMessageID == message.id,
            searchQuery: viewModel.searchText
        )
    }

    private var composerSpaceLinksRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(viewModel.composerSpaceLinks) { link in
                    HStack(spacing: 8) {
                        Image(systemName: link.icon)
                            .font(.caption.weight(.semibold))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(link.title)
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                            Text(link.subtitle?.nilIfEmpty ?? link.moduleType.title)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Button {
                            viewModel.removeComposerSpaceLink(id: link.id)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(Color(.secondarySystemBackground))
                    )
                }
            }
            .padding(.horizontal, 14)
        }
    }

    private func shouldShowSenderName(for message: SpaceMessage, at index: Int) -> Bool {
        guard !message.isOutgoing else { return false }
        guard index > 0 else { return true }
        let previousMessage = visibleMessages[index - 1]
        return previousMessage.senderId != message.senderId
            || previousMessage.senderName != message.senderName
            || previousMessage.isOutgoing != message.isOutgoing
    }

    private func spacingAfterMessage(at index: Int) -> CGFloat {
        guard index < visibleMessages.count - 1 else { return 0 }

        let current = visibleMessages[index]
        let next = visibleMessages[index + 1]
        let currentIsMedia = current.hasMediaAttachments
        let nextIsMedia = next.hasMediaAttachments

        let baseSpacing: CGFloat
        switch (currentIsMedia, nextIsMedia) {
        case (false, false):
            baseSpacing = 12
        case (false, true):
            baseSpacing = 18
        case (true, false):
            baseSpacing = 20
        case (true, true):
            baseSpacing = 24
        }

        let sameSender = current.senderId == next.senderId
            && current.senderName == next.senderName
            && current.isOutgoing == next.isOutgoing

        guard sameSender else { return baseSpacing }

        switch (currentIsMedia, nextIsMedia) {
        case (false, false):
            return 10
        case (false, true):
            return 16
        case (true, false):
            return 18
        case (true, true):
            return 20
        }
    }

    private func retryFailedMessageAction(for message: SpaceMessage) -> (() -> Void)? {
        guard message.localDeliveryState == .failed else { return nil }
        return {
            viewModel.retryQueuedMessage(message.id)
        }
    }

    private func deleteFailedMessageAction(for message: SpaceMessage) -> (() -> Void)? {
        guard message.localDeliveryState == .failed else { return nil }
        return {
            viewModel.deleteQueuedMessage(message.id)
        }
    }

    private func handleTapMedia(_ media: SpaceMedia) {
        selectedMedia = media
    }

    private func deleteAction(for message: SpaceMessage) -> (() -> Void)? {
        guard viewModel.canDelete(message) else { return nil }
        return {
            pendingDeleteMessage = message
        }
    }

    private func replyAction(for message: SpaceMessage) -> (() -> Void) {
        {
            viewModel.beginReply(to: message)
            isComposerFocused = true
        }
    }

    private func editAction(for message: SpaceMessage) -> (() -> Void)? {
        guard viewModel.canEdit(message) else { return nil }
        return {
            viewModel.beginEditing(message)
            isComposerFocused = true
        }
    }

    private func copyTextAction(for message: SpaceMessage) -> (() -> Void)? {
        guard let text = message.text, !text.isEmpty else { return nil }
        return {
            UIPasteboard.general.string = text
            attachmentPlaceholderMessage = "Message copied."
        }
    }

    private func saveMediaAction(for message: SpaceMessage) -> (() -> Void)? {
        guard message.hasMediaAttachments else { return nil }
        return {
            Task {
                await saveMedia(from: message)
            }
        }
    }

    private func toggleReactionAction(for message: SpaceMessage) -> ((String) -> Void) {
        { emoji in
            Task {
                await viewModel.toggleReaction(for: message, emoji: emoji)
            }
        }
    }

    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool) {
        guard let lastID = visibleMessages.last?.id else { return }
        if animated {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(lastID, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(lastID, anchor: .bottom)
        }
    }

    private var conversationSearchBar: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)

                    TextField("Search messages", text: $viewModel.searchText)
                        .textFieldStyle(.plain)

                    if !viewModel.searchText.isEmpty {
                        Button {
                            viewModel.searchText = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(.secondarySystemBackground))
                )

                Button("Done") {
                    viewModel.dismissSearch()
                }
                .font(.subheadline.weight(.semibold))
            }

            HStack(spacing: 12) {
                Text(searchCountText)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()

                Button {
                    viewModel.selectPreviousSearchMatch()
                } label: {
                    Image(systemName: "chevron.up")
                }
                .buttonStyle(.plain)
                .disabled(viewModel.searchMatchMessageIDs.isEmpty)

                Button {
                    viewModel.selectNextSearchMatch()
                } label: {
                    Image(systemName: "chevron.down")
                }
                .buttonStyle(.plain)
                .disabled(viewModel.searchMatchMessageIDs.isEmpty)
            }
        }
    }

    private var searchCountText: String {
        let total = viewModel.searchMatchMessageIDs.count
        guard total > 0, let selected = viewModel.selectedSearchMatchIndex else {
            return viewModel.searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Search this conversation" : "No matches"
        }
        return "\(selected + 1) of \(total)"
    }

    private func replyComposerPreview(for message: SpaceMessage) -> some View {
        let preview = replyPreviewText(for: message)
        return HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 999, style: .continuous)
                .fill(Color.accentColor)
                .frame(width: 3)

            VStack(alignment: .leading, spacing: 4) {
                Text("Replying to \(message.senderName)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(preview)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
            }

            Spacer()

            Button {
                viewModel.cancelReply()
            } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(Color(.secondarySystemBackground)))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
    }

    private func editComposerPreview(for message: SpaceMessage) -> some View {
        let preview = (message.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 999, style: .continuous)
                .fill(Color.accentColor)
                .frame(width: 3)

            VStack(alignment: .leading, spacing: 4) {
                Text("Editing message")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(preview.isEmpty ? "Message" : "\"\(preview)\"")
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
            }

            Spacer()

            Button {
                viewModel.cancelEditing()
            } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(Color(.secondarySystemBackground)))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
    }

    private func replyPresentation(for message: SpaceMessage) -> MessageReplyPresentation? {
        guard let replyContext = message.replyContext else { return nil }
        let originalMessage = viewModel.messages.first(where: { $0.id == replyContext.messageId })
        return MessageReplyPresentation(
            senderName: replyContext.senderName,
            preview: originalMessage?.deleted == true ? "Original message unavailable" : replyContext.preview,
            isUnavailable: originalMessage?.deleted == true
        )
    }

    private func tapReplyPreviewAction(for message: SpaceMessage, proxy: ScrollViewProxy) -> (() -> Void)? {
        guard let replyContext = message.replyContext else { return nil }
        guard let originalMessage = viewModel.messages.first(where: { $0.id == replyContext.messageId }), !originalMessage.deleted else {
            return nil
        }
        return {
            withAnimation(.easeInOut(duration: 0.22)) {
                proxy.scrollTo(originalMessage.id, anchor: .center)
            }
            highlightedMessageID = originalMessage.id
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                if highlightedMessageID == originalMessage.id {
                    highlightedMessageID = nil
                }
            }
        }
    }

    private func replyPreviewText(for message: SpaceMessage) -> String {
        switch message.type {
        case .video:
            return "🎥 Video"
        case .file:
            return "📄 File"
        case .image, .meme, .gif, .screenshot:
            return "📷 Photo"
        default:
            let preview = (message.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return preview.isEmpty ? "Message" : String(preview.prefix(80))
        }
    }

    private func highlightAndScroll(to messageID: String, proxy: ScrollViewProxy) {
        guard visibleMessages.contains(where: { $0.id == messageID }) else { return }
        withAnimation(.easeInOut(duration: 0.22)) {
            proxy.scrollTo(messageID, anchor: .center)
        }
        highlightedMessageID = messageID
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            if highlightedMessageID == messageID {
                highlightedMessageID = nil
            }
        }
    }

    private func dismissAttachmentSheet(andPerform action: GeneralAttachmentAction) {
        pendingAttachmentAction = action
        isAttachmentMenuPresented = false

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            guard let pendingAttachmentAction else { return }
            handleAttachmentAction(pendingAttachmentAction)
            self.pendingAttachmentAction = nil
        }
    }

    private func handleAttachmentAction(_ action: GeneralAttachmentAction) {
        switch action {
        case .camera:
            attachmentPlaceholderMessage = "Camera capture will be added in a future update."
        case .link:
            isSpaceLinkModulePickerPresented = true
        case .photos:
            activeMediaPickerConfiguration = .photosAndVideos
        case .gifs:
            isGiphyPickerPresented = true
        }
    }

    private func handleTapSpaceLink(_ link: SpaceLinkAttachment) {
        switch link.moduleType {
        case .announcements:
            activeLinkedDestination = .announcements(link.targetId)
        case .polls:
            activeLinkedDestination = .polls(link.targetId)
        case .files:
            activeLinkedDestination = .files(link.targetId)
        case .events:
            activeLinkedDestination = .events(link.targetId)
        case .rooms:
            activeLinkedDestination = .rooms(link.targetId)
        case .media:
            activeLinkedDestination = .media(link.targetId)
        case .lists:
            activeLinkedDestination = .lists(link.targetId)
        case .notes:
            activeLinkedDestination = .notes(link.targetId)
        }
    }

    private func saveMedia(from message: SpaceMessage) async {
        guard let media = message.primaryMedia else { return }

        do {
            if media.type == .video {
                let url = try await encryptedMediaService.temporaryMediaURL(for: media)
                defer { try? FileManager.default.removeItem(at: url) }
                try await encryptedMediaService.saveVideoToPhotos(fileURL: url)
                attachmentPlaceholderMessage = "Video saved to Photos."
            } else {
                let data = try await encryptedMediaService.fullData(for: media)
                guard let image = UIImage(data: data) else {
                    attachmentPlaceholderMessage = "Unable to save this media."
                    return
                }
                try await encryptedMediaService.saveImageToPhotos(image)
                attachmentPlaceholderMessage = "Image saved to Photos."
            }
        } catch {
            attachmentPlaceholderMessage = error.localizedDescription
        }
    }

    private var selectedComposerTitle: String {
        let selectedCount = viewModel.selectedComposerMediaItems.count
        if viewModel.selectedComposerIsVideo {
            return "Selected video"
        }
        if selectedCount == 1 {
            return "1 selected image"
        }
        return "\(selectedCount) selected images"
    }

    private var selectedComposerSubtitle: String {
        return "Add an optional caption below."
    }

    private var linkNavigationLinks: some View {
        Group {
            NavigationLink(
                destination: announcementsLinkedDestinationView,
                isActive: announcementsDestinationBinding
            ) { EmptyView() }
            NavigationLink(
                destination: pollLinkedDestinationView,
                isActive: pollDestinationBinding
            ) { EmptyView() }
            NavigationLink(
                destination: filesLinkedDestinationView,
                isActive: filesDestinationBinding
            ) { EmptyView() }
            NavigationLink(
                destination: eventsLinkedDestinationView,
                isActive: eventsDestinationBinding
            ) { EmptyView() }
            NavigationLink(destination: roomsLinkedDestinationView, isActive: roomsDestinationBinding) { EmptyView() }
            NavigationLink(destination: mediaLinkedDestinationView, isActive: mediaDestinationBinding) { EmptyView() }
            NavigationLink(destination: listsLinkedDestinationView, isActive: listsDestinationBinding) { EmptyView() }
            NavigationLink(destination: notesLinkedDestinationView, isActive: notesDestinationBinding) { EmptyView() }
        }
        .hidden()
    }

    private var pollDestinationBinding: Binding<Bool> {
        Binding(
            get: { if case .polls = activeLinkedDestination { return true } else { return false } },
            set: { if !$0 { activeLinkedDestination = nil } }
        )
    }

    private var announcementsDestinationBinding: Binding<Bool> {
        Binding(
            get: { if case .announcements = activeLinkedDestination { return true } else { return false } },
            set: { if !$0 { activeLinkedDestination = nil } }
        )
    }

    private var filesDestinationBinding: Binding<Bool> {
        Binding(
            get: { if case .files = activeLinkedDestination { return true } else { return false } },
            set: { if !$0 { activeLinkedDestination = nil } }
        )
    }

    private var eventsDestinationBinding: Binding<Bool> {
        Binding(
            get: { if case .events = activeLinkedDestination { return true } else { return false } },
            set: { if !$0 { activeLinkedDestination = nil } }
        )
    }
    private var roomsDestinationBinding: Binding<Bool> {
        Binding(get: { if case .rooms = activeLinkedDestination { true } else { false } }, set: { if !$0 { activeLinkedDestination = nil } })
    }
    private var mediaDestinationBinding: Binding<Bool> {
        Binding(get: { if case .media = activeLinkedDestination { true } else { false } }, set: { if !$0 { activeLinkedDestination = nil } })
    }
    private var listsDestinationBinding: Binding<Bool> {
        Binding(get: { if case .lists = activeLinkedDestination { true } else { false } }, set: { if !$0 { activeLinkedDestination = nil } })
    }
    private var notesDestinationBinding: Binding<Bool> {
        Binding(get: { if case .notes = activeLinkedDestination { true } else { false } }, set: { if !$0 { activeLinkedDestination = nil } })
    }

    @ViewBuilder
    private var announcementsLinkedDestinationView: some View {
        if case .announcements(let announcementID) = activeLinkedDestination {
            AnnouncementsView(space: viewModel.space, initialAnnouncementID: announcementID)
        } else {
            EmptyView()
        }
    }

    @ViewBuilder
    private var pollLinkedDestinationView: some View {
        if case .polls(let pollID) = activeLinkedDestination {
            PollsView(space: viewModel.space, initialPollID: pollID)
        } else {
            EmptyView()
        }
    }

    @ViewBuilder
    private var filesLinkedDestinationView: some View {
        if case .files(let fileID) = activeLinkedDestination {
            FilesView(space: viewModel.space, initialFileID: fileID)
        } else {
            EmptyView()
        }
    }

    @ViewBuilder
    private var eventsLinkedDestinationView: some View {
        if case .events(let eventID) = activeLinkedDestination {
            EventsView(space: viewModel.space, initialEventID: eventID)
        } else {
            EmptyView()
        }
    }
    @ViewBuilder private var roomsLinkedDestinationView: some View {
        if case .rooms(let roomID) = activeLinkedDestination {
            RoomsView(space: viewModel.space, initialRoomID: roomID)
        } else { EmptyView() }
    }
    @ViewBuilder private var mediaLinkedDestinationView: some View {
        if case .media(let mediaID) = activeLinkedDestination {
            PhotosView(space: viewModel.space, initialMediaID: mediaID)
        } else { EmptyView() }
    }
    @ViewBuilder private var listsLinkedDestinationView: some View {
        if case .lists(let listID) = activeLinkedDestination {
            ListsView(space: viewModel.space, initialListID: listID)
        } else { EmptyView() }
    }
    @ViewBuilder private var notesLinkedDestinationView: some View {
        if case .notes(let noteID) = activeLinkedDestination {
            NotesView(space: viewModel.space, initialNoteID: noteID)
        } else { EmptyView() }
    }
}

private struct AttachmentMenuSheet: View {
    let onSelect: (GeneralAttachmentAction) -> Void

    var body: some View {
        NavigationView {
            contentList
                .navigationTitle("Attachments")
                .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private var contentList: some View {
        if #available(iOS 16.0, *) {
            attachmentList
                .scrollContentBackground(.hidden)
                .background(Color(.systemGroupedBackground))
        } else {
            attachmentList
                .background(Color(.systemGroupedBackground))
        }
    }

    private var attachmentList: some View {
        List {
            attachmentRow(
                title: "Camera",
                subtitle: "Capture a new photo or video",
                systemImage: "camera",
                action: .camera
            )
            attachmentRow(
                title: "Link",
                subtitle: "Reference something in this Space",
                systemImage: "link",
                action: .link
            )
            attachmentRow(
                title: "Photos & Videos",
                subtitle: "Choose from your library",
                systemImage: "photo.on.rectangle",
                action: .photos
            )
            attachmentRow(
                title: "GIFs",
                subtitle: "Search and send a GIF",
                systemImage: "sparkles.tv",
                action: .gifs
            )
        }
        .listStyle(.insetGrouped)
    }

    @ViewBuilder
    private func attachmentRow(
        title: String,
        subtitle: String,
        systemImage: String,
        action: GeneralAttachmentAction
    ) -> some View {
        Button {
            onSelect(action)
        } label: {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: systemImage)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 24)
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}

private struct SpaceLinkModulePickerSheet: View {
    let modules: [SpaceLinkModuleDescriptor]
    let onSelect: (SpaceLinkModuleDescriptor) -> Void

    var body: some View {
        NavigationView {
            List {
                if modules.isEmpty {
                    Text("No linkable modules are available in this Space yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(modules) { module in
                        Button {
                            onSelect(module)
                        } label: {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(module.title)
                                        .font(.body.weight(.medium))
                                        .foregroundStyle(.primary)
                                    Text(module.subtitle)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: module.moduleType.icon)
                                    .foregroundStyle(Color.accentColor)
                                    .frame(width: 24)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Link")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }
}

private struct SpaceLinkItemPickerSheet: View {
    let space: Space
    let module: SpaceLinkModuleDescriptor
    let registry: SpaceLinkRegistry
    let onSelect: (SpaceLinkRegistryItem) -> Void
    let onError: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var items: [SpaceLinkRegistryItem] = []
    @State private var isLoading = true

    var body: some View {
        NavigationView {
            List {
                if isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if items.isEmpty {
                    Text("No items are available yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(items) { item in
                        Button {
                            dismiss()
                            onSelect(item)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.title)
                                    .foregroundStyle(.primary)
                                if let subtitle = item.subtitle?.nilIfEmpty {
                                    Text(subtitle)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle(module.title)
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
        .task {
            do {
                items = try await registry.fetchItems(for: module.moduleType, in: space)
            } catch {
                onError(error.localizedDescription)
            }
            isLoading = false
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

private struct PickedComposerMedia {
    let data: Data
    let previewImageData: Data
    let mimeType: String
    let mediaCategory: String
    let isVideo: Bool
}

private struct LegacyMediaPicker: UIViewControllerRepresentable {
    let configuration: GeneralMediaPickerConfiguration
    let onMediaPicked: ([PickedComposerMedia]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator {
        Coordinator(configuration: configuration, onMediaPicked: onMediaPicked, dismiss: dismiss.callAsFunction)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        switch self.configuration.filterKind {
        case .imagesOnly:
            configuration.filter = .images
        case .imagesAndVideos:
            configuration.filter = .any(of: [.images, .videos])
        }
        configuration.selectionLimit = self.configuration.selectionLimit
        if #available(iOS 15.0, *) {
            configuration.selection = .ordered
        }
        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let configuration: GeneralMediaPickerConfiguration
        private let onMediaPicked: ([PickedComposerMedia]) -> Void
        private let dismiss: () -> Void

        init(
            configuration: GeneralMediaPickerConfiguration,
            onMediaPicked: @escaping ([PickedComposerMedia]) -> Void,
            dismiss: @escaping () -> Void
        ) {
            self.configuration = configuration
            self.onMediaPicked = onMediaPicked
            self.dismiss = dismiss
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard !results.isEmpty else {
                dismiss()
                onMediaPicked([])
                return
            }

            let containsVideo = results.contains { $0.itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) }
            if containsVideo && results.count > 1 {
                dismiss()
                onMediaPicked([])
                return
            }

            Task {
                let selections = await self.loadSelections(from: results)
                await MainActor.run {
                    self.dismiss()
                    self.onMediaPicked(selections)
                }
            }
        }

        private func loadSelections(from results: [PHPickerResult]) async -> [PickedComposerMedia] {
            var selections: [PickedComposerMedia] = []

            for result in results.prefix(configuration.selectionLimit) {
                if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    if let selection = await Self.loadImageSelection(from: result, mediaCategory: configuration.mediaCategory) {
                        selections.append(selection)
                    }
                } else if configuration.allowsVideos && result.itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    if let selection = await Self.loadVideoSelection(from: result) {
                        selections.append(selection)
                    }
                }
            }

            return selections
        }

        private static func loadImageSelection(from result: PHPickerResult, mediaCategory: String) async -> PickedComposerMedia? {
            await withCheckedContinuation { continuation in
                result.itemProvider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                    guard let data else {
                        continuation.resume(returning: nil)
                        return
                    }
                    let mimeType = Self.mimeType(for: result.itemProvider, fallback: .image)
                    guard !Self.isAnimatedGIF(mimeType: mimeType) else {
                        continuation.resume(returning: nil)
                        return
                    }
                    continuation.resume(
                        returning: PickedComposerMedia(
                            data: data,
                            previewImageData: data,
                            mimeType: mimeType,
                            mediaCategory: mediaCategory,
                            isVideo: false
                        )
                    )
                }
            }
        }

        private static func loadVideoSelection(from result: PHPickerResult) async -> PickedComposerMedia? {
            await withCheckedContinuation { continuation in
                result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                    guard
                        let url,
                        let data = try? Data(contentsOf: url),
                        let previewImageData = Self.videoPreviewImageData(for: url)
                    else {
                        continuation.resume(returning: nil)
                        return
                    }
                    continuation.resume(
                        returning: PickedComposerMedia(
                            data: data,
                            previewImageData: previewImageData,
                            mimeType: Self.mimeType(for: result.itemProvider, fallback: .movie),
                            mediaCategory: "video",
                            isVideo: true
                        )
                    )
                }
            }
        }

        private static func videoPreviewImageData(for url: URL) -> Data? {
            let asset = AVURLAsset(url: url)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 600, height: 600)
            guard let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) else {
                return nil
            }
            return UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.75)
        }

        private static func mimeType(for provider: NSItemProvider, fallback: UTType) -> String {
            for identifier in provider.registeredTypeIdentifiers {
                if let mimeType = UTType(identifier)?.preferredMIMEType {
                    return mimeType
                }
            }
            return fallback.preferredMIMEType ?? (fallback == .movie ? "video/quicktime" : "image/jpeg")
        }

        private static func isAnimatedGIF(mimeType: String) -> Bool {
            mimeType.caseInsensitiveCompare("image/gif") == .orderedSame
        }
    }
}

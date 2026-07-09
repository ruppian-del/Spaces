import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation

fileprivate enum GeneralAttachmentAction {
    case camera
    case photos
    case memes
    case voice
    case files
}

struct GeneralView: View {
    @StateObject private var viewModel: GeneralViewModel
    @FocusState private var isComposerFocused: Bool
    @State private var selectedMedia: SpaceMedia?
    @State private var isPhotoPickerPresented = false
    @State private var isAttachmentMenuPresented = false
    @State private var attachmentPlaceholderMessage: String?
    @State private var pendingAttachmentAction: GeneralAttachmentAction?
    @State private var pendingDeleteMessage: SpaceMessage?
    @State private var highlightedMessageID: String?
    private let encryptedMediaService = EncryptedMediaService()

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
            .background(Color(.systemGroupedBackground))
            .navigationTitle("General")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                composerBar
                    .background(.ultraThinMaterial)
            }
            .task {
                viewModel.startListeningIfNeeded()
                scrollToBottom(proxy: proxy, animated: false)
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
            .sheet(isPresented: $isPhotoPickerPresented) {
                LegacyMediaPicker { selection in
                    viewModel.selectComposerMedia(
                        data: selection?.data,
                        previewImageData: selection?.previewImageData,
                        mimeType: selection?.mimeType,
                        mediaCategory: selection?.mediaCategory ?? viewModel.pendingMediaCategory,
                        isVideo: selection?.isVideo == true
                    )
                }
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        viewModel.presentSearch()
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                }
            }
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
            if let editingMessage = viewModel.editingMessage {
                editComposerPreview(for: editingMessage)
            }

            if let replyingToMessage = viewModel.replyingToMessage {
                replyComposerPreview(for: replyingToMessage)
            }

            if let image = viewModel.selectedComposerUIImage {
                HStack(alignment: .top, spacing: 12) {
                    ZStack {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 72, height: 72)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                        if viewModel.selectedComposerIsVideo {
                            Image(systemName: "play.circle.fill")
                                .font(.system(size: 28))
                                .foregroundStyle(.white)
                                .shadow(radius: 3)
                        }
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(
                            viewModel.selectedComposerIsVideo
                                ? "Selected video"
                                : "Selected photo"
                        )
                            .font(.subheadline.weight(.semibold))
                        Text("Add an optional caption below.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Button {
                        viewModel.removeComposerMedia()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 14)
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
                .disabled(viewModel.isSending || !viewModel.canUploadMedia)

                TextField(
                    viewModel.isEditing
                        ? "Edit message"
                        : (viewModel.selectedComposerUIImage == nil ? "Message" : "Add a caption..."),
                    text: $viewModel.composerText
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

    @ViewBuilder
    private func messageRow(_ message: SpaceMessage, at index: Int, proxy: ScrollViewProxy) -> some View {
        MessageBubbleView(
            message: message,
            onTapMedia: handleTapMedia,
            onDelete: deleteAction(for: message),
            onReply: replyAction(for: message),
            onEdit: editAction(for: message),
            replyPresentation: replyPresentation(for: message),
            onTapReplyPreview: tapReplyPreviewAction(for: message, proxy: proxy),
            onCopyText: copyTextAction(for: message),
            onSaveMedia: saveMediaAction(for: message),
            reactionOptions: viewModel.reactionOptions(for: message),
            onToggleReaction: toggleReactionAction(for: message),
            showsSenderName: shouldShowSenderName(for: message, at: index),
            isHighlighted: highlightedMessageID == message.id,
            searchQuery: viewModel.searchText
        )
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
        let currentIsMedia = current.media != nil
        let nextIsMedia = next.media != nil

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
        guard message.media != nil else { return nil }
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
        case .photos:
            isPhotoPickerPresented = true
        case .memes:
            attachmentPlaceholderMessage = "GIF and meme picking will be added in a separate media flow."
        case .voice:
            attachmentPlaceholderMessage = "Voice messages are not ready yet."
        case .files:
            attachmentPlaceholderMessage = "File attachments are not ready yet."
        }
    }

    private func saveMedia(from message: SpaceMessage) async {
        guard let media = message.media else { return }

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
                title: "Photos & Videos",
                subtitle: "Choose from your library",
                systemImage: "photo.on.rectangle",
                action: .photos
            )
            attachmentRow(
                title: "GIFs & Memes",
                subtitle: "Search GIFs and memes",
                systemImage: "face.smiling",
                action: .memes
            )
            attachmentRow(
                title: "Voice Message",
                subtitle: "Record audio",
                systemImage: "waveform",
                action: .voice
            )
            attachmentRow(
                title: "Files",
                subtitle: "Browse documents",
                systemImage: "doc",
                action: .files
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

private struct PickedComposerMedia {
    let data: Data
    let previewImageData: Data
    let mimeType: String
    let mediaCategory: String
    let isVideo: Bool
}

private struct LegacyMediaPicker: UIViewControllerRepresentable {
    let onMediaPicked: (PickedComposerMedia?) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator {
        Coordinator(onMediaPicked: onMediaPicked, dismiss: dismiss.callAsFunction)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = 1
        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onMediaPicked: (PickedComposerMedia?) -> Void
        private let dismiss: () -> Void

        init(onMediaPicked: @escaping (PickedComposerMedia?) -> Void, dismiss: @escaping () -> Void) {
            self.onMediaPicked = onMediaPicked
            self.dismiss = dismiss
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let result = results.first else {
                dismiss()
                onMediaPicked(nil)
                return
            }

            if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                result.itemProvider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                    DispatchQueue.main.async {
                        self.dismiss()
                        guard let data else {
                            self.onMediaPicked(nil)
                            return
                        }
                        self.onMediaPicked(
                            PickedComposerMedia(
                                data: data,
                                previewImageData: data,
                                mimeType: Self.mimeType(for: result.itemProvider, fallback: .image),
                                mediaCategory: "photo",
                                isVideo: false
                            )
                        )
                    }
                }
            } else if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                    guard
                        let url,
                        let data = try? Data(contentsOf: url),
                        let previewImageData = Self.videoPreviewImageData(for: url)
                    else {
                        DispatchQueue.main.async {
                            self.dismiss()
                            self.onMediaPicked(nil)
                        }
                        return
                    }
                    DispatchQueue.main.async {
                        self.dismiss()
                        self.onMediaPicked(
                            PickedComposerMedia(
                                data: data,
                                previewImageData: previewImageData,
                                mimeType: Self.mimeType(for: result.itemProvider, fallback: .movie),
                                mediaCategory: "video",
                                isVideo: true
                            )
                        )
                    }
                }
            } else {
                dismiss()
                onMediaPicked(nil)
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
    }
}

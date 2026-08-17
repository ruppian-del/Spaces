import AVFoundation
@preconcurrency import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import UIKit

fileprivate struct PingMediaPickerConfiguration: Identifiable {
    enum FilterKind {
        case imagesAndVideos
    }

    let id: String
    let filterKind: FilterKind
    let mediaCategory: String
    let allowsVideos: Bool
    let selectionLimit: Int

    static let photosAndVideos = PingMediaPickerConfiguration(
        id: "photos-and-videos",
        filterKind: .imagesAndVideos,
        mediaCategory: "photo",
        allowsVideos: true,
        selectionLimit: 10
    )
}

struct PingConversationView: View {
    @StateObject private var viewModel: PingConversationViewModel
    @FocusState private var isComposerFocused: Bool
    @State private var selectedMedia: SpaceMedia?
    @State private var activeMediaPickerConfiguration: PingMediaPickerConfiguration?
    @State private var isGiphyPickerPresented = false
    @State private var isAttachmentMenuPresented = false
    @State private var pendingDeleteMessage: SpaceMessage?
    @State private var highlightedMessageID: String?

    init(ping: Ping) {
        _viewModel = StateObject(wrappedValue: PingConversationViewModel(ping: ping))
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header

                    LazyVStack(spacing: 16) {
                        ForEach(Array(viewModel.messages.filter { !$0.deleted }.enumerated()), id: \.element.id) { index, message in
                            MessageBubbleView(
                                message: message,
                                onTapMedia: { media in
                                    selectedMedia = media
                                },
                                onDelete: deleteAction(for: message),
                                onReply: { viewModel.beginReply(to: message) },
                                onEdit: viewModel.canEdit(message) ? { viewModel.beginEditing(message) } : nil,
                                replyPresentation: replyPresentation(for: message),
                                onTapReplyPreview: tapReplyPreviewAction(for: message, proxy: proxy),
                                onCopyText: copyTextAction(for: message),
                                reactionOptions: viewModel.reactionOptions(for: message),
                                onToggleReaction: { emoji in
                                    Task {
                                        await viewModel.toggleReaction(for: message, emoji: emoji)
                                    }
                                },
                                showsSenderName: shouldShowSenderName(for: message, at: index),
                                isHighlighted: highlightedMessageID == message.id
                            )
                            .id(message.id)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(otherTitle)
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
                scrollToBottom(proxy: proxy, animated: true)
            }
            .fullScreenCover(item: $selectedMedia) { media in
                MediaViewerPlaceholderView(space: placeholderSpace, media: media)
            }
            .confirmationDialog(
                "Attachments",
                isPresented: $isAttachmentMenuPresented,
                titleVisibility: .visible
            ) {
                Button("Photos & Videos") {
                    activeMediaPickerConfiguration = .photosAndVideos
                }
                Button("GIFs") {
                    isGiphyPickerPresented = true
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Choose what to send in this Ping.")
            }
            .sheet(item: $activeMediaPickerConfiguration) { configuration in
                PingMediaPicker(configuration: configuration) { selections in
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
                Text("This will remove it from the conversation for everyone in this Ping.")
            }
            .alert("Ping", isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(viewModel.errorMessage ?? "")
            }
        }
    }

    private var otherTitle: String {
        viewModel.ping.title(for: viewModel.resolvedCurrentUserID)
    }

    private var otherEmoji: String {
        viewModel.ping.emoji(for: viewModel.resolvedCurrentUserID)
    }

    private var placeholderSpace: Space {
        Space(
            id: "ping-\(viewModel.ping.id)",
            name: otherTitle,
            emoji: otherEmoji,
            tintHex: "#7C72FF",
            description: "Private conversation",
            template: .custom,
            ownerId: viewModel.resolvedCurrentUserID ?? "",
            memberIds: viewModel.ping.participantIds,
            unreadCount: 0,
            enabledModules: [.general],
            moduleOrder: [.general, .settings]
        )
    }

    private var header: some View {
        HStack(spacing: 12) {
            Text(otherEmoji)
                .font(.system(size: 28))
                .frame(width: 52, height: 52)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(.secondarySystemBackground))
                )

            VStack(alignment: .leading, spacing: 4) {
                Text(otherTitle)
                    .font(.headline)
                Text("Private conversation")
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
                previewCard(
                    title: "Editing message",
                    subtitle: editingMessage.text ?? "",
                    onClose: viewModel.cancelEditing
                )
            }

            if let replyingToMessage = viewModel.replyingToMessage {
                previewCard(
                    title: "Replying to \(replyingToMessage.senderName)",
                    subtitle: replyingToMessage.text ?? "Message",
                    onClose: viewModel.cancelReply
                )
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
                        .background(Circle().fill(Color(.secondarySystemBackground)))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)

                TextField(
                    viewModel.isEditing ? "Edit message" : (viewModel.hasSelectedComposerMedia ? "Add a caption..." : "Message"),
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

                Button {
                    Task {
                        await viewModel.sendComposer()
                    }
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(viewModel.canSend ? Color.accentColor : Color.secondary.opacity(0.6))
                }
                .buttonStyle(.plain)
                .disabled(!viewModel.canSend)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private func previewCard(title: String, subtitle: String, onClose: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 999, style: .continuous)
                .fill(Color.accentColor)
                .frame(width: 3)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(subtitle)
                    .font(.caption)
                    .lineLimit(2)
            }

            Spacer(minLength: 0)

            Button(action: onClose) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
    }

    private func shouldShowSenderName(for message: SpaceMessage, at index: Int) -> Bool {
        guard !message.isOutgoing else { return false }
        guard index > 0 else { return true }
        let previous = viewModel.messages.filter { !$0.deleted }[index - 1]
        return previous.senderId != message.senderId
    }

    private func replyPresentation(for message: SpaceMessage) -> MessageReplyPresentation? {
        guard let replyContext = message.replyContext else { return nil }
        let isUnavailable = viewModel.messages.first(where: { $0.id == replyContext.messageId })?.deleted == true
        return MessageReplyPresentation(
            senderName: replyContext.senderName,
            preview: isUnavailable ? "Original message unavailable" : replyContext.preview,
            isUnavailable: isUnavailable
        )
    }

    private func tapReplyPreviewAction(for message: SpaceMessage, proxy: ScrollViewProxy) -> (() -> Void)? {
        guard let replyContext = message.replyContext else { return nil }
        return {
            guard let targetMessage = viewModel.messages.first(where: { $0.id == replyContext.messageId }), !targetMessage.deleted else { return }
            highlightAndScroll(to: targetMessage.id, proxy: proxy)
        }
    }

    private func copyTextAction(for message: SpaceMessage) -> (() -> Void)? {
        guard let text = message.text, !text.isEmpty else { return nil }
        return {
            UIPasteboard.general.string = text
        }
    }

    private func deleteAction(for message: SpaceMessage) -> (() -> Void)? {
        guard viewModel.canDelete(message) else { return nil }
        return {
            pendingDeleteMessage = message
        }
    }

    private func highlightAndScroll(to messageID: String, proxy: ScrollViewProxy) {
        highlightedMessageID = messageID
        withAnimation(.easeOut(duration: 0.2)) {
            proxy.scrollTo(messageID, anchor: .center)
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            if highlightedMessageID == messageID {
                highlightedMessageID = nil
            }
        }
    }

    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool) {
        guard let lastID = viewModel.messages.filter({ !$0.deleted }).last?.id else { return }
        if animated {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(lastID, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(lastID, anchor: .bottom)
        }
    }

    private var selectedComposerTitle: String {
        if viewModel.selectedComposerMediaItems.count == 1 {
            return viewModel.selectedComposerIsVideo ? "Video ready to send" : "Photo ready to send"
        }
        return "\(viewModel.selectedComposerMediaItems.count) photos ready to send"
    }

    private var selectedComposerSubtitle: String {
        if viewModel.selectedComposerMediaItems.count == 1 {
            return "Add a caption if you want."
        }
        return "Add an optional caption for this set."
    }
}

private struct PickedPingComposerMedia {
    let data: Data
    let previewImageData: Data
    let mimeType: String
    let mediaCategory: String
    let isVideo: Bool
}

private struct PingMediaPicker: UIViewControllerRepresentable {
    let configuration: PingMediaPickerConfiguration
    let onMediaPicked: ([PickedPingComposerMedia]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator {
        Coordinator(configuration: configuration, onMediaPicked: onMediaPicked, dismiss: dismiss.callAsFunction)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        switch self.configuration.filterKind {
        case .imagesAndVideos:
            configuration.filter = .any(of: [.images, .videos])
        }
        configuration.selectionLimit = self.configuration.selectionLimit

        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let configuration: PingMediaPickerConfiguration
        private let onMediaPicked: ([PickedPingComposerMedia]) -> Void
        private let dismiss: () -> Void

        init(
            configuration: PingMediaPickerConfiguration,
            onMediaPicked: @escaping ([PickedPingComposerMedia]) -> Void,
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

            Task.detached(priority: .userInitiated) {
                let selections = await self.loadSelections(from: results)
                await MainActor.run {
                    self.dismiss()
                    self.onMediaPicked(selections)
                }
            }
        }

        private func loadSelections(from results: [PHPickerResult]) async -> [PickedPingComposerMedia] {
            var selections: [PickedPingComposerMedia] = []

            for result in results {
                if let selection = await loadSelection(from: result) {
                    selections.append(selection)
                }
            }

            return selections
        }

        private func loadSelection(from result: PHPickerResult) async -> PickedPingComposerMedia? {
            let provider = result.itemProvider

            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                return await withCheckedContinuation { continuation in
                    provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                        guard let data else {
                            continuation.resume(returning: nil)
                            return
                        }

                        let mimeType = Self.mimeType(for: provider, fallback: .image)
                        guard !Self.isAnimatedGIF(mimeType: mimeType) else {
                            continuation.resume(returning: nil)
                            return
                        }

                        continuation.resume(returning: PickedPingComposerMedia(
                            data: data,
                            previewImageData: data,
                            mimeType: mimeType,
                            mediaCategory: self.configuration.mediaCategory,
                            isVideo: false
                        ))
                    }
                }
            }

            if configuration.allowsVideos, provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                return await withCheckedContinuation { continuation in
                    provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                        guard let url, let data = try? Data(contentsOf: url) else {
                            continuation.resume(returning: nil)
                            return
                        }

                        let previewImageData = (try? Self.videoPreviewImageData(for: url)) ?? data
                        continuation.resume(returning: PickedPingComposerMedia(
                            data: data,
                            previewImageData: previewImageData,
                            mimeType: Self.mimeType(for: provider, fallback: .movie),
                            mediaCategory: "video",
                            isVideo: true
                        ))
                    }
                }
            }

            return nil
        }

        private static func mimeType(for provider: NSItemProvider, fallback: UTType) -> String {
            for identifier in provider.registeredTypeIdentifiers {
                if let type = UTType(identifier), let mimeType = type.preferredMIMEType {
                    return mimeType
                }
            }
            return fallback.preferredMIMEType ?? "application/octet-stream"
        }

        private static func isAnimatedGIF(mimeType: String) -> Bool {
            mimeType.caseInsensitiveCompare("image/gif") == .orderedSame
        }

        private static func videoPreviewImageData(for url: URL) throws -> Data {
            let asset = AVURLAsset(url: url)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            let cgImage = try generator.copyCGImage(at: .zero, actualTime: nil)
            let image = UIImage(cgImage: cgImage)
            guard let data = image.jpegData(compressionQuality: 0.8) else {
                throw NSError(domain: "PingMediaPicker", code: -1)
            }
            return data
        }
    }
}

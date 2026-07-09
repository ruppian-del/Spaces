import SwiftUI

struct PingConversationView: View {
    @StateObject private var viewModel: PingConversationViewModel
    @FocusState private var isComposerFocused: Bool
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
                                onTapMedia: { _ in },
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

            HStack(alignment: .bottom, spacing: 10) {
                Button {
                    viewModel.errorMessage = "Photos and videos are not enabled for Ping yet."
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(Color(.secondarySystemBackground)))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)

                TextField(
                    viewModel.isEditing ? "Edit message" : "Message",
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
}

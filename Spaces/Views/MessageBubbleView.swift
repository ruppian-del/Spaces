import SwiftUI
import UIKit

struct MessageReplyPresentation: Hashable {
    let senderName: String
    let preview: String
    let isUnavailable: Bool
}

struct MessageBubbleView: View {
    let message: SpaceMessage
    let onTapMedia: (SpaceMedia) -> Void
    var onDelete: (() -> Void)? = nil
    var onReply: (() -> Void)? = nil
    var onEdit: (() -> Void)? = nil
    var replyPresentation: MessageReplyPresentation? = nil
    var onTapReplyPreview: (() -> Void)? = nil
    var onCopyText: (() -> Void)? = nil
    var onSaveMedia: (() -> Void)? = nil
    var reactionOptions: [String] = []
    var onToggleReaction: ((String) -> Void)? = nil
    var showsSenderName: Bool = true
    var isHighlighted: Bool = false
    var searchQuery: String = ""

    var body: some View {
        HStack {
            if message.isOutgoing {
                Spacer(minLength: 48)
                bubbleContent(alignment: .trailing)
            } else {
                bubbleContent(alignment: .leading)
                Spacer(minLength: 48)
            }
        }
    }

    @ViewBuilder
    private func bubbleContent(alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 4) {
            if !message.isOutgoing, showsSenderName {
                highlightedText(message.senderName, font: .caption.weight(.semibold), foreground: .secondary)
            }

            if let replyPresentation {
                replyPreview(replyPresentation, alignment: alignment)
            }

            if let media = message.media {
                mediaBubble(for: media)
            } else if let text = message.text {
                textBubble(text)
            }

            HStack(spacing: 6) {
                Text(message.timestamp)
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                if message.isEdited {
                    Text("Edited")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                if message.isOutgoing, let deliveryStatus = message.deliveryStatus {
                    Text(deliveryStatus)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    }
            }

            if !message.reactions.isEmpty {
                reactionChips(alignment: alignment)
            }
        }
        .padding(.vertical, isHighlighted ? 4 : 0)
        .animation(.easeInOut(duration: 0.18), value: isHighlighted)
    }

    private func textBubble(_ text: String) -> some View {
        highlightedText(text, font: .body, foreground: message.isOutgoing ? .white : .primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(message.isOutgoing ? Color.accentColor : Color(.tertiarySystemFill))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(
                        isHighlighted ? Color.accentColor.opacity(0.5) : (message.isOutgoing ? Color.clear : Color.black.opacity(0.06)),
                        lineWidth: 1
                    )
            )
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .modifier(MessageBubbleContextMenuModifier(
                reactionOptions: reactionOptions,
                currentReactionEmoji: currentReactionEmoji,
                onToggleReaction: onToggleReaction,
                onReply: onReply,
                onEdit: onEdit,
                onCopyText: onCopyText,
                onSaveMedia: onSaveMedia,
                onDelete: onDelete
            ))
            .modifier(MessageBubbleSwipeReplyModifier(onReply: onReply))
    }

    private func mediaBubble(for media: SpaceMedia) -> some View {
        Button {
            onTapMedia(media)
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                EncryptedMediaThumbnailView(
                    media: media,
                    tint: mediaBubbleFill,
                    accentColor: mediaAccentColor
                )
                .frame(width: 224, height: 156)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

                if let caption = media.caption, !caption.isEmpty {
                    Text(caption)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                }
            }
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(message.isOutgoing ? Color.accentColor.opacity(0.12) : Color(.tertiarySystemFill))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(
                        isHighlighted ? Color.accentColor.opacity(0.5) : (message.isOutgoing ? Color.accentColor.opacity(0.18) : Color.black.opacity(0.06)),
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(.plain)
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .modifier(MessageBubbleContextMenuModifier(
            reactionOptions: reactionOptions,
            currentReactionEmoji: currentReactionEmoji,
            onToggleReaction: onToggleReaction,
            onReply: onReply,
            onEdit: onEdit,
            onCopyText: onCopyText,
            onSaveMedia: onSaveMedia,
            onDelete: onDelete
        ))
        .modifier(MessageBubbleSwipeReplyModifier(onReply: onReply))
    }

    @ViewBuilder
    private func replyPreview(_ presentation: MessageReplyPresentation, alignment: HorizontalAlignment) -> some View {
        Button {
            onTapReplyPreview?()
        } label: {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 999, style: .continuous)
                    .fill(isHighlighted ? Color.accentColor : Color.accentColor.opacity(0.6))
                    .frame(width: 3)

                VStack(alignment: .leading, spacing: 2) {
                    highlightedText("↪ \(presentation.senderName)", font: .caption.weight(.semibold), foreground: .secondary)
                    highlightedText(presentation.preview, font: .caption, foreground: .primary)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: 260, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color(.secondarySystemBackground))
            )
        }
        .buttonStyle(.plain)
        .disabled(presentation.isUnavailable || onTapReplyPreview == nil)
        .frame(maxWidth: .infinity, alignment: alignment == .trailing ? .trailing : .leading)
    }

    private var mediaBubbleFill: Color {
        message.isOutgoing ? Color.accentColor.opacity(0.16) : Color(.secondarySystemBackground)
    }

    private var mediaAccentColor: Color {
        message.isOutgoing ? Color.accentColor : Color.primary.opacity(0.75)
    }

    @ViewBuilder
    private func reactionChips(alignment: HorizontalAlignment) -> some View {
        HStack(spacing: 6) {
            ForEach(message.reactions) { reaction in
                Button {
                    onToggleReaction?(reaction.emoji)
                } label: {
                    HStack(spacing: 4) {
                        Text(reaction.emoji)
                        Text("\(reaction.count)")
                            .font(.caption.weight(.semibold))
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(
                        Capsule(style: .continuous)
                            .fill(reaction.isSelectedByCurrentUser ? Color.accentColor.opacity(0.18) : Color(.secondarySystemBackground))
                    )
                    .overlay(
                        Capsule(style: .continuous)
                            .stroke(
                                reaction.isSelectedByCurrentUser ? Color.accentColor.opacity(0.45) : Color.black.opacity(0.08),
                                lineWidth: 1
                            )
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: alignment == .trailing ? .trailing : .leading)
    }

    private var currentReactionEmoji: String? {
        message.reactions.first(where: \.isSelectedByCurrentUser)?.emoji
    }

    private func highlightedText(_ value: String, font: Font, foreground: Color) -> Text {
        Text(highlightedAttributedString(value))
            .font(font)
            .foregroundColor(foreground)
    }

    private func highlightedAttributedString(_ value: String) -> AttributedString {
        var attributed = AttributedString(value)
        let trimmedQuery = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else { return attributed }

        let nsValue = value as NSString
        let fullRange = NSRange(location: 0, length: nsValue.length)
        let highlightColor = UIColor.systemYellow.withAlphaComponent(0.35)
        var searchRange = fullRange

        while true {
            let foundRange = nsValue.range(
                of: trimmedQuery,
                options: [.caseInsensitive, .diacriticInsensitive],
                range: searchRange
            )
            guard foundRange.location != NSNotFound,
                  let stringRange = Range(foundRange, in: value),
                  let lowerBound = AttributedString.Index(stringRange.lowerBound, within: attributed),
                  let upperBound = AttributedString.Index(stringRange.upperBound, within: attributed)
            else {
                break
            }

            attributed[lowerBound..<upperBound].backgroundColor = highlightColor

            let nextLocation = foundRange.location + max(foundRange.length, 1)
            guard nextLocation < nsValue.length else { break }
            searchRange = NSRange(location: nextLocation, length: nsValue.length - nextLocation)
        }

        return attributed
    }
}

private struct MessageBubbleSwipeReplyModifier: ViewModifier {
    let onReply: (() -> Void)?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let onReply {
            content.swipeActions(edge: .leading, allowsFullSwipe: false) {
                Button(action: onReply) {
                    Label("Reply", systemImage: "arrowshape.turn.up.left")
                }
                .tint(.accentColor)
            }
        } else {
            content
        }
    }
}

private struct MessageBubbleContextMenuModifier: ViewModifier {
    let reactionOptions: [String]
    let currentReactionEmoji: String?
    let onToggleReaction: ((String) -> Void)?
    let onReply: (() -> Void)?
    let onEdit: (() -> Void)?
    let onCopyText: (() -> Void)?
    let onSaveMedia: (() -> Void)?
    let onDelete: (() -> Void)?

    func body(content: Content) -> some View {
        content.contextMenu {
            if let onToggleReaction, !reactionOptions.isEmpty {
                ForEach(reactionOptions, id: \.self) { emoji in
                    Button {
                        onToggleReaction(emoji)
                    } label: {
                        Label(emoji, systemImage: currentReactionEmoji == emoji ? "checkmark.circle.fill" : "circle")
                    }
                }
            }

            if onReply != nil || onEdit != nil || onCopyText != nil || onSaveMedia != nil || onDelete != nil {
                Menu {
                    if let onReply {
                        Button(action: onReply) {
                            Label("Reply", systemImage: "arrowshape.turn.up.left")
                        }
                    }

                    if let onEdit {
                        Button(action: onEdit) {
                            Label("Edit", systemImage: "pencil")
                        }
                    }

                    if let onCopyText {
                        Button(action: onCopyText) {
                            Label("Copy Text", systemImage: "doc.on.doc")
                        }
                    }

                    if let onSaveMedia {
                        Button(action: onSaveMedia) {
                            Label("Save Media", systemImage: "square.and.arrow.down")
                        }
                    }

                    if let onDelete {
                        Divider()
                        Button(role: .destructive, action: onDelete) {
                            Label("Delete Message", systemImage: "trash")
                        }
                    }
                } label: {
                    Label("More", systemImage: "ellipsis")
                }
            }
        }
    }
}

private struct EncryptedMediaThumbnailView: View {
    let media: SpaceMedia
    let tint: Color
    let accentColor: Color

    private let encryptedMediaService = EncryptedMediaService()
    @State private var image: UIImage?
    @State private var isLoading = true

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(tint)

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                if media.type == .video {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 38))
                        .foregroundStyle(.white)
                        .shadow(radius: 4)
                }
            } else if isLoading {
                ProgressView()
                    .tint(accentColor)
            } else {
                VStack(spacing: 10) {
                    Image(systemName: media.placeholderImageName)
                        .font(.system(size: 36, weight: .semibold))
                        .foregroundStyle(accentColor)
                    Text(media.type.displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                }
            }
        }
        .task(id: media.id) {
            guard image == nil else { return }
            do {
                let data = try await encryptedMediaService.thumbnailData(for: media)
                await MainActor.run {
                    image = UIImage(data: data)
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    isLoading = false
                }
            }
        }
    }
}

import SwiftUI
import UniformTypeIdentifiers

struct AnnouncementsView: View {
    let space: Space

    @StateObject private var viewModel: AnnouncementsViewModel
    @State private var isComposerPresented = false
    @State private var isShowingInitialAnnouncement = false
    private let initialAnnouncementID: String?

    init(space: Space, initialAnnouncementID: String? = nil) {
        self.space = space
        self.initialAnnouncementID = initialAnnouncementID
        _viewModel = StateObject(wrappedValue: AnnouncementsViewModel(space: space))
    }

    var body: some View {
        Group {
            if viewModel.announcements.isEmpty {
                SpaceEmptyStateView(
                    "No Announcements",
                    systemImage: "megaphone",
                    description: "Important updates for this Space will appear here."
                )
            } else {
                List {
                    ForEach(viewModel.announcements) { announcement in
                        NavigationLink {
                            AnnouncementDetailView(
                                announcementID: announcement.id,
                                viewModel: viewModel
                            )
                        } label: {
                            AnnouncementRow(announcement: announcement, tintHex: space.tintHex)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            if viewModel.canDelete(announcement) {
                                Button(role: .destructive) {
                                    viewModel.delete(announcement)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .refreshable {
                    viewModel.reload()
                }
            }
        }
        .navigationTitle("Announcements")
        .toolbar {
            if viewModel.canCreate {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        isComposerPresented = true
                    } label: {
                        Label("New Announcement", systemImage: "plus")
                    }
                }
            }
        }
        .sheet(isPresented: $isComposerPresented) {
            NavigationStack {
                AnnouncementComposerView(viewModel: viewModel)
            }
        }
        .task {
            await viewModel.start()
            isShowingInitialAnnouncement = initialAnnouncementID.flatMap { id in
                viewModel.announcements.contains(where: { $0.id == id }) ? true : nil
            } ?? false
        }
        .navigationDestination(isPresented: $isShowingInitialAnnouncement) {
            if let initialAnnouncementID {
                AnnouncementDetailView(announcementID: initialAnnouncementID, viewModel: viewModel)
            }
        }
        .alert(
            "Announcements",
            isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) {
                viewModel.errorMessage = nil
            }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }
}

private struct AnnouncementRow: View {
    let announcement: SpaceAnnouncement
    let tintHex: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if announcement.isPinned {
                    Image(systemName: "pin.fill")
                        .foregroundStyle(Color(hex: tintHex))
                }
                Text(announcement.title)
                    .font(.headline)
                    .lineLimit(2)
                Spacer(minLength: 0)
            }

            Text(announcement.body)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(3)

            HStack(spacing: 12) {
                Label(announcement.authorName, systemImage: "person.crop.circle")
                Text(announcement.createdAt, style: .relative)

                Spacer()

                if !announcement.reactions.isEmpty {
                    Label(
                        "\(announcement.reactions.reduce(0) { $0 + $1.count })",
                        systemImage: "hand.thumbsup"
                    )
                }

                if announcement.commentsEnabled {
                    Label("\(announcement.comments.count)", systemImage: "bubble.left")
                }
            }
            .font(.caption)
            .foregroundStyle(.tertiary)

            if let expiresAt = announcement.expiresAt {
                Label("Expires \(expiresAt.formatted(date: .abbreviated, time: .shortened))", systemImage: "clock")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
        .padding(.vertical, 6)
    }
}

private struct AnnouncementDetailView: View {
    let announcementID: String
    @ObservedObject var viewModel: AnnouncementsViewModel

    @Environment(\.dismiss) private var dismiss
    @State private var commentText = ""
    @State private var isEditorPresented = false
    @State private var isReactionPickerPresented = false
    @State private var isDeleteConfirmationPresented = false

    private var announcement: SpaceAnnouncement? {
        viewModel.announcement(id: announcementID)
    }

    var body: some View {
        Group {
            if let announcement {
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        header(announcement)
                        richBody(announcement.body)

                        if !announcement.attachments.isEmpty {
                            attachmentSection(announcement.attachments)
                        }

                        if !announcement.references.isEmpty {
                            referenceSection(announcement.references)
                        }

                        reactionsSection(announcement)

                        if announcement.commentsEnabled {
                            commentsSection(announcement)
                        } else {
                            Label("Comments are disabled for this announcement.", systemImage: "bubble.left.and.exclamationmark.bubble.right")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding()
                                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                        }
                    }
                    .padding(20)
                }
                .background(Color(.systemGroupedBackground))
                .navigationTitle("Announcement")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        if viewModel.canEdit(announcement) {
                            Button("Edit") {
                                isEditorPresented = true
                            }
                        }

                        if viewModel.canDelete(announcement) {
                            Button(role: .destructive) {
                                isDeleteConfirmationPresented = true
                            } label: {
                                Image(systemName: "trash")
                            }
                            .accessibilityLabel("Delete announcement")
                        }
                    }
                }
                .sheet(isPresented: $isEditorPresented) {
                    NavigationStack {
                        AnnouncementComposerView(viewModel: viewModel, existing: announcement)
                    }
                }
                .confirmationDialog("React", isPresented: $isReactionPickerPresented) {
                    ForEach(["👍", "❤️", "🎉", "👀", "✅"], id: \.self) { emoji in
                        Button(emoji) {
                            viewModel.toggleReaction(emoji, announcementID: announcement.id)
                        }
                    }
                }
                .confirmationDialog(
                    "Delete announcement?",
                    isPresented: $isDeleteConfirmationPresented,
                    titleVisibility: .visible
                ) {
                    Button("Delete Announcement", role: .destructive) {
                        viewModel.delete(announcement)
                        dismiss()
                    }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("This removes the announcement from this Space on every device.")
                }
            } else {
                SpaceEmptyStateView("Announcement Unavailable", systemImage: "megaphone")
            }
        }
    }

    private func header(_ announcement: SpaceAnnouncement) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if announcement.isPinned {
                Label("Pinned", systemImage: "pin.fill")
                    .font(.caption.bold())
                    .foregroundStyle(Color(hex: viewModel.space.tintHex))
            }

            Text(announcement.title)
                .font(.largeTitle.bold())

            HStack {
                Text(announcement.authorName)
                Text("•")
                Text(announcement.createdAt.formatted(date: .abbreviated, time: .shortened))
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)

            if let expiresAt = announcement.expiresAt {
                Label("Expires \(expiresAt.formatted(date: .abbreviated, time: .shortened))", systemImage: "clock")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func richBody(_ body: String) -> some View {
        if let attributed = try? AttributedString(markdown: body) {
            Text(attributed)
                .font(.body)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text(body)
                .font(.body)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func attachmentSection(_ attachments: [AnnouncementAttachment]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Attachments")
                .font(.headline)
            ForEach(attachments) { attachment in
                Group {
                    if attachment.kind == .link,
                       let value = attachment.urlString,
                       let url = URL(string: value) {
                        Link(destination: url) { attachmentRow(attachment) }
                    } else if let media = attachment.asSpaceMedia(spaceID: viewModel.space.id) {
                        NavigationLink {
                            MediaViewerPlaceholderView(space: viewModel.space, media: media)
                        } label: {
                            attachmentRow(attachment)
                        }
                    } else {
                        attachmentRow(attachment)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func attachmentRow(_ attachment: AnnouncementAttachment) -> some View {
        HStack(spacing: 12) {
                    Image(systemName: attachment.kind.icon)
                        .frame(width: 28)
                        .foregroundStyle(Color(hex: viewModel.space.tintHex))
                    VStack(alignment: .leading) {
                        Text(attachment.title)
                            .font(.subheadline.weight(.semibold))
                        Text(attachment.kind.title)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "arrow.up.right")
                }
                .padding()
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    private func referenceSection(_ references: [AnnouncementReference]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Related")
                .font(.headline)
            ForEach(references) { reference in
                NavigationLink {
                    AnnouncementReferenceDestination(space: viewModel.space, reference: reference)
                } label: {
                    HStack(spacing: 12) {
                        Text(reference.kind.emoji)
                            .font(.title2)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(reference.kind.title)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(reference.title)
                                .font(.subheadline.weight(.semibold))
                            if let subtitle = reference.subtitle {
                                Text(subtitle)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    .padding()
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func reactionsSection(_ announcement: SpaceAnnouncement) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Reactions")
                    .font(.headline)
                Spacer()
                Button {
                    isReactionPickerPresented = true
                } label: {
                    Label("React", systemImage: "face.smiling")
                }
                .buttonStyle(.bordered)
            }

            if announcement.reactions.isEmpty {
                Text("Be the first to react.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                FlowLayout(spacing: 8) {
                    ForEach(announcement.reactions) { reaction in
                        Button {
                            viewModel.toggleReaction(reaction.emoji, announcementID: announcement.id)
                        } label: {
                            Text("\(reaction.emoji) \(reaction.count)")
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    private func commentsSection(_ announcement: SpaceAnnouncement) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Comments")
                .font(.headline)

            ForEach(announcement.comments) { comment in
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(comment.authorName)
                            .font(.subheadline.bold())
                        Spacer()
                        Text(comment.createdAt, style: .relative)
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    Text(comment.body)
                        .font(.subheadline)
                }
                .padding()
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
            }

            HStack(alignment: .bottom, spacing: 10) {
                TextField("Add a comment", text: $commentText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)

                Button {
                    viewModel.addComment(commentText, announcementID: announcement.id)
                    commentText = ""
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                }
                .disabled(commentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }
}

private struct AnnouncementComposerView: View {
    @ObservedObject var viewModel: AnnouncementsViewModel
    let existing: SpaceAnnouncement?

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var bodyText: String
    @State private var isPinned: Bool
    @State private var hasExpiration: Bool
    @State private var expiresAt: Date
    @State private var commentsEnabled: Bool
    @State private var attachments: [AnnouncementAttachment]
    @State private var references: [AnnouncementReference]
    @State private var isAttachmentEditorPresented = false
    @State private var isFileImporterPresented = false
    @State private var isUploadingAttachment = false
    @State private var isReferencePickerPresented = false

    init(viewModel: AnnouncementsViewModel, existing: SpaceAnnouncement? = nil) {
        self.viewModel = viewModel
        self.existing = existing
        _title = State(initialValue: existing?.title ?? "")
        _bodyText = State(initialValue: existing?.body ?? "")
        _isPinned = State(initialValue: existing?.isPinned ?? false)
        _hasExpiration = State(initialValue: existing?.expiresAt != nil)
        _expiresAt = State(initialValue: existing?.expiresAt ?? Calendar.current.date(byAdding: .day, value: 7, to: Date()) ?? Date())
        _commentsEnabled = State(initialValue: existing?.commentsEnabled ?? true)
        _attachments = State(initialValue: existing?.attachments ?? [])
        _references = State(initialValue: existing?.references ?? [])
    }

    private var canSave: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Form {
            Section("Announcement") {
                TextField("Title", text: $title)
                TextEditor(text: $bodyText)
                    .frame(minHeight: 150)
                HStack {
                    Button("Bold") { bodyText += "**bold**" }
                    Button("Italic") { bodyText += "_italic_" }
                    Button("Link") { bodyText += "[link title](https://)" }
                    Button("List") { bodyText += "\n- item" }
                }
                .buttonStyle(.bordered)
                Text("Rich text supports Markdown such as **bold**, _italic_, and links.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Options") {
                Toggle("Pin Announcement", isOn: $isPinned)
                Toggle("Comments Enabled", isOn: $commentsEnabled)
                Toggle("Expiration Date", isOn: $hasExpiration)
                if hasExpiration {
                    DatePicker(
                        "Expires",
                        selection: $expiresAt,
                        in: Date()...,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                }
            }

            Section {
                ForEach(attachments) { attachment in
                    Label(attachment.title, systemImage: attachment.kind.icon)
                }
                .onDelete { attachments.remove(atOffsets: $0) }

                Button {
                    isFileImporterPresented = true
                } label: {
                    Label(isUploadingAttachment ? "Uploading…" : "Upload Image, Video, or File", systemImage: "paperclip")
                }
                .disabled(isUploadingAttachment)
                Button {
                    isAttachmentEditorPresented = true
                } label: {
                    Label("Add Web Link", systemImage: "link")
                }
            } header: {
                Text("Attachments")
            }

            Section {
                ForEach(references) { reference in
                    Label {
                        VStack(alignment: .leading) {
                            Text(reference.title)
                            Text(reference.kind.title)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: reference.kind.icon)
                    }
                }
                .onDelete { references.remove(atOffsets: $0) }

                Button {
                    isReferencePickerPresented = true
                } label: {
                    Label("Link Another Module", systemImage: "link.badge.plus")
                }
            } header: {
                Text("Cross-Module Links")
            } footer: {
                Text("Links reference the original item. They do not copy its data.")
            }
        }
        .navigationTitle(existing == nil ? "New Announcement" : "Edit Announcement")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    viewModel.save(
                        existing: existing,
                        title: title,
                        body: bodyText,
                        isPinned: isPinned,
                        expiresAt: hasExpiration ? expiresAt : nil,
                        commentsEnabled: commentsEnabled,
                        attachments: attachments,
                        references: references
                    )
                    dismiss()
                }
                .disabled(!canSave)
            }
        }
        .sheet(isPresented: $isAttachmentEditorPresented) {
            NavigationStack {
                AnnouncementAttachmentEditor { attachment in
                    attachments.append(attachment)
                }
            }
        }
        .fileImporter(
            isPresented: $isFileImporterPresented,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            Task {
                isUploadingAttachment = true
                let accessed = url.startAccessingSecurityScopedResource()
                defer {
                    if accessed { url.stopAccessingSecurityScopedResource() }
                    isUploadingAttachment = false
                }
                do {
                    let data = try Data(contentsOf: url)
                    let type = try? url.resourceValues(forKeys: [.contentTypeKey]).contentType
                    let mimeType = type?.preferredMIMEType ?? "application/octet-stream"
                    attachments.append(try await viewModel.uploadAttachment(
                        data: data,
                        fileName: url.lastPathComponent,
                        mimeType: mimeType
                    ))
                } catch {
                    viewModel.errorMessage = error.localizedDescription
                }
            }
        }
        .sheet(isPresented: $isReferencePickerPresented) {
            NavigationStack {
                AnnouncementReferencePicker(space: viewModel.space) { reference in
                    guard !references.contains(where: { $0.kind == reference.kind && $0.targetID == reference.targetID }) else {
                        return
                    }
                    references.append(reference)
                }
            }
        }
    }
}

private struct AnnouncementAttachmentEditor: View {
    let onAdd: (AnnouncementAttachment) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var urlString = ""

    var body: some View {
        Form {
            TextField("Display name", text: $title)
            TextField("Web URL", text: $urlString)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
        }
        .navigationTitle("Add Attachment")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Add") {
                    onAdd(
                        AnnouncementAttachment(
                            kind: .link,
                            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                            urlString: urlString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : urlString
                        )
                    )
                    dismiss()
                }
                .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }
}

@MainActor
private struct AnnouncementReferencePicker: View {
    let space: Space
    let onSelect: (AnnouncementReference) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var events: [SpaceEvent] = []
    @State private var files: [SpaceFileItem] = []
    @State private var media: [SpaceMedia] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    private let spaceService = SpaceService()

    var body: some View {
        List {
            if isLoading {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            }

            if !events.isEmpty {
                Section("Events") {
                    ForEach(events) { event in
                        referenceButton(
                            AnnouncementReference(
                                kind: .event,
                                targetID: event.id,
                                title: event.title,
                                subtitle: event.dateText
                            )
                        )
                    }
                }
            }

            if !files.isEmpty {
                Section("Files") {
                    ForEach(files) { file in
                        referenceButton(
                            AnnouncementReference(
                                kind: .file,
                                targetID: file.id,
                                title: file.name,
                                subtitle: file.typeDescription
                            )
                        )
                    }
                }
            }

            if !media.isEmpty {
                Section("Media") {
                    ForEach(media) { item in
                        referenceButton(
                            AnnouncementReference(
                                kind: .media,
                                targetID: item.id,
                                title: item.caption ?? item.mediaType.rawValue.capitalized,
                                subtitle: item.timestamp
                            )
                        )
                    }
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Link Module Content")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
        .task {
            await load()
        }
    }

    private func referenceButton(_ reference: AnnouncementReference) -> some View {
        Button {
            onSelect(reference)
            dismiss()
        } label: {
            HStack {
                Image(systemName: reference.kind.icon)
                VStack(alignment: .leading) {
                    Text(reference.title)
                    if let subtitle = reference.subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func load() async {
        do {
            async let fetchedEvents = spaceService.fetchEvents(in: space)
            async let fetchedFiles = spaceService.fetchFiles(in: space)
            async let fetchedMessages = spaceService.fetchRecentMessages(in: space, limit: 100)
            events = try await fetchedEvents
            files = try await fetchedFiles
            media = try await fetchedMessages.flatMap { message in
                message.resolvedMediaItems.filter { item in
                    item.mediaType == .video || (item.mediaType == .photo && item.mediaCategory == "photo")
                }
            }
        } catch {
            errorMessage = "Some linked content could not be loaded."
        }
        isLoading = false
    }
}

private struct AnnouncementReferenceDestination: View {
    let space: Space
    let reference: AnnouncementReference

    var body: some View {
        switch reference.kind {
        case .event:
            EventsView(space: space, initialEventID: reference.targetID)
        case .file:
            FilesView(space: space, initialFileID: reference.targetID)
        case .media:
            AnnouncementLinkedMediaView(space: space, mediaID: reference.targetID)
        case .note, .list:
            SpaceEmptyStateView("Linked item unavailable", systemImage: "link.badge.plus")
        }
    }
}

@MainActor
private struct AnnouncementLinkedMediaView: View {
    let space: Space
    let mediaID: String

    @State private var media: SpaceMedia?
    @State private var errorMessage: String?

    var body: some View {
        Group {
            if let media {
                MediaViewerPlaceholderView(space: space, media: media)
            } else if let errorMessage {
                SpaceEmptyStateView("Media unavailable", systemImage: "photo", description: errorMessage)
            } else {
                ProgressView("Loading media…")
            }
        }
        .task {
            do {
                let messages = try await SpaceService().fetchRecentMessages(in: space, limit: 100)
                media = messages.flatMap(\.resolvedMediaItems).first { $0.id == mediaID }
                if media == nil {
                    errorMessage = "The original media item could not be found."
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

private struct FlowLayout: Layout {
    let spacing: CGFloat

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        layout(proposal: proposal, subviews: subviews).size
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        let result = layout(proposal: proposal, subviews: subviews)
        for (index, point) in result.points.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y),
                proposal: .unspecified
            )
        }
    }

    private func layout(proposal: ProposedViewSize, subviews: Subviews) -> (size: CGSize, points: [CGPoint]) {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var points: [CGPoint] = []

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            points.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }

        return (CGSize(width: proposal.width ?? x, height: y + rowHeight), points)
    }
}

private extension AnnouncementAttachment {
    func asSpaceMedia(spaceID: String) -> SpaceMedia? {
        guard let storagePath, let nonce else { return nil }
        let mediaType: MediaType
        let messageType: MessageType
        switch kind {
        case .image:
            mediaType = .photo
            messageType = .image
        case .video:
            mediaType = .video
            messageType = .video
        case .file:
            mediaType = .file
            messageType = .file
        case .link:
            return nil
        }
        let metadata = EncryptedMediaMetadata(
            mediaId: id,
            mediaType: mediaType,
            storagePath: storagePath,
            thumbnailStoragePath: nil,
            encryptionVersion: "aes-gcm-v1",
            nonce: nonce,
            thumbnailNonce: nil,
            mimeType: mimeType ?? "application/octet-stream",
            fileSize: fileSize ?? 0,
            width: nil,
            height: nil,
            duration: nil,
            createdAt: nil,
            uploadedBy: uploadedBy ?? ""
        )
        return SpaceMedia(
            id: id,
            spaceID: spaceID,
            type: messageType,
            mediaType: mediaType,
            placeholderImageName: kind.icon,
            caption: title,
            senderName: "",
            timestamp: "",
            metadata: metadata
        )
    }
}

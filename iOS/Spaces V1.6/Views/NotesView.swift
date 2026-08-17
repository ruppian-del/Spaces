import FirebaseAuth
import FirebaseFirestore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct NotesView: View {
    let space: Space
    let initialNoteID: String?
    @State private var notes: [SpaceNote] = []
    @State private var listener: ListenerRegistration?
    @State private var preferencesListener: ListenerRegistration?
    @State private var orderListener: ListenerRegistration?
    @State private var query = ""
    @State private var canCreate = false
    @State private var isCreating = false
    @State private var selectedNote: SpaceNote?
    @State private var sort: NoteSort = .recentlyUpdated
    @State private var errorMessage: String?
    @State private var manualOrder: [String] = []
    @State private var viewPreferences: [String: NoteViewPreference] = [:]
    private let service = NoteService()
    private let spaces = SpaceService()

    init(space: Space, initialNoteID: String? = nil) {
        self.space = space
        self.initialNoteID = initialNoteID
    }

    private var visible: [SpaceNote] {
        let filtered = notes.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) || $0.markdown.localizedCaseInsensitiveContains(query) }
        switch sort {
        case .recentlyUpdated: return filtered.sorted { $0.updatedAt > $1.updatedAt }
        case .manual:
            return filtered.sorted {
                (manualOrder.firstIndex(of: $0.id) ?? Int.max) < (manualOrder.firstIndex(of: $1.id) ?? Int.max)
            }
        case .recentlyViewed: return filtered.sorted { viewDate($0.id) > viewDate($1.id) }
        case .mostViewed: return filtered.sorted { viewCount($0.id) > viewCount($1.id) }
        }
    }

    var body: some View {
        List {
            if visible.isEmpty {
                SpaceEmptyStateView("No Notes", systemImage: "note.text", description: "Create shared documentation for this Space.")
            }
            ForEach(visible) { note in
                NavigationLink {
                    NoteDetailView(space: space, note: note)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(note.title).font(.headline)
                        Text(note.markdown).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        Text(note.updatedAt.formatted(date: .abbreviated, time: .shortened)).font(.caption2).foregroundStyle(.tertiary)
                    }
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    if sort == .manual && query.isEmpty {
                        Button { moveNote(note.id, offset: 1) } label: { Label("Down", systemImage: "arrow.down") }
                        Button { moveNote(note.id, offset: -1) } label: { Label("Up", systemImage: "arrow.up") }
                    }
                }
            }
        }
        .navigationTitle("Notes")
        .searchable(text: $query, prompt: "Search Notes")
        .navigationBarItems(trailing: HStack {
            Menu {
                Picker("Sort", selection: $sort) {
                    ForEach(NoteSort.allCases) { Text($0.title).tag($0) }
                }
            } label: { Image(systemName: "arrow.up.arrow.down") }
            if canCreate {
                Button { isCreating = true } label: { Image(systemName: "plus") }
            }
        })
        .sheet(isPresented: $isCreating) {
            NoteEditorView(space: space, note: nil) { note in
                Task {
                    do { try await service.save(note, in: space); isCreating = false }
                    catch { errorMessage = error.localizedDescription }
                }
            }
        }
        .navigationDestination(isPresented: Binding(
            get: { selectedNote != nil },
            set: { if !$0 { selectedNote = nil } }
        )) {
            if let selectedNote {
                NoteDetailView(space: space, note: selectedNote)
            }
        }
        .onAppear {
            listener = service.listen(in: space) { result in
                switch result {
                case .success(let values):
                    notes = values
                    syncManualOrder(with: values)
                    if selectedNote == nil, let initialNoteID { selectedNote = values.first { $0.id == initialNoteID } }
                case .failure(let error): errorMessage = error.localizedDescription
                }
            }
            preferencesListener = service.listenToViewPreferences(spaceID: space.id) { viewPreferences = $0 }
            orderListener = service.listenToManualOrder(spaceID: space.id) { remoteOrder in
                if !remoteOrder.isEmpty { manualOrder = remoteOrder }
            }
            Task { canCreate = await spaces.canPerform(.createNotes, in: space) }
        }
        .onDisappear {
            listener?.remove()
            preferencesListener?.remove()
            orderListener?.remove()
        }
        .alert("Notes", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) { Button("OK") {} } message: { Text(errorMessage ?? "") }
    }

    private func viewDate(_ id: String) -> Date { viewPreferences[id]?.lastViewedAt ?? .distantPast }
    private func viewCount(_ id: String) -> Int { viewPreferences[id]?.viewCount ?? 0 }
    private var orderKey: String { "notes.manualOrder.\(Auth.auth().currentUser?.uid ?? "local").\(space.id)" }
    private func syncManualOrder(with values: [SpaceNote]) {
        let saved = UserDefaults.standard.stringArray(forKey: orderKey) ?? []
        manualOrder = saved.filter { id in values.contains { $0.id == id } } + values.map(\.id).filter { !saved.contains($0) }
    }
    private func moveNote(_ id: String, offset: Int) {
        guard let index = manualOrder.firstIndex(of: id) else { return }
        let destination = index + offset
        guard manualOrder.indices.contains(destination) else { return }
        manualOrder.swapAt(index, destination)
        UserDefaults.standard.set(manualOrder, forKey: orderKey)
        Task { await service.saveManualOrder(manualOrder, spaceID: space.id) }
    }
}

private enum NoteSort: String, CaseIterable, Identifiable {
    case manual, recentlyViewed, mostViewed, recentlyUpdated
    var id: String { rawValue }
    var title: String {
        switch self { case .manual: "Manual"; case .recentlyViewed: "Recently Viewed"; case .mostViewed: "Most Viewed"; case .recentlyUpdated: "Recently Updated" }
    }
}

private struct NoteDetailView: View {
    let space: Space
    @State var note: SpaceNote
    @State private var comments: [SpaceNoteComment] = []
    @State private var commentListener: ListenerRegistration?
    @State private var comment = ""
    @State private var isEditing = false
    @State private var canEdit = false
    @State private var canDelete = false
    @State private var confirmDelete = false
    @State private var sharedURL: NoteShareURL?
    @State private var errorMessage: String?
    @Environment(\.dismiss) private var dismiss
    private let service = NoteService()
    private let spaces = SpaceService()

    var body: some View {
        List {
            Section {
                NoteRichBody(markdown: note.markdown, attachments: note.attachments) { open($0) }
            }
            if note.attachments.contains(where: { !$0.isMedia || !note.markdown.contains("{{media:\($0.id)}}") }) {
                Section("Attachments") {
                    ForEach(note.attachments.filter { !$0.isMedia || !note.markdown.contains("{{media:\($0.id)}}") }) { attachment in
                        Button { open(attachment) } label: { Label(attachment.name, systemImage: attachment.isMedia ? "photo" : "doc") }
                    }
                }
            }
            if !note.links.isEmpty {
                Section("Linked Items") {
                    ForEach(note.links) { link in
                        NavigationLink { noteLinkedDestination(link) } label: { Label("\(link.moduleType.title): \(link.title)", systemImage: link.icon) }
                    }
                }
            }
            Section("Comments") {
                ForEach(comments) { value in
                    VStack(alignment: .leading) { Text(value.authorName).font(.caption.bold()); Text(value.body); Text(value.createdAt.formatted()).font(.caption2).foregroundStyle(.secondary) }
                }
                TextField("Add a comment", text: $comment, axis: .vertical).textInputAutocapitalization(.never)
                Button("Post Comment") { postComment() }.disabled(comment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .navigationTitle(note.title)
        .toolbar {
            if canEdit { Button("Edit") { isEditing = true } }
            if canDelete { Button(role: .destructive) { confirmDelete = true } label: { Image(systemName: "trash") } }
        }
        .sheet(isPresented: $isEditing) {
            NoteEditorView(space: space, note: note) { updated in
                Task { do { try await service.save(updated, in: space); note = updated; isEditing = false } catch { errorMessage = error.localizedDescription } }
            }
        }
        .sheet(item: $sharedURL) { ShareSheet(items: [$0.url]) }
        .confirmationDialog("Delete this Note?", isPresented: $confirmDelete) {
            Button("Delete Note", role: .destructive) { Task { do { try await service.delete(note, in: space); dismiss() } catch { errorMessage = error.localizedDescription } } }
        }
        .onAppear {
            let countKey = "note.viewCount.\(note.id)"
            UserDefaults.standard.set(UserDefaults.standard.integer(forKey: countKey) + 1, forKey: countKey)
            UserDefaults.standard.set(Date(), forKey: "note.lastViewed.\(note.id)")
            Task { await service.recordView(noteID: note.id, spaceID: space.id) }
            commentListener = service.listenToComments(space: space, noteID: note.id) { if case .success(let values) = $0 { comments = values } }
            Task {
                let own = note.createdBy == Auth.auth().currentUser?.uid
                canEdit = await spaces.canPerform(own ? .editOwnNotes : .editAnyNotes, in: space)
                canDelete = await spaces.canPerform(own ? .deleteOwnNotes : .deleteAnyNotes, in: space)
            }
        }
        .onDisappear { commentListener?.remove() }
        .alert("Notes", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) { Button("OK") {} } message: { Text(errorMessage ?? "") }
    }

    private func postComment() {
        let body = comment.trimmingCharacters(in: .whitespacesAndNewlines)
        Task { do { try await service.addComment(body, authorName: Auth.auth().currentUser?.displayName ?? "Member", note: note, space: space); comment = "" } catch { errorMessage = error.localizedDescription } }
    }
    private func open(_ attachment: SpaceNoteAttachment) {
        Task { do {
            let data = try await service.download(attachment, space: space)
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("NoteAttachments", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let url = folder.appendingPathComponent(attachment.name); try data.write(to: url, options: .atomic); sharedURL = .init(url: url)
        } catch { errorMessage = error.localizedDescription } }
    }
    @ViewBuilder private func noteLinkedDestination(_ link: SpaceLinkAttachment) -> some View {
        switch link.moduleType {
        case .announcements: AnnouncementsView(space: space, initialAnnouncementID: link.targetId)
        case .polls: PollsView(space: space, initialPollID: link.targetId)
        case .files: FilesView(space: space, initialFileID: link.targetId)
        case .events: EventsView(space: space, initialEventID: link.targetId)
        case .rooms: RoomsView(space: space, initialRoomID: link.targetId)
        case .media: PhotosView(space: space, initialMediaID: link.targetId)
        case .lists: ListsView(space: space, initialListID: link.targetId)
        case .notes: NotesView(space: space, initialNoteID: link.targetId)
        }
    }
}

private struct NoteShareURL: Identifiable { let url: URL; var id: URL { url } }

private struct NoteEditorView: View {
    let space: Space
    let note: SpaceNote?
    let onSave: (SpaceNote) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var markdown: String
    @State private var attachments: [SpaceNoteAttachment]
    @State private var links: [SpaceLinkAttachment]
    @State private var photo: PhotosPickerItem?
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showLinks = false
    @State private var showMentions = false
    @State private var members: [SpaceMember] = []
    @State private var membersListener: ListenerRegistration?
    @State private var errorMessage: String?
    @State private var noteID: String
    private let service = NoteService()

    init(space: Space, note: SpaceNote?, onSave: @escaping (SpaceNote) -> Void) {
        self.space = space; self.note = note; self.onSave = onSave
        _title = State(initialValue: note?.title ?? ""); _markdown = State(initialValue: note?.markdown ?? "")
        _attachments = State(initialValue: note?.attachments ?? []); _links = State(initialValue: note?.links ?? [])
        _noteID = State(initialValue: note?.id ?? UUID().uuidString)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("Note title", text: $title).font(.title2.bold()).padding().textInputAutocapitalization(.never)
                HStack(spacing: 8) {
                    Menu {
                        Button("Heading 1") { insertBlock("# ") }
                        Button("Heading 2") { insertBlock("## ") }
                        Divider()
                        Button("Bulleted List") { insertBlock("- ") }
                        Button("Numbered List") { insertBlock("1. ") }
                        Button("Checklist") { insertBlock("- [ ] ") }
                    } label: {
                        editorTool("textformat", accessibilityLabel: "Format")
                    }
                    Button { markdown += " [link title](https://)" } label: { editorTool("link", accessibilityLabel: "Link") }
                    Button { showMentions = true } label: { editorTool("at", accessibilityLabel: "Mention") }
                    Button { showPhotos = true } label: { editorTool("photo", accessibilityLabel: "Media") }
                    Button { showFiles = true } label: { editorTool("paperclip", accessibilityLabel: "File") }
                    Button { showLinks = true } label: { editorTool("square.grid.2x2", accessibilityLabel: "Tag Module") }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                TextEditor(text: $markdown).padding().textInputAutocapitalization(.never)
            }
            .navigationTitle(note == nil ? "New Note" : "Edit Note")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let now = Date()
                        onSave(.init(id: noteID, spaceID: space.id, title: title.trimmingCharacters(in: .whitespacesAndNewlines), markdown: markdown, attachments: attachments, links: links, createdBy: note?.createdBy ?? "", createdAt: note?.createdAt ?? now, updatedAt: now))
                    }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .photosPicker(isPresented: $showPhotos, selection: $photo, matching: .any(of: [.images, .videos]))
        .onChange(of: photo) { value in guard let value else { return }; Task { do {
            guard let data = try await value.loadTransferable(type: Data.self) else { return }
            let attachment = try await service.upload(data: data, name: "Note Media", mimeType: value.supportedContentTypes.first?.preferredMIMEType ?? "application/octet-stream", isMedia: true, noteID: noteID, space: space)
            attachments.append(attachment)
            markdown += markdown.isEmpty || markdown.hasSuffix("\n") ? "{{media:\(attachment.id)}}\n" : "\n{{media:\(attachment.id)}}\n"
        } catch { errorMessage = error.localizedDescription } } }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item]) { result in Task { do {
            let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            attachments.append(try await service.upload(data: Data(contentsOf: url), name: url.lastPathComponent, mimeType: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream", isMedia: false, noteID: noteID, space: space))
        } catch { errorMessage = error.localizedDescription } } }
        .sheet(isPresented: $showLinks) {
            NoteLinkPicker(space: space) { link in if !links.contains(where: { $0.moduleType == link.moduleType && $0.targetId == link.targetId }) { links.append(link) }; showLinks = false }
        }
        .sheet(isPresented: $showMentions) {
            NavigationStack {
                List(members) { member in
                    Button {
                        markdown += markdown.isEmpty || markdown.hasSuffix(" ") || markdown.hasSuffix("\n") ? "@\(member.displayName)" : " @\(member.displayName)"
                        showMentions = false
                    } label: {
                        Label(member.displayName, systemImage: "person.crop.circle")
                    }
                }
                .navigationTitle("Mention Member")
                .toolbar { Button("Cancel") { showMentions = false } }
            }
        }
        .onAppear {
            membersListener = SpaceService().listenToMembers(for: space) {
                if case .success(let values) = $0 { members = values }
            }
        }
        .onDisappear { membersListener?.remove() }
    }
    private func insertBlock(_ prefix: String) {
        markdown += markdown.isEmpty || markdown.hasSuffix("\n") ? prefix : "\n\(prefix)"
    }

    private func editorTool(_ systemName: String, accessibilityLabel: String) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 17, weight: .semibold))
            .frame(maxWidth: .infinity)
            .frame(height: 38)
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            .accessibilityLabel(accessibilityLabel)
    }
}

private struct NoteRichBody: View {
    let markdown: String
    let attachments: [SpaceNoteAttachment]
    let openAttachment: (SpaceNoteAttachment) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(Array(markdown.components(separatedBy: .newlines).enumerated()), id: \.offset) { _, line in
                if let attachment = inlineMedia(for: line) {
                    Button { openAttachment(attachment) } label: {
                        Label(attachment.name, systemImage: attachment.mimeType.hasPrefix("video/") ? "play.rectangle.fill" : "photo.fill")
                            .frame(maxWidth: .infinity, minHeight: 110)
                            .background(.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                } else if line.hasPrefix("# ") {
                    rendered(String(line.dropFirst(2))).font(.title.bold())
                } else if line.hasPrefix("## ") {
                    rendered(String(line.dropFirst(3))).font(.title2.bold())
                } else if line.hasPrefix("- [ ] ") {
                    Label { rendered(String(line.dropFirst(6))) } icon: { Image(systemName: "square") }
                } else if line.hasPrefix("- [x] ") || line.hasPrefix("- [X] ") {
                    Label { rendered(String(line.dropFirst(6))).strikethrough() } icon: { Image(systemName: "checkmark.square.fill") }
                } else if line.hasPrefix("- ") {
                    HStack(alignment: .firstTextBaseline) { Text("•"); rendered(String(line.dropFirst(2))) }
                } else {
                    rendered(line)
                }
            }
        }
        .textSelection(.enabled)
    }

    private func inlineMedia(for line: String) -> SpaceNoteAttachment? {
        guard line.hasPrefix("{{media:"), line.hasSuffix("}}") else { return nil }
        let id = String(line.dropFirst(8).dropLast(2))
        return attachments.first { $0.id == id && $0.isMedia }
    }

    private func rendered(_ value: String) -> Text {
        guard let attributed = try? AttributedString(markdown: value) else { return Text(value) }
        return Text(attributed)
    }
}

private struct NoteLinkPicker: View {
    let space: Space; let onSelect: (SpaceLinkAttachment) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var module: SpaceLinkModuleDescriptor?
    @State private var items: [SpaceLinkRegistryItem] = []
    private let registry = SpaceLinkRegistry()
    var body: some View {
        NavigationStack {
            List {
                if module == nil {
                    ForEach(registry.availableModules(in: space)) { value in Button { module = value; Task { items = (try? await registry.fetchItems(for: value.moduleType, in: space)) ?? [] } } label: { Label(value.title, systemImage: value.moduleType.icon) } }
                } else {
                    ForEach(items) { item in Button(item.title) { onSelect(item.attachment) } }
                }
            }.navigationTitle(module?.title ?? "Tag Module").toolbar { Button(module == nil ? "Cancel" : "Back") { if module == nil { dismiss() } else { module = nil; items = [] } } }
        }
    }
}

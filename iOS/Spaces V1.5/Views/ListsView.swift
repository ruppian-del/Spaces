import FirebaseAuth
import FirebaseFirestore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ListsView: View {
    let space: Space
    let initialListID: String?
    @State private var lists: [SpaceList] = []
    @State private var listener: ListenerRegistration?
    @State private var query = ""
    @State private var canCreate = false
    @State private var isCreating = false
    @State private var errorMessage: String?
    @State private var selectedList: SpaceList?
    private let service = ListService()
    private let spaceService = SpaceService()

    init(space: Space, initialListID: String? = nil) {
        self.space = space
        self.initialListID = initialListID
    }

    private var visibleLists: [SpaceList] {
        query.isEmpty ? lists : lists.filter { $0.title.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        List {
            if visibleLists.isEmpty {
                SpaceEmptyStateView("No Lists", systemImage: "checklist", description: "Create a shared checklist for this Space.")
            } else {
                ForEach(visibleLists) { list in
                    NavigationLink {
                        ListDetailView(space: space, list: list)
                    } label: {
                        Label(list.title, systemImage: "checklist")
                    }
                }
            }
        }
        .navigationTitle("Lists")
        .navigationDestination(isPresented: Binding(
            get: { selectedList != nil },
            set: { if !$0 { selectedList = nil } }
        )) {
            if let selectedList {
                ListDetailView(space: space, list: selectedList)
            }
        }
        .searchable(text: $query, prompt: "Search Lists")
        .toolbar {
            if canCreate {
                Button { isCreating = true } label: { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $isCreating) {
            ListEditorSheet(space: space, existing: nil) { list in
                Task {
                    do {
                        try await service.saveList(list, in: space)
                        isCreating = false
                    } catch { errorMessage = error.localizedDescription }
                }
            }
        }
        .alert("Lists", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
        .onAppear {
            listener = service.listenToLists(in: space) { result in
                switch result {
                case .success(let values):
                    lists = values
                    if selectedList == nil, let initialListID { selectedList = values.first { $0.id == initialListID } }
                case .failure(let error): errorMessage = error.localizedDescription
                }
            }
            Task { canCreate = await spaceService.canPerform(.createLists, in: space) }
        }
        .onDisappear { listener?.remove() }
    }
}

private struct ListDetailView: View {
    let space: Space
    @State var list: SpaceList
    @State private var items: [SpaceListItem] = []
    @State private var listener: ListenerRegistration?
    @State private var members: [SpaceMember] = []
    @State private var membersListener: ListenerRegistration?
    @State private var query = ""
    @State private var editingItem: SpaceListItem?
    @State private var isAddingItem = false
    @State private var isEditingList = false
    @State private var canEdit = false
    @State private var canDelete = false
    @State private var isDeleting = false
    @State private var errorMessage: String?
    @State private var sharedAttachmentURL: URL?
    @Environment(\.dismiss) private var dismiss
    private let service = ListService()
    private let spaceService = SpaceService()

    private var visibleItems: [SpaceListItem] {
        guard !query.isEmpty else { return items }
        return items.filter {
            $0.title.localizedCaseInsensitiveContains(query) ||
            $0.notes.localizedCaseInsensitiveContains(query)
        }
    }

    private var unsectionedItems: [SpaceListItem] {
        visibleItems.filter { $0.sectionID == nil }
    }

    private func items(in section: SpaceListSection) -> [SpaceListItem] {
        visibleItems.filter { $0.sectionID == section.id }
    }

    var body: some View {
        List {
            if !list.links.isEmpty {
                Section("Linked Items") {
                    ForEach(list.links) { link in
                        NavigationLink {
                            linkedDestination(link)
                        } label: {
                            Label("\(link.moduleType.title): \(link.title)", systemImage: link.icon)
                        }
                    }
                }
            }
            if list.sections.isEmpty {
                itemRows(visibleItems.filter { $0.sectionID == nil })
            } else {
                if !unsectionedItems.isEmpty {
                    Section("Items") { itemRows(unsectionedItems) }
                }
                ForEach(list.sections) { section in
                    Section(section.title) {
                        itemRows(items(in: section))
                    }
                }
            }
        }
        .navigationTitle(list.title)
        .searchable(text: $query, prompt: "Search this List")
        .toolbar {
            if canEdit {
                ToolbarItemGroup(placement: .primaryAction) {
                    EditButton()
                    Button { isAddingItem = true } label: { Image(systemName: "plus") }
                    Menu {
                        Button("List Settings") { isEditingList = true }
                        if canDelete {
                            Button("Delete List", role: .destructive) { isDeleting = true }
                        }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        }
        .sheet(isPresented: $isAddingItem) {
            ListItemEditorSheet(space: space, list: list, item: nil, members: members) { item in save(item) }
        }
        .sheet(item: $editingItem) { item in
            ListItemEditorSheet(space: space, list: list, item: item, members: members) { updated in save(updated) }
        }
        .sheet(isPresented: $isEditingList) {
            ListEditorSheet(space: space, existing: list) { updated in
                Task {
                    do { try await service.saveList(updated, in: space); list = updated; isEditingList = false }
                    catch { errorMessage = error.localizedDescription }
                }
            }
        }
        .confirmationDialog("Delete this List?", isPresented: $isDeleting) {
            Button("Delete List", role: .destructive) {
                Task {
                    do { try await service.deleteList(list, in: space); dismiss() }
                    catch { errorMessage = error.localizedDescription }
                }
            }
        }
        .alert("Lists", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
        .sheet(item: Binding(
            get: { sharedAttachmentURL.map(ListShareableURL.init(url:)) },
            set: { if $0 == nil { sharedAttachmentURL = nil } }
        )) { item in
            ShareSheet(items: [item.url])
        }
        .onAppear {
            listener = service.listenToItems(in: space, listID: list.id) { result in
                if case .success(let values) = result { items = values }
                if case .failure(let error) = result { errorMessage = error.localizedDescription }
            }
            membersListener = spaceService.listenToMembers(for: space) { result in
                if case .success(let values) = result { members = values }
            }
            Task {
                let own = list.createdBy == Auth.auth().currentUser?.uid
                canEdit = await spaceService.canPerform(own ? .editOwnLists : .editAnyLists, in: space)
                canDelete = await spaceService.canPerform(own ? .deleteOwnLists : .deleteAnyLists, in: space)
            }
        }
        .onDisappear { listener?.remove(); membersListener?.remove() }
    }

    @ViewBuilder
    private func itemRows(_ values: [SpaceListItem]) -> some View {
        ForEach(values) { item in
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    Button {
                        var updated = item
                        updated.isCompleted.toggle()
                        updated.updatedAt = Date()
                        save(updated)
                    } label: {
                        Image(systemName: item.isCompleted ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                    }
                    .buttonStyle(.plain)
                    .disabled(!canEdit)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.title).strikethrough(item.isCompleted)
                        if !item.notes.isEmpty { Text(item.notes).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                        if !item.assignedMemberIDs.isEmpty {
                            Text("Assigned: " + members.filter { item.assignedMemberIDs.contains($0.id) }.map { "@\($0.displayName)" }.joined(separator: ", "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        if let dueDate = item.dueDate {
                            Text("Due \(dueDate.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        ForEach(item.attachments) { attachment in
                            Button {
                                openAttachment(attachment)
                            } label: {
                                Label(attachment.name, systemImage: attachment.isMedia ? "photo" : "paperclip")
                                    .font(.caption)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Spacer(minLength: 8)
                    if canEdit {
                        Button {
                            editingItem = item
                        } label: {
                            Image(systemName: "pencil")
                                .frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Edit \(item.title)")
                    }
                }
                if !item.links.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(item.links) { link in
                            NavigationLink {
                                linkedDestination(link)
                            } label: {
                                HStack {
                                    Label("\(link.moduleType.title): \(link.title)", systemImage: link.icon)
                                        .font(.subheadline)
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                }
                                .padding(.vertical, 6)
                                .padding(.horizontal, 10)
                                .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.leading, 32)
                }
            }
            .swipeActions {
                if canEdit {
                    Button("Delete", role: .destructive) {
                        Task {
                            do { try await service.deleteItem(item, list: list, space: space) }
                            catch { errorMessage = error.localizedDescription }
                        }
                    }
                }
            }
        }
        .onMove { offsets, destination in
            var reordered = values
            reordered.move(fromOffsets: offsets, toOffset: destination)
            for (index, var item) in reordered.enumerated() {
                item.order = index
                save(item, dismissEditor: false)
            }
        }
    }

    private func save(_ item: SpaceListItem, dismissEditor: Bool = true) {
        Task {
            do {
                var orderedItem = item
                if item.createdBy.isEmpty && item.order == Int.max {
                    orderedItem.order = (items.map(\.order).max() ?? -1) + 1
                }
                try await service.saveItem(orderedItem, in: list, space: space)
                if dismissEditor { isAddingItem = false; editingItem = nil }
            } catch { errorMessage = error.localizedDescription }
        }
    }

    private func openAttachment(_ attachment: SpaceListItemAttachment) {
        Task {
            do {
                let data = try await service.downloadAttachment(attachment, space: space)
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent("SpacesListAttachments", isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let url = directory.appendingPathComponent(attachment.name)
                try data.write(to: url, options: .atomic)
                sharedAttachmentURL = url
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    @ViewBuilder
    private func linkedDestination(_ link: SpaceLinkAttachment) -> some View {
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

private struct ListEditorSheet: View {
    let space: Space
    let existing: SpaceList?
    let onSave: (SpaceList) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var sections: [SpaceListSection]
    @State private var newSection = ""
    @State private var links: [SpaceLinkAttachment]
    @State private var isShowingLinkPicker = false

    init(space: Space, existing: SpaceList?, onSave: @escaping (SpaceList) -> Void) {
        self.space = space; self.existing = existing; self.onSave = onSave
        _title = State(initialValue: existing?.title ?? "")
        _sections = State(initialValue: existing?.sections ?? [])
        _links = State(initialValue: existing?.links ?? [])
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("List name", text: $title)
                    .textInputAutocapitalization(.never)
                Section("Optional Sections") {
                    ForEach($sections) { $section in
                        TextField("Section", text: $section.title)
                            .textInputAutocapitalization(.never)
                    }
                        .onDelete { sections.remove(atOffsets: $0); normalizeSections() }
                        .onMove { sections.move(fromOffsets: $0, toOffset: $1); normalizeSections() }
                    HStack {
                        TextField("New section", text: $newSection)
                            .textInputAutocapitalization(.never)
                        Button("Add") {
                            let trimmed = newSection.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !trimmed.isEmpty {
                                sections.append(.init(title: trimmed, order: sections.count))
                                newSection = ""
                            }
                        }
                    }
                }
                Section("Linked Items") {
                    ForEach(links) { link in
                        Label("\(link.moduleType.title): \(link.title)", systemImage: link.icon)
                    }
                    .onDelete { links.remove(atOffsets: $0) }
                    Button("Add Linked Item") { isShowingLinkPicker = true }
                }
            }
            .navigationTitle(existing == nil ? "New List" : "List Settings")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let now = Date()
                        onSave(.init(
                            id: existing?.id ?? UUID().uuidString, spaceID: space.id,
                            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                            sections: sections, links: links,
                            createdBy: existing?.createdBy ?? "", createdAt: existing?.createdAt ?? now, updatedAt: now
                        ))
                    }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                ToolbarItem(placement: .bottomBar) { EditButton() }
            }
        }
        .sheet(isPresented: $isShowingLinkPicker) {
            ListCrossLinkPicker(space: space) { link in
                if !links.contains(where: { $0.moduleType == link.moduleType && $0.targetId == link.targetId }) {
                    links.append(link)
                }
                isShowingLinkPicker = false
            }
        }
    }
    private func normalizeSections() { for index in sections.indices { sections[index].order = index } }
}

private struct ListShareableURL: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct ListCrossLinkPicker: View {
    let space: Space
    let onSelect: (SpaceLinkAttachment) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var activeModule: SpaceLinkModuleDescriptor?
    @State private var items: [SpaceLinkRegistryItem] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    private let registry = SpaceLinkRegistry()

    var body: some View {
        NavigationStack {
            List {
                if let activeModule {
                    if isLoading { ProgressView() }
                    ForEach(items) { item in
                        Button { onSelect(item.attachment) } label: {
                            VStack(alignment: .leading) {
                                Text(item.title).foregroundStyle(.primary)
                                if let subtitle = item.subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                } else {
                    ForEach(registry.availableModules(in: space)) { module in
                        Button {
                            activeModule = module
                            isLoading = true
                            Task {
                                do { items = try await registry.fetchItems(for: module.moduleType, in: space) }
                                catch { errorMessage = error.localizedDescription }
                                isLoading = false
                            }
                        } label: {
                            Label(module.title, systemImage: module.moduleType.icon)
                        }
                    }
                }
            }
            .navigationTitle(activeModule == nil ? "Link Module" : activeModule?.title ?? "Items")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(activeModule == nil ? "Cancel" : "Back") {
                        if activeModule == nil { dismiss() } else { activeModule = nil; items = [] }
                    }
                }
            }
        }
        .alert("Link", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }
}

private struct ListItemEditorSheet: View {
    let space: Space
    let list: SpaceList
    let item: SpaceListItem?
    let members: [SpaceMember]
    let onSave: (SpaceListItem) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var notes: String
    @State private var assignees: Set<String>
    @State private var hasDueDate: Bool
    @State private var dueDate: Date
    @State private var sectionID: String?
    @State private var attachments: [SpaceListItemAttachment]
    @State private var links: [SpaceLinkAttachment]
    @State private var isShowingLinkPicker = false
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var isShowingPhotos = false
    @State private var isShowingFiles = false
    @State private var errorMessage: String?
    private let service = ListService()

    init(space: Space, list: SpaceList, item: SpaceListItem?, members: [SpaceMember], onSave: @escaping (SpaceListItem) -> Void) {
        self.space = space; self.list = list; self.item = item; self.members = members; self.onSave = onSave
        _title = State(initialValue: item?.title ?? "")
        _notes = State(initialValue: item?.notes ?? "")
        _assignees = State(initialValue: item?.assignedMemberIDs ?? [])
        _hasDueDate = State(initialValue: item?.dueDate != nil)
        _dueDate = State(initialValue: item?.dueDate ?? Date())
        _sectionID = State(initialValue: item?.sectionID)
        _attachments = State(initialValue: item?.attachments ?? [])
        _links = State(initialValue: item?.links ?? [])
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Item", text: $title)
                    .textInputAutocapitalization(.never)
                TextField("Notes or details", text: $notes, axis: .vertical)
                    .textInputAutocapitalization(.never)
                Picker("Section", selection: $sectionID) {
                    Text("No Section").tag(String?.none)
                    ForEach(list.sections) { Text($0.title).tag(Optional($0.id)) }
                }
                Section("Assign with @mentions") {
                    ForEach(members) { member in
                        Toggle("@\(member.displayName)", isOn: assigneeBinding(for: member.id))
                    }
                }
                Toggle("Due Date", isOn: $hasDueDate)
                if hasDueDate { DatePicker("Due", selection: $dueDate) }
                Section("Attachments") {
                    ForEach(attachments) { attachment in
                        Label(attachment.name, systemImage: attachment.isMedia ? "photo" : "doc")
                    }
                    .onDelete { attachments.remove(atOffsets: $0) }
                    Button("Attach Media") { isShowingPhotos = true }
                    Button("Attach File") { isShowingFiles = true }
                }
                Section("Linked Items") {
                    ForEach(links) { link in
                        Label("\(link.moduleType.title): \(link.title)", systemImage: link.icon)
                    }
                    .onDelete { links.remove(atOffsets: $0) }
                    Button("Tag Another Module") { isShowingLinkPicker = true }
                }
            }
            .navigationTitle(item == nil ? "New Item" : "Edit Item")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let now = Date()
                        onSave(.init(
                            id: item?.id ?? UUID().uuidString, listID: list.id,
                            title: title.trimmingCharacters(in: .whitespacesAndNewlines), notes: notes,
                            isCompleted: item?.isCompleted ?? false, assignedMemberIDs: assignees,
                            dueDate: hasDueDate ? dueDate : nil, sectionID: sectionID,
                            order: item?.order ?? Int.max, attachments: attachments,
                            links: links,
                            createdBy: item?.createdBy ?? "", createdAt: item?.createdAt ?? now, updatedAt: now
                        ))
                    }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .photosPicker(isPresented: $isShowingPhotos, selection: $selectedPhoto, matching: .any(of: [.images, .videos]))
        .onChange(of: selectedPhoto) { value in
            guard let value else { return }
            Task {
                do {
                    guard let data = try await value.loadTransferable(type: Data.self) else { return }
                    attachments.append(try await service.uploadAttachment(
                        data: data, name: "List Media", mimeType: value.supportedContentTypes.first?.preferredMIMEType ?? "application/octet-stream",
                        isMedia: true, space: space, listID: list.id
                    ))
                    selectedPhoto = nil
                } catch { errorMessage = error.localizedDescription }
            }
        }
        .fileImporter(isPresented: $isShowingFiles, allowedContentTypes: [.item]) { result in
            Task {
                do {
                    let url = try result.get()
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() } }
                    attachments.append(try await service.uploadAttachment(
                        data: try Data(contentsOf: url), name: url.lastPathComponent,
                        mimeType: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream",
                        isMedia: false, space: space, listID: list.id
                    ))
                } catch { errorMessage = error.localizedDescription }
            }
        }
        .sheet(isPresented: $isShowingLinkPicker) {
            ListCrossLinkPicker(space: space) { link in
                if !links.contains(where: { $0.moduleType == link.moduleType && $0.targetId == link.targetId }) {
                    links.append(link)
                }
                isShowingLinkPicker = false
            }
        }
        .alert("Attachment", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func assigneeBinding(for memberID: String) -> Binding<Bool> {
        Binding(
            get: { assignees.contains(memberID) },
            set: { isSelected in
                if isSelected {
                    assignees.insert(memberID)
                } else {
                    assignees.remove(memberID)
                }
            }
        )
    }
}

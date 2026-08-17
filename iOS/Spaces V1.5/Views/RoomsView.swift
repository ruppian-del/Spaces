import FirebaseAuth
import FirebaseFirestore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import UIKit

private enum RoomAttachmentAction {
    case camera
    case link
    case photos
    case files
    case gifs
}

struct RoomsView: View {
    let space: Space
    let initialRoomID: String?

    @State private var rooms: [SpaceRoom] = []
    @State private var members: [SpaceMember] = []
    @State private var listener: ListenerRegistration?
    @State private var membersListener: ListenerRegistration?
    @State private var errorMessage: String?
    @State private var isShowingCreateRoom = false
    @State private var canCreateRooms = false
    @State private var selectedRoom: SpaceRoom?
    private let service = RoomService()
    private let spaceService = SpaceService()

    init(space: Space, initialRoomID: String? = nil) {
        self.space = space
        self.initialRoomID = initialRoomID
    }

    var body: some View {
        List {
            if rooms.isEmpty {
                SpaceEmptyStateView(
                    "No Rooms",
                    systemImage: "bubble.left.and.text.bubble.right",
                    description: "Create a Room to organize a discussion."
                )
            } else {
                ForEach(rooms) { room in
                    NavigationLink {
                        RoomConversationView(space: space, room: room, members: members)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Label(room.name, systemImage: room.isPrivate ? "lock.fill" : "number")
                                .font(.headline)
                            if !room.topic.isEmpty {
                                Text(room.topic)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Rooms")
        .navigationDestination(isPresented: Binding(
            get: { selectedRoom != nil },
            set: { if !$0 { selectedRoom = nil } }
        )) {
            if let selectedRoom {
                RoomConversationView(space: space, room: selectedRoom, members: members)
            }
        }
        .toolbar {
            if canCreateRooms {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        isShowingCreateRoom = true
                    } label: {
                        Label("Create Room", systemImage: "plus")
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingCreateRoom) {
            CreateRoomSheet(space: space, members: members) { room in
                Task {
                    do {
                        try await service.saveRoom(room)
                        isShowingCreateRoom = false
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        }
        .onAppear {
            guard listener == nil else { return }
            listener = service.listenToRooms(in: space) { result in
                switch result {
                case .success(let values):
                    rooms = values
                    if selectedRoom == nil, let initialRoomID {
                        selectedRoom = values.first { $0.id == initialRoomID }
                    }
                    case .failure(let error): errorMessage = error.localizedDescription
                }
            }
            membersListener = spaceService.listenToMembers(for: space) { result in
                if case .success(let values) = result { members = values }
            }
            Task {
                canCreateRooms = await spaceService.canPerform(.createRooms, in: space)
            }
        }
        .onDisappear {
            listener?.remove()
            listener = nil
            membersListener?.remove()
            membersListener = nil
        }
        .alert("Rooms", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }
}

private struct CreateRoomSheet: View {
    let space: Space
    let members: [SpaceMember]
    let onCreate: (SpaceRoom) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var topic = ""
    @State private var isPrivate = false
    @State private var isReadOnly = false
    @State private var selectedMemberIDs: Set<String> = []

    private var currentUserID: String { Auth.auth().currentUser?.uid ?? "" }

    var body: some View {
        NavigationStack {
            Form {
                Section("Room") {
                    TextField("Name", text: $name)
                    TextField("Topic (optional)", text: $topic, axis: .vertical)
                    Toggle("Private Room", isOn: $isPrivate)
                    Toggle("Only Owners and Admins Can Post", isOn: $isReadOnly)
                }

                if isPrivate {
                    Section {
                        ForEach(members.filter { $0.id != currentUserID }) { member in
                            Button {
                                if selectedMemberIDs.contains(member.id) {
                                    selectedMemberIDs.remove(member.id)
                                } else {
                                    selectedMemberIDs.insert(member.id)
                                }
                            } label: {
                                HStack {
                                    Text("\(member.emojiAvatar) \(member.displayName)")
                                        .foregroundStyle(.primary)
                                    Spacer()
                                    if selectedMemberIDs.contains(member.id) {
                                        Image(systemName: "checkmark.circle.fill")
                                    }
                                }
                            }
                        }
                    } header: {
                        Text("Room Members")
                    } footer: {
                        Text("Only selected members can see and participate in this Room.")
                    }
                }
            }
            .navigationTitle("New Room")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        let now = Date()
                        var memberIDs = selectedMemberIDs
                        memberIDs.insert(currentUserID)
                        onCreate(SpaceRoom(
                            id: UUID().uuidString,
                            spaceID: space.id,
                            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                            topic: topic.trimmingCharacters(in: .whitespacesAndNewlines),
                            isPrivate: isPrivate,
                            memberIDs: memberIDs,
                            createdBy: currentUserID,
                            createdAt: now,
                            updatedAt: now,
                            postingMemberIDs: isReadOnly
                                ? Set(members.filter { $0.role == .owner || $0.role == .admin }.map(\.id))
                                    .union([space.ownerId])
                                : nil
                        ))
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || currentUserID.isEmpty)
                }
            }
        }
    }
}

private struct RoomConversationView: View {
    @Environment(\.dismiss) private var dismiss
    let space: Space
    @State var room: SpaceRoom
    let members: [SpaceMember]

    @State private var messages: [RoomMessage] = []
    @State private var draft = ""
    @State private var searchText = ""
    @State private var replyingTo: RoomMessage?
    @State private var composerLinks: [SpaceLinkAttachment] = []
    @State private var isShowingLinkPicker = false
    @State private var isAttachmentMenuPresented = false
    @State private var pendingAttachmentAction: RoomAttachmentAction?
    @State private var isShowingGiphyPicker = false
    @State private var isShowingCamera = false
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var isShowingPhotoPicker = false
    @State private var isShowingFilePicker = false
    @State private var sharedAttachmentURL: URL?
    @State private var listener: ListenerRegistration?
    @State private var roomListener: ListenerRegistration?
    @State private var canManageMembers = false
    @State private var canEditRoom = false
    @State private var canDeleteRoom = false
    @State private var canPostInRooms = false
    @State private var canDeleteOthersMessages = false
    @State private var editingMessage: RoomMessage?
    @State private var editingMessageBody = ""
    @State private var deletingMessage: RoomMessage?
    @State private var isShowingInviteMembers = false
    @State private var isShowingEditRoom = false
    @State private var isConfirmingDelete = false
    @State private var errorMessage: String?
    private let service = RoomService()
    private let spaceService = SpaceService()

    private var visibleMessages: [RoomMessage] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return messages }
        return messages.filter {
            $0.body.localizedCaseInsensitiveContains(query)
                || $0.senderName.localizedCaseInsensitiveContains(query)
                || ($0.replyPreview?.localizedCaseInsensitiveContains(query) ?? false)
                || $0.links.contains { $0.searchableText.localizedCaseInsensitiveContains(query) }
        }
    }

    init(space: Space, room: SpaceRoom, members: [SpaceMember]) {
        self.space = space
        _room = State(initialValue: room)
        self.members = members
    }

    var body: some View {
        VStack(spacing: 0) {
            List(visibleMessages) { message in
                VStack(alignment: .leading, spacing: 4) {
                    if message.isPinned {
                        Label("Pinned", systemImage: "pin.fill")
                            .font(.caption2.bold())
                            .foregroundStyle(.secondary)
                    }
                    Text(message.senderName).font(.caption.bold())
                    if let preview = message.replyPreview {
                        Text("↩ \(preview)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    Text(message.body)
                    ForEach(message.attachments) { attachment in
                        Button {
                            Task {
                                do {
                                    let data = try await service.downloadAttachment(spaceID: space.id, roomID: room.id, attachment: attachment)
                                    let url = FileManager.default.temporaryDirectory
                                        .appendingPathComponent(attachment.id)
                                        .appendingPathExtension((attachment.name as NSString).pathExtension)
                                    try data.write(to: url, options: .atomic)
                                    sharedAttachmentURL = url
                                } catch { errorMessage = error.localizedDescription }
                            }
                        } label: {
                            Label(attachment.name, systemImage: attachment.isMedia ? "photo.fill" : "doc.fill")
                                .font(.subheadline)
                        }
                    }
                    ForEach(message.links) { link in
                        NavigationLink {
                            linkedDestination(for: link)
                        } label: {
                            Label("\(link.moduleType.title): \(link.title)", systemImage: link.icon)
                                .font(.subheadline)
                        }
                    }
                    if !message.reactions.isEmpty {
                        HStack {
                            ForEach(message.reactions) { reaction in
                                Text("\(reaction.emoji) \(reaction.userIDs.count)")
                                    .font(.caption)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 3)
                                    .background(.thinMaterial, in: Capsule())
                            }
                        }
                    }
                    Text(message.createdAt, style: .time)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .contextMenu {
                    Button {
                        replyingTo = message
                    } label: {
                        Label("Reply", systemImage: "arrowshape.turn.up.left")
                    }
                    ForEach(["👍", "❤️", "🎉", "👀", "✅"], id: \.self) { emoji in
                        Button(emoji) { toggleReaction(emoji, on: message) }
                    }
                    if message.senderID == Auth.auth().currentUser?.uid {
                        Button {
                            editingMessage = message
                            editingMessageBody = message.body
                        } label: {
                            Label("Edit Message", systemImage: "pencil")
                        }
                    }
                    if message.senderID == Auth.auth().currentUser?.uid || canDeleteOthersMessages {
                        Button(role: .destructive) {
                            deletingMessage = message
                        } label: {
                            Label("Delete Message", systemImage: "trash")
                        }
                    }
                    if canManageMembers {
                        Button {
                            Task {
                                try? await service.updateMessage(
                                    spaceID: space.id,
                                    roomID: room.id,
                                    messageID: message.id,
                                    fields: ["isPinned": !message.isPinned]
                                )
                            }
                        } label: {
                            Label(message.isPinned ? "Unpin" : "Pin", systemImage: "pin")
                        }
                    }
                }
            }
            if let replyingTo {
                HStack {
                    VStack(alignment: .leading) {
                        Text("Replying to \(replyingTo.senderName)").font(.caption.bold())
                        Text(replyingTo.body).font(.caption).lineLimit(1)
                    }
                    Spacer()
                    Button { self.replyingTo = nil } label: { Image(systemName: "xmark.circle.fill") }
                }
                .padding(.horizontal)
                .padding(.top, 8)
            }
            if !composerLinks.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(composerLinks) { link in
                            Label(link.title, systemImage: link.icon)
                                .font(.caption)
                                .padding(7)
                                .background(.thinMaterial, in: Capsule())
                        }
                    }
                    .padding(.horizontal)
                }
            }
            if canPost {
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

                    TextField("Message", text: $draft, axis: .vertical)
                        .textFieldStyle(.plain)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .fill(Color(.secondarySystemBackground))
                        )

                    Button {
                        let body = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !body.isEmpty || !composerLinks.isEmpty else { return }
                        draft = ""
                        let links = composerLinks
                        composerLinks = []
                        Task {
                            do {
                                try await service.sendMessage(
                                    spaceID: space.id,
                                    roomID: room.id,
                                    senderName: Auth.auth().currentUser?.displayName ?? "Member",
                                    body: body,
                                    reply: replyingTo,
                                    links: links
                                )
                                replyingTo = nil
                            } catch {
                                errorMessage = error.localizedDescription
                            }
                        }
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 30))
                            .foregroundStyle(
                                draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && composerLinks.isEmpty
                                    ? Color.secondary.opacity(0.6)
                                    : Color.accentColor
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && composerLinks.isEmpty)
                }
                .padding()
            } else {
                Label("Only Space Owners and Admins can post in this Room.", systemImage: "lock.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
        }
        .navigationTitle(room.name)
        .searchable(text: $searchText, prompt: "Search messages")
        .onChange(of: selectedPhotoItem) { item in
            guard canPost, let item else { return }
            Task {
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else { return }
                    try await service.sendAttachment(
                        spaceID: space.id, roomID: room.id,
                        senderName: Auth.auth().currentUser?.displayName ?? "Member",
                        data: data, name: "Shared Media",
                        mimeType: item.supportedContentTypes.first?.preferredMIMEType ?? "application/octet-stream",
                        isMedia: true
                    )
                    selectedPhotoItem = nil
                } catch { errorMessage = error.localizedDescription }
            }
        }
        .photosPicker(
            isPresented: $isShowingPhotoPicker,
            selection: $selectedPhotoItem,
            matching: .any(of: [.images, .videos])
        )
        .fileImporter(isPresented: $isShowingFilePicker, allowedContentTypes: [.item]) { result in
            guard canPost else { return }
            Task {
                do {
                    let url = try result.get()
                    let accessing = url.startAccessingSecurityScopedResource()
                    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                    try await service.sendAttachment(
                        spaceID: space.id, roomID: room.id,
                        senderName: Auth.auth().currentUser?.displayName ?? "Member",
                        data: try Data(contentsOf: url), name: url.lastPathComponent,
                        mimeType: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream",
                        isMedia: false
                    )
                } catch { errorMessage = error.localizedDescription }
            }
        }
        .sheet(item: $editingMessage) { message in
            NavigationStack {
                Form {
                    TextField("Message", text: $editingMessageBody, axis: .vertical)
                }
                .navigationTitle("Edit Message")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { editingMessage = nil }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            let body = editingMessageBody.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !body.isEmpty else { return }
                            Task {
                                do {
                                    try await service.editMessage(spaceID: space.id, roomID: room.id, message: message, body: body)
                                    editingMessage = nil
                                } catch { errorMessage = error.localizedDescription }
                            }
                        }
                    }
                }
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $isAttachmentMenuPresented) {
            RoomAttachmentMenuSheet { action in
                pendingAttachmentAction = action
                isAttachmentMenuPresented = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                    guard let pendingAttachmentAction else { return }
                    switch pendingAttachmentAction {
                    case .camera:
                        if UIImagePickerController.isSourceTypeAvailable(.camera) {
                            isShowingCamera = true
                        } else {
                            isShowingPhotoPicker = true
                        }
                    case .link:
                        isShowingLinkPicker = true
                    case .photos:
                        isShowingPhotoPicker = true
                    case .files:
                        isShowingFilePicker = true
                    case .gifs:
                        isShowingGiphyPicker = true
                    }
                    self.pendingAttachmentAction = nil
                }
            }
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isShowingGiphyPicker) {
            GiphyPickerView { selection in
                guard canPost, let selection else { return }
                Task {
                    do {
                        try await service.sendAttachment(
                            spaceID: space.id,
                            roomID: room.id,
                            senderName: Auth.auth().currentUser?.displayName ?? "Member",
                            data: selection.data,
                            name: "GIF",
                            mimeType: selection.mimeType,
                            isMedia: true
                        )
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingCamera) {
            RoomCameraPicker { capture in
                isShowingCamera = false
                guard canPost, let capture else { return }
                Task {
                    do {
                        try await service.sendAttachment(
                            spaceID: space.id,
                            roomID: room.id,
                            senderName: Auth.auth().currentUser?.displayName ?? "Member",
                            data: capture.data,
                            name: capture.name,
                            mimeType: capture.mimeType,
                            isMedia: true
                        )
                    } catch { errorMessage = error.localizedDescription }
                }
            }
        }
        .sheet(isPresented: Binding(
            get: { sharedAttachmentURL != nil },
            set: { if !$0 { sharedAttachmentURL = nil } }
        )) {
            if let sharedAttachmentURL {
                ShareSheet(items: [sharedAttachmentURL])
            }
        }
        .toolbar {
            if canEditRoom || canDeleteRoom {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        if canEditRoom {
                            Button {
                                isShowingEditRoom = true
                            } label: {
                                Label("Room Settings", systemImage: "gearshape")
                            }
                        }
                        if canDeleteRoom {
                            Button(role: .destructive) {
                                isConfirmingDelete = true
                            } label: {
                                Label("Delete Room", systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            if room.isPrivate && canManageMembers {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        isShowingInviteMembers = true
                    } label: {
                        Label("Invite Members", systemImage: "person.badge.plus")
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingInviteMembers) {
            InviteRoomMembersSheet(
                members: members,
                selectedMemberIDs: room.memberIDs
            ) { selectedIDs in
                var updatedRoom = room
                updatedRoom.memberIDs = selectedIDs
                updatedRoom.memberIDs.insert(room.createdBy)
                updatedRoom.updatedAt = Date()
                Task {
                    do {
                        try await service.saveRoom(updatedRoom)
                        room = updatedRoom
                        isShowingInviteMembers = false
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingEditRoom) {
            EditRoomSheet(
                room: room,
                members: members,
                canManageMembers: canManageMembers,
                canDelete: canDeleteRoom,
                onManageMembers: {
                    isShowingEditRoom = false
                    isShowingInviteMembers = true
                },
                onDelete: {
                    Task {
                        do {
                            try await service.deleteRoom(room)
                            isShowingEditRoom = false
                            dismiss()
                        } catch { errorMessage = error.localizedDescription }
                    }
                }
            ) { updatedRoom in
                Task {
                    do {
                        try await service.saveRoom(updatedRoom)
                        room = updatedRoom
                        isShowingEditRoom = false
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingLinkPicker) {
            RoomLinkPickerSheet(space: space) { link in
                if !composerLinks.contains(where: { $0.moduleType == link.moduleType && $0.targetId == link.targetId }) {
                    composerLinks.append(link)
                }
                isShowingLinkPicker = false
            }
        }
        .confirmationDialog("Delete this Room?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("Delete Room", role: .destructive) {
                Task {
                    do {
                        try await service.deleteRoom(room)
                        dismiss()
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This permanently removes the Room.")
        }
        .confirmationDialog("Delete this message?", isPresented: Binding(
            get: { deletingMessage != nil },
            set: { if !$0 { deletingMessage = nil } }
        )) {
            Button("Delete Message", role: .destructive) {
                guard let message = deletingMessage else { return }
                deletingMessage = nil
                Task {
                    do {
                        try await service.deleteMessage(
                            spaceID: space.id,
                            roomID: room.id,
                            message: message,
                            canDeleteOthers: canDeleteOthersMessages
                        )
                    } catch { errorMessage = error.localizedDescription }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Rooms", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
        .onAppear {
            listener = service.listenToMessages(spaceID: space.id, roomID: room.id) { result in
                if case .success(let values) = result { messages = values }
            }
            roomListener = service.listenToRoom(spaceID: space.id, roomID: room.id) { result in
                if case .success(let updatedRoom) = result { room = updatedRoom }
            }
            Task {
                canManageMembers = await spaceService.canPerform(.manageRoomMembers, in: space)
                let ownsRoom = room.createdBy == Auth.auth().currentUser?.uid
                canEditRoom = await spaceService.canPerform(ownsRoom ? .editOwnRooms : .editOthersRooms, in: space)
                canDeleteRoom = await spaceService.canPerform(ownsRoom ? .deleteOwnRooms : .deleteOthersRooms, in: space)
                canPostInRooms = await spaceService.canPerform(.postInRooms, in: space)
                canDeleteOthersMessages = await spaceService.canPerform(.deleteOthersRooms, in: space)
            }
        }
        .onDisappear {
            listener?.remove()
            roomListener?.remove()
        }
    }

    private var canPost: Bool {
        guard canPostInRooms else { return false }
        guard room.postingMemberIDs != nil else { return true }
        guard let userID = Auth.auth().currentUser?.uid,
              let role = members.first(where: { $0.id == userID })?.role else { return false }
        return role == .owner || role == .admin
    }

    private func toggleReaction(_ emoji: String, on message: RoomMessage) {
        guard let userID = Auth.auth().currentUser?.uid else { return }
        var reactions = message.reactions
        if let index = reactions.firstIndex(where: { $0.emoji == emoji }) {
            if reactions[index].userIDs.contains(userID) {
                reactions[index].userIDs.remove(userID)
                if reactions[index].userIDs.isEmpty { reactions.remove(at: index) }
            } else {
                reactions[index].userIDs.insert(userID)
            }
        } else {
            reactions.append(.init(emoji: emoji, userIDs: [userID]))
        }
        Task {
            try? await service.updateMessage(
                spaceID: space.id,
                roomID: room.id,
                messageID: message.id,
                fields: ["reactions": reactions.map { ["emoji": $0.emoji, "userIds": Array($0.userIDs)] }]
            )
        }
    }

    @ViewBuilder
    private func linkedDestination(for link: SpaceLinkAttachment) -> some View {
        switch link.moduleType {
        case .announcements:
            AnnouncementsView(space: space, initialAnnouncementID: link.targetId)
        case .events:
            EventsView(space: space, initialEventID: link.targetId)
        case .files:
            FilesView(space: space, initialFileID: link.targetId)
        case .polls:
            PollsView(space: space, initialPollID: link.targetId)
        case .rooms:
            RoomsView(space: space, initialRoomID: link.targetId)
        case .media:
            PhotosView(space: space, initialMediaID: link.targetId)
        case .lists:
            ListsView(space: space, initialListID: link.targetId)
        case .notes:
            NotesView(space: space, initialNoteID: link.targetId)
        }
    }
}

private struct RoomAttachmentMenuSheet: View {
    let onSelect: (RoomAttachmentAction) -> Void

    var body: some View {
        NavigationStack {
            List {
                row("Camera", "Capture a new photo or video", "camera", .camera)
                row("Link", "Reference something in this Space", "link", .link)
                row("Photos & Videos", "Choose from your library", "photo.on.rectangle", .photos)
                row("Files", "Choose a file from this device", "doc", .files)
                row("GIFs", "Search and send a GIF", "sparkles.tv", .gifs)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Attachments")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func row(
        _ title: String,
        _ subtitle: String,
        _ systemImage: String,
        _ action: RoomAttachmentAction
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

private struct RoomCameraCapture {
    let data: Data
    let name: String
    let mimeType: String
}

private struct RoomCameraPicker: UIViewControllerRepresentable {
    let onComplete: (RoomCameraCapture?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onComplete: onComplete) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        picker.videoQuality = .typeMedium
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onComplete: (RoomCameraCapture?) -> Void
        init(onComplete: @escaping (RoomCameraCapture?) -> Void) { self.onComplete = onComplete }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onComplete(nil)
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9) {
                onComplete(.init(data: data, name: "Camera Photo.jpg", mimeType: "image/jpeg"))
            } else if let url = info[.mediaURL] as? URL, let data = try? Data(contentsOf: url) {
                onComplete(.init(data: data, name: "Camera Video.mov", mimeType: "video/quicktime"))
            } else {
                onComplete(nil)
            }
        }
    }
}

private struct RoomLinkPickerSheet: View {
    let space: Space
    let onSelect: (SpaceLinkAttachment) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selectedModule: SpaceLinkModuleDescriptor?
    @State private var items: [SpaceLinkRegistryItem] = []
    private let registry = SpaceLinkRegistry()

    var body: some View {
        NavigationStack {
            List {
                if let selectedModule {
                    ForEach(items) { item in
                        Button(item.title) { onSelect(item.attachment) }
                    }
                    if items.isEmpty { Text("No items are available.").foregroundStyle(.secondary) }
                } else {
                    ForEach(registry.availableModules(in: space)) { module in
                        Button {
                            selectedModule = module
                            Task { items = (try? await registry.fetchItems(for: module.moduleType, in: space)) ?? [] }
                        } label: {
                            Label(module.title, systemImage: module.moduleType.icon)
                        }
                    }
                }
            }
            .navigationTitle(selectedModule?.title ?? "Link Module Item")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(selectedModule == nil ? "Cancel" : "Back") {
                        if selectedModule == nil { dismiss() }
                        else { selectedModule = nil; items = [] }
                    }
                }
            }
        }
    }
}

private struct EditRoomSheet: View {
    let room: SpaceRoom
    let members: [SpaceMember]
    let canManageMembers: Bool
    let canDelete: Bool
    let onManageMembers: () -> Void
    let onDelete: () -> Void
    let onSave: (SpaceRoom) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var topic: String
    @State private var isPrivate: Bool
    @State private var ownersAndAdminsOnly: Bool
    @State private var isConfirmingDelete = false

    init(
        room: SpaceRoom,
        members: [SpaceMember],
        canManageMembers: Bool,
        canDelete: Bool,
        onManageMembers: @escaping () -> Void,
        onDelete: @escaping () -> Void,
        onSave: @escaping (SpaceRoom) -> Void
    ) {
        self.room = room
        self.members = members
        self.canManageMembers = canManageMembers
        self.canDelete = canDelete
        self.onManageMembers = onManageMembers
        self.onDelete = onDelete
        self.onSave = onSave
        _name = State(initialValue: room.name)
        _topic = State(initialValue: room.topic)
        _isPrivate = State(initialValue: room.isPrivate)
        _ownersAndAdminsOnly = State(initialValue: room.postingMemberIDs != nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Room Details") {
                    TextField("Name", text: $name)
                    TextField("Topic (optional)", text: $topic, axis: .vertical)
                }
                Section("Access") {
                    Toggle("Private Room", isOn: $isPrivate)
                        .disabled(!canManageMembers)
                    Toggle("Only Owners and Admins Can Post", isOn: $ownersAndAdminsOnly)
                        .disabled(!canManageMembers)
                    LabeledContent("Members", value: "\(room.memberIDs.count)")
                    if canManageMembers {
                        Button {
                            onManageMembers()
                        } label: {
                            Label("Manage Room Members", systemImage: "person.2")
                        }
                    }
                    if !canManageMembers {
                        Text("Only members with Manage Room Members permission can change access and posting controls.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("Room Information") {
                    LabeledContent("Created By", value: members.first(where: { $0.id == room.createdBy })?.displayName ?? "Member")
                    LabeledContent("Created", value: room.createdAt.formatted(date: .abbreviated, time: .shortened))
                }
                if canDelete {
                    Section {
                        Button("Delete Room", role: .destructive) { isConfirmingDelete = true }
                    }
                }
            }
            .navigationTitle("Room Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var updated = room
                        updated.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        updated.topic = topic.trimmingCharacters(in: .whitespacesAndNewlines)
                        if canManageMembers {
                            updated.isPrivate = isPrivate
                            updated.memberIDs.insert(room.createdBy)
                            updated.postingMemberIDs = ownersAndAdminsOnly
                                ? Set(members.filter { $0.role == .owner || $0.role == .admin }.map(\.id))
                                : nil
                        }
                        updated.updatedAt = Date()
                        onSave(updated)
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .confirmationDialog("Delete this Room?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
                Button("Delete Room", role: .destructive, action: onDelete)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This permanently removes the Room.")
            }
        }
    }
}

private struct InviteRoomMembersSheet: View {
    let members: [SpaceMember]
    let selectedMemberIDs: Set<String>
    let onInvite: (Set<String>) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedIDs: Set<String>

    init(members: [SpaceMember], selectedMemberIDs: Set<String>, onInvite: @escaping (Set<String>) -> Void) {
        self.members = members
        self.selectedMemberIDs = selectedMemberIDs
        self.onInvite = onInvite
        _selectedIDs = State(initialValue: selectedMemberIDs)
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(members) { member in
                        Button {
                            if selectedIDs.contains(member.id) {
                                selectedIDs.remove(member.id)
                            } else {
                                selectedIDs.insert(member.id)
                            }
                        } label: {
                            HStack {
                                Text("\(member.emojiAvatar) \(member.displayName)")
                                    .foregroundStyle(.primary)
                                Spacer()
                                if selectedIDs.contains(member.id) {
                                    Image(systemName: "checkmark.circle.fill")
                                }
                            }
                        }
                }
            }
            .navigationTitle("Manage Members")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Invite") { onInvite(selectedIDs) }
                        .disabled(selectedIDs.isEmpty)
                }
            }
        }
    }
}

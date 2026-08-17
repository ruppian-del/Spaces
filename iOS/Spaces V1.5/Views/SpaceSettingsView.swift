import SwiftUI
import UIKit

struct SpaceSettingsView: View {
    @StateObject private var viewModel: SpaceSettingsViewModel
    @FocusState private var isEmojiFieldFocused: Bool
    @State private var activeErrorMessage: String?
    @State private var isShowingModuleOrderSheet = false
    @State private var isShowingRolePermissions = false

    init(space: Space) {
        _viewModel = StateObject(wrappedValue: SpaceSettingsViewModel(space: space))
    }

    private var inviteSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.pendingInvite != nil },
            set: { isPresented in
                if !isPresented {
                    viewModel.dismissInvite()
                }
            }
        )
    }

    private var settingsAlertBinding: Binding<Bool> {
        Binding(
            get: { activeErrorMessage != nil },
            set: { isPresented in
                if !isPresented {
                    activeErrorMessage = nil
                    viewModel.errorMessage = nil
                }
            }
        )
    }

    private var hideFilesAlertBinding: Binding<Bool> {
        Binding(
            get: { viewModel.shouldConfirmHidingFiles },
            set: { isPresented in
                if !isPresented {
                    viewModel.cancelHideFiles()
                }
            }
        )
    }

    private var filesEnabledBinding: Binding<Bool> {
        Binding(
            get: { viewModel.filesEnabled },
            set: { newValue in
                Task {
                    await viewModel.handleFilesToggle(newValue)
                }
            }
        )
    }

    private var eventsEnabledBinding: Binding<Bool> {
        Binding(
            get: { viewModel.eventsEnabled },
            set: { newValue in
                Task {
                    await viewModel.handleEventsToggle(newValue)
                }
            }
        )
    }

    private var announcementsEnabledBinding: Binding<Bool> {
        Binding(
            get: { viewModel.announcementsEnabled },
            set: { newValue in
                Task {
                    await viewModel.handleAnnouncementsToggle(newValue)
                }
            }
        )
    }

    private var pollsEnabledBinding: Binding<Bool> {
        Binding(
            get: { viewModel.pollsEnabled },
            set: { newValue in
                Task {
                    await viewModel.handlePollsToggle(newValue)
                }
            }
        )
    }

    private var roomsEnabledBinding: Binding<Bool> {
        Binding(
            get: { viewModel.roomsEnabled },
            set: { value in Task { await viewModel.handleRoomsToggle(value) } }
        )
    }

    private var listsEnabledBinding: Binding<Bool> {
        Binding(
            get: { viewModel.listsEnabled },
            set: { value in Task { await viewModel.handleListsToggle(value) } }
        )
    }
    private var notesEnabledBinding: Binding<Bool> {
        Binding(get: { viewModel.notesEnabled }, set: { value in Task { await viewModel.handleNotesToggle(value) } })
    }

    var body: some View {
        Form {
            Section("Appearance") {
                TextField("Space Name", text: $viewModel.spaceName)
                    .textInputAutocapitalization(.words)

                HStack(spacing: 12) {
                    Button {
                        isEmojiFieldFocused = true
                    } label: {
                        Text(viewModel.displayEmoji)
                            .font(.system(size: 28))
                            .frame(width: 56, height: 56)
                            .background(Color(.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Space Emoji")

                    TextField("Space Emoji", text: $viewModel.spaceEmoji)
                        .focused($isEmojiFieldFocused)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: viewModel.spaceEmoji) { _ in
                            viewModel.sanitizeEmojiInput()
                        }
                }

                ColorPicker("Space Color", selection: $viewModel.spaceColor, supportsOpacity: false)

                TextField("Space Description", text: $viewModel.spaceDescription)

                Picker("Template Type", selection: $viewModel.templateType) {
                    ForEach(SpaceTemplate.allCases) { template in
                        Text(template.rawValue).tag(template)
                    }
                }
            }

            Section("Members & Roles") {
                Button("Manage Roles") {
                    isShowingRolePermissions = true
                }
                .disabled(!viewModel.canManageRoles)

                HStack {
                    Text("Default Access")
                    Spacer()
                    Text(viewModel.templateType.rawValue)
                        .foregroundStyle(.secondary)
                }
            }

            moduleSettingsSection

            Section("Invites") {
                Toggle("Allow Member Invites", isOn: $viewModel.allowMemberInvites)
                    .disabled(!viewModel.canManageInvites)

                Button("Edit Invite Link") {
                    Task {
                        await viewModel.openInviteEditor()
                    }
                }
                .disabled(!viewModel.canManageInvites || viewModel.isCreatingInvite || viewModel.isLoadingInvite)
            }

            Section("Privacy & Safety") {
                Toggle("Private Space", isOn: $viewModel.isPrivateSpace)
                Toggle("Safe Mode", isOn: $viewModel.safeModeEnabled)

                Button("Report Space") {
                }
            }

            Section("Notifications") {
                Toggle("Notifications", isOn: $viewModel.notificationsEnabled)
            }

            Section {
                Button("Leave Space", role: .destructive) {
                }

                Button("Delete Space", role: .destructive) {
                }
            } header: {
                Text("Danger Zone")
                    .foregroundStyle(.red)
            } footer: {
                Text("These actions are placeholders only. No destructive changes happen yet.")
                    .foregroundStyle(.red)
            }
        }
        .sheet(isPresented: $isShowingRolePermissions) {
            RolePermissionsEditor(space: viewModel.space)
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: inviteSheetBinding) {
            if let invite = viewModel.pendingInvite {
                SpaceInviteSheet(viewModel: viewModel, invite: invite) {
                    viewModel.dismissInvite()
                }
            }
        }
        .sheet(isPresented: $isShowingModuleOrderSheet) {
            ModuleOrderSheet(viewModel: viewModel)
        }
        .onChange(of: viewModel.errorMessage) { message in
            activeErrorMessage = message
        }
        .task {
            await viewModel.loadModuleSettings()
        }
        .alert("Settings", isPresented: settingsAlertBinding) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(activeErrorMessage ?? "")
        }
        .alert("Hide Files?", isPresented: hideFilesAlertBinding) {
            Button("Cancel", role: .cancel) {
                viewModel.cancelHideFiles()
            }
            Button("Hide Files") {
                Task {
                    await viewModel.confirmHideFiles()
                }
            }
        } message: {
            Text("Files will be hidden, not deleted.")
        }
    }

    private var moduleSettingsSection: some View {
        Section {
            if viewModel.canManageModules {
                Button("Reorder Modules") {
                    isShowingModuleOrderSheet = true
                }

                Toggle("Announcements", isOn: announcementsEnabledBinding)
                    .disabled(viewModel.isUpdatingAnnouncementsModule)
                Toggle("Rooms", isOn: roomsEnabledBinding)
                    .disabled(viewModel.isUpdatingRoomsModule)
                Toggle("Lists", isOn: listsEnabledBinding)
                    .disabled(viewModel.isUpdatingListsModule)
                Toggle("Notes", isOn: notesEnabledBinding)
                    .disabled(viewModel.isUpdatingNotesModule)

                Toggle("Events", isOn: eventsEnabledBinding)
                    .disabled(viewModel.isUpdatingEventsModule)

                Toggle("Files", isOn: filesEnabledBinding)
                    .disabled(viewModel.isUpdatingFilesModule)

                Toggle("Polls", isOn: pollsEnabledBinding)
                    .disabled(viewModel.isUpdatingPollsModule)
            } else {
                HStack {
                    Text("Announcements")
                    Spacer()
                    Text(announcementsStatusText)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Text("Rooms")
                    Spacer()
                    Text(viewModel.roomsEnabled ? "Enabled" : "Disabled")
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Text("Lists")
                    Spacer()
                    Text(viewModel.listsEnabled ? "Enabled" : "Disabled").foregroundStyle(.secondary)
                }
                HStack {
                    Text("Notes")
                    Spacer()
                    Text(viewModel.notesEnabled ? "Enabled" : "Disabled").foregroundStyle(.secondary)
                }

                HStack {
                    Text("Events")
                    Spacer()
                    Text(eventsStatusText)
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text("Files")
                    Spacer()
                    Text(filesStatusText)
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text("Polls")
                    Spacer()
                    Text(pollsStatusText)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Module Settings")
        } footer: {
            Text(moduleSettingsFooterText)
        }
    }

    private var filesStatusText: String {
        viewModel.filesEnabled ? "Enabled" : "Disabled"
    }

    private var announcementsStatusText: String {
        viewModel.announcementsEnabled ? "Enabled" : "Disabled"
    }

    private var announcementsFooterText: String {
        if viewModel.announcementsEnabled {
            return "Announcements can be disabled later. Existing announcements will be hidden, not deleted."
        }
        return "Owners and admins can enable Announcements for important Space-wide updates."
    }

    private var eventsStatusText: String {
        viewModel.eventsEnabled ? "Enabled" : "Disabled"
    }

    private var eventsFooterText: String {
        if viewModel.eventsEnabled {
            return "Events can be disabled later. Existing events will be hidden, not deleted."
        }
        return "Owners and admins can enable Events later when this Space needs planning and calendars."
    }

    private var filesFooterText: String {
        if viewModel.filesEnabled {
            return "Files can be disabled later. If files already exist, they will be hidden, not deleted."
        }
        return "Owners and admins can enable Files later if this Space needs shared documents."
    }

    private var pollsStatusText: String {
        viewModel.pollsEnabled ? "Enabled" : "Disabled"
    }

    private var pollsFooterText: String {
        if viewModel.pollsEnabled {
            return "Polls stay available until you turn them off. Existing polls will be hidden, not deleted."
        }
        return "Owners and admins can enable Polls later when this Space needs questions and voting."
    }

    private var moduleSettingsFooterText: String {
        [announcementsFooterText, eventsFooterText, filesFooterText, pollsFooterText].joined(separator: "\n\n")
    }
}

private struct RolePermissionsEditor: View {
    let space: Space
    @Environment(\.dismiss) private var dismiss
    @State private var role: SpaceMemberRole = .admin
    @State private var permissions: Set<SpacePermission>
    @State private var errorMessage: String?
    private let service = SpaceService()

    init(space: Space) {
        self.space = space
        let initialRole = SpaceMemberRole.admin
        _permissions = State(initialValue: space.rolePermissionOverrides[initialRole] ?? initialRole.capabilities)
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Role", selection: $role) {
                    ForEach([SpaceMemberRole.admin, .moderator, .member, .guest]) { role in
                        Text(role.rawValue).tag(role)
                    }
                }
                Section("Permissions") {
                    ForEach(SpacePermission.allCases) { permission in
                        Toggle(permission.title, isOn: Binding(
                            get: { permissions.contains(permission) },
                            set: { enabled in
                                if enabled { permissions.insert(permission) }
                                else { permissions.remove(permission) }
                            }
                        ))
                    }
                }
            }
            .navigationTitle("Roles & Permissions")
            .onChange(of: role) { newRole in
                permissions = space.rolePermissionOverrides[newRole] ?? newRole.capabilities
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            do {
                                try await service.setPermissions(permissions, for: role, in: space)
                                dismiss()
                            } catch { errorMessage = error.localizedDescription }
                        }
                    }
                }
            }
            .alert("Roles & Permissions", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) { Button("OK", role: .cancel) {} } message: { Text(errorMessage ?? "") }
        }
    }
}

private struct ModuleOrderSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var viewModel: SpaceSettingsViewModel
    @State private var editMode: EditMode = .active
    @State private var draftOrder: [SpaceModule]
    @State private var isSaving = false

    init(viewModel: SpaceSettingsViewModel) {
        self.viewModel = viewModel
        _draftOrder = State(initialValue: viewModel.moduleOrder)
    }

    var body: some View {
        NavigationView {
            List {
                ForEach(draftOrder) { module in
                    HStack(spacing: 12) {
                        Text(module.emoji)
                            .font(.title3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(module.title)
                                .font(.headline)
                            Text(module.description)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .onMove { indices, newOffset in
                    draftOrder.move(fromOffsets: indices, toOffset: newOffset)
                }
            }
            .environment(\.editMode, $editMode)
            .navigationTitle("Reorder Modules")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done") {
                        isSaving = true
                        Task {
                            if await viewModel.saveModuleOrder(draftOrder) {
                                dismiss()
                            }
                            isSaving = false
                        }
                    }
                    .disabled(isSaving)
                }
            }
        }
    }
}

private struct SpaceInviteSheet: View {
    @ObservedObject var viewModel: SpaceSettingsViewModel
    let invite: SpaceInvite
    let onDismiss: () -> Void
    @State private var isShowingShareSheet = false

    var body: some View {
        NavigationView {
            List {
                Section("Invite Code") {
                    Text(invite.code)
                        .font(.system(.title2, design: .monospaced).bold())
                        .textSelection(.enabled)
                }

                Section {
                    SpaceInviteDetailRow(title: "Space", value: "\(invite.spaceEmoji) \(invite.spaceName)")
                    SpaceInviteDetailRow(title: "Status", value: invite.active ? "Active" : "Inactive")
                    SpaceInviteDetailRow(title: "Expires", value: invite.expiresAt.formatted(date: .abbreviated, time: .shortened))
                    SpaceInviteDetailRow(title: "Remaining Uses", value: "\(invite.remainingUses)")
                }

                Section("Actions") {
                    Button("Copy Code") {
                        UIPasteboard.general.string = invite.code
                    }

                    Button("Share Code") {
                        isShowingShareSheet = true
                    }

                    Button(invite.active ? "Disable Link" : "Enable Link") {
                        Task {
                            await viewModel.setInviteActive(!invite.active)
                        }
                    }
                    .disabled(viewModel.isUpdatingInvite)

                    Button("Regenerate Link") {
                        Task {
                            await viewModel.regenerateInvite()
                        }
                    }
                    .disabled(viewModel.isUpdatingInvite)
                }
            }
            .navigationTitle("Invite Link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        onDismiss()
                    }
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .sheet(isPresented: $isShowingShareSheet) {
            ShareSheet(items: [InviteLink.url(for: invite.code) ?? "spaces://join?code=\(invite.code)"])
        }
    }
}

private struct SpaceInviteDetailRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
    }
}

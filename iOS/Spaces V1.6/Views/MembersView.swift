import SwiftUI
import UIKit

struct MembersView: View {
    @StateObject private var viewModel: MembersViewModel
    @State private var activeErrorMessage: String?
    @State private var isShowingError = false

    init(space: Space) {
        _viewModel = StateObject(wrappedValue: MembersViewModel(space: space))
    }

    var body: some View {
        List {
            Section {
                Button {
                    Task {
                        await viewModel.createInvite()
                    }
                } label: {
                    Label("Invite Member", systemImage: "person.badge.plus")
                }
                .disabled(!viewModel.canInviteMembers || viewModel.isCreatingInvite)
            } footer: {
                if !viewModel.canInviteMembers {
                    Text("Only Space owners or admins can create invite codes.")
                }
            }

            Section("Members") {
                ForEach(viewModel.members) { member in
                    NavigationLink {
                        MemberDetailView(
                            member: member,
                            canManageMember: viewModel.canRemove(member),
                            availableRoles: viewModel.availableRoles(for: member),
                            onUpdateRole: { role in
                                Task {
                                    await viewModel.updateRole(for: member.id, to: role)
                                }
                            },
                            onRemove: {
                                await viewModel.removeMember(withID: member.id)
                            }
                        )
                    } label: {
                        MemberRowView(member: member)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Members")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(
            isPresented: Binding(
                get: { viewModel.pendingInvite != nil },
                set: { isPresented in
                    if !isPresented {
                        viewModel.dismissInvite()
                    }
                }
            )
        ) {
            if let invite = viewModel.pendingInvite {
                InviteCodeSheet(invite: invite) {
                    viewModel.dismissInvite()
                }
            }
        }
        .task {
            viewModel.startListeningIfNeeded()
        }
        .onChange(of: viewModel.errorMessage) { message in
            activeErrorMessage = message
            isShowingError = message != nil
        }
        .alert("Members", isPresented: $isShowingError) {
            Button("OK", role: .cancel) {
                activeErrorMessage = nil
                Task { @MainActor in
                    viewModel.clearError()
                }
            }
        } message: {
            Text(activeErrorMessage ?? "")
        }
    }
}

private struct MemberRowView: View {
    let member: SpaceMember

    var body: some View {
        HStack(spacing: 14) {
            Text(member.emojiAvatar)
                .font(.title2)
                .frame(width: 42, height: 42)
                .background(
                    Circle()
                        .fill(Color(.secondarySystemBackground))
                )

            VStack(alignment: .leading, spacing: 4) {
                Text(member.displayName)
                    .font(.headline)

                Text(member.role.rawValue)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Text(member.status)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

struct MemberDetailView: View {
    @StateObject private var viewModel: MemberDetailViewModel
    let canManageMember: Bool
    let availableRoles: [SpaceMemberRole]
    let onUpdateRole: (SpaceMemberRole) -> Void
    let onRemove: () async -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var isShowingRoleDialog = false

    init(
        member: SpaceMember,
        canManageMember: Bool,
        availableRoles: [SpaceMemberRole],
        onUpdateRole: @escaping (SpaceMemberRole) -> Void,
        onRemove: @escaping () async -> Bool
    ) {
        _viewModel = StateObject(wrappedValue: MemberDetailViewModel(member: member))
        self.canManageMember = canManageMember
        self.availableRoles = availableRoles
        self.onUpdateRole = onUpdateRole
        self.onRemove = onRemove
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Text(viewModel.member.emojiAvatar)
                        .font(.system(size: 56))
                        .frame(width: 92, height: 92)
                        .background(
                            Circle()
                                .fill(Color(.secondarySystemBackground))
                        )

                    VStack(spacing: 6) {
                        Text(viewModel.member.displayName)
                            .font(.title3.bold())

                        Text(viewModel.member.role.rawValue)
                            .font(.headline)
                            .foregroundStyle(.secondary)

                        Text(viewModel.member.status)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }

            Section("Actions") {
                Button {
                } label: {
                    Label("Ping", systemImage: "bell")
                }

                Button {
                    isShowingRoleDialog = true
                } label: {
                    Label("Change Role", systemImage: "person.crop.circle.badge.checkmark")
                }
                .disabled(availableRoles.isEmpty)

                Button(role: .destructive) {
                    Task {
                        if await onRemove() {
                            dismiss()
                        }
                    }
                } label: {
                    Label("Remove from Space", systemImage: "person.crop.circle.badge.minus")
                }
                .disabled(!canManageMember)
            }

            Section("Role Permissions") {
                ForEach(Array(viewModel.member.role.capabilities).sorted(by: { $0.title < $1.title })) { permission in
                    Text(permission.title)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Member")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Change Role", isPresented: $isShowingRoleDialog, titleVisibility: .visible) {
            ForEach(availableRoles) { role in
                Button(role.rawValue) {
                    viewModel.updateRole(role)
                    onUpdateRole(role)
                }
            }
        }
    }
}

private struct InviteCodeSheet: View {
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
                    InviteDetailRow(title: "Space", value: "\(invite.spaceEmoji) \(invite.spaceName)")
                    InviteDetailRow(title: "Expires", value: invite.expiresAt.formatted(date: .abbreviated, time: .shortened))
                    InviteDetailRow(title: "Remaining Uses", value: "\(invite.remainingUses)")
                }

                Section("Actions") {
                    Button("Copy Code") {
                        UIPasteboard.general.string = invite.code
                    }

                    Button("Share Code") {
                        isShowingShareSheet = true
                    }
                }
            }
            .navigationTitle("Invite Member")
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

private struct InviteDetailRow: View {
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

import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var appViewModel: AppViewModel
    @StateObject private var viewModel = HomeViewModel()
    @StateObject private var notificationsViewModel = NotificationsViewModel()
    @State private var isShowingCreateSpace = false
    @State private var isShowingJoinSpace = false
    @State private var isShowingNotifications = false
    @State private var isShowingGlobalSearch = false
    @State private var activeAlert: HomeFeedbackAlert?
    @State private var joinSpaceInlineMessage: HomeFeedbackAlert?

    private let columns = [
        GridItem(.flexible(), spacing: 16),
        GridItem(.flexible(), spacing: 16)
    ]

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(viewModel.greetingTitle)
                            .font(.largeTitle.bold())
                        Text(viewModel.greetingSubtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }


                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(viewModel.spaces) { space in
                            NavigationLink(destination: SpaceDetailView(space: space)) {
                                SpaceCardView(space: space)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Button {
                        isShowingCreateSpace = true
                    } label: {
                        Label("Create Space", systemImage: "plus.circle.fill")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical,5)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.indigo)

                    Button {
                        isShowingJoinSpace = true
                    } label: {
                        Label("Join with Invite Code", systemImage: "person.badge.plus")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 5)
                    }
                    .buttonStyle(.bordered)
                }
                .padding(20)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Spaces")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        isShowingGlobalSearch = true
                    } label: {
                        Image(systemName: "magnifyingglass")
                            .font(.headline)
                    }
                    .accessibilityLabel("Search")
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        isShowingNotifications = true
                    } label: {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "bell")
                                .font(.headline)

                            if notificationsViewModel.unreadCount > 0 {
                                Text("\(min(notificationsViewModel.unreadCount, 99))")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 2)
                                    .background(Capsule().fill(Color.red))
                                    .offset(x: 10, y: -8)
                            }
                        }
                    }
                    .accessibilityLabel("Notifications")
                }
            }
            .sheet(isPresented: $isShowingCreateSpace) {
                CreateSpaceSheet { name, emoji, tintHex, description, template, enabledModules in
                    Task {
                        await viewModel.createSpace(
                            name: name,
                            emoji: emoji,
                            tintHex: tintHex,
                            description: description,
                            template: template,
                            enabledModules: enabledModules
                        )
                    }
                }
            }
            .sheet(isPresented: $isShowingJoinSpace) {
                JoinSpaceSheet(
                    isJoining: viewModel.isJoining,
                    feedback: joinSpaceInlineMessage
                ) { code in
                    joinSpaceInlineMessage = nil
                    viewModel.errorMessage = nil
                    viewModel.successMessage = nil

                    let didJoin = await viewModel.redeemInvite(code: code)

                    if didJoin {
                        isShowingJoinSpace = false
                    } else if let message = viewModel.errorMessage {
                        joinSpaceInlineMessage = HomeFeedbackAlert(title: "Unable to Join", message: message)
                        viewModel.errorMessage = nil
                    }
                } onDismissFeedback: {
                    joinSpaceInlineMessage = nil
                }
            }
            .sheet(isPresented: $isShowingNotifications) {
                NotificationsView(viewModel: notificationsViewModel)
            }
            .sheet(isPresented: $isShowingGlobalSearch) {
                GlobalSearchView(spaces: viewModel.spaces)
            }
            .task {
                viewModel.startListeningIfNeeded()
            }
            .task {
                notificationsViewModel.startListeningIfNeeded()
            }
            .task(id: appViewModel.pendingInviteCode) {
                guard let code = appViewModel.pendingInviteCode, !code.isEmpty else { return }
                appViewModel.clearPendingInviteCode()
                _ = await viewModel.redeemInvite(code: code)
            }
            .onChange(of: viewModel.errorMessage) { message in
                guard let message else { return }

                if isShowingJoinSpace {
                    joinSpaceInlineMessage = HomeFeedbackAlert(title: "Unable to Join", message: message)
                    viewModel.errorMessage = nil
                } else {
                    activeAlert = HomeFeedbackAlert(title: "Spaces", message: message)
                }
            }
            .onChange(of: viewModel.successMessage) { message in
                guard let message else { return }

                if isShowingJoinSpace {
                    joinSpaceInlineMessage = HomeFeedbackAlert(title: "Joined Space", message: message)
                    viewModel.successMessage = nil
                } else {
                    activeAlert = HomeFeedbackAlert(title: "Joined Space", message: message)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .alert(
            item: Binding(
                get: {
                    isShowingJoinSpace ? nil : activeAlert
                },
                set: { newValue in
                    activeAlert = newValue
                }
            )
        ) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("OK")) {
                    viewModel.errorMessage = nil
                    viewModel.successMessage = nil
                    activeAlert = nil
                }
            )
        }
    }
}

private struct HomeFeedbackAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

private struct JoinSpaceSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var inviteCode = ""

    let isJoining: Bool
    let feedback: HomeFeedbackAlert?
    let onJoin: (String) async -> Void
    let onDismissFeedback: () -> Void

    var body: some View {
        NavigationView {
            Form {
                Section("Invite Code") {
                    TextField("Enter code", text: $inviteCode)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .onChange(of: inviteCode) { _ in
                            onDismissFeedback()
                        }
                }
                if let feedback {
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(feedback.title)
                                .font(.headline)
                                .foregroundStyle(feedback.title == "Joined Space" ? .green : .red)

                            Text(feedback.message)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }

                        Button("Dismiss") {
                            onDismissFeedback()
                        }
                    }
                }
            }
            .navigationTitle("Join Space")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("Join") {
                        Task {
                            await onJoin(inviteCode)
                        }
                    }
                    .disabled(inviteCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isJoining)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
}

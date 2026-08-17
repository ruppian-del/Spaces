import SwiftUI

struct NotificationsView: View {
    @ObservedObject var viewModel: NotificationsViewModel
    @State private var showingMarkAllReadConfirmation = false

    var body: some View {
        NavigationView {
            List {
                if viewModel.isLoading && viewModel.items.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                        .listRowBackground(Color.clear)
                } else if viewModel.items.isEmpty {
                    emptyState
                        .listRowInsets(EdgeInsets(top: 20, leading: 20, bottom: 20, trailing: 20))
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(viewModel.groupedItems, id: \.section.id) { group in
                        Section(group.section.rawValue) {
                            ForEach(group.items) { item in
                                NavigationLink {
                                    destinationView(for: item)
                                } label: {
                                    NotificationRowView(item: item)
                                }
                                .simultaneousGesture(TapGesture().onEnded {
                                    Task {
                                        await viewModel.markRead(item)
                                    }
                                })
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Notifications")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if viewModel.unreadCount > 0 {
                        Button(viewModel.isMarkingAllRead ? "Reading..." : "Read All") {
                            showingMarkAllReadConfirmation = true
                        }
                        .disabled(viewModel.isMarkingAllRead)
                    }
                }
            }
            .task {
                viewModel.startListeningIfNeeded()
            }
            .alert(item: $viewModel.activeError) { error in
                Alert(
                    title: Text("Notifications"),
                    message: Text(error.message),
                    dismissButton: .cancel()
                )
            }
            .confirmationDialog(
                "Mark all notifications as read?",
                isPresented: $showingMarkAllReadConfirmation,
                titleVisibility: .visible
            ) {
                Button("Read All") {
                    Task {
                        await viewModel.markAllRead()
                    }
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("This keeps your notifications, but clears their unread state.")
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    @ViewBuilder
    private func destinationView(for item: SpaceNotificationItem) -> some View {
        if let space = viewModel.spacesByID[item.spaceId] {
            switch item.targetType {
            case .general:
                GeneralView(space: space)
            case .photos:
                PhotosView(space: space)
            case .files:
                FilesView(space: space)
            case .polls:
                PollsView(space: space)
            case .events:
                EventsView(space: space)
            case .members:
                MembersView(space: space)
            case .space, .none:
                SpaceDetailView(space: space)
            }
        } else {
            NotificationDetailView(item: item)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "bell")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Color.accentColor)

            Text("No notifications")
                .font(.headline)

            Text("Important updates from your Spaces will show up here.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

private struct NotificationRowView: View {
    let item: SpaceNotificationItem

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                Circle()
                    .fill(item.isUnread ? Color.accentColor.opacity(0.14) : Color(.secondarySystemBackground))
                    .frame(width: 42, height: 42)

                Text(item.actorEmoji ?? item.spaceEmoji)
                    .font(.title3)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.primaryText)
                            .font(.headline)
                            .foregroundStyle(item.isUnread ? Color.primary : Color.primary.opacity(0.9))

                        if let subtitle = item.subtitle {
                            Text(subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Spacer()

                    Text(item.timestampText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 8) {
                    Label(item.spaceName, systemImage: item.type.systemImageName)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if item.isUnread {
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 8, height: 8)
                    }
                }
            }
        }
        .padding(.vertical, 6)
        .listRowBackground(
            item.isUnread ? Color.accentColor.opacity(0.05) : Color.clear
        )
    }
}

private struct NotificationDetailView: View {
    let item: SpaceNotificationItem

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    Text(item.actorEmoji ?? item.spaceEmoji)
                        .font(.largeTitle)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.primaryText)
                            .font(.headline)
                        if let subtitle = item.subtitle {
                            Text(subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 6)
            }

            Section("Details") {
                detailRow(label: "Space", value: item.spaceName)
                detailRow(label: "Time", value: item.timestampText)
                detailRow(label: "Type", value: item.type.rawValue)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Notification")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detailRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
        }
    }
}

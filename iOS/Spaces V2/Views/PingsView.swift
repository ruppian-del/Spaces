import SwiftUI

struct PingsView: View {
    @StateObject private var viewModel = PingsViewModel()
    @State private var isComposerPresented = false
    @State private var pendingCreatedPingID: String?

    var body: some View {
        NavigationView {
            List {
                ForEach(viewModel.pings) { ping in
                    NavigationLink(
                        destination: PingConversationView(ping: ping),
                        tag: ping.id,
                        selection: $pendingCreatedPingID
                    ) {
                        PingRowView(
                            ping: ping,
                            currentUserID: viewModel.currentUserID()
                        )
                    }
                }

                if !viewModel.isLoading && viewModel.pings.isEmpty {
                    Text("No Pings yet")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("Pings")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        isComposerPresented = true
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                }
            }
            .task {
                viewModel.startListeningIfNeeded()
            }
            .sheet(isPresented: $isComposerPresented) {
                NavigationView {
                    List(viewModel.availableParticipants) { participant in
                        Button {
                            Task {
                                if let ping = await viewModel.createOrOpenPing(with: participant) {
                                    pendingCreatedPingID = ping.id
                                    isComposerPresented = false
                                }
                            }
                        } label: {
                            HStack(spacing: 12) {
                                Text(participant.emojiAvatar)
                                    .font(.title2)
                                Text(participant.displayName)
                                    .foregroundStyle(.primary)
                            }
                        }
                    }
                    .navigationTitle("New Ping")
                    .toolbar {
                        ToolbarItem(placement: .navigationBarLeading) {
                            Button("Close") {
                                isComposerPresented = false
                            }
                        }
                    }
                    .task {
                        await viewModel.loadAvailableParticipants()
                    }
                }
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
        .navigationViewStyle(StackNavigationViewStyle())
    }
}

private struct PingRowView: View {
    let ping: Ping
    let currentUserID: String?

    var body: some View {
        HStack(spacing: 14) {
            Text(ping.emoji(for: currentUserID))
                .font(.title2)
                .frame(width: 42, height: 42)
                .background(
                    Circle()
                        .fill(Color(.secondarySystemBackground))
                )

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(ping.title(for: currentUserID))
                        .font(.headline)

                    Spacer()

                    Text(ping.timestampText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Text(ping.lastMessagePreviewText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            if ping.unreadCount > 0 {
                Text("\(ping.unreadCount)")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Color.accentColor))
            }
        }
        .padding(.vertical, 4)
    }
}

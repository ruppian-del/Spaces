import SwiftUI

struct GlobalSearchView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel: GlobalSearchViewModel

    init(spaces: [Space]) {
        _viewModel = StateObject(wrappedValue: GlobalSearchViewModel(spaces: spaces))
    }

    var body: some View {
        NavigationView {
            List {
                if viewModel.searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Search everything")
                                .font(.headline)
                            Text("Search Spaces, people, Pings, and recent messages.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 8)
                    }
                    .listRowBackground(Color.clear)
                } else {
                    if !viewModel.spaceResults.isEmpty {
                        Section("Spaces") {
                            ForEach(viewModel.spaceResults) { space in
                                NavigationLink(destination: SpaceDetailView(space: space)) {
                                    searchRow(
                                        title: space.name,
                                        subtitle: space.description,
                                        leading: space.emoji
                                    )
                                }
                            }
                        }
                    }

                    if !viewModel.pingResults.isEmpty || !viewModel.peopleResults.isEmpty {
                        Section("Pings & People") {
                            ForEach(viewModel.pingResults) { ping in
                                NavigationLink(destination: PingConversationView(ping: ping)) {
                                    searchRow(
                                        title: ping.title(for: viewModel.currentUserID),
                                        subtitle: "Existing Ping",
                                        leading: ping.emoji(for: viewModel.currentUserID)
                                    )
                                }
                            }

                            ForEach(viewModel.peopleResults) { participant in
                                NavigationLink(destination: PingParticipantDestinationView(participant: participant)) {
                                    searchRow(
                                        title: participant.displayName,
                                        subtitle: "Start a Ping",
                                        leading: participant.emojiAvatar
                                    )
                                }
                            }
                        }
                    }

                    if viewModel.isLoading {
                        Section("Messages") {
                            HStack(spacing: 12) {
                                ProgressView()
                                Text("Searching recent messages…")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    } else if !viewModel.messageResults.isEmpty {
                        Section("Messages") {
                            ForEach(viewModel.messageResults) { result in
                                switch result.conversationKind {
                                case .space(let space):
                                    NavigationLink(destination: GeneralView(space: space)) {
                                        messageRow(result: result)
                                    }
                                case .ping(let ping):
                                    NavigationLink(destination: PingConversationView(ping: ping)) {
                                        messageRow(result: result)
                                    }
                                }
                            }
                        }
                    } else if viewModel.spaceResults.isEmpty && viewModel.pingResults.isEmpty && viewModel.peopleResults.isEmpty {
                        Section {
                            VStack(spacing: 8) {
                                Text("No results")
                                    .font(.headline)
                                Text("Try a different name, person, or message keyword.")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 20)
                        }
                        .listRowBackground(Color.clear)
                    }
                }
            }
            .searchable(text: $viewModel.searchText, prompt: "Search Spaces")
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        dismiss()
                    }
                }
            }
            .onAppear {
                viewModel.start()
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    private func searchRow(title: String, subtitle: String, leading: String) -> some View {
        HStack(spacing: 12) {
            Text(leading)
                .font(.title2)
                .frame(width: 34)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 2)
    }

    private func messageRow(result: GlobalSearchViewModel.SpaceMessageSearchResult) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "text.bubble")
                .foregroundStyle(.indigo)
                .frame(width: 20, height: 20)

            VStack(alignment: .leading, spacing: 4) {
                Text(result.title)
                    .font(.headline)
                    .foregroundStyle(.primary)

                Text(result.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text(result.preview)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct PingParticipantDestinationView: View {
    let participant: PingParticipant
    @State private var ping: Ping?
    @State private var errorMessage: String?
    private let pingService = PingService()

    var body: some View {
        Group {
            if let ping {
                PingConversationView(ping: ping)
            } else if let errorMessage {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.title2)
                        .foregroundStyle(.orange)
                    Text("Unable to Open Ping")
                        .font(.headline)
                    Text(errorMessage)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(24)
            } else {
                ProgressView("Opening Ping…")
                    .task {
                        do {
                            ping = try await pingService.createOrOpenPing(with: participant)
                        } catch {
                            errorMessage = error.localizedDescription
                        }
                    }
            }
        }
        .navigationTitle(participant.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }
}

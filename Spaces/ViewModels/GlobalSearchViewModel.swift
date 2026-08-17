import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class GlobalSearchViewModel: ObservableObject {
    struct SpaceMessageSearchResult: Identifiable, Hashable {
        enum ConversationKind: Hashable {
            case space(Space)
            case ping(Ping)
        }

        let id: String
        let conversationKind: ConversationKind
        let title: String
        let subtitle: String
        let preview: String
        let createdAt: Date?
    }

    @Published var searchText = "" {
        didSet {
            refreshStaticResults()
            performMessageSearch()
        }
    }

    @Published private(set) var spaceResults: [Space] = []
    @Published private(set) var pingResults: [Ping] = []
    @Published private(set) var peopleResults: [PingParticipant] = []
    @Published private(set) var messageResults: [SpaceMessageSearchResult] = []
    @Published private(set) var isLoading = false
    @Published private(set) var currentUserID: String?

    private let spaceService: SpaceService
    private let pingService: PingService
    private var spaces: [Space]
    private var pings: [Ping] = []
    private var people: [PingParticipant] = []
    private var pingsListener: ListenerRegistration?
    private var searchTask: Task<Void, Never>?

    convenience init(
        spaces: [Space]
    ) {
        self.init(
            spaces: spaces,
            spaceService: SpaceService(),
            pingService: PingService()
        )
    }

    init(
        spaces: [Space],
        spaceService: SpaceService,
        pingService: PingService
    ) {
        self.spaces = spaces
        self.spaceService = spaceService
        self.pingService = pingService
        self.currentUserID = pingService.currentUserID()
        refreshStaticResults()
    }

    deinit {
        pingsListener?.remove()
        searchTask?.cancel()
    }

    func updateSpaces(_ spaces: [Space]) {
        self.spaces = spaces
        refreshStaticResults()
        performMessageSearch()
    }

    func start() {
        guard pingsListener == nil else { return }

        pingsListener = pingService.listenToPingsForCurrentUser { [weak self] result in
            guard let self else { return }
            Task { @MainActor in
                self.currentUserID = self.pingService.currentUserID()
                switch result {
                case .success(let pings):
                    self.pings = pings
                    self.refreshStaticResults()
                    self.performMessageSearch()
                case .failure:
                    self.pings = []
                    self.refreshStaticResults()
                }
            }
        }

        Task {
            let participants = (try? await pingService.fetchAvailableParticipants()) ?? []
            people = participants
            refreshStaticResults()
        }
    }

    private func refreshStaticResults() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            spaceResults = []
            pingResults = []
            peopleResults = []
            messageResults = []
            isLoading = false
            searchTask?.cancel()
            searchTask = nil
            return
        }

        let normalized = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        spaceResults = spaces.filter {
            [$0.name, $0.description, $0.emoji].contains { value in
                value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains(normalized)
            }
        }

        pingResults = pings.filter { ping in
            let title = ping.title(for: pingService.currentUserID())
            let emoji = ping.emoji(for: pingService.currentUserID())
            return [title, emoji].contains { value in
                value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains(normalized)
            }
        }

        let pingParticipantIDs = Set(pings.compactMap { $0.otherParticipant(for: pingService.currentUserID())?.id })
        peopleResults = people.filter { participant in
            !pingParticipantIDs.contains(participant.id) &&
            [participant.displayName, participant.emojiAvatar].contains { value in
                value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains(normalized)
            }
        }
    }

    private func performMessageSearch() {
        searchTask?.cancel()
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            messageResults = []
            isLoading = false
            return
        }

        searchTask = Task { [weak self] in
            guard let self else { return }
            isLoading = true

            let normalizedQuery = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            var results: [SpaceMessageSearchResult] = []

            for space in spaces {
                if Task.isCancelled { return }
                let messages = (try? await spaceService.fetchRecentMessages(in: space, limit: 25)) ?? []
                for message in messages where !message.deleted {
                    let searchable = [
                        message.senderName,
                        message.text ?? "",
                        message.replyContext?.preview ?? "",
                        message.spaceLinks.map(\.searchableText).joined(separator: "\n")
                    ]
                        .joined(separator: "\n")
                        .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
                    guard searchable.contains(normalizedQuery) else { continue }
                    results.append(
                        SpaceMessageSearchResult(
                            id: "space-\(space.id)-\(message.id)",
                            conversationKind: .space(space),
                            title: space.name,
                            subtitle: message.senderName,
                            preview: message.text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                                ?? message.spaceLinks.first?.title
                                ?? "Message",
                            createdAt: message.createdAt
                        )
                    )
                }
            }

            for ping in pings {
                if Task.isCancelled { return }
                let messages = (try? await pingService.fetchRecentMessages(in: ping, limit: 25)) ?? []
                for message in messages where !message.deleted {
                    let searchable = [
                        message.senderName,
                        message.text ?? "",
                        message.replyContext?.preview ?? "",
                        message.spaceLinks.map(\.searchableText).joined(separator: "\n")
                    ]
                        .joined(separator: "\n")
                        .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
                    guard searchable.contains(normalizedQuery) else { continue }
                    results.append(
                        SpaceMessageSearchResult(
                            id: "ping-\(ping.id)-\(message.id)",
                            conversationKind: .ping(ping),
                            title: ping.title(for: pingService.currentUserID()),
                            subtitle: message.senderName,
                            preview: message.text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                                ?? message.spaceLinks.first?.title
                                ?? "Message",
                            createdAt: message.createdAt
                        )
                    )
                }
            }

            guard !Task.isCancelled else { return }

            messageResults = results.sorted { lhs, rhs in
                (lhs.createdAt ?? .distantPast) > (rhs.createdAt ?? .distantPast)
            }
            isLoading = false
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

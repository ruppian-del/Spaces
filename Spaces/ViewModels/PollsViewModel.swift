import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class PollsViewModel: ObservableObject {
    @Published private(set) var polls: [SpacePoll] = []
    @Published private(set) var isLoading = false
    @Published private(set) var currentUserID: String?
    @Published private(set) var canManageAllPolls = false
    @Published var errorMessage: String?

    let space: Space
    private let spaceService: SpaceService
    private var pollListener: ListenerRegistration?
    private var voteListeners: [String: ListenerRegistration] = [:]

    init(space: Space, spaceService: SpaceService? = nil) {
        self.space = space
        self.spaceService = spaceService ?? SpaceService()
    }

    deinit {
        pollListener?.remove()
        voteListeners.values.forEach { $0.remove() }
    }

    func startListeningIfNeeded() {
        guard pollListener == nil else { return }
        isLoading = true
        currentUserID = spaceService.currentUserID()

        Task {
            canManageAllPolls = await spaceService.canManageModules(in: space)
        }

        pollListener = spaceService.listenToPolls(in: space) { [weak self] result in
            guard let self else { return }

            switch result {
            case .success(let polls):
                self.mergePolls(polls)
                self.syncVoteListeners(for: polls)
            case .failure(let error):
                self.errorMessage = error.localizedDescription
            }
            self.isLoading = false
        }
    }

    func poll(withID pollID: String) -> SpacePoll? {
        polls.first(where: { $0.id == pollID })
    }

    func canDelete(_ poll: SpacePoll) -> Bool {
        poll.createdBy == currentUserID || canManageAllPolls
    }

    func createPoll(
        question: String,
        optionTexts: [String],
        closesAt: Date?,
        allowMultipleVotes: Bool,
        anonymous: Bool
    ) async -> Bool {
        do {
            try await spaceService.createPoll(
                in: space,
                question: question,
                optionTexts: optionTexts,
                closesAt: closesAt,
                allowMultipleVotes: allowMultipleVotes,
                anonymous: anonymous
            )
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func toggleVote(for optionID: String, in poll: SpacePoll) async {
        guard let currentUserID else { return }

        var selectedOptionIDs = poll.selectedOptionIDs(for: currentUserID)

        if poll.allowMultipleVotes {
            if selectedOptionIDs.contains(optionID) {
                selectedOptionIDs.remove(optionID)
            } else {
                selectedOptionIDs.insert(optionID)
            }

            guard !selectedOptionIDs.isEmpty else { return }
        } else {
            if selectedOptionIDs.count == 1, selectedOptionIDs.contains(optionID) {
                return
            }
            selectedOptionIDs = [optionID]
        }

        do {
            try await spaceService.submitPollVote(
                in: space,
                poll: poll,
                optionIDs: Array(selectedOptionIDs)
            )
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func delete(_ poll: SpacePoll) async {
        do {
            try await spaceService.deletePoll(in: space, poll: poll)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func mergePolls(_ incomingPolls: [SpacePoll]) {
        polls = incomingPolls.map { incoming in
            guard let existing = polls.first(where: { $0.id == incoming.id }) else {
                return incoming
            }

            var merged = incoming
            merged.votes = existing.votes
            return merged
        }
    }

    private func syncVoteListeners(for polls: [SpacePoll]) {
        let activeIDs = Set(polls.map(\.id))

        for pollID in voteListeners.keys.filter({ !activeIDs.contains($0) }) {
            voteListeners[pollID]?.remove()
            voteListeners.removeValue(forKey: pollID)
        }

        for poll in polls where voteListeners[poll.id] == nil {
            voteListeners[poll.id] = spaceService.listenToPollVotes(in: space, pollID: poll.id) { [weak self] result in
                guard let self else { return }

                switch result {
                case .success(let votes):
                    guard let index = self.polls.firstIndex(where: { $0.id == poll.id }) else { return }
                    self.polls[index].votes = votes
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }
}

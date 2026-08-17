import Foundation

struct SpacePollOption: Identifiable, Hashable {
    let id: String
    let text: String
}

struct SpacePollVote: Identifiable, Hashable {
    let id: String
    let userID: String
    let displayName: String?
    let emojiAvatar: String?
    let optionIDs: [String]
    let createdAt: Date?
    let updatedAt: Date?
}

struct SpacePoll: Identifiable, Hashable {
    let id: String
    let spaceID: String
    let question: String
    let options: [SpacePollOption]
    let createdBy: String
    let createdByName: String
    let createdAt: Date?
    let updatedAt: Date?
    let closesAt: Date?
    let allowMultipleVotes: Bool
    let anonymous: Bool
    let deleted: Bool
    var votes: [SpacePollVote] = []

    var isClosed: Bool {
        guard let closesAt else { return false }
        return closesAt <= Date()
    }

    var totalVotes: Int {
        votes.count
    }

    func votesCount(for optionID: String) -> Int {
        votes.filter { $0.optionIDs.contains(optionID) }.count
    }

    func percentage(for optionID: String) -> Double {
        guard totalVotes > 0 else { return 0 }
        return Double(votesCount(for: optionID)) / Double(totalVotes)
    }

    func selectedOptionIDs(for userID: String?) -> Set<String> {
        guard let userID else { return [] }
        return Set(votes.first(where: { $0.userID == userID })?.optionIDs ?? [])
    }
}

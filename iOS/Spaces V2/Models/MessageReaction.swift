import Foundation

struct MessageReaction: Identifiable, Hashable {
    var id: String { emoji }
    let emoji: String
    let count: Int
    let isSelectedByCurrentUser: Bool
    let userNames: [String]

    init(
        emoji: String,
        count: Int,
        isSelectedByCurrentUser: Bool,
        userNames: [String] = []
    ) {
        self.emoji = emoji
        self.count = count
        self.isSelectedByCurrentUser = isSelectedByCurrentUser
        self.userNames = userNames
    }
}

import Foundation

struct MessageReaction: Identifiable, Hashable {
    var id: String { emoji }
    let emoji: String
    let count: Int
    let isSelectedByCurrentUser: Bool
}

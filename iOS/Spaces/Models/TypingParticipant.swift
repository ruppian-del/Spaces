import Foundation

struct TypingParticipant: Identifiable, Hashable {
    let id: String
    let displayName: String
    let isTyping: Bool
    let lastUpdated: Date
}

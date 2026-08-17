import Foundation

struct SpaceInvite: Identifiable, Hashable {
    let id: String
    let code: String
    let spaceId: String
    let spaceName: String
    let spaceEmoji: String
    let createdBy: String
    let createdAt: Date
    let expiresAt: Date
    let maxUses: Int
    let usedCount: Int
    let active: Bool

    var remainingUses: Int {
        max(0, maxUses - usedCount)
    }
}

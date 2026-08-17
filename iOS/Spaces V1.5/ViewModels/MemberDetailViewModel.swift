import Combine
import Foundation

@MainActor
final class MemberDetailViewModel: ObservableObject {
    @Published private(set) var member: SpaceMember

    init(member: SpaceMember) {
        self.member = member
    }

    func updateRole(_ role: SpaceMemberRole) {
        member = SpaceMember(
            id: member.id,
            displayName: member.displayName,
            emojiAvatar: member.emojiAvatar,
            role: role,
            status: member.status
        )
    }
}

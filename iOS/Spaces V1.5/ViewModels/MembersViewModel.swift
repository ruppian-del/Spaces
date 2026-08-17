import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class MembersViewModel: ObservableObject {
    @Published private(set) var members: [SpaceMember]
    @Published private(set) var isLoading = false
    @Published private(set) var isCreatingInvite = false
    @Published private(set) var pendingInvite: SpaceInvite?
    @Published var errorMessage: String?

    let space: Space

    private let spaceService: SpaceService
    private let currentUserID: String?
    private var listener: ListenerRegistration?

    init(space: Space) {
        self.space = space
        self.members = MockData.spaceMembers(for: space)
        self.spaceService = SpaceService()
        self.currentUserID = AuthService().currentSession()?.uid
    }

    init(space: Space, members: [SpaceMember]? = nil, spaceService: SpaceService) {
        self.space = space
        self.members = members ?? MockData.spaceMembers(for: space)
        self.spaceService = spaceService
        self.currentUserID = AuthService().currentSession()?.uid
    }

    deinit {
        listener?.remove()
    }

    var canInviteMembers: Bool {
        space.hasCapability(.inviteMembers, for: currentUserID, members: members)
    }

    var canManageRoles: Bool {
        members.contains(where: { canManageRoles(for: $0) })
    }

    var canManageMembers: Bool {
        members.contains(where: { canRemove($0) })
    }

    func canManageRoles(for member: SpaceMember) -> Bool {
        !availableRoles(for: member).isEmpty
    }

    func availableRoles(for member: SpaceMember) -> [SpaceMemberRole] {
        guard let currentRole = currentUserRole else { return [] }
        return SpaceMemberRole.allCases.filter {
            currentRole.canChangeRole(
                of: member.role,
                to: $0,
                isTargetOwner: member.id == space.ownerId
            )
        }
    }

    func canRemove(_ member: SpaceMember) -> Bool {
        guard let currentRole = currentUserRole else { return false }
        return currentRole.canRemove(
            targetRole: member.role,
            isTargetOwner: member.id == space.ownerId
        )
    }

    func startListeningIfNeeded() {
        guard listener == nil else { return }
        isLoading = true
        listener = spaceService.listenToMembers(for: space) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let members):
                self.members = members
                self.isLoading = false
            case .failure(let error):
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }
    }

    func createInvite() async {
        guard pendingInvite == nil else { return }
        await openInviteEditor()
    }

    func openInviteEditor() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }

        do {
            if let existingInvite = try await spaceService.fetchLatestInvite(for: space) {
                pendingInvite = existingInvite
            } else {
                await createFreshInvite()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func createFreshInvite() async {
        guard !isCreatingInvite else { return }
        isCreatingInvite = true
        defer { isCreatingInvite = false }

        do {
            pendingInvite = try await spaceService.createInvite(for: space)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func dismissInvite() {
        pendingInvite = nil
    }

    func clearError() {
        errorMessage = nil
    }

    func updateRole(for memberID: String, to role: SpaceMemberRole) async {
        do {
            try await spaceService.updateMemberRole(in: space, memberID: memberID, role: role)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func removeMember(withID memberID: String) async -> Bool {
        do {
            try await spaceService.removeMember(from: space, memberID: memberID)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    private var currentUserRole: SpaceMemberRole? {
        space.role(for: currentUserID, members: members)
    }
}

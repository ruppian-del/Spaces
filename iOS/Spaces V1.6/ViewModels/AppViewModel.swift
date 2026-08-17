import Combine
import Foundation

@MainActor
final class AppViewModel: ObservableObject {
    @Published var hasCompletedOnboarding = false
    @Published private(set) var pendingInviteCode: String?
    @Published private(set) var pendingOrganizationInviteCode: String?

    func completeOnboarding() {
        hasCompletedOnboarding = true
    }

    func handleIncomingURL(_ url: URL) -> Bool {
        if let code = InviteLink.organizationCode(from: url), !code.isEmpty { pendingOrganizationInviteCode = code; return true }
        guard let code = InviteLink.code(from: url), !code.isEmpty else {
            return false
        }

        pendingInviteCode = code
        return true
    }

    func clearPendingOrganizationInviteCode() { pendingOrganizationInviteCode = nil }

    func clearPendingInviteCode() {
        pendingInviteCode = nil
    }
}

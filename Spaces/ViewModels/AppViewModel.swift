import Combine
import Foundation

@MainActor
final class AppViewModel: ObservableObject {
    @Published var hasCompletedOnboarding = false
    @Published private(set) var pendingInviteCode: String?

    func completeOnboarding() {
        hasCompletedOnboarding = true
    }

    func handleIncomingURL(_ url: URL) -> Bool {
        guard let code = InviteLink.code(from: url), !code.isEmpty else {
            return false
        }

        pendingInviteCode = code
        return true
    }

    func clearPendingInviteCode() {
        pendingInviteCode = nil
    }
}

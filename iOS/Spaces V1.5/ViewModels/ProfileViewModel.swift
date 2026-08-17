import Combine
import Foundation

@MainActor
final class ProfileViewModel: ObservableObject {
    @Published private(set) var profile: UserProfile

    init(profile: UserProfile? = nil) {
        self.profile = profile ?? UserProfile(
            id: "mock-ian",
            uid: "mock-ian",
            displayName: "Ian",
            emojiAvatar: "🧑‍💻",
            statusMessage: "Building Spaces",
            email: nil,
            phoneNumber: "(555) 013-1009",
            linkedProviders: [.apple, .google, .phone]
        )
    }

    var linkedProvidersText: String {
        profile.linkedProviders.map(\.rawValue).joined(separator: ", ")
    }
}

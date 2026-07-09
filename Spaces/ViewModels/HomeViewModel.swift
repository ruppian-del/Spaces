import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var spaces: [Space] = []
    @Published private(set) var isLoading = false
    @Published private(set) var isCreating = false
    @Published private(set) var isJoining = false
    @Published private(set) var greetingTitle = "Welcome"
    @Published private(set) var greetingSubtitle = "Your spaces will show up here."
    @Published var errorMessage: String?
    @Published var successMessage: String?

    private let spaceService: SpaceService
    private let authService: AuthService
    private let userProfileService: UserProfileService
    private var listener: ListenerRegistration?

    init() {
        self.spaceService = SpaceService()
        self.authService = AuthService()
        self.userProfileService = UserProfileService()
    }

    init(spaceService: SpaceService) {
        self.spaceService = spaceService
        self.authService = AuthService()
        self.userProfileService = UserProfileService()
    }

    deinit {
        listener?.remove()
    }

    func startListeningIfNeeded() {
        Task {
            await loadGreeting()
        }
        guard listener == nil else { return }
        isLoading = true
        listener = spaceService.listenToSpacesForCurrentUser { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let spaces):
                self.spaces = spaces
                self.greetingSubtitle = spaces.isEmpty ? "Create or join a Space to get started." : "Your spaces are active tonight."
                self.isLoading = false
            case .failure(let error):
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }
    }

    func createSpace(
        name: String,
        emoji: String,
        tintHex: String,
        description: String,
        template: SpaceTemplate,
        enabledModules: [SpaceModule]
    ) async {
        guard !isCreating else { return }
        isCreating = true
        defer { isCreating = false }

        do {
            let newSpace = try await spaceService.createSpace(
                name: name,
                emoji: emoji,
                tintHex: tintHex,
                description: description,
                template: template,
                enabledModules: enabledModules
            )
            spaces.insert(newSpace, at: 0)
            greetingSubtitle = "Your spaces are active tonight."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func redeemInvite(code: String) async -> Bool {
        guard !isJoining else { return false }
        isJoining = true
        defer { isJoining = false }

        do {
            let space = try await spaceService.redeemInvite(code: code)
            successMessage = "Joined \(space.name)."
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    private func loadGreeting() async {
        let fallbackName = authService.currentSession()?.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let profileName: String?
        if let uid = authService.currentSession()?.uid {
            if let profile = try? await userProfileService.fetchUserProfile(uid: uid) {
                profileName = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            } else {
                profileName = nil
            }
        } else {
            profileName = nil
        }

        let resolvedName = [profileName, fallbackName]
            .compactMap { $0 }
            .first { !$0.isEmpty }
            .map { String($0.split(separator: " ").first ?? Substring($0)) }

        greetingTitle = Self.buildGreetingTitle(name: resolvedName)
    }

    private static func buildGreetingTitle(name: String?) -> String {
        let hour = Calendar.current.component(.hour, from: Date())
        let greeting: String
        switch hour {
        case 5..<12:
            greeting = "Good Morning"
        case 12..<17:
            greeting = "Good Afternoon"
        default:
            greeting = "Good Evening"
        }
        guard let name, !name.isEmpty else { return greeting }
        return "\(greeting), \(name)"
    }
}

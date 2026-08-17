import Combine
import FirebaseFirestore
import Foundation
import SwiftUI

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var spaces: [Space] = []
    @Published private(set) var isLoading = false
    @Published private(set) var isCreating = false
    @Published private(set) var isJoining = false
    @Published private(set) var greetingTitle = "Welcome"
    @Published private(set) var greetingSubtitle = "Your spaces will show up here."
    @Published private(set) var draftPreviews: [String: String] = [:]
    @Published var errorMessage: String?
    @Published var successMessage: String?

    private let spaceService: SpaceService
    private let authService: AuthService
    private let userProfileService: UserProfileService
    private let draftStore: SpaceDraftStore
    private var listener: ListenerRegistration?
    private var spaceOrder: [String] = []

    init() {
        self.spaceService = SpaceService()
        self.authService = AuthService()
        self.userProfileService = UserProfileService()
        self.draftStore = SpaceDraftStore()
    }

    init(spaceService: SpaceService) {
        self.spaceService = spaceService
        self.authService = AuthService()
        self.userProfileService = UserProfileService()
        self.draftStore = SpaceDraftStore()
    }

    deinit {
        listener?.remove()
    }

    func startListeningIfNeeded() {
        Task {
            await loadGreeting()
            await loadSpaceOrder()
            refreshDraftPreviews()
        }
        guard listener == nil else { return }
        isLoading = true
        listener = spaceService.listenToSpacesForCurrentUser { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let spaces):
                self.handleIncomingSpaces(spaces)
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
        enabledModules: [SpaceModule],
        organizationID: String? = nil
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
                enabledModules: enabledModules,
                organizationID: organizationID
            )
            spaces.removeAll { $0.id == newSpace.id }
            spaces.append(newSpace)
            await appendSpaceToOrder(newSpace.id)
            spaces = orderedSpaces(spaces)
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
            await appendSpaceToOrder(space.id)
            successMessage = "Joined \(space.name)."
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func moveSpaces(fromOffsets: IndexSet, toOffset: Int) async {
        var updatedSpaces = spaces
        updatedSpaces.move(fromOffsets: fromOffsets, toOffset: toOffset)
        spaces = updatedSpaces
        spaceOrder = updatedSpaces.map(\.id)

        do {
            try await spaceService.saveSpaceOrderForCurrentUser(spaceOrder)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refreshDraftPreviews() {
        guard let userID = authService.currentSession()?.uid else {
            draftPreviews = [:]
            return
        }
        draftPreviews = draftStore.draftPreviews(for: userID)
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

    private func loadSpaceOrder() async {
        do {
            spaceOrder = try await spaceService.fetchSpaceOrderForCurrentUser()
            if !spaces.isEmpty {
                spaces = orderedSpaces(spaces)
                await reconcileSpaceOrder(with: spaces)
            }
        } catch {
            spaceOrder = []
        }
    }

    private func handleIncomingSpaces(_ incomingSpaces: [Space]) {
        let ordered = orderedSpaces(incomingSpaces)
        spaces = ordered
        Task {
            await reconcileSpaceOrder(with: ordered)
        }
    }

    private func orderedSpaces(_ rawSpaces: [Space]) -> [Space] {
        let orderIndex = Dictionary(uniqueKeysWithValues: spaceOrder.enumerated().map { ($1, $0) })
        return rawSpaces.sorted { lhs, rhs in
            let lhsIndex = orderIndex[lhs.id] ?? Int.max
            let rhsIndex = orderIndex[rhs.id] ?? Int.max
            if lhsIndex != rhsIndex {
                return lhsIndex < rhsIndex
            }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    private func reconcileSpaceOrder(with visibleSpaces: [Space]) async {
        let visibleIDs = Set(visibleSpaces.map(\.id))
        var nextOrder = spaceOrder.filter { visibleIDs.contains($0) }

        for id in visibleSpaces.map(\.id) where !nextOrder.contains(id) {
            nextOrder.append(id)
        }

        guard nextOrder != spaceOrder else { return }
        spaceOrder = nextOrder
        try? await spaceService.saveSpaceOrderForCurrentUser(nextOrder)
    }

    private func appendSpaceToOrder(_ spaceID: String) async {
        guard !spaceOrder.contains(spaceID) else { return }
        spaceOrder.append(spaceID)
        try? await spaceService.saveSpaceOrderForCurrentUser(spaceOrder)
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

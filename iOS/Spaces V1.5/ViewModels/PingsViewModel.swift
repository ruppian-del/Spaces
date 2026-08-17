import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class PingsViewModel: ObservableObject {
    @Published private(set) var pings: [Ping] = []
    @Published private(set) var availableParticipants: [PingParticipant] = []
    @Published private(set) var isLoading = false
    @Published private(set) var isLoadingParticipants = false
    @Published private(set) var isCreatingPing = false
    @Published var errorMessage: String?

    private let pingService: PingService
    private var listener: ListenerRegistration?

    init(pingService: PingService? = nil) {
        self.pingService = pingService ?? PingService()
    }

    deinit {
        listener?.remove()
    }

    func startListeningIfNeeded() {
        guard listener == nil else { return }
        isLoading = true
        listener = pingService.listenToPingsForCurrentUser { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let pings):
                self.pings = pings
                self.isLoading = false
            case .failure(let error):
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }
    }

    func loadAvailableParticipants() async {
        guard !isLoadingParticipants else { return }
        isLoadingParticipants = true
        defer { isLoadingParticipants = false }

        do {
            availableParticipants = try await pingService.fetchAvailableParticipants()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createOrOpenPing(with participant: PingParticipant) async -> Ping? {
        guard !isCreatingPing else { return nil }
        isCreatingPing = true
        defer { isCreatingPing = false }

        do {
            return try await pingService.createOrOpenPing(with: participant)
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func currentUserID() -> String? {
        pingService.currentUserID()
    }
}

import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class ActivityViewModel: ObservableObject {
    struct ActivityError: Identifiable {
        let id = UUID()
        let message: String
    }

    @Published private(set) var items: [ActivityItem] = []
    @Published private(set) var spacesByID: [String: Space] = [:]
    @Published private(set) var isLoading = false
    @Published var activeError: ActivityError?

    private let spaceService: SpaceService
    private var spacesListener: ListenerRegistration?
    private var activityListener: ListenerRegistration?

    init(spaceService: SpaceService? = nil) {
        self.spaceService = spaceService ?? SpaceService()
    }

    deinit {
        spacesListener?.remove()
        activityListener?.remove()
    }

    var currentUserID: String? {
        spaceService.currentUserID()
    }

    var groupedItems: [(section: ActivitySection, items: [ActivityItem])] {
        ActivitySection.allCases.compactMap { section in
            let sectionItems = items.filter { $0.section == section }
            return sectionItems.isEmpty ? nil : (section, sectionItems)
        }
    }

    func startListeningIfNeeded() {
        guard spacesListener == nil else { return }
        isLoading = true
        spacesListener = spaceService.listenToSpacesForCurrentUser { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let spaces):
                let mappedSpaces = Dictionary(uniqueKeysWithValues: spaces.map { ($0.id, $0) })
                let spaceIDs = spaces.map(\.id)
                self.enqueueStateUpdate { [weak self] in
                    self?.spacesByID = mappedSpaces
                    self?.restartActivityListeners(spaceIDs: spaceIDs)
                }
            case .failure(let error):
                let message = error.localizedDescription
                self.enqueueStateUpdate { [weak self] in
                    self?.activeError = ActivityError(message: message)
                    self?.isLoading = false
                }
            }
        }
    }

    func markRead(_ item: ActivityItem) async {
        guard item.isUnread(for: currentUserID) else { return }
        do {
            try await spaceService.markActivityRead(item)
        } catch {
            let message = error.localizedDescription
            enqueueStateUpdate { [weak self] in
                self?.activeError = ActivityError(message: message)
            }
        }
    }

    func clear(_ item: ActivityItem) async {
        do {
            try await spaceService.clearActivity(item)
        } catch {
            let message = error.localizedDescription
            enqueueStateUpdate { [weak self] in
                self?.activeError = ActivityError(message: message)
            }
        }
    }

    func clearAll() async {
        let spaceIDs = Array(Set(items.map(\.spaceID))).sorted()
        guard !spaceIDs.isEmpty else { return }

        do {
            try await spaceService.clearAllActivity(forSpaceIDs: spaceIDs)
        } catch {
            let message = error.localizedDescription
            enqueueStateUpdate { [weak self] in
                self?.activeError = ActivityError(message: message)
            }
        }
    }

    private func restartActivityListeners(spaceIDs: [String]) {
        activityListener?.remove()
        activityListener = spaceService.listenToActivity(forSpaceIDs: spaceIDs) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let items):
                self.enqueueStateUpdate { [weak self] in
                    self?.items = items
                    self?.isLoading = false
                }
            case .failure(let error):
                let message = error.localizedDescription
                self.enqueueStateUpdate { [weak self] in
                    self?.activeError = ActivityError(message: message)
                    self?.isLoading = false
                }
            }
        }
    }

    private func enqueueStateUpdate(_ update: @escaping () -> Void) {
        DispatchQueue.main.async(execute: update)
    }
}

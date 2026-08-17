import Combine
import FirebaseFirestore
import Foundation
import UIKit

@MainActor
final class NotificationsViewModel: ObservableObject {
    struct NotificationError: Identifiable {
        let id = UUID()
        let message: String
    }

    @Published private(set) var items: [SpaceNotificationItem] = []
    @Published private(set) var spacesByID: [String: Space] = [:]
    @Published private(set) var isLoading = false
    @Published private(set) var isMarkingAllRead = false
    @Published var activeError: NotificationError?

    private let spaceService: SpaceService
    private var spacesListener: ListenerRegistration?
    private var notificationsListener: ListenerRegistration?

    init(spaceService: SpaceService? = nil) {
        self.spaceService = spaceService ?? SpaceService()
    }

    deinit {
        spacesListener?.remove()
        notificationsListener?.remove()
    }

    var unreadCount: Int {
        items.filter(\.isUnread).count
    }

    var groupedItems: [(section: NotificationSection, items: [SpaceNotificationItem])] {
        NotificationSection.allCases.compactMap { section in
            let sectionItems = items.filter { $0.section == section }
            return sectionItems.isEmpty ? nil : (section, sectionItems)
        }
    }

    func startListeningIfNeeded() {
        guard spacesListener == nil, notificationsListener == nil else { return }
        isLoading = true

        spacesListener = spaceService.listenToSpacesForCurrentUser { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let spaces):
                let mappedSpaces = Dictionary(uniqueKeysWithValues: spaces.map { ($0.id, $0) })
                self.enqueueStateUpdate { [weak self] in
                    self?.spacesByID = mappedSpaces
                }
            case .failure(let error):
                let message = error.localizedDescription
                self.enqueueStateUpdate { [weak self] in
                    self?.activeError = NotificationError(message: message)
                }
            }
        }

        notificationsListener = spaceService.listenToNotifications { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let items):
                self.enqueueStateUpdate { [weak self] in
                    self?.items = items
                    self?.isLoading = false
                    self?.updateAppIconBadge()
                }
            case .failure(let error):
                let message = error.localizedDescription
                self.enqueueStateUpdate { [weak self] in
                    self?.activeError = NotificationError(message: message)
                    self?.isLoading = false
                }
            }
        }
    }

    func markRead(_ item: SpaceNotificationItem) async {
        guard item.isUnread else { return }
        do {
            try await spaceService.markNotificationRead(item)
        } catch {
            let message = error.localizedDescription
            enqueueStateUpdate { [weak self] in
                self?.activeError = NotificationError(message: message)
            }
        }
    }

    func markAllRead() async {
        guard unreadCount > 0, !isMarkingAllRead else { return }
        isMarkingAllRead = true

        do {
            try await spaceService.markAllNotificationsRead()
        } catch {
            let message = error.localizedDescription
            enqueueStateUpdate { [weak self] in
                self?.activeError = NotificationError(message: message)
            }
        }

        enqueueStateUpdate { [weak self] in
            self?.isMarkingAllRead = false
        }
    }

    private func enqueueStateUpdate(_ update: @escaping () -> Void) {
        DispatchQueue.main.async(execute: update)
    }

    private func updateAppIconBadge() {
        UIApplication.shared.applicationIconBadgeNumber = unreadCount
    }
}

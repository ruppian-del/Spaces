import FirebaseCore
import FirebaseFirestore
import Foundation
import UIKit

@MainActor
final class TypingIndicatorService {
    private struct TypingDocument {
        let userID: String
        let displayName: String
        let isTyping: Bool
        let lastUpdated: Date
    }

    private let authService: AuthService
    private let userProfileService: UserProfileService
    private let firestore: Firestore?
    private let inactivityInterval: TimeInterval
    private let staleInterval: TimeInterval
    private let refreshInterval: TimeInterval
    private let refreshWriteInterval: TimeInterval

    private var listener: ListenerRegistration?
    private var inactiveTask: Task<Void, Never>?
    private var staleRefreshTask: Task<Void, Never>?
    private var profileTask: Task<Void, Never>?
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var activeSpaceID: String?
    private var currentUserID: String?
    private var currentDisplayName: String = "Member"
    private var activeDocuments: [String: TypingDocument] = [:]
    private var isCurrentlyPublishingTyping = false
    private var hasComposerText = false
    private var lastPublishedAt: Date?

    var onParticipantsChanged: (([TypingParticipant]) -> Void)?
    var onError: ((String) -> Void)?

    init(
        authService: AuthService? = nil,
        userProfileService: UserProfileService? = nil,
        firestore: Firestore? = nil,
        inactivityInterval: TimeInterval = 5,
        staleInterval: TimeInterval = 10,
        refreshInterval: TimeInterval = 1,
        refreshWriteInterval: TimeInterval = 3
    ) {
        let resolvedAuthService = authService ?? AuthService()
        let resolvedFirestore = firestore ?? FirebaseApp.app().map { _ in Firestore.firestore() }

        self.authService = resolvedAuthService
        self.firestore = resolvedFirestore
        self.userProfileService = userProfileService ?? UserProfileService(firestore: resolvedFirestore)
        self.inactivityInterval = inactivityInterval
        self.staleInterval = staleInterval
        self.refreshInterval = refreshInterval
        self.refreshWriteInterval = refreshWriteInterval
        registerLifecycleObservers()
    }

    deinit {
        let listener = listener
        let observers = lifecycleObservers
        Task { @MainActor in
            listener?.remove()
            observers.forEach(NotificationCenter.default.removeObserver)
        }
    }

    func start(spaceID: String) {
        if let activeSpaceID, activeSpaceID != spaceID {
            publishTypingState(isTyping: false)
        }
        guard activeSpaceID != spaceID || listener == nil else { return }
        clearListener()
        activeSpaceID = spaceID
        currentUserID = authService.currentSession()?.uid
        loadCurrentProfile()
        listenForTyping(in: spaceID)
        startStaleRefreshLoop()
    }

    func updateComposerText(_ text: String) {
        let hasText = !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        hasComposerText = hasText

        if hasText {
            publishTypingIfNeeded(force: !isCurrentlyPublishingTyping)
            scheduleInactiveClear()
        } else {
            clearInactiveTask()
            publishTypingState(isTyping: false)
        }
    }

    func stop() {
        hasComposerText = false
        clearInactiveTask()
        publishTypingState(isTyping: false)
        clearListener()
        clearStaleRefreshLoop()
        activeSpaceID = nil
        activeDocuments = [:]
        onParticipantsChanged?([])
    }

    func messageSent() {
        hasComposerText = false
        clearInactiveTask()
        publishTypingState(isTyping: false)
    }

    private func registerLifecycleObservers() {
        let center = NotificationCenter.default
        lifecycleObservers = [
            center.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor in
                    self?.handleDidEnterBackground()
                }
            },
            center.addObserver(
                forName: UIApplication.willEnterForegroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor in
                    self?.handleWillEnterForeground()
                }
            },
            center.addObserver(
                forName: UIApplication.willTerminateNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor in
                    self?.handleWillTerminate()
                }
            }
        ]
    }

    private func handleDidEnterBackground() {
        guard activeSpaceID != nil else { return }
        publishTypingState(isTyping: false)
    }

    private func handleWillEnterForeground() {
        guard hasComposerText else { return }
        publishTypingIfNeeded(force: true)
        scheduleInactiveClear()
    }

    private func handleWillTerminate() {
        guard activeSpaceID != nil else { return }
        publishTypingState(isTyping: false)
    }

    private func loadCurrentProfile() {
        profileTask?.cancel()
        profileTask = Task { [weak self] in
            guard let self else { return }
            guard let session = authService.currentSession() else { return }
            currentUserID = session.uid
            currentDisplayName = session.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "Member"
                : session.displayName
            if let profile = try? await userProfileService.fetchUserProfile(uid: session.uid) {
                let trimmedName = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmedName.isEmpty {
                    currentDisplayName = trimmedName
                }
            }
        }
    }

    private func listenForTyping(in spaceID: String) {
        guard let firestore else { return }
        listener = firestore.collection("spaces")
            .document(spaceID)
            .collection("typing")
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self else { return }
                if let error {
                    let message = "Typing indicator failed to load: \(error.localizedDescription)"
                    print("[TypingIndicatorService] \(message)")
                    self.onError?(message)
                    return
                }
                let now = Date()
                let documents = snapshot?.documents.compactMap { document -> TypingDocument? in
                    let data = document.data()
                    let userID = (data["userId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? document.documentID
                    let trimmedDisplayName = (data["displayName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    let displayName = trimmedDisplayName.isEmpty ? "Member" : trimmedDisplayName
                    let isTyping = data["isTyping"] as? Bool ?? false
                    let lastUpdated = (data["lastUpdated"] as? Timestamp)?.dateValue() ?? .distantPast
                    return TypingDocument(userID: userID, displayName: displayName, isTyping: isTyping, lastUpdated: lastUpdated)
                } ?? []

                self.activeDocuments = Dictionary(uniqueKeysWithValues: documents.map { ($0.userID, $0) })
                self.emitVisibleParticipants(now: now)
            }
    }

    private func startStaleRefreshLoop() {
        clearStaleRefreshLoop()
        staleRefreshTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(refreshInterval * 1_000_000_000))
                guard !Task.isCancelled else { break }
                emitVisibleParticipants(now: Date())
            }
        }
    }

    private func clearListener() {
        listener?.remove()
        listener = nil
    }

    private func clearStaleRefreshLoop() {
        staleRefreshTask?.cancel()
        staleRefreshTask = nil
    }

    private func scheduleInactiveClear() {
        clearInactiveTask()
        inactiveTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(inactivityInterval * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self.hasComposerText = false
            self.publishTypingState(isTyping: false)
        }
    }

    private func clearInactiveTask() {
        inactiveTask?.cancel()
        inactiveTask = nil
    }

    private func publishTypingIfNeeded(force: Bool) {
        guard hasComposerText else { return }
        if force {
            publishTypingState(isTyping: true)
            return
        }
        if let lastPublishedAt, Date().timeIntervalSince(lastPublishedAt) < refreshWriteInterval {
            return
        }
        publishTypingState(isTyping: true)
    }

    private func publishTypingState(isTyping: Bool) {
        guard let firestore, let spaceID = activeSpaceID, let userID = currentUserID ?? authService.currentSession()?.uid else { return }
        currentUserID = userID
        isCurrentlyPublishingTyping = isTyping
        lastPublishedAt = Date()

        let payload: [String: Any] = [
            "userId": userID,
            "displayName": currentDisplayName,
            "isTyping": isTyping,
            "lastUpdated": FieldValue.serverTimestamp()
        ]

        firestore.collection("spaces")
            .document(spaceID)
            .collection("typing")
            .document(userID)
            .setData(payload, merge: true) { [weak self] error in
                guard let self, let error else { return }
                let message = "Typing indicator failed to update: \(error.localizedDescription)"
                print("[TypingIndicatorService] \(message)")
                self.onError?(message)
            }
    }

    private func emitVisibleParticipants(now: Date) {
        let currentUserID = currentUserID ?? authService.currentSession()?.uid
        let participants = activeDocuments.values
            .filter { document in
                document.isTyping &&
                document.userID != currentUserID &&
                now.timeIntervalSince(document.lastUpdated) <= staleInterval
            }
            .sorted { lhs, rhs in
                lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
            }
            .map {
                TypingParticipant(
                    id: $0.userID,
                    displayName: $0.displayName,
                    isTyping: $0.isTyping,
                    lastUpdated: $0.lastUpdated
                )
            }

        onParticipantsChanged?(participants)
    }
}

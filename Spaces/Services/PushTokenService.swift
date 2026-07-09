import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseMessaging
import Foundation
import UIKit
import UserNotifications

final class PushTokenService: NSObject, UNUserNotificationCenterDelegate, MessagingDelegate {
    static let shared = PushTokenService()

    private let deviceIdentityService = DeviceIdentityService()
    private let defaults = UserDefaults.standard
    private let lastBoundUserKey = "spaces.push.lastBoundUserId"
    private let currentTokenKey = "spaces.push.currentFcmToken"
    private var hasConfigured = false
    private var hasAPNsToken = false

    private override init() {
        super.init()
    }

    func configureIfNeeded() {
        guard !hasConfigured else { return }
        hasConfigured = true
        UNUserNotificationCenter.current().delegate = self
        Messaging.messaging().delegate = self
        log("Configured notification center + messaging delegate.")
    }

    @MainActor
    func activateForCurrentUser() async {
        configureIfNeeded()

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        log("Notification permission status: \(authorizationStatusDescription(settings.authorizationStatus))")
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            log("Calling registerForRemoteNotifications() for existing authorization.")
            UIApplication.shared.registerForRemoteNotifications()
        case .notDetermined:
            let granted = (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
            log("Notification authorization request completed. granted=\(granted)")
            if granted {
                log("Calling registerForRemoteNotifications() after permission grant.")
                UIApplication.shared.registerForRemoteNotifications()
            }
        case .denied:
            log("Notification permission denied. Skipping APNs registration.")
            break
        @unknown default:
            log("Notification permission returned unknown status.")
            break
        }

        await syncCurrentMessagingToken()
    }

    func handleAPNsToken(_ token: Data) {
        configureIfNeeded()
        hasAPNsToken = true
        log("didRegisterForRemoteNotificationsWithDeviceToken fired. APNs token bytes=\(token.count) token=\(token.hexString)")
        Messaging.messaging().apnsToken = token
        log("APNs token set on Firebase Messaging.")
        Task {
            await syncCurrentMessagingToken()
        }
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken, !fcmToken.isEmpty else {
            log("Received empty FCM registration token.")
            return
        }
        log("FCM registration token received. length=\(fcmToken.count) apnsTokenAvailable=\(hasAPNsToken)")
        defaults.set(fcmToken, forKey: currentTokenKey)
        Task {
            await saveTokenIfPossible(fcmToken)
        }
    }

    @MainActor
    func disableCurrentTokenForSignedOutUser() async {
        guard
            let previousUserID = defaults.string(forKey: lastBoundUserKey)?.trimmingCharacters(in: .whitespacesAndNewlines),
            !previousUserID.isEmpty,
            let token = defaults.string(forKey: currentTokenKey)?.trimmingCharacters(in: .whitespacesAndNewlines),
            !token.isEmpty,
            let firestore = firestoreOrNil()
        else {
            log("No signed-out token state to disable.")
            defaults.removeObject(forKey: lastBoundUserKey)
            return
        }

        let tokenID = Self.tokenDocumentID(for: token)
        let path = "users/\(previousUserID)/pushTokens/\(tokenID)"
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                firestore.collection("users")
                    .document(previousUserID)
                    .collection("pushTokens")
                    .document(tokenID)
                    .setData([
                        "id": tokenID,
                        "userId": previousUserID,
                        "token": token,
                        "platform": "ios",
                        "deviceId": deviceIdentityService.currentDeviceID(),
                        "enabled": false,
                        "updatedAt": FieldValue.serverTimestamp()
                    ], merge: true) { error in
                        if let error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(returning: ())
                        }
                    }
            }
            log("Disabled signed-out push token at \(path)")
        } catch {
            log("Failed to disable signed-out token at \(path): \(error.localizedDescription)")
        }

        defaults.removeObject(forKey: lastBoundUserKey)
    }

    @MainActor
    func setCurrentTokenEnabled(_ enabled: Bool) async throws {
        guard
            let session = currentSession(),
            let token = defaults.string(forKey: currentTokenKey)?.trimmingCharacters(in: .whitespacesAndNewlines),
            !token.isEmpty,
            let firestore = firestoreOrNil()
        else {
            throw PushTokenServiceError.tokenUnavailable
        }

        await disablePreviousBindingIfNeeded(
            for: token,
            currentUserID: session.uid,
            firestore: firestore
        )

        let tokenID = Self.tokenDocumentID(for: token)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            firestore.collection("users")
                .document(session.uid)
                .collection("pushTokens")
                .document(tokenID)
                .setData([
                    "id": tokenID,
                    "userId": session.uid,
                    "token": token,
                    "platform": "ios",
                    "deviceId": deviceIdentityService.currentDeviceID(),
                    "enabled": enabled,
                    "updatedAt": FieldValue.serverTimestamp()
                ], merge: true) { error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(returning: ())
                    }
                }
        }

        if enabled {
            defaults.set(session.uid, forKey: lastBoundUserKey)
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        log("Foreground push received. identifier=\(notification.request.identifier)")
        completionHandler([.banner, .list, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        log("Notification interaction received. identifier=\(response.notification.request.identifier)")
        completionHandler()
    }

    private func syncCurrentMessagingToken() async {
        guard hasAPNsToken else {
            log("Skipping FCM token retrieval because APNs token is not available yet.")
            return
        }

        log("Attempting to retrieve FCM token. apnsTokenAvailable=\(hasAPNsToken)")
        do {
            let token = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
                Messaging.messaging().token { token, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let token, !token.isEmpty {
                        continuation.resume(returning: token)
                    } else {
                        continuation.resume(throwing: PushTokenServiceError.tokenUnavailable)
                    }
                }
            }
            defaults.set(token, forKey: currentTokenKey)
            log("Retrieved FCM token successfully. length=\(token.count)")
            await saveTokenIfPossible(token)
        } catch {
            log("Unable to sync FCM token: \(error.localizedDescription)")
        }
    }

    private func saveTokenIfPossible(_ token: String) async {
        guard
            let session = currentSession(),
            let firestore = firestoreOrNil()
        else {
            log("Skipping token save because auth session or Firestore is unavailable.")
            return
        }

        let tokenID = Self.tokenDocumentID(for: token)
        let path = "users/\(session.uid)/pushTokens/\(tokenID)"
        await disablePreviousBindingIfNeeded(
            for: token,
            currentUserID: session.uid,
            firestore: firestore
        )

        log("Saving FCM token to Firestore. path=\(path)")
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                firestore.collection("users")
                    .document(session.uid)
                    .collection("pushTokens")
                    .document(tokenID)
                    .setData([
                        "id": tokenID,
                        "userId": session.uid,
                        "token": token,
                        "platform": "ios",
                        "deviceId": deviceIdentityService.currentDeviceID(),
                        "createdAt": FieldValue.serverTimestamp(),
                        "updatedAt": FieldValue.serverTimestamp(),
                        "enabled": true
                    ], merge: true) { error in
                        if let error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(returning: ())
                        }
                    }
            }
            defaults.set(session.uid, forKey: lastBoundUserKey)
            log("FCM token saved to Firestore successfully. path=\(path)")
        } catch {
            log("Failed to save FCM token to Firestore at \(path): \(error.localizedDescription)")
        }
    }

    private func disablePreviousBindingIfNeeded(
        for token: String,
        currentUserID: String,
        firestore: Firestore
    ) async {
        guard
            let previousUserID = defaults.string(forKey: lastBoundUserKey)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty,
            previousUserID != currentUserID
        else {
            return
        }

        await disableToken(token: token, for: previousUserID, firestore: firestore)
    }

    private func disableToken(token: String, for userID: String, firestore: Firestore) async {
        let tokenID = Self.tokenDocumentID(for: token)
        let path = "users/\(userID)/pushTokens/\(tokenID)"
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                firestore.collection("users")
                    .document(userID)
                    .collection("pushTokens")
                    .document(tokenID)
                    .setData([
                        "id": tokenID,
                        "userId": userID,
                        "token": token,
                        "platform": "ios",
                        "deviceId": deviceIdentityService.currentDeviceID(),
                        "enabled": false,
                        "updatedAt": FieldValue.serverTimestamp()
                    ], merge: true) { error in
                        if let error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(returning: ())
                        }
                    }
            }
            log("Disabled previous-user token at \(path)")
        } catch {
            log("Failed to disable previous-user token at \(path): \(error.localizedDescription)")
        }
    }

    private func firestoreOrNil() -> Firestore? {
        guard FirebaseApp.app() != nil else { return nil }
        return Firestore.firestore()
    }

    private func currentSession() -> AuthSession? {
        guard let user = Auth.auth().currentUser else { return nil }
        return AuthSession(
            uid: user.uid,
            displayName: user.displayName?.nilIfEmpty ?? user.phoneNumber?.nilIfEmpty ?? user.email?.nilIfEmpty ?? "Signed In",
            email: user.email,
            phoneNumber: user.phoneNumber,
            providers: []
        )
    }

    private static func tokenDocumentID(for token: String) -> String {
        let digest = SHA256.hash(data: Data(token.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func authorizationStatusDescription(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .notDetermined:
            return "notDetermined"
        case .denied:
            return "denied"
        case .authorized:
            return "authorized"
        case .provisional:
            return "provisional"
        case .ephemeral:
            return "ephemeral"
        @unknown default:
            return "unknown"
        }
    }

    private func log(_ message: String) {
        print("[PushTokenService] \(message)")
    }
}

private enum PushTokenServiceError: LocalizedError {
    case tokenUnavailable

    var errorDescription: String? {
        switch self {
        case .tokenUnavailable:
            return "Push token unavailable."
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}

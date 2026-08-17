

import FirebaseCore
import FirebaseAuth
import FirebaseMessaging
import UIKit

final class SpacesAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        print("[SpacesAppDelegate] didFinishLaunchingWithOptions")

        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
            print("[SpacesAppDelegate] FirebaseApp configured")
        }

        _ = Auth.auth()
        PushTokenService.shared.configureIfNeeded()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        print("[SpacesAppDelegate] didRegisterForRemoteNotificationsWithDeviceToken fired. bytes=\(deviceToken.count)")
        PushTokenService.shared.handleAPNsToken(deviceToken)
        Messaging.messaging().apnsToken = deviceToken
        print("[SpacesAppDelegate] Forwarded APNs token to PushTokenService and Firebase Messaging.")
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("[SpacesAppDelegate] Failed to register for remote notifications: \(error.localizedDescription)")
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        if Auth.auth().canHandle(url) {
            print("[SpacesAppDelegate] Firebase Auth handled incoming URL.")
            return true
        }

        return false
    }
}

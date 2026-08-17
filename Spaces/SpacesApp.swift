import FirebaseCore
import FirebaseAuth
import GiphyUISDK
import SwiftUI

@main
struct SpacesApp: App {
    @UIApplicationDelegateAdaptor(SpacesAppDelegate.self) private var appDelegate
    @StateObject private var appViewModel = AppViewModel()
    @StateObject private var authViewModel = AuthViewModel()

    init() {
        FirebaseBootstrap.configureIfNeeded()
        GiphyBootstrap.configureIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if authViewModel.isResolvingUserState {
                    ProgressView()
                        .controlSize(.large)
                } else if authViewModel.requiresProfileCreation {
                    OnboardingView {
                    }
                } else if authViewModel.isAuthenticated || appViewModel.hasCompletedOnboarding {
                    RootTabView()
                } else {
                    OnboardingView {
                        appViewModel.completeOnboarding()
                    }
                }
            }
            .environmentObject(appViewModel)
            .environmentObject(authViewModel)
            .animation(.easeInOut(duration: 0.25), value: appViewModel.hasCompletedOnboarding)
            .task(id: authViewModel.isAuthenticated) {
                if authViewModel.isAuthenticated {
                    await PushTokenService.shared.activateForCurrentUser()
                }
            }
            .onOpenURL { url in
                if Auth.auth().canHandle(url) {
                    print("[PhoneLink] Firebase Auth handled incoming URL.")
                    return
                }

                if appViewModel.handleIncomingURL(url) {
                    return
                }

                _ = authViewModel.handleIncomingURL(url)
            }
        }
    }
}

struct SpacesApp_Previews: PreviewProvider {
    static var previews: some View {
        OnboardingView {
        }
        .environmentObject(AppViewModel())
        .environmentObject(AuthViewModel())
    }
}

private enum FirebaseBootstrap {
    static func configureIfNeeded() {
        guard FirebaseApp.app() == nil else { return }
        guard
            let filePath = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
            let options = FirebaseOptions(contentsOfFile: filePath)
        else {
            return
        }

        FirebaseApp.configure(options: options)
    }
}

private enum GiphyBootstrap {
    static func configureIfNeeded(bundle: Bundle = .main) {
        guard
            let apiKey = (bundle.object(forInfoDictionaryKey: "GIPHYAPIKey") as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
            !apiKey.isEmpty
        else {
            return
        }

        Giphy.configure(apiKey: apiKey)
    }
}

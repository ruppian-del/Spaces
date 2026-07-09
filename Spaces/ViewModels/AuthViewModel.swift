import Combine
import AuthenticationServices
import Foundation

struct AuthAlert: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let message: String
}

@MainActor
final class AuthViewModel: ObservableObject {
    @Published var activeAlert: AuthAlert?
    @Published private(set) var isAuthenticated = false
    @Published private(set) var isSigningInWithApple = false
    @Published private(set) var isSigningInWithGoogle = false
    @Published private(set) var isLinkingApple = false
    @Published private(set) var isLinkingGoogle = false
    @Published private(set) var isResolvingUserState = false
    @Published private(set) var requiresProfileCreation = false
    @Published private(set) var currentSession: AuthSession?
    @Published private(set) var currentUserProfile: UserProfile?
    @Published var isPhoneSheetPresented = false
    @Published var phoneSheetFeedback: AuthAlert?
    @Published var phoneNumber = ""
    @Published var verificationCode = ""
    @Published private(set) var pendingPhoneVerificationID: String?
    @Published private(set) var isPhoneAuthLoading = false
    @Published private(set) var isSavingProfile = false

    private let authService: AuthService
    private let userProfileService: UserProfileService

    init() {
        self.authService = AuthService()
        self.userProfileService = UserProfileService()
        restoreExistingSessionIfNeeded()
    }

    init(authService: AuthService, userProfileService: UserProfileService) {
        self.authService = authService
        self.userProfileService = userProfileService
        restoreExistingSessionIfNeeded()
    }

    func prepareAppleSignInRequest(_ request: ASAuthorizationAppleIDRequest) {
        do {
            try authService.configureAppleSignInRequest(request)
        } catch {
            activeAlert = AuthAlert(title: "Authentication", message: error.localizedDescription)
        }
    }

    func handleAppleSignInCompletion(_ result: Result<ASAuthorization, Error>) {
        guard !isSigningInWithApple else { return }

        isSigningInWithApple = true

        Task {
            defer {
                isSigningInWithApple = false
            }

            do {
                let session = try await authService.handleAppleSignInResult(result)
                await resolveAuthenticatedSession(session)
            } catch let error as ASAuthorizationError where error.code == .canceled {
                return
            } catch {
                activeAlert = AuthAlert(title: "Authentication", message: error.localizedDescription)
            }
        }
    }

    func signInWithGoogle() {
        guard !isSigningInWithGoogle else { return }

        isSigningInWithGoogle = true
        Task {
            defer {
                isSigningInWithGoogle = false
            }

            do {
                let session = try await authService.signInWithGoogle()
                await resolveAuthenticatedSession(session)
            } catch {
                activeAlert = AuthAlert(title: "Authentication", message: error.localizedDescription)
            }
        }
    }

    func prepareAppleLinkRequest(_ request: ASAuthorizationAppleIDRequest) {
        do {
            try authService.configureAppleLinkRequest(request)
        } catch {
            activeAlert = AuthAlert(title: "Linked Accounts", message: error.localizedDescription)
        }
    }

    func handleAppleLinkCompletion(_ result: Result<ASAuthorization, Error>) {
        guard !isLinkingApple else { return }

        isLinkingApple = true
        Task {
            defer {
                isLinkingApple = false
            }

            do {
                let session = try await authService.handleAppleLinkResult(result)
                await resolveAuthenticatedSession(session)
                activeAlert = AuthAlert(title: "Linked Accounts", message: "Apple is now linked to this account.")
            } catch let error as ASAuthorizationError where error.code == .canceled {
                return
            } catch {
                activeAlert = AuthAlert(title: "Linked Accounts", message: error.localizedDescription)
            }
        }
    }

    func linkGoogle() {
        guard !isLinkingGoogle else { return }

        isLinkingGoogle = true
        Task {
            defer {
                isLinkingGoogle = false
            }

            do {
                let session = try await authService.linkGoogle()
                await resolveAuthenticatedSession(session)
                activeAlert = AuthAlert(title: "Linked Accounts", message: "Google is now linked to this account.")
            } catch {
                activeAlert = AuthAlert(title: "Linked Accounts", message: error.localizedDescription)
            }
        }
    }

    func signInWithPhone() {
        isPhoneSheetPresented = true
    }

    func dismissPhoneSheet() {
        isPhoneSheetPresented = false
        phoneNumber = ""
        verificationCode = ""
        pendingPhoneVerificationID = nil
        phoneSheetFeedback = nil
        isPhoneAuthLoading = false
    }

    private func normalizedUSPhoneNumber(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let digits = trimmed.filter(\.isNumber)

        if trimmed.hasPrefix("+") {
            return trimmed
        }
        if digits.count == 10 {
            return "+1\(digits)"
        }
        if digits.count == 11 && digits.hasPrefix("1") {
            return "+\(digits)"
        }
        return trimmed
    }

    func startPhoneSignIn() {
        print("[PhoneLink] startPhoneSignIn() called")
        guard !isPhoneAuthLoading else { return }

        let normalizedNumber = normalizedUSPhoneNumber(phoneNumber)
        phoneNumber = normalizedNumber
        print("[PhoneLink] Normalized number: \(normalizedNumber)")

        isPhoneAuthLoading = true

        Task {
            do {
                let verificationID = try await authService.startPhoneSignIn(phoneNumber: normalizedNumber)
                print("[PhoneLink] Verification ID received")
                pendingPhoneVerificationID = verificationID
                isPhoneAuthLoading = false
                phoneSheetFeedback = AuthAlert(title: "Verification Code Sent", message: "Enter the 6-digit code sent to your phone.")
            } catch {
                print("[PhoneLink] Failed: \(error)")
                isPhoneAuthLoading = false
                phoneSheetFeedback = AuthAlert(title: "Phone Sign-In", message: error.localizedDescription)
            }
        }
    }

    func submitPhoneVerificationCode() {
        guard !isPhoneAuthLoading else { return }
        guard let verificationID = pendingPhoneVerificationID else {
            phoneSheetFeedback = AuthAlert(title: "Phone Sign-In", message: "Request a verification code first.")
            return
        }

        isPhoneAuthLoading = true

        Task {
            do {
                let session = try await authService.verifyPhoneCode(
                    verificationID: verificationID,
                    code: verificationCode
                )
                isPhoneAuthLoading = false
                dismissPhoneSheet()
                await resolveAuthenticatedSession(session)
            } catch {
                isPhoneAuthLoading = false
                phoneSheetFeedback = AuthAlert(title: "Phone Sign-In", message: error.localizedDescription)
            }
        }
    }

    func createProfile(
        displayName: String,
        emojiAvatar: String,
        statusMessage: String
    ) {
        guard !isSavingProfile else { return }
        guard let session = currentSession else {
            activeAlert = AuthAlert(title: "Profile", message: "Sign in before creating a profile.")
            return
        }

        isSavingProfile = true

        Task {
            do {
                let profile = try await userProfileService.createUserProfile(
                    session: session,
                    displayName: displayName,
                    emojiAvatar: emojiAvatar,
                    statusMessage: statusMessage
                )
                currentUserProfile = profile
                requiresProfileCreation = false
                isAuthenticated = true
                isSavingProfile = false
            } catch {
                isSavingProfile = false
                activeAlert = AuthAlert(title: "Profile", message: error.localizedDescription)
            }
        }
    }

    func handleIncomingURL(_ url: URL) -> Bool {
        authService.handleOpenURL(url)
    }

    func signOut() {
        Task {
            await PushTokenService.shared.disableCurrentTokenForSignedOutUser()

            do {
                let message = try authService.signOut()
                isAuthenticated = false
                requiresProfileCreation = false
                currentSession = nil
                currentUserProfile = nil
                activeAlert = AuthAlert(title: "Signed Out", message: message)
            } catch {
                activeAlert = AuthAlert(title: "Authentication", message: error.localizedDescription)
            }
        }
    }

    func applyUpdatedProfile(_ profile: UserProfile) {
        currentUserProfile = profile
    }

    private func restoreExistingSessionIfNeeded() {
        guard let session = authService.currentSession() else { return }

        Task {
            await resolveAuthenticatedSession(session)
        }
    }

    private func resolveAuthenticatedSession(_ session: AuthSession) async {
        currentSession = session
        isResolvingUserState = true

        do {
            let syncedProfile = try await userProfileService.syncAuthSessionIfProfileExists(session)
            if let profile = try await userProfileService.fetchUserProfile(uid: session.uid) {
                currentUserProfile = syncedProfile ?? profile
                requiresProfileCreation = false
                isAuthenticated = true
            } else {
                currentUserProfile = nil
                requiresProfileCreation = true
                isAuthenticated = false
            }
        } catch {
            currentUserProfile = nil
            requiresProfileCreation = false
            isAuthenticated = false
            activeAlert = AuthAlert(title: "Authentication", message: error.localizedDescription)
        }

        isResolvingUserState = false
    }
}

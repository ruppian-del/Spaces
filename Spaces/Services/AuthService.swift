import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import Foundation
import GoogleSignIn
import UIKit

struct AuthSession {
    let uid: String
    let displayName: String
    let email: String?
    let phoneNumber: String?
    let providers: [LinkedProvider]
}

@MainActor
final class AuthService {
    private var currentAppleNonce: String?
    private let phoneAuthUIDelegate = SpacesPhoneAuthUIDelegate()

    func configureAppleSignInRequest(_ request: ASAuthorizationAppleIDRequest) throws {
        guard FirebaseApp.app() != nil else {
            throw AuthServiceError.firebaseNotConfigured
        }

        let nonce = Self.randomNonce()
        currentAppleNonce = nonce
        request.requestedScopes = [.fullName, .email]
        request.nonce = Self.sha256(nonce)
    }

    func configureAppleLinkRequest(_ request: ASAuthorizationAppleIDRequest) throws {
        try configureAppleSignInRequest(request)
    }

    func handleAppleSignInResult(_ result: Result<ASAuthorization, Error>) async throws -> AuthSession {
        defer {
            currentAppleNonce = nil
        }

        switch result {
        case .failure(let error):
            throw error
        case .success(let authorization):
            return try await signInToFirebase(with: authorization)
        }
    }

    func handleAppleLinkResult(_ result: Result<ASAuthorization, Error>) async throws -> AuthSession {
        defer {
            currentAppleNonce = nil
        }

        switch result {
        case .failure(let error):
            throw error
        case .success(let authorization):
            return try await linkAppleCredentialToFirebase(with: authorization)
        }
    }

    func signInWithGoogle() async throws -> AuthSession {
        try await authenticateWithGoogle(linking: false)
    }

    func linkGoogle() async throws -> AuthSession {
        try await authenticateWithGoogle(linking: true)
    }

    func currentSession() -> AuthSession? {
        guard let user = Auth.auth().currentUser else {
            return nil
        }

        return session(from: user)
    }

    func startPhoneSignIn(phoneNumber: String) async throws -> String {
        guard FirebaseApp.app() != nil else {
            throw AuthServiceError.firebaseNotConfigured
        }

        let normalizedPhoneNumber = Self.normalizePhoneNumber(phoneNumber)
        let digitCount = normalizedPhoneNumber.filter(\.isNumber).count
        guard normalizedPhoneNumber.hasPrefix("+"), digitCount >= 10 else {
            throw AuthServiceError.invalidPhoneNumber
        }

        return try await withCheckedThrowingContinuation { continuation in
            PhoneAuthProvider.provider().verifyPhoneNumber(normalizedPhoneNumber, uiDelegate: nil) { verificationID, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let verificationID {
                    continuation.resume(returning: verificationID)
                } else {
                    continuation.resume(throwing: AuthServiceError.phoneVerificationFailed)
                }
            }
        }
    }

    func verifyPhoneCode(verificationID: String, code: String) async throws -> AuthSession {
        guard FirebaseApp.app() != nil else {
            throw AuthServiceError.firebaseNotConfigured
        }

        let trimmedCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedCode.count >= 6 else {
            throw AuthServiceError.invalidVerificationCode
        }

        let credential = PhoneAuthProvider.provider().credential(
            withVerificationID: verificationID,
            verificationCode: trimmedCode
        )

        if let currentUser = Auth.auth().currentUser {
            let authResult: AuthDataResult = try await withCheckedThrowingContinuation {
                (continuation: CheckedContinuation<AuthDataResult, Error>) in
                currentUser.link(with: credential) { result, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let result {
                        continuation.resume(returning: result)
                    } else {
                        continuation.resume(throwing: AuthServiceError.phoneVerificationFailed)
                    }
                }
            }

            return session(from: authResult.user)
        }

        let authResult: AuthDataResult = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<AuthDataResult, Error>) in
            Auth.auth().signIn(with: credential) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: AuthServiceError.phoneVerificationFailed)
                }
            }
        }

        return session(from: authResult.user)
    }

    private static func normalizePhoneNumber(_ phoneNumber: String) -> String {
        let trimmed = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        var normalized = ""
        for character in trimmed {
            if character.isWholeNumber {
                normalized.append(character)
            } else if character == "+", normalized.isEmpty {
                normalized.append(character)
            }
        }
        return normalized
    }

    func handleOpenURL(_ url: URL) -> Bool {
        Auth.auth().canHandle(url)
    }

    func signOut() throws -> String {
        guard Auth.auth().currentUser != nil else {
            return "You are already signed out."
        }

        try Auth.auth().signOut()
        return "Signed out."
    }

    private func authenticateWithGoogle(linking: Bool) async throws -> AuthSession {
        guard FirebaseApp.app() != nil else {
            throw AuthServiceError.firebaseNotConfigured
        }

        guard let presentingViewController = Self.topViewController() else {
            throw AuthServiceError.unableToPresentAuthentication
        }

        guard let clientID = FirebaseApp.app()?.options.clientID?.trimmingCharacters(in: .whitespacesAndNewlines),
              !clientID.isEmpty else {
            throw AuthServiceError.missingGoogleConfiguration
        }

        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        let signInResult = try await GIDSignIn.sharedInstance.signIn(withPresenting: presentingViewController)
        let user = signInResult.user
        guard let idToken = user.idToken?.tokenString else {
            throw AuthServiceError.missingGoogleIdentityToken
        }

        let credential = GoogleAuthProvider.credential(
            withIDToken: idToken,
            accessToken: user.accessToken.tokenString
        )

        if linking {
            guard let currentUser = Auth.auth().currentUser else {
                throw AuthServiceError.userNotSignedIn
            }

            let authResult: AuthDataResult = try await withCheckedThrowingContinuation {
                (continuation: CheckedContinuation<AuthDataResult, Error>) in
                currentUser.link(with: credential) { result, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let result {
                        continuation.resume(returning: result)
                    } else {
                        continuation.resume(throwing: AuthServiceError.googleSignInUnavailable)
                    }
                }
            }
            return session(from: authResult.user)
        }

        let authResult: AuthDataResult = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<AuthDataResult, Error>) in
            Auth.auth().signIn(with: credential) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: AuthServiceError.googleSignInUnavailable)
                }
            }
        }
        return session(from: authResult.user)
    }

    private func signInToFirebase(with authorization: ASAuthorization) async throws -> AuthSession {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
            throw AuthServiceError.invalidAppleCredential
        }

        guard let nonce = currentAppleNonce else {
            throw AuthServiceError.invalidAppleCredential
        }

        guard let identityToken = credential.identityToken else {
            throw AuthServiceError.missingIdentityToken
        }

        guard let idTokenString = String(data: identityToken, encoding: .utf8) else {
            throw AuthServiceError.unableToEncodeIdentityToken
        }

        let firebaseCredential = OAuthProvider.appleCredential(
            withIDToken: idTokenString,
            rawNonce: nonce,
            fullName: credential.fullName
        )

        let authResult: AuthDataResult = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<AuthDataResult, Error>) in
            Auth.auth().signIn(with: firebaseCredential) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: AuthServiceError.invalidAppleCredential)
                }
            }
        }

        let displayNameParts = [credential.fullName?.givenName, credential.fullName?.familyName]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        let formattedName = PersonNameComponentsFormatter().string(from: credential.fullName ?? PersonNameComponents())
        let resolvedDisplayName = !formattedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? formattedName
            : (displayNameParts.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                ?? authResult.user.displayName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                ?? authResult.user.email
                ?? "Signed In")

        return session(from: authResult.user, fallbackDisplayName: resolvedDisplayName)
    }

    private func linkAppleCredentialToFirebase(with authorization: ASAuthorization) async throws -> AuthSession {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
            throw AuthServiceError.invalidAppleCredential
        }
        guard let currentUser = Auth.auth().currentUser else {
            throw AuthServiceError.userNotSignedIn
        }
        guard let nonce = currentAppleNonce else {
            throw AuthServiceError.invalidAppleCredential
        }
        guard let identityToken = credential.identityToken else {
            throw AuthServiceError.missingIdentityToken
        }
        guard let idTokenString = String(data: identityToken, encoding: .utf8) else {
            throw AuthServiceError.unableToEncodeIdentityToken
        }

        let firebaseCredential = OAuthProvider.appleCredential(
            withIDToken: idTokenString,
            rawNonce: nonce,
            fullName: credential.fullName
        )

        let authResult: AuthDataResult = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<AuthDataResult, Error>) in
            currentUser.link(with: firebaseCredential) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: AuthServiceError.invalidAppleCredential)
                }
            }
        }

        let formattedName = PersonNameComponentsFormatter().string(from: credential.fullName ?? PersonNameComponents())
        let resolvedDisplayName = formattedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : formattedName
        return session(from: authResult.user, fallbackDisplayName: resolvedDisplayName)
    }

    private static func randomNonce(length: Int = 32) -> String {
        precondition(length > 0)
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remainingLength = length

        while remainingLength > 0 {
            let randoms: [UInt8] = (0 ..< 16).map { _ in
                var random: UInt8 = 0
                let errorCode = SecRandomCopyBytes(kSecRandomDefault, 1, &random)
                if errorCode != errSecSuccess {
                    fatalError("Unable to generate nonce. SecRandomCopyBytes failed with OSStatus \(errorCode)")
                }
                return random
            }

            randoms.forEach { random in
                if remainingLength == 0 {
                    return
                }

                if random < charset.count {
                    result.append(charset[Int(random)])
                    remainingLength -= 1
                }
            }
        }

        return result
    }

    private static func sha256(_ input: String) -> String {
        let inputData = Data(input.utf8)
        let hashedData = SHA256.hash(data: inputData)
        return hashedData.map { String(format: "%02x", $0) }.joined()
    }

    private static func topViewController(
        base: UIViewController? = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
    ) -> UIViewController? {
        if let navigationController = base as? UINavigationController {
            return topViewController(base: navigationController.visibleViewController)
        }
        if let tabBarController = base as? UITabBarController {
            return topViewController(base: tabBarController.selectedViewController)
        }
        if let presentedViewController = base?.presentedViewController {
            return topViewController(base: presentedViewController)
        }
        return base
    }

    private func session(from user: User, fallbackDisplayName: String? = nil) -> AuthSession {
        let displayName = fallbackDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? user.displayName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? user.phoneNumber
            ?? user.email
            ?? "Signed In"
        let providers = user.providerData
            .compactMap { LinkedProvider(firebaseProviderID: $0.providerID) }
            .uniqued()

        return AuthSession(
            uid: user.uid,
            displayName: displayName,
            email: user.email,
            phoneNumber: user.phoneNumber,
            providers: providers
        )
    }
}

private final class SpacesPhoneAuthUIDelegate: NSObject, AuthUIDelegate {
    func present(
        _ viewControllerToPresent: UIViewController,
        animated flag: Bool,
        completion: (() -> Void)? = nil
    ) {
        guard let presenter = Self.topViewController() else {
            completion?()
            return
        }

        presenter.present(viewControllerToPresent, animated: flag, completion: completion)
    }

    func dismiss(animated flag: Bool, completion: (() -> Void)? = nil) {
        Self.topViewController()?.dismiss(animated: flag, completion: completion)
    }

    private static func topViewController(
        base: UIViewController? = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
    ) -> UIViewController? {
        if let navigationController = base as? UINavigationController {
            return topViewController(base: navigationController.visibleViewController)
        }

        if let tabBarController = base as? UITabBarController {
            return topViewController(base: tabBarController.selectedViewController)
        }

        if let presentedViewController = base?.presentedViewController {
            return topViewController(base: presentedViewController)
        }

        return base
    }
}

enum AuthServiceError: LocalizedError {
    case firebaseNotConfigured
    case invalidAppleCredential
    case missingIdentityToken
    case unableToEncodeIdentityToken
    case invalidPhoneNumber
    case invalidVerificationCode
    case phoneVerificationFailed
    case googleSignInUnavailable
    case missingGoogleConfiguration
    case missingGoogleIdentityToken
    case userNotSignedIn
    case unableToPresentAuthentication

    var errorDescription: String? {
        switch self {
        case .firebaseNotConfigured:
            return "Firebase is not configured. Add a valid GoogleService-Info.plist to continue."
        case .invalidAppleCredential:
            return "Apple sign-in returned an invalid credential."
        case .missingIdentityToken:
            return "Apple sign-in did not return an identity token."
        case .unableToEncodeIdentityToken:
            return "Unable to decode the Apple identity token."
        case .invalidPhoneNumber:
            return "Enter a valid phone number including the country code, like +15551234567."
        case .invalidVerificationCode:
            return "Enter the 6-digit verification code."
        case .phoneVerificationFailed:
            return "Phone verification could not be completed."
        case .googleSignInUnavailable:
            return "Google sign-in is not available right now."
        case .missingGoogleConfiguration:
            return "Google sign-in is not configured correctly. Add the iOS CLIENT_ID and REVERSED_CLIENT_ID to GoogleService-Info.plist."
        case .missingGoogleIdentityToken:
            return "Google sign-in did not return an identity token."
        case .userNotSignedIn:
            return "Sign in before linking another provider."
        case .unableToPresentAuthentication:
            return "Unable to present the authentication flow."
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

private extension Sequence where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}

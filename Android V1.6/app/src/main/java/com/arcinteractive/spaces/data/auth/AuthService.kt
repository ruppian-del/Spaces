package com.arcinteractive.spaces.data.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.FirebaseException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit
import com.google.firebase.auth.OAuthProvider

class AuthService {
    suspend fun signInWithGoogle(context: Context): AuthSession {
        val firebaseAuth = firebaseAuthOrNull(context)
            ?: throw IllegalStateException("Firebase is not configured yet. Add google-services.json to enable Google sign-in.")
        val serverClientId = resolveServerClientId(context)
            ?: throw IllegalStateException("Add an updated google-services.json with Google sign-in enabled to continue.")

        val credentialManager = CredentialManager.create(context)
        val credential = getGoogleCredential(
            credentialManager = credentialManager,
            context = context,
            serverClientId = serverClientId
        )

        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("Google sign-in did not return a valid credential.")
        }

        val googleCredential = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: GoogleIdTokenParsingException) {
            throw IllegalStateException("Unable to read the Google sign-in response.")
        }

        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        val authResult = suspendCancellableCoroutine { continuation ->
            firebaseAuth.signInWithCredential(firebaseCredential)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return authResult.user?.toAuthSession()
            ?: throw IllegalStateException("Google sign-in did not return a Firebase user.")
    }

    suspend fun linkGoogle(context: Context): AuthSession {
        val firebaseAuth = firebaseAuthOrNull(context)
            ?: throw IllegalStateException("Firebase is not configured yet.")
        val currentUser = firebaseAuth.currentUser
            ?: throw IllegalStateException("Sign in before linking another provider.")
        val serverClientId = resolveServerClientId(context)
            ?: throw IllegalStateException("Add an updated google-services.json with Google sign-in enabled to continue.")

        val credentialManager = CredentialManager.create(context)
        val credential = getGoogleCredential(
            credentialManager = credentialManager,
            context = context,
            serverClientId = serverClientId
        )

        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("Google sign-in did not return a valid credential.")
        }

        val googleCredential = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: GoogleIdTokenParsingException) {
            throw IllegalStateException("Unable to read the Google sign-in response.")
        }

        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        val authResult = suspendCancellableCoroutine { continuation ->
            currentUser.linkWithCredential(firebaseCredential)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return authResult.user?.toAuthSession()
            ?: throw IllegalStateException("Google linking did not return a Firebase user.")
    }

    suspend fun signInWithApple(activity: Activity): AuthSession {
        val firebaseAuth = firebaseAuthOrNull(activity)
            ?: throw IllegalStateException("Firebase is not configured yet.")
        val provider = buildAppleProvider()
        val authResult = suspendCancellableCoroutine<com.google.firebase.auth.AuthResult> { continuation ->
            firebaseAuth.startActivityForSignInWithProvider(activity, provider)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return authResult.user?.toAuthSession()
            ?: throw IllegalStateException("Apple sign-in did not return a Firebase user.")
    }

    suspend fun linkApple(activity: Activity): AuthSession {
        val firebaseAuth = firebaseAuthOrNull(activity)
            ?: throw IllegalStateException("Firebase is not configured yet.")
        val currentUser = firebaseAuth.currentUser
            ?: throw IllegalStateException("Sign in before linking another provider.")
        val provider = buildAppleProvider()
        val authResult = suspendCancellableCoroutine<com.google.firebase.auth.AuthResult> { continuation ->
            currentUser.startActivityForLinkWithProvider(activity, provider)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return authResult.user?.toAuthSession()
            ?: throw IllegalStateException("Apple linking did not return a Firebase user.")
    }

    suspend fun startPhoneSignIn(
        activity: Activity,
        phoneNumber: String
    ): PhoneAuthStartResult {
        val firebaseAuth = firebaseAuthOrNull(activity)
            ?: return PhoneAuthStartResult.Error("Firebase is not configured yet.")

        val normalizedPhoneNumber = phoneNumber.trim()
        if (!normalizedPhoneNumber.startsWith("+") || normalizedPhoneNumber.length < 10) {
            return PhoneAuthStartResult.Error("Enter a valid phone number including the country code, like +1 555 123 4567.")
        }

        return suspendCancellableCoroutine { continuation ->
            var resumed = false

            fun resumeOnce(result: PhoneAuthStartResult) {
                if (resumed) return
                resumed = true
                continuation.resume(result)
            }

            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    firebaseAuth.signInWithCredential(credential)
                        .addOnSuccessListener { authResult ->
                            val session = authResult.user?.toAuthSession()
                            if (session != null) {
                                resumeOnce(PhoneAuthStartResult.SignedIn(session))
                            } else {
                                resumeOnce(PhoneAuthStartResult.Error("Phone sign-in did not return a Firebase user."))
                            }
                        }
                        .addOnFailureListener { error ->
                            resumeOnce(PhoneAuthStartResult.Error(error.localizedMessage ?: "Phone sign-in failed."))
                        }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    resumeOnce(PhoneAuthStartResult.Error(e.localizedMessage ?: "Phone verification failed."))
                }

                override fun onCodeSent(
                    verificationId: String,
                    forceResendingToken: PhoneAuthProvider.ForceResendingToken
                ) {
                    resumeOnce(PhoneAuthStartResult.CodeSent(verificationId))
                }
            }

            val options = PhoneAuthOptions.newBuilder(firebaseAuth)
                .setPhoneNumber(normalizedPhoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()

            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    suspend fun verifyPhoneCode(
        context: Context,
        verificationId: String,
        code: String
    ): AuthSession {
        val firebaseAuth = firebaseAuthOrNull(context)
            ?: throw IllegalStateException("Firebase is not configured yet.")

        val trimmedCode = code.trim()
        if (trimmedCode.length < 6) {
            throw IllegalArgumentException("Enter the 6-digit verification code.")
        }

        val credential = PhoneAuthProvider.getCredential(verificationId, trimmedCode)
        try {
            val authResult = suspendCancellableCoroutine { continuation ->
                firebaseAuth.signInWithCredential(credential)
                    .addOnSuccessListener { result -> continuation.resume(result) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
            return authResult.user?.toAuthSession()
                ?: throw IllegalStateException("Phone sign-in did not return a Firebase user.")
        } catch (error: FirebaseAuthInvalidCredentialsException) {
            throw IllegalArgumentException("The verification code is invalid.")
        }
    }

    fun currentSession(context: Context): AuthSession? {
        val firebaseAuth = firebaseAuthOrNull(context) ?: return null
        return firebaseAuth.currentUser?.toAuthSession()
    }

    suspend fun refreshCurrentSession(context: Context): AuthSession {
        val firebaseAuth = firebaseAuthOrNull(context)
            ?: throw IllegalStateException("Firebase is not configured yet.")
        val user = firebaseAuth.currentUser
            ?: throw IllegalStateException("Sign in before continuing.")

        suspendCancellableCoroutine<GetTokenResult> { continuation ->
            user.getIdToken(true)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return user.toAuthSession()
    }

    suspend fun signOut(context: Context): String {
        val firebaseAuth = firebaseAuthOrNull(context)
            ?: return "Firebase is not configured yet."

        if (firebaseAuth.currentUser == null) {
            return "You are already signed out."
        }

        firebaseAuth.signOut()
        kotlin.runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
        return "Signed out."
    }

    fun addAuthStateListener(
        context: Context,
        onChanged: (AuthSession?) -> Unit
    ): FirebaseAuth.AuthStateListener? {
        val firebaseAuth = firebaseAuthOrNull(context) ?: return null
        val listener = FirebaseAuth.AuthStateListener { auth ->
            onChanged(auth.currentUser?.toAuthSession())
        }
        firebaseAuth.addAuthStateListener(listener)
        return listener
    }

    fun removeAuthStateListener(context: Context, listener: FirebaseAuth.AuthStateListener?) {
        val firebaseAuth = firebaseAuthOrNull(context) ?: return
        listener ?: return
        firebaseAuth.removeAuthStateListener(listener)
    }

    private fun resolveServerClientId(context: Context): String? {
        val resourceId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resourceId == 0) return null
        return context.getString(resourceId).takeIf { it.isNotBlank() }
    }

    private suspend fun getGoogleCredential(
        credentialManager: CredentialManager,
        context: Context,
        serverClientId: String
    ): androidx.credentials.Credential {
        return credentialManager.getCredential(
            context = context,
            request = buildGoogleCredentialRequest(serverClientId)
        ).credential
    }

    private fun buildGoogleCredentialRequest(
        serverClientId: String
    ): GetCredentialRequest {
        val googleIdOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .build()

        return GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
    }

    private fun buildAppleProvider(): OAuthProvider {
        val providerBuilder = OAuthProvider.newBuilder("apple.com")
        providerBuilder.scopes = listOf("email", "name")
        return providerBuilder.build()
    }

    private fun firebaseAuthOrNull(context: Context): FirebaseAuth? {
        if (FirebaseApp.getApps(context).isEmpty()) {
            return null
        }

        return runCatching { FirebaseAuth.getInstance() }.getOrNull()
    }

    private fun com.google.firebase.auth.FirebaseUser.toAuthSession(): AuthSession {
        val providers = providerData
            .mapNotNull { provider -> provider.providerId?.let(com.arcinteractive.spaces.data.model.LinkedProvider::fromFirebaseProviderId) }
            .distinct()

        return AuthSession(
            uid = uid,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: phoneNumber ?: email ?: "Signed In",
            email = email,
            phoneNumber = phoneNumber,
            providers = providers
        )
    }
}

sealed interface PhoneAuthStartResult {
    data class CodeSent(val verificationId: String) : PhoneAuthStartResult
    data class SignedIn(val session: AuthSession) : PhoneAuthStartResult
    data class Error(val message: String) : PhoneAuthStartResult
}

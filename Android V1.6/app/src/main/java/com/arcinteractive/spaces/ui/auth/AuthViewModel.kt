package com.arcinteractive.spaces.ui.auth

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcinteractive.spaces.data.auth.AuthSession
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.auth.PhoneAuthStartResult
import com.arcinteractive.spaces.data.auth.PushTokenService
import com.arcinteractive.spaces.data.firestore.FirestoreListenerRegistry
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val lastActionMessage: String? = null,
    val isAuthenticated: Boolean = false,
    val isResolvingUserState: Boolean = false,
    val requiresProfileCreation: Boolean = false,
    val currentSession: AuthSession? = null,
    val currentUserProfile: UserProfile? = null,
    val isSigningInWithApple: Boolean = false,
    val isSigningInWithGoogle: Boolean = false,
    val isLinkingApple: Boolean = false,
    val isLinkingGoogle: Boolean = false,
    val isPhoneDialogOpen: Boolean = false,
    val phoneNumberInput: String = "",
    val verificationCodeInput: String = "",
    val pendingPhoneVerificationId: String? = null,
    val isPhoneAuthLoading: Boolean = false,
    val isSavingProfile: Boolean = false
)

class AuthViewModel(
    private val authService: AuthService = AuthService(),
    private val userProfileService: UserProfileService = UserProfileService(),
    private val pushTokenService: PushTokenService = PushTokenService()
) : ViewModel() {
    constructor() : this(AuthService(), UserProfileService(), PushTokenService())

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var authListenerContext: Context? = null

    fun restoreExistingSession(context: Context) {
        if (_uiState.value.isResolvingUserState || _uiState.value.currentSession != null || _uiState.value.isAuthenticated) return

        val session = authService.currentSession(context) ?: return
        viewModelScope.launch {
            resolveAuthenticatedSession(context, session)
        }
    }

    fun startObservingAuthState(context: Context) {
        if (authStateListener != null) return
        authListenerContext = context.applicationContext

        authStateListener = authService.addAuthStateListener(context) { session ->
            if (session == null) {
                FirestoreListenerRegistry.stopAllFirestoreListeners()
                _uiState.update {
                    it.copy(
                        isAuthenticated = false,
                        isResolvingUserState = false,
                        requiresProfileCreation = false,
                        currentSession = null,
                        currentUserProfile = null
                    )
                }
                return@addAuthStateListener
            }

            val currentState = _uiState.value
            if (currentState.isResolvingUserState) return@addAuthStateListener
            if (currentState.currentSession?.uid == session.uid &&
                (currentState.isAuthenticated || currentState.requiresProfileCreation)
            ) {
                return@addAuthStateListener
            }
            if (currentState.currentSession?.uid != null && currentState.currentSession?.uid != session.uid) {
                FirestoreListenerRegistry.stopAllFirestoreListeners()
            }

            viewModelScope.launch {
                resolveAuthenticatedSession(context, session)
            }
        }
    }

    fun signInWithGoogle(context: Context) {
        if (_uiState.value.isSigningInWithGoogle) return

        _uiState.update { it.copy(isSigningInWithGoogle = true) }
        viewModelScope.launch {
            runCatching {
                authService.signInWithGoogle(context)
            }.onSuccess { session ->
                _uiState.update { it.copy(isSigningInWithGoogle = false) }
                resolveAuthenticatedSession(context, session)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastActionMessage = error.localizedMessage ?: "Google sign-in failed.",
                        isSigningInWithGoogle = false
                    )
                }
            }
        }
    }

    fun signInWithApple(activity: Activity?) {
        if (activity == null) {
            _uiState.update { it.copy(lastActionMessage = "Unable to start Apple sign-in from this screen.") }
            return
        }
        if (_uiState.value.isSigningInWithApple) return

        _uiState.update { it.copy(isSigningInWithApple = true) }
        viewModelScope.launch {
            runCatching {
                authService.signInWithApple(activity)
            }.onSuccess { session ->
                _uiState.update { it.copy(isSigningInWithApple = false) }
                resolveAuthenticatedSession(activity, session)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastActionMessage = error.localizedMessage ?: "Apple sign-in failed.",
                        isSigningInWithApple = false
                    )
                }
            }
        }
    }

    fun linkGoogle(context: Context) {
        if (_uiState.value.isLinkingGoogle) return

        _uiState.update { it.copy(isLinkingGoogle = true) }
        viewModelScope.launch {
            runCatching {
                authService.linkGoogle(context)
            }.onSuccess { session ->
                _uiState.update { it.copy(isLinkingGoogle = false) }
                resolveAuthenticatedSession(context, session)
                _uiState.update { it.copy(lastActionMessage = "Google is now linked to this account.") }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastActionMessage = error.localizedMessage ?: "Google linking failed.",
                        isLinkingGoogle = false
                    )
                }
            }
        }
    }

    fun linkApple(activity: Activity?) {
        if (activity == null) {
            _uiState.update { it.copy(lastActionMessage = "Unable to start Apple linking from this screen.") }
            return
        }
        if (_uiState.value.isLinkingApple) return

        _uiState.update { it.copy(isLinkingApple = true) }
        viewModelScope.launch {
            runCatching {
                authService.linkApple(activity)
            }.onSuccess { session ->
                _uiState.update { it.copy(isLinkingApple = false) }
                resolveAuthenticatedSession(activity, session)
                _uiState.update { it.copy(lastActionMessage = "Apple is now linked to this account.") }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastActionMessage = appleFailureMessage(error, "linking"),
                        isLinkingApple = false
                    )
                }
            }
        }
    }

    private fun appleFailureMessage(error: Throwable, action: String): String {
        val firebaseError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseAuthException>()
            .firstOrNull()

        return if (firebaseError != null) {
            "Apple $action failed (${firebaseError.errorCode}): " +
                (firebaseError.localizedMessage ?: "No details were returned.")
        } else {
            "Apple $action failed: " + (error.localizedMessage ?: "No details were returned.")
        }
    }

    fun signInWithPhone() {
        _uiState.update { it.copy(isPhoneDialogOpen = true) }
    }

    fun dismissPhoneDialog() {
        _uiState.update {
            it.copy(
                isPhoneDialogOpen = false,
                phoneNumberInput = "",
                verificationCodeInput = "",
                pendingPhoneVerificationId = null,
                isPhoneAuthLoading = false
            )
        }
    }

    fun updatePhoneNumberInput(value: String) {
        _uiState.update { it.copy(phoneNumberInput = value) }
    }

    fun updateVerificationCodeInput(value: String) {
        _uiState.update { it.copy(verificationCodeInput = value) }
    }

    fun startPhoneSignIn(activity: Activity?) {
        if (activity == null) {
            _uiState.update { it.copy(lastActionMessage = "Unable to start phone sign-in from this screen.") }
            return
        }

        val currentState = _uiState.value
        if (currentState.isPhoneAuthLoading) return

        _uiState.update { it.copy(isPhoneAuthLoading = true) }
        viewModelScope.launch {
            when (val result = authService.startPhoneSignIn(activity, _uiState.value.phoneNumberInput)) {
                is PhoneAuthStartResult.CodeSent -> {
                    _uiState.update {
                        it.copy(
                            lastActionMessage = "Verification code sent.",
                            pendingPhoneVerificationId = result.verificationId,
                            isPhoneAuthLoading = false
                        )
                    }
                }
                is PhoneAuthStartResult.SignedIn -> {
                    dismissPhoneDialog()
                    _uiState.update { it.copy(isPhoneAuthLoading = false) }
                    resolveAuthenticatedSession(activity, result.session)
                }
                is PhoneAuthStartResult.Error -> {
                    _uiState.update {
                        it.copy(
                            lastActionMessage = result.message,
                            isPhoneAuthLoading = false
                        )
                    }
                }
            }
        }
    }

    fun submitPhoneVerificationCode(context: Context) {
        val currentState = _uiState.value
        val verificationId = currentState.pendingPhoneVerificationId
            ?: run {
                _uiState.update { it.copy(lastActionMessage = "Request a verification code first.") }
                return
            }
        if (currentState.isPhoneAuthLoading) return

        _uiState.update { it.copy(isPhoneAuthLoading = true) }
        viewModelScope.launch {
            runCatching {
                authService.verifyPhoneCode(
                    context = context,
                    verificationId = verificationId,
                    code = _uiState.value.verificationCodeInput
                )
            }.onSuccess { session ->
                dismissPhoneDialog()
                _uiState.update { it.copy(isPhoneAuthLoading = false) }
                resolveAuthenticatedSession(context, session)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastActionMessage = error.localizedMessage ?: "Phone sign-in failed.",
                        isPhoneAuthLoading = false
                    )
                }
            }
        }
    }

    fun createProfile(
        context: Context,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String
    ) {
        val session = _uiState.value.currentSession
        if (session == null) {
            _uiState.update { it.copy(lastActionMessage = "Sign in before creating a profile.") }
            return
        }
        if (_uiState.value.isSavingProfile) return

        _uiState.update { it.copy(isSavingProfile = true) }
        viewModelScope.launch {
            runCatching {
                userProfileService.createUserProfile(
                    context = context,
                    session = session,
                    displayName = displayName,
                    emojiAvatar = emojiAvatar,
                    statusMessage = statusMessage
                )
            }.onSuccess { profile ->
                _uiState.update {
                    it.copy(
                        currentUserProfile = profile,
                        requiresProfileCreation = false,
                        isAuthenticated = true,
                        isSavingProfile = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastActionMessage = error.localizedMessage ?: "Unable to create profile.",
                        isSavingProfile = false
                    )
                }
            }
        }
    }

    fun signOut(context: Context) {
        viewModelScope.launch {
            runCatching {
                pushTokenService.disableCurrentTokenForSignedOutUser(context)
            }
            FirestoreListenerRegistry.stopAllFirestoreListeners()
            val message = runCatching {
                authService.signOut(context)
            }.getOrElse { error ->
                error.localizedMessage ?: "Sign out failed."
            }

            _uiState.update {
                it.copy(
                    lastActionMessage = message,
                    isAuthenticated = false,
                    isResolvingUserState = false,
                    requiresProfileCreation = false,
                    currentSession = null,
                    currentUserProfile = null
                )
            }
        }
    }

    fun clearLastActionMessage() {
        _uiState.update { it.copy(lastActionMessage = null) }
    }

    fun applyUpdatedProfile(profile: UserProfile) {
        _uiState.update { it.copy(currentUserProfile = profile) }
    }

    private suspend fun resolveAuthenticatedSession(context: Context, session: AuthSession) {
        _uiState.update {
            it.copy(
                isResolvingUserState = true
            )
        }

        runCatching {
            val refreshedSession = authService.refreshCurrentSession(context)
            refreshedSession to userProfileService.syncAuthSessionIfProfileExists(context, refreshedSession)
        }.onSuccess { profile ->
            val refreshedSession = profile.first
            val currentUserProfile = profile.second
            _uiState.update {
                it.copy(
                    lastActionMessage = "Signed in as ${refreshedSession.displayName}.",
                    currentSession = refreshedSession,
                    currentUserProfile = currentUserProfile,
                    requiresProfileCreation = currentUserProfile == null,
                    isAuthenticated = currentUserProfile != null,
                    isResolvingUserState = false
                )
            }
        }.onFailure { error ->
            FirestoreListenerRegistry.stopAllFirestoreListeners()
            _uiState.update {
                it.copy(
                    lastActionMessage = error.localizedMessage ?: "Unable to load your account.",
                    currentSession = null,
                    currentUserProfile = null,
                    requiresProfileCreation = false,
                    isAuthenticated = false,
                    isResolvingUserState = false
                )
            }
        }
    }

    override fun onCleared() {
        authListenerContext?.let { context ->
            authService.removeAuthStateListener(context, authStateListener)
        }
        authStateListener = null
        authListenerContext = null
        super.onCleared()
    }
}

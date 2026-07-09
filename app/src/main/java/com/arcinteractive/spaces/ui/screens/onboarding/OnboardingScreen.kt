package com.arcinteractive.spaces.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.MainActivity
import com.arcinteractive.spaces.ui.auth.AuthViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val authUiState by authViewModel.uiState.collectAsState()
    val pages = OnboardingPage.entries
    val context = LocalContext.current
    val activity = context as? MainActivity
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(
        initialPage = uiState.currentPage.ordinal,
        pageCount = { pages.size }
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.isShowingSplash) {
        if (uiState.isShowingSplash) {
            delay(2000)
            viewModel.advanceFromSplash()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val page = pages[pagerState.currentPage]
        if (page != uiState.currentPage) {
            viewModel.setCurrentPage(page)
        }
    }

    LaunchedEffect(uiState.currentPage) {
        if (pagerState.currentPage != uiState.currentPage.ordinal) {
            pagerState.animateScrollToPage(uiState.currentPage.ordinal)
        }
    }

    LaunchedEffect(Unit) {
        authViewModel.restoreExistingSession(context)
        if (authUiState.requiresProfileCreation) {
            viewModel.prepareForRequiredProfileCreation(authUiState.currentSession?.displayName)
        }
    }

    LaunchedEffect(authUiState.requiresProfileCreation) {
        if (authUiState.requiresProfileCreation && uiState.currentPage != OnboardingPage.Profile) {
            viewModel.prepareForRequiredProfileCreation(authUiState.currentSession?.displayName)
            pagerState.animateScrollToPage(OnboardingPage.Profile.ordinal)
        }
    }

    LaunchedEffect(authUiState.lastActionMessage) {
        val message = authUiState.lastActionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        authViewModel.clearLastActionMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { _ ->
        if (authUiState.isPhoneDialogOpen) {
            PhoneSignInDialog(
                phoneNumber = authUiState.phoneNumberInput,
                verificationCode = authUiState.verificationCodeInput,
                hasPendingVerification = authUiState.pendingPhoneVerificationId != null,
                isLoading = authUiState.isPhoneAuthLoading,
                onPhoneNumberChange = authViewModel::updatePhoneNumberInput,
                onVerificationCodeChange = authViewModel::updateVerificationCodeInput,
                onDismiss = authViewModel::dismissPhoneDialog,
                onConfirm = {
                    if (authUiState.pendingPhoneVerificationId == null) {
                        authViewModel.startPhoneSignIn(activity)
                    } else {
                        authViewModel.submitPhoneVerificationCode(context)
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedVisibility(
                visible = uiState.isShowingSplash,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OnboardingSplash()
            }

            AnimatedVisibility(
                visible = !uiState.isShowingSplash,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        if (uiState.currentPage != OnboardingPage.Profile && !authUiState.requiresProfileCreation) {
                            TextButton(onClick = onComplete) {
                                Text("Skip")
                            }
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = true,
                        modifier = Modifier.weight(1f)
                    ) { pageIndex ->
                        when (pages[pageIndex]) {
                            OnboardingPage.Welcome -> WelcomePage(
                                onGetStarted = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(OnboardingPage.Spaces.ordinal)
                                    }
                                },
                                onSignIn = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(OnboardingPage.Authentication.ordinal)
                                    }
                                }
                            )

                            OnboardingPage.Spaces -> SpacesPage()
                            OnboardingPage.Pings -> PingsPage()
                            OnboardingPage.Privacy -> PrivacyPage()
                            OnboardingPage.Authentication -> AuthenticationPage(
                                onAppleSignIn = { authViewModel.signInWithApple(activity) },
                                onGoogleSignIn = { authViewModel.signInWithGoogle(context) },
                                onPhoneSignIn = authViewModel::signInWithPhone,
                                isAppleSigningIn = authUiState.isSigningInWithApple,
                                isGoogleSigningIn = authUiState.isSigningInWithGoogle
                            )
                            OnboardingPage.Profile -> ProfilePage(
                                displayName = uiState.displayName,
                                emojiAvatar = uiState.emojiAvatar,
                                displayEmoji = uiState.displayEmoji,
                                statusMessage = uiState.statusMessage,
                                isSaving = authUiState.isSavingProfile,
                                isRequired = authUiState.requiresProfileCreation,
                                onDisplayNameChange = viewModel::updateDisplayName,
                                onEmojiAvatarChange = viewModel::updateEmojiAvatar,
                                onStatusMessageChange = viewModel::updateStatusMessage
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pages.forEachIndexed { index, _ ->
                                val width by animateFloatAsState(
                                    targetValue = if (pagerState.currentPage == index) 22f else 8f,
                                    label = "indicatorWidth"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width.dp, 8.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            if (pagerState.currentPage == index) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                                            }
                                        )
                                )
                            }
                        }

                        if (uiState.currentPage == OnboardingPage.Profile) {
                            Button(
                                onClick = {
                                    if (authUiState.requiresProfileCreation) {
                                        authViewModel.createProfile(
                                            context = context,
                                            displayName = uiState.displayName,
                                            emojiAvatar = uiState.displayEmoji,
                                            statusMessage = uiState.statusMessage
                                        )
                                    } else {
                                        onComplete()
                                    }
                                },
                                enabled = uiState.canContinueProfile && !authUiState.isSavingProfile,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Continue")
                            }
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pages.lastIndex))
                                    }
                                },
                                enabled = !authUiState.requiresProfileCreation,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Next")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneSignInDialog(
    phoneNumber: String,
    verificationCode: String,
    hasPendingVerification: Boolean,
    isLoading: Boolean,
    onPhoneNumberChange: (String) -> Unit,
    onVerificationCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isLoading) onDismiss()
        },
        title = {
            Text(if (hasPendingVerification) "Enter Verification Code" else "Continue with Phone")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasPendingVerification) {
                    Text(
                        text = "Enter your phone number with country code, like +1 555 123 4567.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = onPhoneNumberChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Phone Number") },
                        singleLine = true
                    )
                } else {
                    Text(
                        text = "Enter the 6-digit code we sent to your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = onVerificationCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Verification Code") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading
            ) {
                Text(
                    when {
                        isLoading -> "Working..."
                        hasPendingVerification -> "Verify"
                        else -> "Send Code"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun OnboardingSplash() {
    val alpha by animateFloatAsState(targetValue = 1f, label = "splashAlpha")

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.alpha(alpha)
        ) {
            Text(
                text = "💬",
                fontSize = 54.sp
            )
            Text(
                text = "Spaces",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun WelcomePage(
    onGetStarted: () -> Unit,
    onSignIn: () -> Unit
) {
    OnboardingPageContainer {
        Spacer(modifier = Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "Communication built around Spaces.",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Private conversations, shared memories, events, and more-all organized into Spaces.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Started")
        }
        OutlinedButton(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign In")
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SpacesPage() {
    OnboardingPageContainer {
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "🏠 Family",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                listOf("💬 General", "📷 Photos", "📅 Events", "👥 Members").forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Text(
                            text = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Everything your group needs in one Space.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PingsPage() {
    OnboardingPageContainer {
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Text(
                            text = "👩",
                            fontSize = 30.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Column {
                        Text("Mom", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Direct ping",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = "Call me when you can",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "I can in about 10 minutes.",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Private conversations stay simple.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Pings are private conversations while Spaces are built for groups.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PrivacyPage() {
    OnboardingPageContainer {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Privacy comes first.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Built to keep your conversations personal and protected.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        listOf(
            "🔒 End-to-End Encryption",
            "🛡 Privacy by default",
            "🤖 AI that assists, never spies"
        ).forEach { item ->
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item,
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AuthenticationPage(
    onAppleSignIn: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onPhoneSignIn: () -> Unit,
    isAppleSigningIn: Boolean,
    isGoogleSigningIn: Boolean
) {
    OnboardingPageContainer {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Get set up in seconds.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Continue with Google or your phone number with Firebase.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(
            onClick = onAppleSignIn,
            enabled = !isAppleSigningIn,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isAppleSigningIn) "Signing In..." else "Continue with Apple")
        }
        OutlinedButton(
            onClick = onGoogleSignIn,
            enabled = !isGoogleSigningIn,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isGoogleSigningIn) "Signing In..." else "Continue with Google")
        }
        OutlinedButton(onClick = onPhoneSignIn, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with Phone Number")
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProfilePage(
    displayName: String,
    emojiAvatar: String,
    displayEmoji: String,
    statusMessage: String,
    isSaving: Boolean,
    isRequired: Boolean,
    onDisplayNameChange: (String) -> Unit,
    onEmojiAvatarChange: (String) -> Unit,
    onStatusMessageChange: (String) -> Unit
) {
    OnboardingPageContainer {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Create Profile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (isRequired) {
                "Finish setting up your account before entering Spaces."
            } else {
                "This helps people recognize you across Spaces and Pings."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display Name") },
            singleLine = true
        )
        OutlinedTextField(
            value = emojiAvatar,
            onValueChange = onEmojiAvatarChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Emoji Avatar") },
            singleLine = true,
            leadingIcon = {
                Text(displayEmoji, fontSize = 24.sp)
            }
        )
        OutlinedTextField(
            value = statusMessage,
            onValueChange = onStatusMessageChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Status Message (Optional)") },
            singleLine = true
        )
        if (isSaving) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Saving profile...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OnboardingPageContainer(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

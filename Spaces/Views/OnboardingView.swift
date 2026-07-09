import AuthenticationServices
import SwiftUI

struct OnboardingView: View {
    @StateObject private var viewModel = OnboardingViewModel()
    @EnvironmentObject private var authViewModel: AuthViewModel
    @FocusState private var isEmojiFieldFocused: Bool

    let onComplete: () -> Void

    var body: some View {
        ZStack {
            Color(.systemGroupedBackground)
                .ignoresSafeArea()

            if viewModel.isShowingSplash {
                OnboardingSplashView {
                    viewModel.advanceFromSplash()
                }
                .transition(.opacity)
            } else {
                VStack(spacing: 0) {
                    topBar

                    TabView(selection: $viewModel.currentPage) {
                        welcomePage.tag(OnboardingViewModel.Page.welcome)
                        spacesPage.tag(OnboardingViewModel.Page.spaces)
                        pingsPage.tag(OnboardingViewModel.Page.pings)
                        privacyPage.tag(OnboardingViewModel.Page.privacy)
                        authenticationPage.tag(OnboardingViewModel.Page.authentication)
                        if authViewModel.requiresProfileCreation {
                            createProfilePage.tag(OnboardingViewModel.Page.profile)
                        }
                    }
                    .tabViewStyle(PageTabViewStyle(indexDisplayMode: .never))
                    .animation(.easeInOut(duration: 0.3), value: viewModel.currentPage)
                    .onChange(of: viewModel.currentPage) { page in
                        if page == .profile && !authViewModel.requiresProfileCreation {
                            viewModel.setPage(.authentication)
                        }
                    }

                    bottomBar
                }
                .transition(.opacity)
            }
        }
        .alert(
            item: Binding(
                get: {
                    authViewModel.isPhoneSheetPresented ? nil : authViewModel.activeAlert
                },
                set: { newValue in
                    authViewModel.activeAlert = newValue
                }
            )
        ) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("OK"))
            )
        }
        .onAppear {
            if authViewModel.requiresProfileCreation {
                viewModel.prepareForRequiredProfileCreation(displayName: authViewModel.currentSession?.displayName)
            }
        }
        .onChange(of: authViewModel.requiresProfileCreation) { requiresProfileCreation in
            guard requiresProfileCreation else { return }
            viewModel.prepareForRequiredProfileCreation(displayName: authViewModel.currentSession?.displayName)
        }
    }

    private var topBar: some View {
        HStack {
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
    }

    private var bottomBar: some View {
        VStack(spacing: 16) {
            HStack(spacing: 8) {
                ForEach(OnboardingViewModel.Page.allCases) { page in
                    Capsule()
                        .fill(page == viewModel.currentPage ? Color.accentColor : Color.secondary.opacity(0.22))
                        .frame(width: page == viewModel.currentPage ? 22 : 8, height: 8)
                        .animation(.spring(response: 0.28, dampingFraction: 0.82), value: viewModel.currentPage)
                }
            }

            if viewModel.currentPage == .profile {
                Button("Continue", action: profileContinueAction)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!viewModel.canContinueProfile || authViewModel.isSavingProfile)
            } else if viewModel.currentPage != .authentication {
                Button("Next") {
                    viewModel.nextPage()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(authViewModel.requiresProfileCreation)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 24)
        .background(.thinMaterial)
    }

    private var welcomePage: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer()

            VStack(alignment: .leading, spacing: 14) {
                Text("Communication built around Spaces.")
                    .font(.largeTitle.bold())

                Text("Private conversations, shared memories, events, and more-all organized into Spaces.")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }

            VStack(spacing: 12) {
                Button("Get Started") {
                    viewModel.setPage(.spaces)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

                Button("Sign In") {
                    viewModel.setPage(.authentication)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }

            Spacer()
        }
        .padding(28)
    }

    private var spacesPage: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(spacing: 18) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("🏠 Family")
                        .font(.title.bold())

                    VStack(alignment: .leading, spacing: 12) {
                        onboardingModuleRow("💬", "Space Pings")
                        onboardingModuleRow("📷", "Photos")
                        onboardingModuleRow("📅", "Events")
                        onboardingModuleRow("👥", "Members")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
                .background(
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .fill(Color(.secondarySystemBackground))
                )
            }

            VStack(spacing: 12) {
                Text("Everything your group needs in one Space.")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
            }

            Spacer()
        }
        .padding(28)
    }

    private var pingsPage: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    Text("👩")
                        .font(.system(size: 30))
                        .frame(width: 56, height: 56)
                        .background(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(Color(.secondarySystemBackground))
                        )

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Mom")
                            .font(.headline)
                        Text("Direct ping")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    incomingBubble("Call me when you can")
                    outgoingBubble("I can in about 10 minutes.")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(24)
            .background(
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .fill(Color(.secondarySystemBackground))
            )

            VStack(spacing: 12) {
                Text("Private conversations stay simple.")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)

                Text("Pings are private one-to-one conversations, while Spaces are built for groups.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Spacer()
        }
        .padding(28)
    }

    private var privacyPage: some View {
        VStack(spacing: 28) {
            Spacer()

            VStack(spacing: 12) {
                Text("Privacy comes first.")
                    .font(.title.bold())
                Text("Built to keep your conversations personal and protected.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 14) {
                privacyRow("🔒", "End-to-End Encryption")
                privacyRow("🛡", "Privacy by default")
                privacyRow("🤖", "AI that assists, never spies")
            }

            Spacer()
        }
        .padding(28)
    }

    private var authenticationPage: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(spacing: 12) {
                Text("Get set up in seconds.")
                    .font(.title.bold())
                Text("Continue with Apple or Google to create your account and start using Spaces.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 12) {
                SignInWithAppleButton(.signIn) { request in
                    authViewModel.prepareAppleSignInRequest(request)
                } onCompletion: { result in
                    authViewModel.handleAppleSignInCompletion(result)
                }
                .signInWithAppleButtonStyle(.black)
                .frame(width: 300, height: 50)
                .clipShape(Capsule())
                .disabled(authViewModel.isSigningInWithApple)

                Button(action: authViewModel.signInWithGoogle) {
                    Image("google")
                        .resizable()
                        .renderingMode(.original)
                        .scaledToFit()
                        .frame(width: 300, height: 50)
                }
                .buttonStyle(.plain)
                .disabled(authViewModel.isSigningInWithGoogle)
            }

            Spacer()
        }
        .padding(28)
    }

    private var createProfilePage: some View {
        VStack(alignment: .leading, spacing: 20) {
            Spacer()

            VStack(alignment: .leading, spacing: 12) {
                Text("Create your profile")
                    .font(.title.bold())
                Text(authViewModel.requiresProfileCreation ? "Finish setting up your account before entering Spaces." : "This helps people recognize you across Spaces and Pings.")
                    .font(.body)
                    .foregroundStyle(.secondary)
            }

            VStack(spacing: 16) {
                TextField("Display Name", text: $viewModel.displayName)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.words)

                HStack(spacing: 12) {
                    Button {
                        isEmojiFieldFocused = true
                    } label: {
                        Text(viewModel.displayEmoji)
                            .font(.system(size: 28))
                            .frame(width: 56, height: 56)
                            .background(Color(.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)

                    TextField("Emoji Avatar", text: $viewModel.emojiAvatar)
                        .focused($isEmojiFieldFocused)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: viewModel.emojiAvatar) { _ in
                            viewModel.sanitizeEmojiInput()
                        }
                }

                TextField("Status Message (Optional)", text: $viewModel.statusMessage)
                    .textFieldStyle(.roundedBorder)
            }

            Spacer()
        }
        .padding(28)
        .overlay(alignment: .center) {
            if authViewModel.isSavingProfile {
                ProgressView()
                    .controlSize(.large)
            }
        }
    }

    private func onboardingModuleRow(_ emoji: String, _ title: String) -> some View {
        HStack(spacing: 12) {
            Text(emoji)
            Text(title)
                .font(.headline)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color(.systemGroupedBackground))
        )
    }

    private func privacyRow(_ emoji: String, _ title: String) -> some View {
        HStack(spacing: 14) {
            Text(emoji)
                .font(.title3)
            Text(title)
                .font(.headline)
            Spacer()
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
    }


    private func profileContinueAction() {
        if authViewModel.requiresProfileCreation {
            authViewModel.createProfile(
                displayName: viewModel.displayName,
                emojiAvatar: viewModel.displayEmoji,
                statusMessage: viewModel.statusMessage
            )
        } else {
            onComplete()
        }
    }

    private func incomingBubble(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color(.tertiarySystemFill))
            )
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func outgoingBubble(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color.accentColor)
            )
            .frame(maxWidth: .infinity, alignment: .trailing)
    }
}


private struct OnboardingSplashView: View {
    @State private var isVisible = false

    let onContinue: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 54, weight: .semibold))
                .foregroundStyle(.indigo)

            Text("Spaces")
                .font(.largeTitle.bold())
        }
        .opacity(isVisible ? 1 : 0)
        .onAppear {
            withAnimation(.easeIn(duration: 0.6)) {
                isVisible = true
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                onContinue()
            }
        }
    }
}

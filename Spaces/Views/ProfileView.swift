import AuthenticationServices
import SwiftUI

@MainActor
struct ProfileView: View {
    @EnvironmentObject private var authViewModel: AuthViewModel
    @StateObject private var viewModel: ProfileSettingsViewModel
    @State private var isShowingEditProfile = false
    @State private var isShowingSignOutConfirmation = false
    @State private var devicePendingRemoval: AccountDevice?

    init() {
        _viewModel = StateObject(wrappedValue: ProfileSettingsViewModel())
    }

    private var profile: UserProfile? {
        viewModel.profile ?? authViewModel.currentUserProfile
    }

    private var linkedProviders: [LinkedProvider] {
        if let providers = authViewModel.currentSession?.providers, !providers.isEmpty {
            return providers
        }
        return profile?.linkedProviders ?? []
    }

    private var currentDevicePushBinding: Binding<Bool> {
        Binding(
            get: { viewModel.currentDevicePushEnabled },
            set: { newValue in
                Task {
                    await viewModel.setCurrentDeviceNotificationsEnabled(newValue, session: authViewModel.currentSession)
                }
            }
        )
    }

    var body: some View {
        NavigationView {
            List {
                Section {
                    profileHeader
                        .listRowInsets(EdgeInsets(top: 12, leading: 0, bottom: 12, trailing: 0))
                }

                Section("Account") {
                    infoRow(title: "Email", value: profile?.email ?? "Not available")
                    infoRow(title: "Linked Providers", value: linkedProvidersText)
                    linkedAccountsSection
                }

                Section("Devices") {
                    if viewModel.devices.isEmpty {
                        Text("No registered devices yet.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(viewModel.devices) { device in
                            DeviceRowView(
                                device: device,
                                friendlyName: friendlyDeviceName(for: device),
                                isCurrent: device.deviceID == viewModel.currentDeviceID,
                                pushStatus: pushStatusText(for: device),
                                isRemoving: viewModel.isRemovingDevice(device),
                                onRemove: device.deviceID == viewModel.currentDeviceID ? nil : {
                                    devicePendingRemoval = device
                                }
                            )
                        }
                    }
                }

                Section("Notifications") {
                    Toggle("Notifications on This Device", isOn: currentDevicePushBinding)
                        .disabled(viewModel.isUpdatingNotifications)
                    infoRow(title: "Per-Space Settings", value: "Coming Soon")
                    infoRow(title: "Quiet Hours", value: "Coming Soon")
                }

                Section("Security & Privacy") {
                    infoRow(title: "End-to-End Encryption", value: "Enabled")
                    NavigationLink(destination: BlockedUsersView(viewModel: viewModel, session: authViewModel.currentSession)) {
                        HStack {
                            Text("Blocked Users")
                            Spacer()
                            Text("\(profile?.blockedUsers.count ?? 0)")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section("Appearance") {
                    infoRow(title: "Theme", value: "System")
                }

                Section("Storage") {
                    infoRow(title: "Cached Media", value: viewModel.formattedCacheSize() ?? "Unavailable")
                    Button(viewModel.isClearingCache ? "Clearing Cache..." : "Clear Cache") {
                        Task {
                            await viewModel.clearCache()
                        }
                    }
                    .disabled(viewModel.isClearingCache)
                }

                Section("Help & About") {
                    infoRow(title: "Version", value: appVersionText)
                    NavigationLink(destination: ProfileDetailTextView(title: "Support", bodyText: supportBodyText)) {
                        Text("Support")
                    }
                    NavigationLink(destination: ProfileDetailTextView(title: "Terms", bodyText: termsBodyText)) {
                        Text("Terms")
                    }
                    NavigationLink(destination: ProfileDetailTextView(title: "Privacy", bodyText: privacyBodyText)) {
                        Text("Privacy")
                    }
                    NavigationLink(destination: AboutSpacesView(bodyText: aboutBodyText)) {
                        Text("About")
                    }
                }

                Section {
                    Button("Sign Out", role: .destructive) {
                        isShowingSignOutConfirmation = true
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("You")
            .navigationBarTitleDisplayMode(.inline)
            .overlay {
                if viewModel.isLoading && profile == nil {
                    ProgressView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Edit Profile") {
                        isShowingEditProfile = true
                    }
                    .disabled(profile == nil)
                }
            }
            .task(id: authViewModel.currentSession?.uid) {
                await viewModel.loadIfNeeded(session: authViewModel.currentSession, cachedProfile: authViewModel.currentUserProfile)
            }
            .sheet(isPresented: $isShowingEditProfile) {
                EditProfileSheet(
                    profile: profile,
                    isSaving: viewModel.isSavingProfile
                ) { displayName, emojiAvatar, statusMessage in
                    if let updatedProfile = await viewModel.saveProfile(
                        session: authViewModel.currentSession,
                        displayName: displayName,
                        emojiAvatar: emojiAvatar,
                        statusMessage: statusMessage
                    ) {
                        authViewModel.applyUpdatedProfile(updatedProfile)
                        isShowingEditProfile = false
                    }
                }
            }
            .confirmationDialog(
                "Sign out of Spaces?",
                isPresented: $isShowingSignOutConfirmation,
                titleVisibility: .visible
            ) {
                Button("Sign Out", role: .destructive) {
                    authViewModel.signOut()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This will disable push notifications on this device and sign you out of Firebase.")
            }
            .confirmationDialog(
                "Remove this device?",
                isPresented: Binding(
                    get: { devicePendingRemoval != nil },
                    set: { isPresented in
                        if !isPresented {
                            devicePendingRemoval = nil
                        }
                    }
                ),
                titleVisibility: .visible
            ) {
                if let devicePendingRemoval {
                    Button("Remove Device", role: .destructive) {
                        Task {
                            await viewModel.removeDevice(devicePendingRemoval, session: authViewModel.currentSession)
                            self.devicePendingRemoval = nil
                        }
                    }
                }
                Button("Cancel", role: .cancel) {
                    devicePendingRemoval = nil
                }
            } message: {
                Text("This device will be removed from your recent devices list.")
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .alert(item: $viewModel.activeAlert) { alert in
            Alert(title: Text(alert.title), message: Text(alert.message), dismissButton: .default(Text("OK")))
        }
        .alert(item: $authViewModel.activeAlert) { alert in
            Alert(title: Text(alert.title), message: Text(alert.message), dismissButton: .default(Text("OK")))
        }
    }


    @ViewBuilder
    private var linkedAccountsSection: some View {
        if linkedProviders.contains(.apple) {
            infoRow(title: "Apple", value: "Linked")
        } else {
            SignInWithAppleButton(.continue) { request in
                authViewModel.prepareAppleLinkRequest(request)
            } onCompletion: { result in
                authViewModel.handleAppleLinkCompletion(result)
            }
            .signInWithAppleButtonStyle(.black)
            .frame(height: 44)
            .disabled(authViewModel.isLinkingApple)
        }

        if linkedProviders.contains(.google) {
            infoRow(title: "Google", value: "Linked")
        } else {
            Button("Link Google") {
                authViewModel.linkGoogle()
            }
            .disabled(authViewModel.isLinkingGoogle)
        }
    }

    private var profileHeader: some View {
        VStack(spacing: 12) {
            Text(profile?.emojiAvatar ?? "🧑‍💻")
                .font(.system(size: 46))
                .frame(width: 92, height: 92)
                .background(
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [.indigo.opacity(0.18), .blue.opacity(0.14)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                )

            Text(profile?.displayName ?? "Your Account")
                .font(.title.bold())

            if let contactLine = contactLine {
                Text(contactLine)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if let statusMessage = profile?.statusMessage, !statusMessage.isEmpty {
                Text(statusMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }

    private var linkedProvidersText: String {
        guard !linkedProviders.isEmpty else { return "Unavailable" }
        return linkedProviders.map(\.rawValue).sorted().joined(separator: ", ")
    }

    private var contactLine: String? {
        if let email = profile?.email, !email.isEmpty {
            return email
        }
        if let phoneNumber = profile?.phoneNumber, !phoneNumber.isEmpty {
            return phoneNumber
        }
        return nil
    }

    private var appVersionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unknown"
        return "\(version) (\(build))"
    }

    private func friendlyDeviceName(for device: AccountDevice) -> String {
        if device.deviceID == viewModel.currentDeviceID {
            let currentName = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
            if !currentName.isEmpty {
                return currentName
            }
        }

        switch device.platform.lowercased() {
        case "ios":
            return "iPhone or iPad"
        case "android":
            return "Android device"
        default:
            return device.platform.capitalized
        }
    }

    private var supportBodyText: String {
        """
        Need Help?

        If you’re having trouble with Spaces, we’re here to help.

        Whether you’ve found a bug, have a question, or want to suggest a feature, we’d love to hear from you.

        Common Issues

        Notifications not working

        • Verify notifications are enabled for Spaces in iOS Settings.
        • Make sure “Notifications on This Device” is enabled in Spaces.

        Messages won’t send

        • Check your internet connection.
        • Verify both participants are still connected.

        Problems signing in

        • Confirm you’re using the same sign-in provider originally linked to your account.
        • If you’ve lost access to your account, contact support.

        Contact Support

        Email

        support@arcinteractive.studio

        When contacting support, including your app version and device model, and any screenshots of the issue you are having, it helps us investigate more quickly.

        Response Times

        We typically respond within 1–2 business days.
        """
    }

    private var termsBodyText: String {
        """
        Welcome to Spaces

        Spaces is designed for private, meaningful conversations between people. By using Spaces, you agree to use the service responsibly.

        Acceptable Use

        You agree not to:

        • Harass or threaten others
        • Impersonate another person
        • Distribute malware or malicious content
        • Attempt unauthorized access to accounts or systems
        • Use Spaces for illegal activities

        Your Content

        You retain ownership of everything you create in Spaces.

        Because conversations are end-to-end encrypted, we cannot read your messages.

        You are responsible for any content you send.

        Account Security

        You are responsible for maintaining access to your account and keeping your linked authentication providers secure.

        Service Availability

        We strive to keep Spaces available at all times but cannot guarantee uninterrupted service.

        Features may change, improve, or be removed over time.

        Termination

        Accounts that repeatedly violate these Terms may be suspended or permanently removed.
        """
    }

    private var privacyBodyText: String {
        """
        Your Privacy Matters

        Privacy isn’t just a feature of Spaces—it’s part of how the app is built.

        Information We Collect

        To provide the service, we store:

        • Your display name
        • Profile emoji
        • Linked sign-in providers
        • Account identifier
        • Device information required for notifications
        • Encrypted conversation metadata needed to deliver messages

        What We Don’t Read

        Messages sent in Spaces are protected using end-to-end encryption.

        We cannot read your conversations.

        We do not use message content for advertising or profiling.

        Notifications

        Push notifications are used only to notify you of activity you’ve chosen to receive.

        Notification settings can be changed at any time.

        Data Security

        We use industry-standard encryption for data in transit and secure cloud infrastructure for account information.

        Account Deletion

        Deleting your account permanently removes your account information from Spaces.

        Some encrypted message records may remain on recipients’ devices until deleted by them.

        Questions

        If you have questions about privacy, contact:

        info@arcinteractive.studio
        """
    }

    private var aboutBodyText: String {
        """
        Built for Better Conversations

        Spaces is a communication app focused on intentional conversations—not endless feeds.

        Whether you’re chatting one-on-one or collaborating inside shared Spaces, the goal is simple:

        Give people a place to communicate without unnecessary noise.

        Features

        • End-to-end encrypted messaging
        • Secure device management
        • Private one-to-one Pings
        • Shared Spaces for conversations
        • Cross-device synchronization
        • Privacy-first design

        Built By

        Designed and developed by

        ArcInteractive

        Thank You

        Thank you for helping shape Spaces from the very beginning.

        Every report, suggestion, and conversation helps make the app better.
        """
    }

    private func pushStatusText(for device: AccountDevice) -> String {
        let matchingTokens = viewModel.pushTokens.filter { $0.deviceID == device.deviceID }
        guard !matchingTokens.isEmpty else { return "No push token" }
        return matchingTokens.contains(where: \.enabled) ? "Push enabled" : "Push disabled"
    }

    @ViewBuilder
    private func infoRow(title: String, value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
    }
}

private struct DeviceRowView: View {
    let device: AccountDevice
    let friendlyName: String
    let isCurrent: Bool
    let pushStatus: String
    let isRemoving: Bool
    let onRemove: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(friendlyName)
                    .font(.headline)

                if isCurrent {
                    Text("Current")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.indigo.opacity(0.14)))
                }

                Spacer()

                if let onRemove {
                    Button(isRemoving ? "Removing..." : "Remove Device", role: .destructive, action: onRemove)
                        .disabled(isRemoving)
                        .font(.subheadline)
                }
            }

            Text(pushStatus)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if let lastActiveAt = device.lastActiveAt {
                Text("Last active \(readableDeviceActivityText(for: lastActiveAt))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct EditProfileSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var displayName: String
    @State private var emojiAvatar: String
    @State private var statusMessage: String
    private let initialDisplayName: String
    private let initialEmojiAvatar: String
    private let initialStatusMessage: String

    let isSaving: Bool
    let onSave: (String, String, String) async -> Void

    init(
        profile: UserProfile?,
        isSaving: Bool,
        onSave: @escaping (String, String, String) async -> Void
    ) {
        let resolvedDisplayName = profile?.displayName ?? ""
        let resolvedEmojiAvatar = profile?.emojiAvatar ?? "🧑‍💻"
        let resolvedStatusMessage = profile?.statusMessage ?? ""

        _displayName = State(initialValue: resolvedDisplayName)
        _emojiAvatar = State(initialValue: resolvedEmojiAvatar)
        _statusMessage = State(initialValue: resolvedStatusMessage)
        self.initialDisplayName = resolvedDisplayName
        self.initialEmojiAvatar = resolvedEmojiAvatar
        self.initialStatusMessage = resolvedStatusMessage
        self.isSaving = isSaving
        self.onSave = onSave
    }

    private var trimmedDisplayName: String {
        displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var trimmedEmojiAvatar: String {
        emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var resolvedEmojiPreview: String {
        trimmedEmojiAvatar.isEmpty ? "🧑‍💻" : String(trimmedEmojiAvatar.prefix(1))
    }

    private var resolvedDisplayNamePreview: String {
        trimmedDisplayName.isEmpty ? "Display Name" : trimmedDisplayName
    }

    private var resolvedStatusPreview: String {
        let trimmedStatus = statusMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedStatus.isEmpty ? "Add a status message" : trimmedStatus
    }

    private var hasChanges: Bool {
        trimmedDisplayName != originalDisplayName ||
        resolvedEmojiPreview != originalEmojiAvatar ||
        statusMessage.trimmingCharacters(in: .whitespacesAndNewlines) != originalStatusMessage
    }

    private var canSave: Bool {
        !trimmedDisplayName.isEmpty && hasChanges && !isSaving
    }

    private var originalDisplayName: String {
        initialDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var originalEmojiAvatar: String {
        let trimmed = initialEmojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "🧑‍💻" : String(trimmed.prefix(1))
    }

    private var originalStatusMessage: String {
        initialStatusMessage.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    HStack(spacing: 14) {
                        Text(resolvedEmojiPreview)
                            .font(.system(size: 34))
                            .frame(width: 68, height: 68)
                            .background(
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .fill(Color.indigo.opacity(0.12))
                            )

                        VStack(alignment: .leading, spacing: 4) {
                            Text(resolvedDisplayNamePreview)
                                .font(.headline)
                            Text(resolvedStatusPreview)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("Live Preview")
                }

                Section("Profile") {
                    VStack(alignment: .leading, spacing: 6) {
                        TextField("Display Name", text: $displayName)
                        Text("\(trimmedDisplayName.count) characters")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    TextField("Emoji Avatar", text: $emojiAvatar)

                    VStack(alignment: .leading, spacing: 6) {
                        TextField("Status Message", text: $statusMessage)
                        Text("\(statusMessage.trimmingCharacters(in: .whitespacesAndNewlines).count) characters")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Edit Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "Saving..." : "Save") {
                        Task {
                            await onSave(displayName, emojiAvatar, statusMessage)
                        }
                    }
                    .disabled(!canSave)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
}

private struct BlockedUsersView: View {
    @ObservedObject var viewModel: ProfileSettingsViewModel
    let session: AuthSession?

    var body: some View {
        List {
            if let blockedUsers = viewModel.profile?.blockedUsers, !blockedUsers.isEmpty {
                ForEach(blockedUsers) { blockedUser in
                    HStack(spacing: 12) {
                        Text(blockedUser.emojiAvatar)
                            .font(.title2)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(blockedUser.displayName)
                                .font(.headline)

                            if let blockedAt = blockedUser.blockedAt {
                                Text("Blocked \(blockedDateFormatter.string(from: blockedAt))")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }

                        Spacer()

                        Button("Unblock") {
                            Task {
                                await viewModel.unblockUser(blockedUser, session: session)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
            } else {
                VStack(spacing: 10) {
                    Text("No blocked users")
                        .font(.headline)
                    Text("People you block will appear here, and you can unblock them at any time.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 32)
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Blocked Users")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ProfileDetailTextView: View {
    let title: String
    let bodyText: String

    var body: some View {
        ScrollView {
            Text(bodyText)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(.systemGroupedBackground))
    }
}

private struct AboutSpacesView: View {
    let bodyText: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Spaces")
                        .font(.largeTitle.bold())
                    Text("Version 1.0")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }

                Divider()

                Text(bodyText)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(20)
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(.systemGroupedBackground))
    }
}


private let relativeDateFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .short
    return formatter
}()

private let blockedDateFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter
}()

private func readableDeviceActivityText(for date: Date) -> String {
    let relative = relativeDateFormatter.localizedString(for: date, relativeTo: Date())
    let absolute = blockedDateFormatter.string(from: date)
    return "\(relative) (\(absolute))"
}

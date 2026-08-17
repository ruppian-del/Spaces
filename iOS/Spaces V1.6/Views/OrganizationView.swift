import SwiftUI
import FirebaseFirestore

struct OrganizationView: View {
    @EnvironmentObject private var appViewModel: AppViewModel
    @State private var organization: Organization?
    @State private var isShowingBootstrap = false
    @State private var organizationName = ""
    @State private var isSaving = false
    @State private var errorMessage: String?
    @State private var listener: ListenerRegistration?
    @State private var ownedSpacesListener: ListenerRegistration?
    @State private var ownedSpaces: [OrganizationOwnedSpace] = []
    @State private var members: [OrganizationMember] = []
    @State private var membersListener: ListenerRegistration?
    @State private var inviteCode: String?
    @State private var isSharingInvite = false
    @State private var isShowingJoin = false
    @State private var joinCode = ""
    private let service = OrganizationService()
    private let authService = AuthService()
    private let userProfileService = UserProfileService()
    @State private var currentUserID: String?
    @State private var currentUserProfileName: String?
    @State private var memberProfileNames: [String: String] = [:]
    @State private var debugOverrideRevision = 0

    var body: some View {
        NavigationView {
            Group {
                if let organization {
                    List {
                        Section("Organization") {
                            LabeledContent("Name", value: organization.name)
                            LabeledContent("Status", value: "Active")
                        }
                        Section {
                            capacityRow("People", used: uniqueOrganizationMemberCount, limit: displayedEntitlements(for: organization).peopleCapacity)
                            capacityRow("Active Spaces", used: ownedSpaces.filter { !$0.isArchived }.count, limit: displayedEntitlements(for: organization).activeSpaceCapacity)
                            storageCapacityRow(used: organization.usage.mediaStorageBytes, limit: displayedEntitlements(for: organization).mediaStorageCapacityBytes)
                        } header: {
                            Text("Capacity")
                        } footer: {
                            Text("Capacity is intentionally unconfigured until the founding organization’s approved allowance is entered.")
                        }
                        Section("Administration") {
                            LabeledContent("Organization administrators", value: "\(administrators.count)")
                            NavigationLink("Manage Administrators") {
                                OrganizationAdministratorManagementView(
                                    organization: organization,
                                    administrators: administrators,
                                    displayName: memberDisplayName,
                                    canManageOrganization: canManageOrganization,
                                    service: service
                                )
                            }
                        }
                        Section("Organization Spaces") {
                            if ownedSpaces.isEmpty {
                                Text("No Spaces have been added to this organization.")
                                    .foregroundStyle(.secondary)
                            } else {
                                ForEach(ownedSpaces) { space in
                                    HStack {
                                        Text(space.emoji)
                                        VStack(alignment: .leading) {
                                            Text(space.name)
                                            if space.isArchived { Text("Archived").font(.caption).foregroundStyle(.secondary) }
                                            Text("\(space.memberCount) member\(space.memberCount == 1 ? "" : "s")")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }
                        }
#if DEBUG
                        Section("Debug Testing") {
                            NavigationLink("Organization Test Overrides") {
                                OrganizationDebugOverridesView(organization: organization) {
                                    debugOverrideRevision += 1
                                }
                            }
                            Text("Overrides affect only this debug build and never change the organization record.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
#endif
                    }
                } else {
                    VStack(spacing: 14) {
                        Image(systemName: "building.2")
                            .font(.system(size: 42))
                            .foregroundStyle(.indigo)
                        Text("Set Up Your Organization")
                            .font(.title2.bold())
                        Text("Create the founding organization before adding administrators, people, and organization-owned Spaces.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    }
                    .padding(32)
                }
            }
            .navigationTitle("Organization")
            .toolbar {
                if organization == nil {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("Set Up") { isShowingBootstrap = true }
                    }
                    ToolbarItem(placement: .navigationBarLeading) { Button("Join") { isShowingJoin = true } }
                }
            }
            .sheet(isPresented: $isShowingBootstrap) {
                NavigationView {
                    Form {
                        Section("Founding Organization") {
                            TextField("Church or organization name", text: $organizationName)
                                .textInputAutocapitalization(.words)
                        }
                        Section {
                            Text("You become the primary administrator. People, active-Space, modules, and storage limits remain unconfigured until approved.")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .navigationTitle("Set Up Organization")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { isShowingBootstrap = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(isSaving ? "Creating…" : "Create") {
                                Task { await createOrganization() }
                            }
                            .disabled(isSaving || organizationName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                }
            }
            .sheet(isPresented: $isSharingInvite) {
                if let inviteCode, let url = InviteLink.organizationURL(for: inviteCode) { ShareSheet(items: [url]) }
            }
            .sheet(isPresented: $isShowingJoin) {
                NavigationView { Form { TextField("Organization invite code", text: $joinCode).textInputAutocapitalization(.characters) }
                    .navigationTitle("Join Organization").toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { isShowingJoin = false } }
                        ToolbarItem(placement: .confirmationAction) { Button("Join") { Task { await redeemInvite() } }.disabled(joinCode.isEmpty) }
                    }
                }
            }
        .alert("Organization", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK") { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
        }
        .navigationViewStyle(.stack)
        .onAppear {
            startListeningIfNeeded()
            Task { await loadCurrentUserProfile() }
        }
        .onChange(of: appViewModel.pendingOrganizationInviteCode) { code in
            guard let code else { return }
            Task { await redeemIncomingInvite(code) }
        }
        .onDisappear {
            listener?.remove()
            listener = nil
            ownedSpacesListener?.remove()
            ownedSpacesListener = nil
            membersListener?.remove()
        }
    }

    private func capacityRow(_ label: String, used: Int64, limit: Int64?) -> some View {
        LabeledContent(label, value: limit.map { "\(used) of \($0)" } ?? "Not configured")
    }

    private func storageCapacityRow(used: Int64, limit: Int64?) -> some View {
        LabeledContent("Media storage", value: limit.map { "\(storageLabel(used)) of \(storageLabel($0))" } ?? "Not configured")
    }

    private func storageLabel(_ bytes: Int64) -> String {
        let gigabyte = 1_024 * 1_024 * 1_024
        let value = Double(bytes) / Double(gigabyte)
        return value.rounded() == value ? "\(Int(value)) GB" : String(format: "%.1f GB", value)
    }

    private func capacityRow(_ label: String, used: Int, limit: Int?) -> some View {
        LabeledContent(label, value: limit.map { "\(used) of \($0)" } ?? "Not configured")
    }

    private func displayedEntitlements(for organization: Organization) -> OrganizationEntitlements {
        let entitlements = organization.entitlements
        if entitlements.peopleCapacity == nil,
           entitlements.activeSpaceCapacity == nil,
           entitlements.mediaStorageCapacityBytes == nil,
           entitlements.enabledModuleIDs.isEmpty {
            return .effective(stored: .foundation)
        }
        _ = debugOverrideRevision
        return .effective(stored: entitlements)
    }

    private func memberDisplayName(_ member: OrganizationMember) -> String {
        if let profileName = memberProfileNames[member.userID], !profileName.isEmpty { return profileName }
        if member.userID == currentUserID, let currentUserProfileName, !currentUserProfileName.isEmpty { return currentUserProfileName }
        return member.displayName
    }

    private var canManageOrganization: Bool {
        guard let currentUserID else { return false }
        return members.first(where: { $0.userID == currentUserID })?.role.canManageOrganization == true
    }

    private var administrators: [OrganizationMember] {
        var seen = Set<String>()
        return members.filter { $0.role.canManageOrganization && seen.insert($0.userID).inserted }
    }

    private var uniqueOrganizationMemberCount: Int {
        Set(ownedSpaces.flatMap(\.memberIDs)).count
    }

    private func loadCurrentUserProfile() async {
        guard let session = authService.currentSession() else { return }
        currentUserID = session.uid
        currentUserProfileName = try? await userProfileService.fetchUserProfile(uid: session.uid)?.displayName
    }

    private func loadProfileNames(for members: [OrganizationMember]) async {
        var names: [String: String] = [:]
        for member in members {
            if let displayName = try? await userProfileService.fetchUserProfile(uid: member.userID)?.displayName,
               !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                names[member.userID] = displayName
            }
        }
        memberProfileNames = names
    }


    private func createOrganization() async {
        isSaving = true
        defer { isSaving = false }
        do {
            organization = try await service.createFoundingOrganization(named: organizationName)
            isShowingBootstrap = false
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func startListeningIfNeeded() {
        guard listener == nil else { return }
        listener = service.listenToOrganizationsForCurrentUser { result in
            switch result {
            case .success(let organizations):
                let selected = organizations.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }.first
                if organization?.id != selected?.id {
                    ownedSpacesListener?.remove()
                    ownedSpaces = []
                    if let selected {
                        Task { try? await service.refreshCurrentMemberDisplayName(in: selected) }
                        ownedSpacesListener = service.listenToOwnedSpaces(in: selected) { result in
                            ownedSpaces = (try? result.get()) ?? []
                        }
                        membersListener = service.listenToMembers(in: selected) { result in
                            let loadedMembers = (try? result.get()) ?? []
                            members = loadedMembers
                            Task { await loadProfileNames(for: loadedMembers) }
                        }
                    }
                }
                organization = selected
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
    }

    private func createInvite(as role: OrganizationRole) async {
        guard let organization else { return }
        do {
            inviteCode = try await service.createInvite(for: organization, role: role).id
            isSharingInvite = true
        } catch { errorMessage = error.localizedDescription }
    }

    private func redeemInvite() async {
        do { try await service.redeemInvite(code: joinCode); isShowingJoin = false; joinCode = "" }
        catch { errorMessage = error.localizedDescription }
    }

    private func redeemIncomingInvite(_ code: String) async {
        await redeemInviteCode(code)
        appViewModel.clearPendingOrganizationInviteCode()
    }

    private func redeemInviteCode(_ code: String) async {
        do { try await service.redeemInvite(code: code); isShowingJoin = false; joinCode = "" }
        catch { errorMessage = error.localizedDescription }
    }
}

#if DEBUG
private struct OrganizationDebugOverridesView: View {
    let organization: Organization
    let onChange: () -> Void

    @State private var peopleCapacity: String
    @State private var activeSpaceCapacity: String
    @State private var storageMegabytes: String
    @State private var enabledModuleIDs: Set<String>
    @State private var message: String?
    @State private var isSaving = false
    private let service = OrganizationService()

    init(organization: Organization, onChange: @escaping () -> Void) {
        self.organization = organization
        self.onChange = onChange
        let effective = organization.entitlements
        _peopleCapacity = State(initialValue: effective.peopleCapacity.map(String.init) ?? "")
        _activeSpaceCapacity = State(initialValue: effective.activeSpaceCapacity.map(String.init) ?? "")
        _storageMegabytes = State(initialValue: effective.mediaStorageCapacityBytes.map { String($0 / (1_024 * 1_024)) } ?? "")
        _enabledModuleIDs = State(initialValue: effective.enabledModuleIDs)
    }

    var body: some View {
        Form {
            Section("Capacity Overrides") {
                TextField("People", text: $peopleCapacity).keyboardType(.numberPad)
                TextField("Active Spaces", text: $activeSpaceCapacity).keyboardType(.numberPad)
                TextField("Storage (MB)", text: $storageMegabytes).keyboardType(.numberPad)
            }

            Section("Module Entitlements") {
                ForEach(SpaceModule.allCases) { module in
                    Toggle(module.title, isOn: Binding(
                        get: { enabledModuleIDs.contains(module.rawValue) },
                        set: { enabled in
                            if enabled { enabledModuleIDs.insert(module.rawValue) }
                            else { enabledModuleIDs.remove(module.rawValue) }
                        }
                    ))
                }
            }

            Section {
                Button("Apply Overrides") { Task { await apply() } }.disabled(isSaving)
                Button("Reset to Organization Values", role: .destructive) { Task { await reset() } }.disabled(isSaving)
            } footer: {
                Text("Debug-only values are stored with this organization so the same test configuration is used on every debug device. Release builds ignore them.")
            }

            if let message {
                Section { Text(message).foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("Test Overrides")
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadSavedValues() }
    }

    private func apply() async {
        guard let people = Int(peopleCapacity), people >= 0,
              let spaces = Int(activeSpaceCapacity), spaces >= 0,
              let megabytes = Int64(storageMegabytes), megabytes >= 0 else {
            message = "Enter zero or a positive whole number for every capacity."
            return
        }
        isSaving = true
        defer { isSaving = false }
        do {
            try await service.setDebugEntitlementOverrides(for: organization.id, entitlements: OrganizationEntitlements(peopleCapacity: people, activeSpaceCapacity: spaces, enabledModuleIDs: enabledModuleIDs, mediaStorageCapacityBytes: megabytes * 1_024 * 1_024))
            message = "Debug overrides saved for every debug device."
            onChange()
        } catch { message = error.localizedDescription }
    }

    private func reset() async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await service.clearDebugEntitlementOverrides(for: organization.id)
            message = "Overrides cleared. Reopen this screen to display the organization values."
            onChange()
        } catch { message = error.localizedDescription }
    }

    private func loadSavedValues() async {
        do {
            let saved = try await service.effectiveEntitlements(for: organization.id)
            peopleCapacity = saved.peopleCapacity.map(String.init) ?? ""
            activeSpaceCapacity = saved.activeSpaceCapacity.map(String.init) ?? ""
            storageMegabytes = saved.mediaStorageCapacityBytes.map { String($0 / (1_024 * 1_024)) } ?? ""
            enabledModuleIDs = saved.enabledModuleIDs
        } catch { message = error.localizedDescription }
    }
}
#endif

private struct OrganizationAdministratorManagementView: View {
    let organization: Organization
    let administrators: [OrganizationMember]
    let displayName: (OrganizationMember) -> String
    let canManageOrganization: Bool
    let service: OrganizationService

    @State private var inviteCode: String?
    @State private var isSharingInvite = false
    @State private var memberPendingRemoval: OrganizationMember?
    @State private var errorMessage: String?

    var body: some View {
        List {
            if canManageOrganization {
                Section("Invite") {
                    Button("Invite Administrator") { Task { await createInvite() } }
                }
            }
            Section("Administrators") {
                ForEach(administrators) { member in
                    HStack {
                        LabeledContent(displayName(member), value: roleLabel(member.role))
                        if canManageOrganization && member.role != .primaryAdministrator {
                            Menu("Manage") {
                                Button("Remove from Organization", role: .destructive) { memberPendingRemoval = member }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Manage Administrators")
        .sheet(isPresented: $isSharingInvite) {
            if let inviteCode, let url = InviteLink.organizationURL(for: inviteCode) { ShareSheet(items: [url]) }
        }
        .alert("Remove from Organization?", isPresented: Binding(get: { memberPendingRemoval != nil }, set: { if !$0 { memberPendingRemoval = nil } }), presenting: memberPendingRemoval) { member in
            Button("Remove", role: .destructive) { Task { await remove(member) } }
            Button("Cancel", role: .cancel) { memberPendingRemoval = nil }
        } message: { member in
            Text("\(displayName(member)) will keep their existing Space memberships, but will no longer be an organization member.")
        }
        .alert("Organization", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK") { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
    }

    private func roleLabel(_ role: OrganizationRole) -> String {
        switch role {
        case .primaryAdministrator: "Primary admin"
        case .administrator: "Admin"
        case .member: "Member"
        }
    }

    private func createInvite() async {
        do {
            inviteCode = try await service.createInvite(for: organization, role: .administrator).id
            isSharingInvite = true
        }
        catch { errorMessage = error.localizedDescription }
    }

    private func remove(_ member: OrganizationMember) async {
        do { try await service.remove(member, from: organization); memberPendingRemoval = nil }
        catch { errorMessage = error.localizedDescription }
    }
}

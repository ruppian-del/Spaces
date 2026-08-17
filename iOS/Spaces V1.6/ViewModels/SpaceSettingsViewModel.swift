import Combine
import SwiftUI
import Foundation

@MainActor
final class SpaceSettingsViewModel: ObservableObject {
    @Published var spaceName: String
    @Published var spaceEmoji: String
    @Published var spaceColor: Color
    @Published var spaceDescription: String
    @Published var templateType: SpaceTemplate
    @Published var notificationsEnabled: Bool
    @Published var allowMemberInvites: Bool
    @Published var isPrivateSpace: Bool
    @Published var safeModeEnabled: Bool
    @Published private(set) var announcementsEnabled: Bool
    @Published private(set) var roomsEnabled: Bool
    @Published private(set) var eventsEnabled: Bool
    @Published private(set) var filesEnabled: Bool
    @Published private(set) var pollsEnabled: Bool
    @Published private(set) var listsEnabled: Bool
    @Published private(set) var notesEnabled: Bool
    @Published private(set) var isUpdatingEventsModule = false
    @Published private(set) var isUpdatingAnnouncementsModule = false
    @Published private(set) var isUpdatingRoomsModule = false
    @Published private(set) var canManageModules = false
    @Published private(set) var canManageRoles = false
    @Published private(set) var canManageInvites = false
    @Published private(set) var isUpdatingFilesModule = false
    @Published private(set) var isUpdatingPollsModule = false
    @Published private(set) var isUpdatingListsModule = false
    @Published private(set) var isUpdatingNotesModule = false
    @Published private(set) var shouldConfirmHidingFiles = false
    @Published private(set) var isLoadingInvite = false
    @Published private(set) var isCreatingInvite = false
    @Published private(set) var isUpdatingInvite = false
    @Published private(set) var pendingInvite: SpaceInvite?
    @Published private(set) var moduleOrder: [SpaceModule]
    @Published var errorMessage: String?

    let space: Space
    private let spaceService: SpaceService

    init(space: Space) {
        self.space = space
        self.spaceService = SpaceService()
        self.spaceName = space.name
        self.spaceEmoji = Self.defaultEmoji(for: space)
        self.spaceColor = Color(hex: space.tintHex)
        self.spaceDescription = space.subtitle
        self.templateType = space.template
        self.notificationsEnabled = true
        self.allowMemberInvites = true
        self.isPrivateSpace = true
        self.safeModeEnabled = true
        self.announcementsEnabled = space.announcementsEnabled
        self.roomsEnabled = space.roomsEnabled
        self.eventsEnabled = space.eventsEnabled
        self.filesEnabled = space.filesEnabled
        self.pollsEnabled = space.pollsEnabled
        self.listsEnabled = space.listsEnabled
        self.notesEnabled = space.notesEnabled
        self.moduleOrder = space.moduleOrder
    }

    init(space: Space, spaceService: SpaceService) {
        self.space = space
        self.spaceService = spaceService
        self.spaceName = space.name
        self.spaceEmoji = Self.defaultEmoji(for: space)
        self.spaceColor = Color(hex: space.tintHex)
        self.spaceDescription = space.subtitle
        self.templateType = space.template
        self.notificationsEnabled = true
        self.allowMemberInvites = true
        self.isPrivateSpace = true
        self.safeModeEnabled = true
        self.announcementsEnabled = space.announcementsEnabled
        self.roomsEnabled = space.roomsEnabled
        self.eventsEnabled = space.eventsEnabled
        self.filesEnabled = space.filesEnabled
        self.pollsEnabled = space.pollsEnabled
        self.listsEnabled = space.listsEnabled
        self.notesEnabled = space.notesEnabled
        self.moduleOrder = space.moduleOrder
    }

    func addToOrganization(_ organizationID: String) async {
        do {
            try await spaceService.addToOrganization(organizationID, in: space)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    var displayEmoji: String {
        let trimmed = spaceEmoji.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? Self.defaultEmoji(for: space) : trimmed
    }

    func sanitizeEmojiInput() {
        let compact = spaceEmoji.trimmingCharacters(in: .whitespacesAndNewlines)

        if compact.isEmpty {
            spaceEmoji = ""
            return
        }

        if let firstCluster = compact.first {
            spaceEmoji = String(firstCluster)
        }
    }

    func openInviteEditor() async {
        guard !isLoadingInvite else { return }
        isLoadingInvite = true
        defer { isLoadingInvite = false }

        do {
            if let existingInvite = try await spaceService.fetchLatestInvite(for: space) {
                pendingInvite = existingInvite
            } else {
                await createInvite()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createInvite() async {
        guard !isCreatingInvite else { return }
        isCreatingInvite = true
        defer { isCreatingInvite = false }

        do {
            pendingInvite = try await spaceService.createInvite(for: space)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setInviteActive(_ isActive: Bool) async {
        guard let pendingInvite, !isUpdatingInvite else { return }
        isUpdatingInvite = true
        defer { isUpdatingInvite = false }

        do {
            self.pendingInvite = try await spaceService.updateInviteActiveState(code: pendingInvite.code, isActive: isActive)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func regenerateInvite() async {
        guard !isUpdatingInvite else { return }
        isUpdatingInvite = true
        defer { isUpdatingInvite = false }

        do {
            pendingInvite = try await spaceService.regenerateInvite(for: space, replacing: pendingInvite)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func dismissInvite() {
        pendingInvite = nil
    }

    func loadModuleSettings() async {
        canManageModules = await spaceService.canManageModules(in: space)
        canManageRoles = await spaceService.canPerform(.manageRoles, in: space)
        canManageInvites = await spaceService.canPerform(.inviteMembers, in: space)
        if let latestOrder = try? await spaceService.fetchModuleOrder(in: space) {
            moduleOrder = latestOrder
        }
    }

    func moveModule(fromOffsets: IndexSet, toOffset: Int) async {
        var updatedOrder = moduleOrder
        updatedOrder.move(fromOffsets: fromOffsets, toOffset: toOffset)
        moduleOrder = updatedOrder

        do {
            try await spaceService.updateModuleOrder(in: space, modules: updatedOrder)
        } catch {
            errorMessage = error.localizedDescription
            if let latestOrder = try? await spaceService.fetchModuleOrder(in: space) {
                moduleOrder = latestOrder
            }
        }
    }

    func saveModuleOrder(_ updatedOrder: [SpaceModule]) async -> Bool {
        moduleOrder = updatedOrder
        do {
            try await spaceService.updateModuleOrder(in: space, modules: updatedOrder)
            moduleOrder = try await spaceService.fetchModuleOrder(in: space)
            return true
        } catch {
            errorMessage = error.localizedDescription
            if let latestOrder = try? await spaceService.fetchModuleOrder(in: space) {
                moduleOrder = latestOrder
            }
            return false
        }
    }

    func handleFilesToggle(_ isEnabled: Bool) async {
        guard canManageModules else {
            filesEnabled = space.filesEnabled
            return
        }
        guard isEnabled != filesEnabled else { return }

        if !isEnabled {
            do {
                if try await spaceService.filesModuleHasContent(in: space) {
                    shouldConfirmHidingFiles = true
                    return
                }
            } catch {
                errorMessage = error.localizedDescription
                return
            }
        }

        await applyFilesToggle(isEnabled)
    }

    func confirmHideFiles() async {
        shouldConfirmHidingFiles = false
        await applyFilesToggle(false)
    }

    func cancelHideFiles() {
        shouldConfirmHidingFiles = false
    }

    private func applyFilesToggle(_ isEnabled: Bool) async {
        guard !isUpdatingFilesModule else { return }
        isUpdatingFilesModule = true
        defer { isUpdatingFilesModule = false }

        do {
            try await spaceService.setFilesEnabled(in: space, isEnabled: isEnabled)
            filesEnabled = isEnabled
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func handleEventsToggle(_ isEnabled: Bool) async {
        guard canManageModules else {
            eventsEnabled = space.eventsEnabled
            return
        }
        guard isEnabled != eventsEnabled else { return }
        await applyEventsToggle(isEnabled)
    }

    func handleAnnouncementsToggle(_ isEnabled: Bool) async {
        guard canManageModules else {
            announcementsEnabled = space.announcementsEnabled
            return
        }
        guard isEnabled != announcementsEnabled else { return }
        guard !isUpdatingAnnouncementsModule else { return }
        isUpdatingAnnouncementsModule = true
        defer { isUpdatingAnnouncementsModule = false }

        do {
            try await spaceService.setAnnouncementsEnabled(in: space, isEnabled: isEnabled)
            announcementsEnabled = isEnabled
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func handleRoomsToggle(_ isEnabled: Bool) async {
        guard canManageModules else { roomsEnabled = space.roomsEnabled; return }
        guard isEnabled != roomsEnabled, !isUpdatingRoomsModule else { return }
        isUpdatingRoomsModule = true
        defer { isUpdatingRoomsModule = false }
        do {
            try await spaceService.setRoomsEnabled(in: space, isEnabled: isEnabled)
            roomsEnabled = isEnabled
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func applyEventsToggle(_ isEnabled: Bool) async {
        guard !isUpdatingEventsModule else { return }
        isUpdatingEventsModule = true
        defer { isUpdatingEventsModule = false }

        do {
            try await spaceService.setEventsEnabled(in: space, isEnabled: isEnabled)
            eventsEnabled = isEnabled
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func handlePollsToggle(_ isEnabled: Bool) async {
        guard canManageModules else {
            pollsEnabled = space.pollsEnabled
            return
        }
        guard isEnabled != pollsEnabled else { return }
        await applyPollsToggle(isEnabled)
    }

    func handleListsToggle(_ isEnabled: Bool) async {
        guard canManageModules else { listsEnabled = space.listsEnabled; return }
        guard isEnabled != listsEnabled, !isUpdatingListsModule else { return }
        isUpdatingListsModule = true
        defer { isUpdatingListsModule = false }
        do {
            try await spaceService.setListsEnabled(in: space, isEnabled: isEnabled)
            listsEnabled = isEnabled
        } catch { errorMessage = error.localizedDescription }
    }
    func handleNotesToggle(_ isEnabled: Bool) async {
        guard canManageModules else { notesEnabled = space.notesEnabled; return }
        guard isEnabled != notesEnabled, !isUpdatingNotesModule else { return }
        isUpdatingNotesModule = true
        defer { isUpdatingNotesModule = false }
        do { try await spaceService.setNotesEnabled(in: space, isEnabled: isEnabled); notesEnabled = isEnabled }
        catch { errorMessage = error.localizedDescription }
    }

    private func applyPollsToggle(_ isEnabled: Bool) async {
        guard !isUpdatingPollsModule else { return }
        isUpdatingPollsModule = true
        defer { isUpdatingPollsModule = false }

        do {
            try await spaceService.setPollsEnabled(in: space, isEnabled: isEnabled)
            pollsEnabled = isEnabled
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private static func defaultEmoji(for space: Space) -> String {
        let trimmed = space.emoji.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "🏠" : trimmed
    }
}

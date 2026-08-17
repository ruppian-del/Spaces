import Foundation

struct SpaceDraft: Codable, Equatable {
    let spaceID: String
    let text: String
    let updatedAt: Date
    let spaceLinks: [SpaceLinkAttachment]
    let replyToMessageID: String?
    let replyToSenderName: String?
    let replyToType: String?
    let replyToPreview: String?
    let submittedQueuedMessageID: String?

    var previewText: String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? spaceLinks.first?.title : trimmed
    }
}

final class SpaceDraftStore {
    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func draft(for userID: String, spaceID: String) -> SpaceDraft? {
        loadDrafts(for: userID)[spaceID]
    }

    func save(_ draft: SpaceDraft, for userID: String) {
        var drafts = loadDrafts(for: userID)
        drafts[draft.spaceID] = draft
        persist(drafts, for: userID)
    }

    func clearDraft(for userID: String, spaceID: String) {
        var drafts = loadDrafts(for: userID)
        drafts.removeValue(forKey: spaceID)
        persist(drafts, for: userID)
    }

    func draftPreviews(for userID: String) -> [String: String] {
        loadDrafts(for: userID).compactMapValues { $0.previewText }
    }

    private func storageKey(for userID: String) -> String {
        "spaces.drafts.\(userID)"
    }

    private func loadDrafts(for userID: String) -> [String: SpaceDraft] {
        guard
            let data = defaults.data(forKey: storageKey(for: userID)),
            let drafts = try? decoder.decode([String: SpaceDraft].self, from: data)
        else {
            return [:]
        }
        return drafts
    }

    private func persist(_ drafts: [String: SpaceDraft], for userID: String) {
        if drafts.isEmpty {
            defaults.removeObject(forKey: storageKey(for: userID))
            return
        }

        guard let data = try? encoder.encode(drafts) else { return }
        defaults.set(data, forKey: storageKey(for: userID))
    }
}

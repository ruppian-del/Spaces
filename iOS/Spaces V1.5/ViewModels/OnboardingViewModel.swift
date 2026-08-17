import Combine
import Foundation
import SwiftUI

@MainActor
final class OnboardingViewModel: ObservableObject {
    enum Page: Int, CaseIterable, Identifiable {
        case welcome
        case spaces
        case pings
        case privacy
        case authentication
        case profile

        var id: Int { rawValue }
    }

    static let defaultEmoji = "🧑‍💻"

    @Published var currentPage: Page = .welcome
    @Published var displayName: String = ""
    @Published var emojiAvatar: String = defaultEmoji
    @Published var statusMessage: String = ""
    @Published var isShowingSplash = true

    var canContinueProfile: Bool {
        !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var displayEmoji: String {
        let trimmed = emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? Self.defaultEmoji : trimmed
    }

    var currentPageIndex: Int {
        currentPage.rawValue
    }

    var pageCount: Int {
        Page.allCases.count
    }

    var showsNextButton: Bool {
        currentPage != .profile
    }

    func advanceFromSplash() {
        withAnimation(.easeInOut(duration: 0.3)) {
            isShowingSplash = false
        }
    }

    func nextPage() {
        guard let nextPage = Page(rawValue: currentPage.rawValue + 1) else { return }
        withAnimation(.easeInOut(duration: 0.3)) {
            currentPage = nextPage
        }
    }

    func setPage(_ page: Page) {
        withAnimation(.easeInOut(duration: 0.3)) {
            currentPage = page
        }
    }

    func sanitizeEmojiInput() {
        let compact = emojiAvatar.trimmingCharacters(in: .whitespacesAndNewlines)

        if compact.isEmpty {
            emojiAvatar = ""
            return
        }

        if let firstCluster = compact.first {
            emojiAvatar = String(firstCluster)
        }
    }

    func applyAuthenticatedProfileDefaults(displayName: String?) {
        if self.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            self.displayName = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        }
    }

    func prepareForRequiredProfileCreation(displayName: String?) {
        applyAuthenticatedProfileDefaults(displayName: displayName)
        isShowingSplash = false
        currentPage = .profile
    }
}

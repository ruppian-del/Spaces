import Combine
import SwiftUI

@MainActor
final class CreateSpaceViewModel: ObservableObject {
    static let defaultEmoji = "🏠"

    @Published var name: String = ""
    @Published var emoji: String = CreateSpaceViewModel.defaultEmoji
    @Published var color: Color = .indigo
    @Published var description: String = SpaceTemplate.family.defaultSubtitle
    @Published var template: SpaceTemplate = .family
    @Published var enabledModules: [SpaceModule] = SpaceTemplate.family.defaultEnabledModules

    var isCreateEnabled: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var displayEmoji: String {
        let trimmed = emoji.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? Self.defaultEmoji : trimmed
    }

    var tintHex: String {
        color.hexString ?? "#4F46E5"
    }

    func sanitizeEmojiInput() {
        let compact = emoji.trimmingCharacters(in: .whitespacesAndNewlines)

        if compact.isEmpty {
            emoji = ""
            return
        }

        if let firstCluster = compact.first {
            emoji = String(firstCluster)
        }
    }

    func updateTemplate(_ template: SpaceTemplate) {
        let currentDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let previousDefault = self.template.defaultSubtitle
        self.template = template
        enabledModules = template.defaultEnabledModules

        if currentDescription.isEmpty || currentDescription == previousDefault {
            description = template.defaultSubtitle
        }
    }

    func isModuleEnabled(_ module: SpaceModule) -> Bool {
        enabledModules.contains(module)
    }

    func setModuleEnabled(_ module: SpaceModule, isEnabled: Bool) {
        guard template == .custom else { return }
        guard module != .settings else { return }
        guard module != .general || isEnabled else { return }

        if isEnabled {
            if !enabledModules.contains(module) {
                enabledModules.append(module)
            }
        } else {
            enabledModules.removeAll { $0 == module }
        }

        enabledModules = SpaceModule.configurableModules.filter { enabledModules.contains($0) }
    }
}

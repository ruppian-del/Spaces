import Foundation

enum MessageType: String, CaseIterable, Identifiable, Hashable {
    case text
    case image
    case video
    case meme
    case gif
    case screenshot
    case file

    var id: String { rawValue }

    var systemImageName: String {
        switch self {
        case .text: return "text.bubble"
        case .image: return "photo"
        case .video: return "play.rectangle.fill"
        case .meme: return "face.smiling.inverse"
        case .gif: return "sparkles.tv"
        case .screenshot: return "rectangle.on.rectangle"
        case .file: return "doc.fill"
        }
    }

    var displayName: String {
        rawValue.capitalized
    }

    var isPhotosModuleSupported: Bool {
        switch self {
        case .image, .video:
            return true
        case .text, .meme, .gif, .screenshot, .file:
            return false
        }
    }
}

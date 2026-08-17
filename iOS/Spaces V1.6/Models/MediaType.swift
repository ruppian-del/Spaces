import Foundation

enum MediaType: String, CaseIterable, Hashable {
    case photo
    case meme
    case gif
    case video
    case file
    case voice
    case profilePhoto
    case coverPhoto

    var defaultPlaceholderImageName: String {
        switch self {
        case .photo, .profilePhoto, .coverPhoto:
            return "photo"
        case .meme:
            return "face.smiling.inverse"
        case .gif:
            return "sparkles.tv"
        case .video:
            return "play.rectangle.fill"
        case .file:
            return "doc.fill"
        case .voice:
            return "waveform"
        }
    }
}

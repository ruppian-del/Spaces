import Foundation

struct GiphyAsset: Identifiable, Hashable {
    let id: String
    let title: String
    let previewURL: URL
    let originalURL: URL
    let mimeType: String
    let width: Int?
    let height: Int?

    var mediaCategory: String {
        mimeType == "image/gif" ? "gif" : "meme"
    }
}

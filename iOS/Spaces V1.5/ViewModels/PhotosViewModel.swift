import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class PhotosViewModel: ObservableObject {
    @Published private(set) var mediaItems: [SpaceMedia]
    @Published private(set) var isLoading = false
    @Published private(set) var canUploadMedia = false
    @Published var errorMessage: String?

    let space: Space
    private let spaceService: SpaceService
    private var listener: ListenerRegistration?

    init(space: Space, mediaItems: [SpaceMedia]? = nil, spaceService: SpaceService? = nil) {
        self.space = space
        self.spaceService = spaceService ?? SpaceService()
        self.mediaItems = mediaItems ?? MockData.photosMedia(for: space)
    }

    deinit {
        listener?.remove()
    }

    func startListeningIfNeeded() {
        guard listener == nil else { return }
        isLoading = true
        Task {
            canUploadMedia = await spaceService.canPerform(.uploadPhotosVideos, in: space)
        }
        listener = spaceService.listenToMessages(in: space) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let messages):
                self.mediaItems = messages.flatMap { message in
                    message.resolvedMediaItems.compactMap { media in
                        switch media.mediaType {
                        case .photo:
                            return media.mediaCategory == "photo" ? media : nil
                        case .video:
                            return media
                        default:
                            return nil
                        }
                    }
                }
                self.isLoading = false
                self.errorMessage = nil
            case .failure(let error):
                self.mediaItems = []
                self.isLoading = false
                self.errorMessage = error.localizedDescription
            }
        }
    }

    func uploadMedia(data: Data, mimeType: String, isVideo: Bool) async {
        guard canUploadMedia else { return }
        do {
            if isVideo {
                _ = try await spaceService.sendVideoMessage(
                    in: space,
                    videoData: data,
                    caption: nil,
                    mimeType: mimeType
                )
            } else {
                _ = try await spaceService.sendImageMessage(
                    in: space,
                    imageData: data,
                    caption: nil,
                    mediaCategory: "photo"
                )
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

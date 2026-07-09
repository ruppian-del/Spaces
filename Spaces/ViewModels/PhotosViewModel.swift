import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class PhotosViewModel: ObservableObject {
    @Published private(set) var mediaItems: [SpaceMedia]
    @Published private(set) var isLoading = false
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
        listener = spaceService.listenToMessages(in: space) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let messages):
                self.mediaItems = messages.compactMap { message in
                    guard
                        message.type == .image,
                        let media = message.media,
                        media.mediaCategory == "photo"
                    else {
                        return nil
                    }
                    return media
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
}

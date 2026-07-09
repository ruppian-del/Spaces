import AVKit
import SwiftUI
import UIKit

struct MediaViewerPlaceholderView: View {
    let space: Space
    let media: SpaceMedia

    @Environment(\.dismiss) private var dismiss
    private let encryptedMediaService = EncryptedMediaService()

    @State private var image: UIImage?
    @State private var videoURL: URL?
    @State private var player: AVPlayer?
    @State private var shareURL: URL?
    @State private var isShowingShareSheet = false
    @State private var isLoading = true
    @State private var feedbackMessage: String?
    @State private var isShowingFeedback = false
    @State private var verticalDismissOffset: CGFloat = 0

    var body: some View {
        ZStack(alignment: .top) {
            Color.black
                .ignoresSafeArea()

            Group {
                if media.type == .video, let player {
                    NativeVideoPlayerView(player: player)
                        .ignoresSafeArea()
                } else if let image {
                    ZoomableImageViewer(image: image)
                        .offset(y: verticalDismissOffset)
                        .gesture(
                            DragGesture(minimumDistance: 10)
                                .onChanged { value in
                                    guard abs(value.translation.height) > abs(value.translation.width) else { return }
                                    verticalDismissOffset = max(0, value.translation.height)
                                }
                                .onEnded { value in
                                    if value.translation.height > 120 {
                                        dismiss()
                                    } else {
                                        withAnimation(.spring(response: 0.25, dampingFraction: 0.82)) {
                                            verticalDismissOffset = 0
                                        }
                                    }
                                }
                        )
                } else if isLoading {
                    ProgressView()
                        .tint(.white)
                } else {
                    VStack(spacing: 14) {
                        Image(systemName: media.type.systemImageName)
                            .font(.system(size: 54, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.9))
                        Text(media.type == .video ? "Unable to load video" : "Unable to load image")
                            .font(.headline)
                            .foregroundStyle(.white.opacity(0.9))
                    }
                }
            }

            viewerOverlay
        }
        .task(id: media.id) {
            await loadMedia()
        }
        .sheet(isPresented: $isShowingShareSheet) {
            if let shareURL {
                ShareSheet(items: [shareURL])
            }
        }
        .alert(media.type == .video ? "Save Video" : "Save Image", isPresented: $isShowingFeedback) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(feedbackMessage ?? "")
        }
        .onDisappear {
            player?.pause()
            player = nil
            if let videoURL {
                try? FileManager.default.removeItem(at: videoURL)
            }
            if let shareURL, shareURL != videoURL {
                try? FileManager.default.removeItem(at: shareURL)
            }
        }
    }

    private var viewerOverlay: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(.white.opacity(0.95))
                }
                .buttonStyle(.plain)

                Spacer()

                Button("Save") {
                    saveMedia()
                }
                .foregroundStyle(.white)
                .disabled((image == nil && videoURL == nil) || isLoading)

                Button("Share") {
                    shareMedia()
                }
                .foregroundStyle(.white)
                .disabled((image == nil && videoURL == nil) || isLoading)
            }
            .font(.headline)
            .padding(.horizontal, 18)
            .padding(.top, 16)

            Spacer()

            VStack(alignment: .leading, spacing: 8) {
                Text(media.senderName)
                    .font(.headline)
                    .foregroundStyle(.white)
                Text(media.timestamp)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.78))
                if let caption = media.caption, !caption.isEmpty {
                    Text(caption)
                        .font(.body)
                        .foregroundStyle(.white.opacity(0.92))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(
                LinearGradient(
                    colors: [.clear, .black.opacity(0.72)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
        .ignoresSafeArea(edges: .top)
    }

    private func loadMedia() async {
        do {
            if media.type == .video {
                let url = try await encryptedMediaService.temporaryMediaURL(for: media)
                await MainActor.run {
                    videoURL = url
                    player = AVPlayer(url: url)
                    isLoading = false
                }
            } else {
                let data = try await encryptedMediaService.fullData(for: media)
                await MainActor.run {
                    image = UIImage(data: data)
                    isLoading = false
                }
            }
        } catch {
            await MainActor.run {
                isLoading = false
            }
        }
    }

    private func saveMedia() {
        guard !isLoading else {
            feedbackMessage = media.type == .video ? "Video is still loading." : "Image is still loading."
            isShowingFeedback = true
            return
        }

        Task {
            do {
                if media.type == .video {
                    guard let videoURL else {
                        feedbackMessage = "Video is still loading."
                        isShowingFeedback = true
                        return
                    }
                    try await encryptedMediaService.saveVideoToPhotos(fileURL: videoURL)
                    feedbackMessage = "Video saved to Photos."
                } else if let image {
                    try await encryptedMediaService.saveImageToPhotos(image)
                    feedbackMessage = "Image saved to Photos."
                } else {
                    feedbackMessage = "Image is still loading."
                }
            } catch {
                feedbackMessage = error.localizedDescription
            }
            isShowingFeedback = true
        }
    }

    private func shareMedia() {
        guard !isLoading else { return }

        Task {
            do {
                if media.type == .video, let videoURL {
                    shareURL = videoURL
                } else if let image, let imageData = image.jpegData(compressionQuality: 0.95) {
                    shareURL = try encryptedMediaService.shareURL(
                        for: imageData,
                        suggestedFileName: media.id,
                        pathExtension: "jpg"
                    )
                }
                if shareURL != nil {
                    isShowingShareSheet = true
                }
            } catch {
                feedbackMessage = error.localizedDescription
                isShowingFeedback = true
            }
        }
    }
}

private struct ZoomableImageViewer: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.delegate = context.coordinator
        scrollView.maximumZoomScale = 4
        scrollView.minimumZoomScale = 1
        scrollView.bouncesZoom = true
        scrollView.showsVerticalScrollIndicator = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.backgroundColor = .black

        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(imageView)
        context.coordinator.imageView = imageView

        let doubleTapRecognizer = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTapRecognizer.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTapRecognizer)
        context.coordinator.scrollView = scrollView

        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor)
        ])

        return scrollView
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?
        weak var scrollView: UIScrollView?

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            imageView
        }

        @objc func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard let scrollView else { return }

            if scrollView.zoomScale > scrollView.minimumZoomScale {
                scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
                return
            }

            let targetZoomScale = min(scrollView.maximumZoomScale, 2.5)
            let location = recognizer.location(in: imageView)
            let width = scrollView.bounds.width / targetZoomScale
            let height = scrollView.bounds.height / targetZoomScale
            let zoomRect = CGRect(
                x: location.x - (width / 2),
                y: location.y - (height / 2),
                width: width,
                height: height
            )
            scrollView.zoom(to: zoomRect, animated: true)
        }
    }
}

private struct NativeVideoPlayerView: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.showsPlaybackControls = true
        controller.entersFullScreenWhenPlaybackBegins = false
        controller.exitsFullScreenWhenPlaybackEnds = false
        controller.view.backgroundColor = .black
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {
        uiViewController.player = player
    }
}

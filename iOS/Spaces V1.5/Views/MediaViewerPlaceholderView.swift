import AVKit
import SwiftUI
import UIKit
import WebKit

struct MediaViewerPlaceholderView: View {
    let space: Space
    let media: SpaceMedia

    @Environment(\.dismiss) private var dismiss
    private let encryptedMediaService = EncryptedMediaService()

    @State private var image: UIImage?
    @State private var videoURL: URL?
    @State private var gifURL: URL?
    @State private var galleryImages: [String: UIImage] = [:]
    @State private var player: AVPlayer?
    @State private var shareURL: URL?
    @State private var isShowingShareSheet = false
    @State private var isLoading = true
    @State private var feedbackMessage: String?
    @State private var isShowingFeedback = false
    @State private var verticalDismissOffset: CGFloat = 0
    @State private var selectedGalleryIndex = 0

    var body: some View {
        ZStack {
            Color.black
                .ignoresSafeArea()

            Group {
                if isGalleryMode {
                    galleryContent
                } else if media.type == .video, let player {
                    NativeVideoPlayerView(player: player)
                        .ignoresSafeArea()
                } else if isGIF, let gifURL {
                    AnimatedGIFView(fileURL: gifURL)
                        .ignoresSafeArea()
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
                        Text(media.type == .video ? "Unable to load video" : (isGIF ? "Unable to load GIF" : "Unable to load image"))
                            .font(.headline)
                            .foregroundStyle(.white.opacity(0.9))
                    }
                }
            }
        }
        .safeAreaInset(edge: .top) {
            topToolbar
        }
        .safeAreaInset(edge: .bottom) {
            bottomMetadata
        }
        .task(id: media.id) {
            selectedGalleryIndex = initialGalleryIndex
            await loadMedia()
        }
        .onChange(of: selectedGalleryIndex) { _ in
            guard isGalleryMode else { return }
            if galleryImages[currentGalleryMedia.id] == nil {
                isLoading = true
            }
            Task {
                await loadGalleryImageIfNeeded(for: currentGalleryMedia)
            }
        }
        .sheet(isPresented: $isShowingShareSheet) {
            if let shareURL {
                ShareSheet(items: [shareURL])
            }
        }
        .alert(media.type == .video ? "Save Video" : (isGIF ? "Save GIF" : "Save Image"), isPresented: $isShowingFeedback) {
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
            if let gifURL {
                try? FileManager.default.removeItem(at: gifURL)
            }
            if let shareURL, shareURL != videoURL {
                try? FileManager.default.removeItem(at: shareURL)
            }
        }
    }

    private var galleryContent: some View {
        TabView(selection: $selectedGalleryIndex) {
            ForEach(Array(galleryMediaItems.enumerated()), id: \.element.id) { index, item in
                Group {
                    if let galleryImage = galleryImages[item.id] {
                        ZoomableImageViewer(image: galleryImage)
                            .offset(y: verticalDismissOffset)
                    } else if isLoading && index == selectedGalleryIndex {
                        ProgressView()
                            .tint(.white)
                    } else {
                        ProgressView()
                            .tint(.white)
                    }
                }
                .tag(index)
                .ignoresSafeArea()
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
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
    }

    private var topToolbar: some View {
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
        .padding(.vertical, 16)
        .background(
            LinearGradient(
                colors: [.black.opacity(0.5), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var bottomMetadata: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(currentGalleryMedia.senderName)
                .font(.headline)
                .foregroundStyle(.white)
            Text(currentGalleryMedia.timestamp)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.78))
            if let caption = currentGalleryMedia.caption, !caption.isEmpty {
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

    private func loadMedia() async {
        if isGalleryMode {
            await MainActor.run {
                image = nil
                videoURL = nil
                gifURL = nil
                isLoading = true
            }
            await loadGalleryImageIfNeeded(for: currentGalleryMedia)
            return
        }

        do {
            if media.type == .video {
                let url = try await encryptedMediaService.temporaryMediaURL(for: media)
                await MainActor.run {
                    videoURL = url
                    player = AVPlayer(url: url)
                    isLoading = false
                }
            } else if isGIF {
                let url = try await encryptedMediaService.temporaryMediaURL(for: media)
                await MainActor.run {
                    gifURL = url
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

    private func loadGalleryImageIfNeeded(for media: SpaceMedia) async {
        guard galleryImages[media.id] == nil else {
            await MainActor.run {
                isLoading = false
            }
            return
        }

        await MainActor.run {
            isLoading = true
        }

        do {
            let data = try await encryptedMediaService.fullData(for: media)
            let resolvedImage = UIImage(data: data)
            await MainActor.run {
                if let resolvedImage {
                    galleryImages[media.id] = resolvedImage
                }
                isLoading = false
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
                if isGalleryMode {
                    guard let galleryImage = galleryImages[currentGalleryMedia.id] else {
                        feedbackMessage = "Image is still loading."
                        isShowingFeedback = true
                        return
                    }
                    try await encryptedMediaService.saveImageToPhotos(galleryImage)
                    feedbackMessage = "Image saved to Photos."
                } else if media.type == .video {
                    guard let videoURL else {
                        feedbackMessage = "Video is still loading."
                        isShowingFeedback = true
                        return
                    }
                    try await encryptedMediaService.saveVideoToPhotos(fileURL: videoURL)
                    feedbackMessage = "Video saved to Photos."
                } else if isGIF {
                    guard let gifURL else {
                        feedbackMessage = "GIF is still loading."
                        isShowingFeedback = true
                        return
                    }
                    try await encryptedMediaService.saveImageFileToPhotos(fileURL: gifURL)
                    feedbackMessage = "GIF saved to Photos."
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
                if isGalleryMode, let galleryImage = galleryImages[currentGalleryMedia.id], let imageData = galleryImage.jpegData(compressionQuality: 0.95) {
                    shareURL = try encryptedMediaService.shareURL(
                        for: imageData,
                        suggestedFileName: currentGalleryMedia.id,
                        pathExtension: "jpg"
                    )
                } else if media.type == .video, let videoURL {
                    shareURL = videoURL
                } else if isGIF, let gifURL {
                    shareURL = gifURL
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

    private var isGIF: Bool {
        media.type == .gif || media.mediaType == .gif || media.metadata?.mimeType.lowercased() == "image/gif"
    }

    private var galleryMediaItems: [SpaceMedia] {
        let items = media.galleryItems ?? [media]
        return items.isEmpty ? [media] : items
    }

    private var initialGalleryIndex: Int {
        max(0, min(media.gallerySelectedIndex, galleryMediaItems.count - 1))
    }

    private var currentGalleryMedia: SpaceMedia {
        let items = galleryMediaItems
        guard items.indices.contains(selectedGalleryIndex) else {
            return items[initialGalleryIndex]
        }
        return items[selectedGalleryIndex]
    }

    private var isGalleryMode: Bool {
        let items = galleryMediaItems
        return items.count > 1 && items.allSatisfy { $0.type != .video && $0.mediaType != .video && $0.type != .gif && $0.mediaType != .gif }
    }
}

enum AnimatedGIFContentMode {
    case fit
    case fill
}

struct AnimatedGIFView: UIViewRepresentable {
    let fileURL: URL
    var contentMode: AnimatedGIFContentMode = .fit

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        webView.scrollView.isScrollEnabled = false
        webView.isUserInteractionEnabled = false
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let objectFit = contentMode == .fill ? "cover" : "contain"
        let html = """
        <!doctype html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
            background: transparent;
        }
        body {
            display: flex;
            align-items: center;
            justify-content: center;
        }
        img {
            width: 100%;
            height: 100%;
            object-fit: \(objectFit);
            display: block;
            pointer-events: none;
            user-select: none;
            -webkit-user-select: none;
        }
        </style>
        </head>
        <body>
            <img src="\(fileURL.lastPathComponent)" alt="GIF" />
        </body>
        </html>
        """
        webView.loadHTMLString(html, baseURL: fileURL.deletingLastPathComponent())
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

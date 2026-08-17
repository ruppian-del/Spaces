import SwiftUI
import UIKit
import GiphyUISDK

struct PickedRemoteComposerMedia {
    let data: Data
    let previewImageData: Data
    let mimeType: String
    let mediaCategory: String
    let isVideo: Bool
}

struct GiphyPickerView: View {
    let onMediaPicked: (PickedRemoteComposerMedia?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?

    var body: some View {
        GiphyPickerControllerBridge(
            onDismiss: {
                dismiss()
            },
            onError: { message in
                errorMessage = message
            },
            onMediaPicked: { selection in
                print("[GIF] Selected")
                dismiss()
                onMediaPicked(selection)
            }
        )
        .ignoresSafeArea()
        .alert("Unable to send GIF", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }
}

private struct GiphyPickerControllerBridge: UIViewControllerRepresentable {
    let onDismiss: () -> Void
    let onError: (String) -> Void
    let onMediaPicked: (PickedRemoteComposerMedia) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onDismiss: onDismiss, onError: onError, onMediaPicked: onMediaPicked)
    }

    func makeUIViewController(context: Context) -> GiphyViewController {
        let controller = GiphyViewController()
        controller.delegate = context.coordinator
        controller.mediaTypeConfig = [.gifs, .stickers, .recents]
        controller.theme = GPHTheme(type: .automatic)
        controller.showConfirmationScreen = false
        controller.rating = .ratedPG13
        controller.shouldLocalizeSearch = true
        controller.renditionType = .fixedWidth
        return controller
    }

    func updateUIViewController(_ uiViewController: GiphyViewController, context: Context) {}

    final class Coordinator: NSObject, GiphyDelegate {
        private let onDismiss: () -> Void
        private let onError: (String) -> Void
        private let onMediaPicked: (PickedRemoteComposerMedia) -> Void

        init(
            onDismiss: @escaping () -> Void,
            onError: @escaping (String) -> Void,
            onMediaPicked: @escaping (PickedRemoteComposerMedia) -> Void
        ) {
            self.onDismiss = onDismiss
            self.onError = onError
            self.onMediaPicked = onMediaPicked
        }

        func didDismiss(controller: GiphyViewController?) {
            onDismiss()
        }

        func didSelectMedia(giphyViewController: GiphyViewController, media: GPHMedia) {
            Task {
                do {
                    let selection = try await Self.selection(from: media)
                    await MainActor.run {
                        print("[GIF] Selected")
                        onMediaPicked(selection)
                    }
                } catch {
                    await MainActor.run {
                        onError(error.localizedDescription)
                    }
                }
            }
        }

        private static func selection(from media: GPHMedia) async throws -> PickedRemoteComposerMedia {
            guard
                let originalURLString = media.url(rendition: .original, fileType: .gif)
                    ?? media.url(rendition: .fixedWidth, fileType: .gif),
                let originalURL = URL(string: originalURLString),
                let previewURLString = media.url(rendition: .fixedWidth, fileType: .gif)
                    ?? media.url(rendition: .downsized, fileType: .gif)
                    ?? media.url(rendition: .original, fileType: .gif),
                let previewURL = URL(string: previewURLString)
            else {
                throw GiphyPickerError.missingAssetURL
            }

            async let originalData = URLSession.shared.data(from: originalURL)
            async let previewData = URLSession.shared.data(from: previewURL)
            let (original, _) = try await originalData
            let (preview, _) = try await previewData

            return PickedRemoteComposerMedia(
                data: original,
                previewImageData: preview,
                mimeType: "image/gif",
                mediaCategory: "gif",
                isVideo: false
            )
        }
    }
}

private enum GiphyPickerError: LocalizedError {
    case missingAssetURL

    var errorDescription: String? {
        switch self {
        case .missingAssetURL:
            return "That GIF could not be prepared for sending."
        }
    }
}

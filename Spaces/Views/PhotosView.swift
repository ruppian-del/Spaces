import AVFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct PhotosView: View {
    @StateObject private var viewModel: PhotosViewModel
    @State private var selectedMedia: SpaceMedia?
    @State private var isShowingMediaPicker = false

    private let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12)
    ]

    init(space: Space) {
        _viewModel = StateObject(wrappedValue: PhotosViewModel(space: space))
    }

    var body: some View {
        ScrollView {
            if viewModel.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(24)
            } else if viewModel.mediaItems.isEmpty {
                emptyState
                    .padding(20)
            } else {
                GeometryReader { proxy in
                    let contentWidth = max(proxy.size.width - 32, 0)
                    let cellWidth = max((contentWidth - 12) / 2, 0)

                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(viewModel.mediaItems) { media in
                            Button {
                                selectedMedia = media
                            } label: {
                                mediaTile(media)
                                    .frame(width: cellWidth, alignment: .topLeading)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .frame(width: contentWidth, alignment: .topLeading)
                    .padding(.horizontal, 16)
                }
                .frame(height: photosGridHeight(for: viewModel.mediaItems.count))
            }
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Photos")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            viewModel.startListeningIfNeeded()
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    isShowingMediaPicker = true
                } label: {
                    Image(systemName: "plus")
                }
                .disabled(!viewModel.canUploadMedia)
            }
        }
        .sheet(isPresented: $isShowingMediaPicker) {
            PhotosModuleMediaPicker { pickedMedia in
                guard let pickedMedia else { return }
                Task {
                    await viewModel.uploadMedia(
                        data: pickedMedia.data,
                        mimeType: pickedMedia.mimeType,
                        isVideo: pickedMedia.isVideo
                    )
                }
            }
        }
        .fullScreenCover(item: $selectedMedia) { media in
            MediaViewerPlaceholderView(space: viewModel.space, media: media)
        }
        .alert("Photos", isPresented: Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private func mediaTile(_ media: SpaceMedia) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            PhotosThumbnailView(media: media, tintHex: viewModel.space.tintHex)
                .frame(maxWidth: .infinity)
                .frame(height: 156)
                .clipped()

            VStack(alignment: .leading, spacing: 4) {
                Text(media.senderName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Text(media.timestamp)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if let caption = media.caption, !caption.isEmpty {
                    Text(caption)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private func photosGridHeight(for itemCount: Int) -> CGFloat {
        let rows = max(Int(ceil(Double(itemCount) / 2.0)), 0)
        guard rows > 0 else { return 0 }
        let tileHeight: CGFloat = 252
        let rowSpacing: CGFloat = 12
        return CGFloat(rows) * tileHeight + CGFloat(max(rows - 1, 0)) * rowSpacing
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "photo.on.rectangle.angled")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Color(hex: viewModel.space.tintHex))

            Text("No Photos Yet")
                .font(.headline)

            Text("Shared photos and videos for \(viewModel.space.name) will appear here.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

private struct PickedPhotosModuleMedia {
    let data: Data
    let mimeType: String
    let isVideo: Bool
}

private struct PhotosModuleMediaPicker: UIViewControllerRepresentable {
    let onMediaPicked: (PickedPhotosModuleMedia?) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator {
        Coordinator(onMediaPicked: onMediaPicked, dismiss: dismiss.callAsFunction)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = 1

        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onMediaPicked: (PickedPhotosModuleMedia?) -> Void
        private let dismiss: () -> Void

        init(onMediaPicked: @escaping (PickedPhotosModuleMedia?) -> Void, dismiss: @escaping () -> Void) {
            self.onMediaPicked = onMediaPicked
            self.dismiss = dismiss
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let result = results.first else {
                dismiss()
                onMediaPicked(nil)
                return
            }

            if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                result.itemProvider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                    DispatchQueue.main.async {
                        self.dismiss()
                        guard let data else {
                            self.onMediaPicked(nil)
                            return
                        }
                        self.onMediaPicked(
                            PickedPhotosModuleMedia(
                                data: data,
                                mimeType: Self.mimeType(for: result.itemProvider, fallback: .image),
                                isVideo: false
                            )
                        )
                    }
                }
            } else if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                    guard let url, let data = try? Data(contentsOf: url) else {
                        DispatchQueue.main.async {
                            self.dismiss()
                            self.onMediaPicked(nil)
                        }
                        return
                    }

                    DispatchQueue.main.async {
                        self.dismiss()
                        self.onMediaPicked(
                            PickedPhotosModuleMedia(
                                data: data,
                                mimeType: Self.mimeType(for: result.itemProvider, fallback: .movie),
                                isVideo: true
                            )
                        )
                    }
                }
            } else {
                dismiss()
                onMediaPicked(nil)
            }
        }

        private static func mimeType(for provider: NSItemProvider, fallback: UTType) -> String {
            for identifier in provider.registeredTypeIdentifiers {
                if let type = UTType(identifier), let mimeType = type.preferredMIMEType {
                    return mimeType
                }
            }
            return fallback.preferredMIMEType ?? "application/octet-stream"
        }
    }
}

private struct PhotosThumbnailView: View {
    let media: SpaceMedia
    let tintHex: String

    private let encryptedMediaService = EncryptedMediaService()
    @State private var image: UIImage?
    @State private var isLoading = true

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color(hex: tintHex).opacity(0.12))

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
            } else if isLoading {
                ProgressView()
                    .tint(Color(hex: tintHex))
            } else {
                Image(systemName: "photo")
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(Color(hex: tintHex))
            }
        }
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .task(id: media.id) {
            guard image == nil else { return }
            do {
                let data = try await encryptedMediaService.thumbnailData(for: media)
                await MainActor.run {
                    image = UIImage(data: data)
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    isLoading = false
                }
            }
        }
    }
}

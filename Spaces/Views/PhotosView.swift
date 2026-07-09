import SwiftUI

struct PhotosView: View {
    @StateObject private var viewModel: PhotosViewModel
    @State private var selectedMedia: SpaceMedia?

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
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(viewModel.mediaItems) { media in
                        Button {
                            selectedMedia = media
                        } label: {
                            mediaTile(media)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(16)
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
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .fullScreenCover(item: $selectedMedia) { media in
            MediaViewerPlaceholderView(space: viewModel.space, media: media)
        }
    }

    private func mediaTile(_ media: SpaceMedia) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            PhotosThumbnailView(media: media, tintHex: viewModel.space.tintHex)
                .frame(height: 156)

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
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
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
            } else if isLoading {
                ProgressView()
                    .tint(Color(hex: tintHex))
            } else {
                Image(systemName: "photo")
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(Color(hex: tintHex))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
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

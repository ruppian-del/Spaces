import SwiftUI
import UniformTypeIdentifiers

struct FilesView: View {
    @StateObject private var viewModel: FilesViewModel
    @State private var isImporterPresented = false
    @State private var renameDraft = ""
    @State private var hasOpenedInitialFile = false
    private let initialFileID: String?

    init(space: Space, initialFileID: String? = nil) {
        _viewModel = StateObject(wrappedValue: FilesViewModel(space: space))
        self.initialFileID = initialFileID
    }

    var body: some View {
        List {
            if viewModel.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
                    .listRowBackground(Color.clear)
            } else if viewModel.isEmpty {
                emptyState
                    .listRowInsets(EdgeInsets(top: 20, leading: 20, bottom: 20, trailing: 20))
                    .listRowBackground(Color.clear)
            } else {
                if !viewModel.folders.isEmpty {
                    Section("Folders") {
                        ForEach(viewModel.folders) { folder in
                            FolderRow(folder: folder, tintHex: viewModel.space.tintHex)
                        }
                    }
                }

                if !viewModel.files.isEmpty {
                    Section("Files") {
                        ForEach(viewModel.files) { file in
                            fileRow(for: file)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Files")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $viewModel.searchText, prompt: "Search files")
        .task {
            viewModel.startListeningIfNeeded()
        }
        .onChange(of: viewModel.files, perform: handleInitialFileSelection)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Picker("Sort By", selection: $viewModel.sortOption) {
                        ForEach(FilesViewModel.SortOption.allCases) { option in
                            Text(option.rawValue).tag(option)
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down.circle")
                }
            }

            ToolbarItem(placement: .primaryAction) {
                Button {
                    isImporterPresented = true
                } label: {
                    Image(systemName: "plus")
                }
                .disabled(!viewModel.canUploadFiles)
            }
        }
        .fileImporter(
            isPresented: $isImporterPresented,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                Task {
                    await viewModel.uploadFile(from: url)
                }
            case .failure(let error):
                viewModel.errorMessage = error.localizedDescription
            }
        }
        .fileExporter(
            isPresented: exportBinding,
            document: viewModel.exportPayload?.document,
            contentType: viewModel.exportPayload?.contentType ?? .data,
            defaultFilename: viewModel.exportPayload?.defaultFilename ?? "Spaces File"
        ) { result in
            if case .failure(let error) = result {
                viewModel.errorMessage = error.localizedDescription
            }
            viewModel.clearExportPayload()
        }
        .sheet(item: $viewModel.selectedMedia) { media in
            MediaViewerPlaceholderView(space: viewModel.space, media: media)
        }
        .sheet(item: Binding(
            get: {
                viewModel.previewDocumentURL.map { PreviewDocument(url: $0) }
            },
            set: { item in
                if item == nil {
                    viewModel.clearPreviewDocument()
                }
            }
        )) { item in
            NavigationView {
                FilePreviewView(fileURL: item.url)
                    .navigationTitle(item.url.lastPathComponent)
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
        .sheet(item: Binding(
            get: {
                viewModel.shareURL.map { ShareableURL(url: $0) }
            },
            set: { item in
                if item == nil {
                    viewModel.clearShareURL()
                }
            }
        )) { item in
            ShareSheet(items: [item.url])
        }
        .alert("Files", isPresented: errorAlertBinding) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .alert("Rename File", isPresented: renameAlertBinding) {
            TextField("File name", text: $renameDraft)
            Button("Cancel", role: .cancel) {
                viewModel.renameTargetFile = nil
            }
            Button("Save") {
                guard let target = viewModel.renameTargetFile else { return }
                Task {
                    await viewModel.rename(target, to: renameDraft)
                }
            }
        } message: {
            Text("Update the file name without changing the encrypted file itself.")
        }
        .alert("Delete this file?", isPresented: deleteAlertBinding) {
            Button("Cancel", role: .cancel) {
                viewModel.pendingDeleteFile = nil
            }
            Button("Delete", role: .destructive) {
                Task {
                    await viewModel.deletePendingFile()
                }
            }
        } message: {
            Text("This will hide the file from the Space. The encrypted upload will remain in storage for now.")
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "folder")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Color(hex: viewModel.space.tintHex))

            Text("No Files Yet")
                .font(.headline)

            Text("Upload PDFs, documents, photos, videos, audio, archives, and other shared files for \(viewModel.space.name).")
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

    private var errorAlertBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { isPresented in
                if !isPresented {
                    viewModel.errorMessage = nil
                }
            }
        )
    }

    private var renameAlertBinding: Binding<Bool> {
        Binding(
            get: { viewModel.renameTargetFile != nil },
            set: { isPresented in
                if !isPresented {
                    viewModel.renameTargetFile = nil
                }
            }
        )
    }

    private var deleteAlertBinding: Binding<Bool> {
        Binding(
            get: { viewModel.pendingDeleteFile != nil },
            set: { isPresented in
                if !isPresented {
                    viewModel.pendingDeleteFile = nil
                }
            }
        )
    }

    private var exportBinding: Binding<Bool> {
        Binding(
            get: { viewModel.exportPayload != nil },
            set: { isPresented in
                if !isPresented {
                    viewModel.clearExportPayload()
                }
            }
        )
    }

    private func handleInitialFileSelection(_ files: [SpaceFileItem]) {
        guard let initialFileID else { return }
        guard !hasOpenedInitialFile else { return }
        guard let file = files.first(where: { $0.id == initialFileID }) else { return }
        hasOpenedInitialFile = true
        Task {
            await viewModel.open(file)
        }
    }

    @ViewBuilder
    private func fileRow(for file: SpaceFileItem) -> some View {
        FileRow(file: file, tintHex: viewModel.space.tintHex)
            .contentShape(Rectangle())
            .onTapGesture {
                Task {
                    await viewModel.open(file)
                }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                fileSwipeActions(for: file)
            }
            .contextMenu {
                fileContextMenu(for: file)
            }
    }

    @ViewBuilder
    private func fileSwipeActions(for file: SpaceFileItem) -> some View {
        Button {
            Task {
                await viewModel.share(file)
            }
        } label: {
            Label("Share", systemImage: "square.and.arrow.up")
        }
        .tint(.blue)

        Button {
            Task {
                await viewModel.prepareDownload(file)
            }
        } label: {
            Label("Download", systemImage: "arrow.down.circle")
        }
        .tint(.teal)
    }

    @ViewBuilder
    private func fileContextMenu(for file: SpaceFileItem) -> some View {
        Button {
            Task {
                await viewModel.open(file)
            }
        } label: {
            Label("Open", systemImage: "arrow.up.forward.app")
        }

        Button {
            Task {
                await viewModel.prepareDownload(file)
            }
        } label: {
            Label("Download", systemImage: "arrow.down.circle")
        }

        Button {
            Task {
                await viewModel.share(file)
            }
        } label: {
            Label("Share", systemImage: "square.and.arrow.up")
        }

        if viewModel.canManage(file) {
            Button {
                renameDraft = file.name
                viewModel.renameTargetFile = file
            } label: {
                Label("Rename", systemImage: "pencil")
            }

            Button(role: .destructive) {
                viewModel.pendingDeleteFile = file
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }
}

private struct FolderRow: View {
    let folder: SpaceFolder
    let tintHex: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "folder.fill")
                .foregroundStyle(Color(hex: tintHex))
                .font(.headline)

            VStack(alignment: .leading, spacing: 4) {
                Text(folder.name)
                    .font(.headline)
                Text("Created by \(folder.createdBy)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Text(folder.timestamp)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

private struct FileRow: View {
    let file: SpaceFileItem
    let tintHex: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: file.iconName)
                .foregroundStyle(Color(hex: tintHex))
                .font(.headline)

                VStack(alignment: .leading, spacing: 4) {
                    Text(file.name)
                        .font(.headline)
                        .lineLimit(2)
                    Text("\(file.uploadedByName) • \(file.typeDescription) • \(file.sizeDescription)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

            Spacer(minLength: 8)

            Text(file.timestamp)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

private struct PreviewDocument: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

private struct ShareableURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

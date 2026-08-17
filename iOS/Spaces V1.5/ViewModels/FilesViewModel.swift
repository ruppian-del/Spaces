import Combine
import FirebaseFirestore
import Foundation
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class FilesViewModel: ObservableObject {
    enum SortOption: String, CaseIterable, Identifiable {
        case dateNewest = "Date"
        case name = "Name"
        case size = "Size"
        case type = "Type"

        var id: String { rawValue }
    }

    struct ExportPayload {
        let document: ExportFileDocument
        let defaultFilename: String
        let contentType: UTType
    }

    @Published private(set) var folders: [SpaceFolder] = []
    @Published private(set) var allFiles: [SpaceFileItem] = []
    @Published private(set) var isLoading = false
    @Published private(set) var currentUserID: String?
    @Published private(set) var canManageAllFiles = false
    @Published var errorMessage: String?
    @Published var searchText = ""
    @Published var sortOption: SortOption = .dateNewest
    @Published var selectedMedia: SpaceMedia?
    @Published var previewDocumentURL: URL?
    @Published var shareURL: URL?
    @Published var exportPayload: ExportPayload?
    @Published var renameTargetFile: SpaceFileItem?
    @Published var pendingDeleteFile: SpaceFileItem?
    @Published private(set) var canUploadFiles = false

    let space: Space
    private let spaceService: SpaceService
    private var folderListener: ListenerRegistration?
    private var fileListener: ListenerRegistration?
    private var hasLoadedFolders = false
    private var hasLoadedFiles = false

    init(space: Space, spaceService: SpaceService? = nil) {
        self.space = space
        self.spaceService = spaceService ?? SpaceService()
    }

    deinit {
        folderListener?.remove()
        fileListener?.remove()
    }

    var files: [SpaceFileItem] {
        let trimmedQuery = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = allFiles.filter { file in
            trimmedQuery.isEmpty || file.name.localizedCaseInsensitiveContains(trimmedQuery)
        }

        switch sortOption {
        case .dateNewest:
            return filtered.sorted { lhs, rhs in
                (lhs.createdAt ?? .distantPast) > (rhs.createdAt ?? .distantPast)
            }
        case .name:
            return filtered.sorted { lhs, rhs in
                lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
        case .size:
            return filtered.sorted { lhs, rhs in
                lhs.sizeBytes > rhs.sizeBytes
            }
        case .type:
            return filtered.sorted { lhs, rhs in
                if lhs.typeDescription == rhs.typeDescription {
                    return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
                }
                return lhs.typeDescription.localizedCaseInsensitiveCompare(rhs.typeDescription) == .orderedAscending
            }
        }
    }

    var isEmpty: Bool {
        !isLoading && files.isEmpty && folders.isEmpty
    }

    func startListeningIfNeeded() {
        guard folderListener == nil, fileListener == nil else { return }
        isLoading = true
        currentUserID = spaceService.currentUserID()

        Task {
            canManageAllFiles = await spaceService.canManageModules(in: space)
            canUploadFiles = await spaceService.canPerform(.uploadFiles, in: space)
        }

        folderListener = spaceService.listenToFolders(in: space) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let folders):
                self.folders = folders
            case .failure(let error):
                self.errorMessage = error.localizedDescription
            }
            self.hasLoadedFolders = true
            self.finishLoadingIfNeeded()
        }

        fileListener = spaceService.listenToFiles(in: space) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let files):
                self.allFiles = files
            case .failure(let error):
                self.errorMessage = error.localizedDescription
            }
            self.hasLoadedFiles = true
            self.finishLoadingIfNeeded()
        }
    }

    func canManage(_ file: SpaceFileItem) -> Bool {
        file.uploadedBy == currentUserID || canManageAllFiles
    }

    func uploadFile(from fileURL: URL) async {
        guard canUploadFiles else { return }
        do {
            _ = try await spaceService.uploadFile(in: space, from: fileURL)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func open(_ file: SpaceFileItem) async {
        do {
            if file.isImage || file.isVideo {
                selectedMedia = makeMedia(from: file)
                return
            }

            let data = try await spaceService.downloadFileData(file, in: space)
            previewDocumentURL = try temporaryFileURL(for: file, data: data)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func share(_ file: SpaceFileItem) async {
        do {
            let data = try await spaceService.downloadFileData(file, in: space)
            shareURL = try temporaryFileURL(for: file, data: data)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func prepareDownload(_ file: SpaceFileItem) async {
        do {
            let data = try await spaceService.downloadFileData(file, in: space)
            exportPayload = ExportPayload(
                document: ExportFileDocument(data: data),
                defaultFilename: file.name,
                contentType: contentType(for: file)
            )
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func rename(_ file: SpaceFileItem, to newName: String) async {
        do {
            try await spaceService.renameFile(file, in: space, to: newName)
            renameTargetFile = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deletePendingFile() async {
        guard let pendingDeleteFile else { return }
        do {
            try await spaceService.softDeleteFile(pendingDeleteFile, in: space)
            self.pendingDeleteFile = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearShareURL() {
        shareURL = nil
    }

    func clearPreviewDocument() {
        previewDocumentURL = nil
    }

    func clearExportPayload() {
        exportPayload = nil
    }

    private func finishLoadingIfNeeded() {
        if hasLoadedFolders && hasLoadedFiles {
            isLoading = false
        }
    }

    private func makeMedia(from file: SpaceFileItem) -> SpaceMedia {
        let metadata = EncryptedMediaMetadata(
            mediaId: file.id,
            mediaType: file.isVideo ? .video : .photo,
            storagePath: file.storagePath,
            thumbnailStoragePath: nil,
            encryptionVersion: file.encryptionVersion,
            nonce: file.nonceBase64,
            thumbnailNonce: nil,
            mimeType: file.mimeType,
            fileSize: Int(file.sizeBytes),
            width: nil,
            height: nil,
            duration: nil,
            createdAt: file.createdAt,
            uploadedBy: file.uploadedBy
        )

        return SpaceMedia(
            id: file.id,
            spaceID: space.id,
            type: file.isVideo ? .video : .image,
            mediaCategory: file.isVideo ? "video" : "photo",
            mediaType: file.isVideo ? .video : .photo,
            placeholderImageName: file.isVideo ? "video.fill" : "photo.fill",
            caption: file.name,
            senderName: file.uploadedByName,
            timestamp: file.timestamp,
            metadata: metadata
        )
    }

    private func temporaryFileURL(for file: SpaceFileItem, data: Data) throws -> URL {
        let fileName = ((file.name as NSString).deletingPathExtension).isEmpty ? file.id : (file.name as NSString).deletingPathExtension
        return try EncryptedMediaService().shareURL(
            for: data,
            suggestedFileName: fileName,
            pathExtension: file.fileExtension
        )
    }

    private func contentType(for file: SpaceFileItem) -> UTType {
        if #available(iOS 14.0, *),
           let type = UTType(filenameExtension: file.fileExtension) {
            return type
        }
        return .data
    }
}

struct ExportFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

import CryptoKit
import AVFoundation
import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation
import UIKit

struct EncryptedMediaUploadResult {
    let metadata: EncryptedMediaMetadata
}

@MainActor
final class EncryptedMediaService {
    private let authService: AuthService
    private let encryptionService: EncryptionService
    private let firestore: Firestore?
    private let storage: Storage?
    private let decryptedDataCache = NSCache<NSString, NSData>()

    init(
        authService: AuthService? = nil,
        encryptionService: EncryptionService = EncryptionService(),
        firestore: Firestore? = nil,
        storage: Storage? = nil
    ) {
        self.authService = authService ?? AuthService()
        self.encryptionService = encryptionService
        self.firestore = firestore ?? FirebaseApp.app().map { _ in Firestore.firestore() }
        self.storage = storage ?? FirebaseApp.app().map { _ in Storage.storage() }
    }

    func uploadImage(
        spaceID: String,
        mediaID: String,
        originalData: Data,
        mediaType: MediaType,
        mimeType: String = "image/jpeg",
        uploadedBy: String,
        progress: ((Double) -> Void)? = nil
    ) async throws -> EncryptedMediaUploadResult {
        guard let storage else {
            throw EncryptedMediaServiceError.storageNotConfigured
        }
        guard let image = UIImage(data: originalData) else {
            throw EncryptedMediaServiceError.invalidMediaData
        }

        let spaceKey = try await ensureSpaceKey(spaceID: spaceID)
        let fullJPEGData = try compressedJPEGData(for: image, maxDimension: 2200, compressionQuality: 0.78)
        let thumbnailJPEGData = try compressedJPEGData(for: image, maxDimension: 640, compressionQuality: 0.65)
        let encryptedMedia = try encryptionService.encryptData(fullJPEGData, using: spaceKey)
        let encryptedThumbnail = try encryptionService.encryptData(thumbnailJPEGData, using: spaceKey)

        let mediaStoragePath = "spaces/\(spaceID)/media/\(mediaID).enc"
        let thumbnailStoragePath = "spaces/\(spaceID)/media/\(mediaID)_thumb.enc"

        try await uploadData(
            Data(base64Encoded: encryptedMedia.ciphertext) ?? Data(),
            to: storage.reference(withPath: mediaStoragePath),
            progress: { value in progress?(value * 0.8) }
        )
        try await uploadData(
            Data(base64Encoded: encryptedThumbnail.ciphertext) ?? Data(),
            to: storage.reference(withPath: thumbnailStoragePath),
            progress: { value in progress?(0.8 + (value * 0.2)) }
        )

        let metadata = EncryptedMediaMetadata(
            mediaId: mediaID,
            mediaType: mediaType,
            storagePath: mediaStoragePath,
            thumbnailStoragePath: thumbnailStoragePath,
            encryptionVersion: "aes-gcm-v1",
            nonce: encryptedMedia.nonce,
            thumbnailNonce: encryptedThumbnail.nonce,
            mimeType: mimeType,
            fileSize: fullJPEGData.count,
            width: Int(image.size.width),
            height: Int(image.size.height),
            duration: nil,
            createdAt: Date(),
            uploadedBy: uploadedBy
        )
        return EncryptedMediaUploadResult(metadata: metadata)
    }

    func uploadVideo(
        spaceID: String,
        mediaID: String,
        originalData: Data,
        mimeType: String,
        uploadedBy: String,
        progress: ((Double) -> Void)? = nil
    ) async throws -> EncryptedMediaUploadResult {
        guard let storage else {
            throw EncryptedMediaServiceError.storageNotConfigured
        }

        let spaceKey = try await ensureSpaceKey(spaceID: spaceID)
        let sourceURL = try temporaryFileURL(
            for: originalData,
            suggestedFileName: "spaces-\(mediaID)-source",
            pathExtension: fileExtension(for: mimeType)
        )
        defer {
            try? FileManager.default.removeItem(at: sourceURL)
        }

        let asset = AVURLAsset(url: sourceURL)
        let thumbnailJPEGData = try videoThumbnailJPEGData(for: sourceURL)
        let encryptedMedia = try encryptionService.encryptData(originalData, using: spaceKey)
        let encryptedThumbnail = try encryptionService.encryptData(thumbnailJPEGData, using: spaceKey)

        let mediaStoragePath = "spaces/\(spaceID)/media/\(mediaID).enc"
        let thumbnailStoragePath = "spaces/\(spaceID)/media/\(mediaID)_thumb.enc"

        try await uploadData(
            Data(base64Encoded: encryptedMedia.ciphertext) ?? Data(),
            to: storage.reference(withPath: mediaStoragePath),
            progress: { value in progress?(value * 0.85) }
        )
        try await uploadData(
            Data(base64Encoded: encryptedThumbnail.ciphertext) ?? Data(),
            to: storage.reference(withPath: thumbnailStoragePath),
            progress: { value in progress?(0.85 + (value * 0.15)) }
        )

        let track = asset.tracks(withMediaType: .video).first
        let transformedSize = track?.naturalSize.applying(track?.preferredTransform ?? .identity)
        let duration = CMTimeGetSeconds(asset.duration)
        let metadata = EncryptedMediaMetadata(
            mediaId: mediaID,
            mediaType: .video,
            storagePath: mediaStoragePath,
            thumbnailStoragePath: thumbnailStoragePath,
            encryptionVersion: "aes-gcm-v1",
            nonce: encryptedMedia.nonce,
            thumbnailNonce: encryptedThumbnail.nonce,
            mimeType: mimeType,
            fileSize: originalData.count,
            width: transformedSize.map { Int(abs($0.width)) },
            height: transformedSize.map { Int(abs($0.height)) },
            duration: duration.isFinite ? duration : nil,
            createdAt: Date(),
            uploadedBy: uploadedBy
        )
        return EncryptedMediaUploadResult(metadata: metadata)
    }

    func uploadFile(
        spaceID: String,
        storagePath: String,
        originalData: Data,
        mimeType: String,
        uploadedBy: String,
        progress: ((Double) -> Void)? = nil
    ) async throws -> EncryptedMediaUploadResult {
        guard let storage else {
            throw EncryptedMediaServiceError.storageNotConfigured
        }

        let fileName = (storagePath as NSString).lastPathComponent
        let derivedMediaID = (fileName as NSString).deletingPathExtension
        let mediaID = derivedMediaID.isEmpty ? UUID().uuidString : derivedMediaID
        let spaceKey = try await ensureSpaceKey(spaceID: spaceID)
        let encryptedMedia = try encryptionService.encryptData(originalData, using: spaceKey)

        try await uploadData(
            Data(base64Encoded: encryptedMedia.ciphertext) ?? Data(),
            to: storage.reference(withPath: storagePath),
            progress: progress
        )

        let metadata = EncryptedMediaMetadata(
            mediaId: mediaID,
            mediaType: mediaType(for: mimeType),
            storagePath: storagePath,
            thumbnailStoragePath: nil,
            encryptionVersion: "aes-gcm-v1",
            nonce: encryptedMedia.nonce,
            thumbnailNonce: nil,
            mimeType: mimeType,
            fileSize: originalData.count,
            width: nil,
            height: nil,
            duration: nil,
            createdAt: Date(),
            uploadedBy: uploadedBy
        )
        return EncryptedMediaUploadResult(metadata: metadata)
    }

    func thumbnailData(for media: SpaceMedia) async throws -> Data {
        guard let metadata = media.metadata else {
            throw EncryptedMediaServiceError.invalidMediaData
        }
        return try await downloadData(
            spaceID: media.spaceID,
            storagePath: metadata.thumbnailStoragePath,
            nonce: metadata.thumbnailNonce,
            cacheKey: "thumb:\(metadata.mediaId)"
        )
    }

    func fullData(for media: SpaceMedia) async throws -> Data {
        guard let metadata = media.metadata else {
            throw EncryptedMediaServiceError.invalidMediaData
        }
        return try await downloadData(
            spaceID: media.spaceID,
            storagePath: metadata.storagePath,
            nonce: metadata.nonce,
            cacheKey: "full:\(metadata.mediaId)"
        )
    }

    func saveImageToPhotos(_ image: UIImage) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let coordinator = PhotoSaveCoordinator { success, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if success {
                    continuation.resume(returning: ())
                } else {
                    continuation.resume(throwing: EncryptedMediaServiceError.unableToSaveMedia)
                }
            }
            coordinator.save(image)
        }
    }

    func temporaryMediaURL(for media: SpaceMedia) async throws -> URL {
        guard let metadata = media.metadata else {
            throw EncryptedMediaServiceError.invalidMediaData
        }
        let data = try await fullData(for: media)
        return try temporaryFileURL(
            for: data,
            suggestedFileName: metadata.mediaId,
            pathExtension: fileExtension(for: metadata.mimeType)
        )
    }

    func fileData(
        spaceID: String,
        storagePath: String,
        nonce: String
    ) async throws -> Data {
        try await downloadData(
            spaceID: spaceID,
            storagePath: storagePath,
            nonce: nonce,
            cacheKey: "file:\(storagePath)"
        )
    }

    func saveVideoToPhotos(fileURL: URL) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let coordinator = VideoSaveCoordinator { success, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if success {
                    continuation.resume(returning: ())
                } else {
                    continuation.resume(throwing: EncryptedMediaServiceError.unableToSaveMedia)
                }
            }
            coordinator.saveVideo(at: fileURL)
        }
    }

    func shareURL(for data: Data, suggestedFileName: String, pathExtension: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(suggestedFileName)
            .appendingPathExtension(pathExtension)
        try data.write(to: url, options: .atomic)
        return url
    }

    func deleteStorageObjects(for metadata: EncryptedMediaMetadata) async throws {
        guard let storage else {
            throw EncryptedMediaServiceError.storageNotConfigured
        }
        try await deleteObject(at: storage.reference(withPath: metadata.storagePath))
        if let thumbnailStoragePath = metadata.thumbnailStoragePath {
            try await deleteObject(at: storage.reference(withPath: thumbnailStoragePath))
        }
        decryptedDataCache.removeObject(forKey: "full:\(metadata.mediaId)" as NSString)
        decryptedDataCache.removeObject(forKey: "thumb:\(metadata.mediaId)" as NSString)
    }

    private func ensureSpaceKey(spaceID: String) async throws -> SymmetricKey {
        if let cachedKey = encryptionService.cachedSpaceKey(for: spaceID) {
            return cachedKey
        }
        guard let firestore else {
            throw EncryptedMediaServiceError.firestoreNotConfigured
        }
        let reference = firestore.collection("spaces").document(spaceID).collection("encryption").document("key")
        if let snapshot = try? await getDocument(reference),
           snapshot.exists,
           let data = snapshot.data(),
           let keyBase64 = (data["keyBase64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !keyBase64.isEmpty {
            let key = try encryptionService.decodeSpaceKey(keyBase64)
            encryptionService.cacheSpaceKey(key, for: spaceID)
            return key
        }

        guard let session = authService.currentSession() else {
            throw EncryptedMediaServiceError.userNotSignedIn
        }
        let keyBase64 = encryptionService.generateSpaceKeyBase64()
        try? await setData([
            "keyVersion": "aes-gcm-v1",
            "keyBase64": keyBase64,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "createdBy": session.uid
        ], for: reference)
        let createdSnapshot = try await getDocument(reference)
        guard
            createdSnapshot.exists,
            let createdData = createdSnapshot.data(),
            let createdKeyBase64 = createdData["keyBase64"] as? String
        else {
            throw EncryptedMediaServiceError.unableToLoadSpaceKey
        }
        let key = try encryptionService.decodeSpaceKey(createdKeyBase64)
        encryptionService.cacheSpaceKey(key, for: spaceID)
        return key
    }

    private func downloadData(
        spaceID: String?,
        storagePath: String?,
        nonce: String?,
        cacheKey: String
    ) async throws -> Data {
        guard let spaceID, let storagePath, let nonce else {
            throw EncryptedMediaServiceError.invalidMediaData
        }
        if let cached = decryptedDataCache.object(forKey: cacheKey as NSString) {
            return Data(referencing: cached)
        }
        guard let storage else {
            throw EncryptedMediaServiceError.storageNotConfigured
        }
        let encryptedBytes = try await fetchData(from: storage.reference(withPath: storagePath), maxSize: 24 * 1024 * 1024)
        let key = try await ensureSpaceKey(spaceID: spaceID)
        let decrypted = try encryptionService.decryptData(
            ciphertext: encryptedBytes.base64EncodedString(),
            nonce: nonce,
            using: key
        )
        decryptedDataCache.setObject(decrypted as NSData, forKey: cacheKey as NSString)
        return decrypted
    }

    private func compressedJPEGData(
        for image: UIImage,
        maxDimension: CGFloat,
        compressionQuality: CGFloat
    ) throws -> Data {
        let targetSize = scaledSize(for: image.size, maxDimension: maxDimension)
        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let renderedImage = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        guard let data = renderedImage.jpegData(compressionQuality: compressionQuality) else {
            throw EncryptedMediaServiceError.invalidMediaData
        }
        return data
    }

    private func scaledSize(for size: CGSize, maxDimension: CGFloat) -> CGSize {
        guard max(size.width, size.height) > maxDimension else { return size }
        let scale = maxDimension / max(size.width, size.height)
        return CGSize(width: size.width * scale, height: size.height * scale)
    }

    private func uploadData(
        _ data: Data,
        to reference: StorageReference,
        progress: ((Double) -> Void)?
    ) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let task = reference.putData(data, metadata: nil) { _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
            if let progress {
                task.observe(.progress) { snapshot in
                    let total = Double(snapshot.progress?.totalUnitCount ?? 0)
                    let completed = Double(snapshot.progress?.completedUnitCount ?? 0)
                    guard total > 0 else { return }
                    progress(completed / total)
                }
            }
        }
    }

    private func fetchData(from reference: StorageReference, maxSize: Int64) async throws -> Data {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            reference.getData(maxSize: maxSize) { data, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: EncryptedMediaServiceError.invalidMediaData)
                }
            }
        }
    }

    private func deleteObject(at reference: StorageReference) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            reference.delete { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func videoThumbnailJPEGData(for fileURL: URL) throws -> Data {
        let asset = AVURLAsset(url: fileURL)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        imageGenerator.maximumSize = CGSize(width: 960, height: 960)
        let cgImage = try imageGenerator.copyCGImage(at: .zero, actualTime: nil)
        let image = UIImage(cgImage: cgImage)
        guard let data = image.jpegData(compressionQuality: 0.7) else {
            throw EncryptedMediaServiceError.invalidMediaData
        }
        return data
    }

    private func temporaryFileURL(
        for data: Data,
        suggestedFileName: String,
        pathExtension: String
    ) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(suggestedFileName)
            .appendingPathExtension(pathExtension)
        try data.write(to: url, options: .atomic)
        return url
    }

    private func fileExtension(for mimeType: String) -> String {
        switch mimeType.lowercased() {
        case "video/quicktime":
            return "mov"
        case "video/mp4":
            return "mp4"
        case "application/pdf":
            return "pdf"
        case "video/mpeg":
            return "mpeg"
        case "image/png":
            return "png"
        case "image/heic":
            return "heic"
        case "text/plain":
            return "txt"
        case "application/json":
            return "json"
        case "text/csv":
            return "csv"
        default:
            return "jpg"
        }
    }

    private func mediaType(for mimeType: String) -> MediaType {
        let normalized = mimeType.lowercased()
        if normalized.hasPrefix("image/") {
            return .photo
        }
        if normalized.hasPrefix("video/") {
            return .video
        }
        return .file
    }

    private func getDocument(_ reference: DocumentReference) async throws -> DocumentSnapshot {
        try await withCheckedThrowingContinuation { continuation in
            reference.getDocument { snapshot, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: EncryptedMediaServiceError.unableToLoadSpaceKey)
                }
            }
        }
    }

    private func setData(_ data: [String: Any], for reference: DocumentReference) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            reference.setData(data) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }
}

enum EncryptedMediaServiceError: LocalizedError {
    case storageNotConfigured
    case firestoreNotConfigured
    case userNotSignedIn
    case invalidMediaData
    case unableToLoadSpaceKey
    case unableToSaveMedia

    var errorDescription: String? {
        switch self {
        case .storageNotConfigured:
            return "Storage is not configured yet."
        case .firestoreNotConfigured:
            return "Firestore is not configured yet."
        case .userNotSignedIn:
            return "Sign in before working with shared media."
        case .invalidMediaData:
            return "Unable to process this media."
        case .unableToLoadSpaceKey:
            return "Unable to load the Space encryption key."
        case .unableToSaveMedia:
            return "Unable to save this media."
        }
    }
}

private final class PhotoSaveCoordinator: NSObject {
    private let completion: (Bool, Error?) -> Void

    init(completion: @escaping (Bool, Error?) -> Void) {
        self.completion = completion
    }

    func save(_ image: UIImage) {
        UIImageWriteToSavedPhotosAlbum(
            image,
            self,
            #selector(image(_:didFinishSavingWithError:contextInfo:)),
            nil
        )
    }

    @objc
    private func image(
        _ image: UIImage,
        didFinishSavingWithError error: Error?,
        contextInfo: UnsafeMutableRawPointer?
    ) {
        completion(error == nil, error)
    }
}

private final class VideoSaveCoordinator: NSObject {
    private let completion: (Bool, Error?) -> Void

    init(completion: @escaping (Bool, Error?) -> Void) {
        self.completion = completion
    }

    func saveVideo(at fileURL: URL) {
        UISaveVideoAtPathToSavedPhotosAlbum(
            fileURL.path,
            self,
            #selector(video(_:didFinishSavingWithError:contextInfo:)),
            nil
        )
    }

    @objc
    private func video(
        _ videoPath: String,
        didFinishSavingWithError error: Error?,
        contextInfo: UnsafeMutableRawPointer?
    ) {
        completion(error == nil, error)
    }
}

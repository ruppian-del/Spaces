import Foundation

struct SpaceMedia: Identifiable, Hashable {
    let id: String
    let spaceID: String?
    let type: MessageType
    let mediaCategory: String?
    let mediaType: MediaType
    let placeholderImageName: String
    let caption: String?
    let senderName: String
    let timestamp: String
    let metadata: EncryptedMediaMetadata?
    let mediaStoragePath: String?
    let thumbnailStoragePath: String?
    let mediaNonceBase64: String?
    let thumbnailNonceBase64: String?

    init(
        id: String,
        spaceID: String? = nil,
        type: MessageType,
        mediaCategory: String? = nil,
        mediaType: MediaType? = nil,
        placeholderImageName: String,
        caption: String?,
        senderName: String,
        timestamp: String,
        metadata: EncryptedMediaMetadata? = nil,
        mediaStoragePath: String? = nil,
        thumbnailStoragePath: String? = nil,
        mediaNonceBase64: String? = nil,
        thumbnailNonceBase64: String? = nil
    ) {
        self.id = id
        self.spaceID = spaceID
        self.type = type
        self.mediaCategory = mediaCategory
        self.mediaType = mediaType ?? Self.inferredMediaType(from: type, category: mediaCategory)
        self.placeholderImageName = placeholderImageName
        self.caption = caption
        self.senderName = senderName
        self.timestamp = timestamp
        let resolvedMetadata = metadata ?? Self.defaultMetadata(
            id: id,
            mediaType: self.mediaType,
            mediaStoragePath: mediaStoragePath,
            thumbnailStoragePath: thumbnailStoragePath,
            mediaNonceBase64: mediaNonceBase64,
            thumbnailNonceBase64: thumbnailNonceBase64
        )
        self.metadata = resolvedMetadata
        self.mediaStoragePath = resolvedMetadata?.storagePath ?? mediaStoragePath
        self.thumbnailStoragePath = resolvedMetadata?.thumbnailStoragePath ?? thumbnailStoragePath
        self.mediaNonceBase64 = resolvedMetadata?.nonce ?? mediaNonceBase64
        self.thumbnailNonceBase64 = resolvedMetadata?.thumbnailNonce ?? thumbnailNonceBase64
    }

    init(
        id: UUID,
        spaceID: String? = nil,
        type: MessageType,
        mediaCategory: String? = nil,
        mediaType: MediaType? = nil,
        placeholderImageName: String,
        caption: String?,
        senderName: String,
        timestamp: String,
        metadata: EncryptedMediaMetadata? = nil,
        mediaStoragePath: String? = nil,
        thumbnailStoragePath: String? = nil,
        mediaNonceBase64: String? = nil,
        thumbnailNonceBase64: String? = nil
    ) {
        self.init(
            id: id.uuidString,
            spaceID: spaceID,
            type: type,
            mediaCategory: mediaCategory,
            mediaType: mediaType,
            placeholderImageName: placeholderImageName,
            caption: caption,
            senderName: senderName,
            timestamp: timestamp,
            metadata: metadata,
            mediaStoragePath: mediaStoragePath,
            thumbnailStoragePath: thumbnailStoragePath,
            mediaNonceBase64: mediaNonceBase64,
            thumbnailNonceBase64: thumbnailNonceBase64
        )
    }

    private static func inferredMediaType(from type: MessageType, category: String?) -> MediaType {
        if let category, let mediaType = MediaType(rawValue: category) {
            return mediaType
        }

        switch type {
        case .text:
            return .file
        case .image:
            return .photo
        case .video:
            return .video
        case .meme:
            return .meme
        case .gif:
            return .gif
        case .screenshot:
            return .photo
        case .file:
            return .file
        }
    }

    private static func defaultMetadata(
        id: String,
        mediaType: MediaType,
        mediaStoragePath: String?,
        thumbnailStoragePath: String?,
        mediaNonceBase64: String?,
        thumbnailNonceBase64: String?
    ) -> EncryptedMediaMetadata? {
        guard let mediaStoragePath, let mediaNonceBase64 else { return nil }
        return EncryptedMediaMetadata(
            mediaId: id,
            mediaType: mediaType,
            storagePath: mediaStoragePath,
            thumbnailStoragePath: thumbnailStoragePath,
            encryptionVersion: "aes-gcm-v1",
            nonce: mediaNonceBase64,
            thumbnailNonce: thumbnailNonceBase64,
            mimeType: "image/jpeg",
            fileSize: 0,
            width: nil,
            height: nil,
            duration: nil,
            createdAt: nil,
            uploadedBy: ""
        )
    }
}

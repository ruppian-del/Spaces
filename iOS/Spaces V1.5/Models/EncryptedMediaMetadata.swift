import Foundation

struct EncryptedMediaMetadata: Hashable {
    let mediaId: String
    let mediaType: MediaType
    let storagePath: String
    let thumbnailStoragePath: String?
    let encryptionVersion: String
    let nonce: String
    let thumbnailNonce: String?
    let mimeType: String
    let fileSize: Int
    let width: Int?
    let height: Int?
    let duration: Double?
    let createdAt: Date?
    let uploadedBy: String
}

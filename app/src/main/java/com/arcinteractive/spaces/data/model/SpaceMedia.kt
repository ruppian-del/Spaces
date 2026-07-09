package com.arcinteractive.spaces.data.model

data class SpaceMedia(
    val id: String,
    val spaceId: String? = null,
    val type: MessageType,
    val mediaCategory: String? = null,
    val mediaType: MediaType = inferMediaType(type, mediaCategory),
    val placeholderIconName: String,
    val caption: String?,
    val senderName: String,
    val timestamp: String,
    val mediaStoragePath: String? = null,
    val thumbnailStoragePath: String? = null,
    val mediaNonceBase64: String? = null,
    val thumbnailNonceBase64: String? = null,
    val metadata: EncryptedMediaMetadata? = defaultMetadata(
        id = id,
        mediaType = inferMediaType(type, mediaCategory),
        mediaStoragePath = mediaStoragePath,
        thumbnailStoragePath = thumbnailStoragePath,
        mediaNonceBase64 = mediaNonceBase64,
        thumbnailNonceBase64 = thumbnailNonceBase64
    )
)

private fun inferMediaType(type: MessageType, mediaCategory: String?): MediaType {
    val normalized = mediaCategory?.trim().orEmpty()
    return when {
        normalized.equals("photo", ignoreCase = true) -> MediaType.Photo
        normalized.equals("meme", ignoreCase = true) -> MediaType.Meme
        normalized.equals("gif", ignoreCase = true) -> MediaType.Gif
        normalized.equals("video", ignoreCase = true) -> MediaType.Video
        normalized.equals("file", ignoreCase = true) -> MediaType.File
        normalized.equals("voice", ignoreCase = true) -> MediaType.Voice
        normalized.equals("profilePhoto", ignoreCase = true) -> MediaType.ProfilePhoto
        normalized.equals("coverPhoto", ignoreCase = true) -> MediaType.CoverPhoto
        type == MessageType.Video -> MediaType.Video
        type == MessageType.Meme -> MediaType.Meme
        type == MessageType.Gif -> MediaType.Gif
        type == MessageType.File -> MediaType.File
        else -> MediaType.Photo
    }
}

private fun defaultMetadata(
    id: String,
    mediaType: MediaType,
    mediaStoragePath: String?,
    thumbnailStoragePath: String?,
    mediaNonceBase64: String?,
    thumbnailNonceBase64: String?
): EncryptedMediaMetadata? {
    if (mediaStoragePath.isNullOrBlank() || mediaNonceBase64.isNullOrBlank()) return null
    return EncryptedMediaMetadata(
        mediaId = id,
        mediaType = mediaType,
        storagePath = mediaStoragePath,
        thumbnailStoragePath = thumbnailStoragePath,
        encryptionVersion = "aes-gcm-v1",
        nonce = mediaNonceBase64,
        thumbnailNonce = thumbnailNonceBase64,
        mimeType = "image/jpeg",
        fileSize = 0L,
        uploadedBy = ""
    )
}

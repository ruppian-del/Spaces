package com.arcinteractive.spaces.data.model

import java.util.Date

data class EncryptedMediaMetadata(
    val mediaId: String,
    val mediaType: MediaType,
    val storagePath: String,
    val thumbnailStoragePath: String? = null,
    val encryptionVersion: String,
    val nonce: String,
    val thumbnailNonce: String? = null,
    val mimeType: String,
    val fileSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null,
    val createdAt: Date? = null,
    val uploadedBy: String
)

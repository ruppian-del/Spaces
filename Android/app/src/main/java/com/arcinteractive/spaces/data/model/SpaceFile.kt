package com.arcinteractive.spaces.data.model

import java.util.Date

data class SpaceFolder(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: Date?
)

data class SpaceFileItem(
    val id: String,
    val spaceId: String,
    val name: String,
    val mimeType: String,
    val folderId: String?,
    val storagePath: String,
    val encryptionVersion: String,
    val nonceBase64: String,
    val uploadedBy: String,
    val uploadedByName: String,
    val fileExtension: String,
    val createdAt: Date?,
    val updatedAt: Date?,
    val sizeBytes: Long,
    val deleted: Boolean
) {
    val iconEmoji: String
        get() = when {
            mimeType.startsWith("image/") -> "\uD83D\uDDBC\uFE0F"
            mimeType.startsWith("video/") -> "\uD83C\uDFA5"
            mimeType.startsWith("audio/") -> "\uD83C\uDF99\uFE0F"
            mimeType.contains("pdf", ignoreCase = true) -> "\uD83D\uDCC4"
            mimeType.contains("zip", ignoreCase = true) || mimeType.contains("compressed", ignoreCase = true) -> "\uD83D\uDCE6"
            mimeType.contains("json", ignoreCase = true) || mimeType.contains("csv", ignoreCase = true) || mimeType.startsWith("text/") -> "\uD83D\uDCDD"
            else -> "\uD83D\uDCC1"
        }

    val sizeDescription: String
        get() = humanReadableByteCount(sizeBytes)

    val typeDescription: String
        get() = when {
            mimeType.startsWith("image/") -> "Image"
            mimeType.startsWith("video/") -> "Video"
            mimeType.startsWith("audio/") -> "Audio"
            mimeType.contains("pdf", ignoreCase = true) -> "PDF"
            mimeType.contains("spreadsheet", ignoreCase = true) || mimeType.contains("csv", ignoreCase = true) -> "Spreadsheet"
            mimeType.contains("presentation", ignoreCase = true) -> "Presentation"
            mimeType.contains("wordprocessingml", ignoreCase = true) || mimeType.contains("msword", ignoreCase = true) -> "Document"
            mimeType.contains("zip", ignoreCase = true) || mimeType.contains("compressed", ignoreCase = true) -> "Archive"
            mimeType.contains("json", ignoreCase = true) -> "JSON"
            mimeType.startsWith("text/") -> "Text"
            else -> "File"
        }

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isVideo: Boolean
        get() = mimeType.startsWith("video/")
}

private fun humanReadableByteCount(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

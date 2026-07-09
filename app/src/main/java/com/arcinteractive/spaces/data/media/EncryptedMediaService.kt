package com.arcinteractive.spaces.data.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.model.EncryptedMediaMetadata
import com.arcinteractive.spaces.data.model.MediaType
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.spaces.EncryptionService
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class EncryptedMediaUploadResult(
    val metadata: EncryptedMediaMetadata
)

class EncryptedMediaService(
    private val authService: AuthService = AuthService(),
    private val encryptionService: EncryptionService = EncryptionService()
) {
    private val decryptedCache = mutableMapOf<String, ByteArray>()

    suspend fun uploadImage(
        context: Context,
        spaceId: String,
        mediaId: String,
        originalBytes: ByteArray,
        mediaType: MediaType,
        mimeType: String = "image/jpeg",
        uploadedBy: String,
        onProgress: ((Double) -> Unit)? = null
    ): EncryptedMediaUploadResult {
        val storage = FirebaseStorage.getInstance()
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            ?: throw IllegalStateException("Unable to read the selected image.")
        val spaceKey = ensureSpaceKey(context, spaceId)
        val fullImageBytes = compressBitmap(bitmap, maxDimension = 2200, quality = 80)
        val thumbnailBytes = compressBitmap(bitmap, maxDimension = 640, quality = 68)
        val encryptedMedia = encryptionService.encryptBytes(fullImageBytes, spaceKey)
        val encryptedThumbnail = encryptionService.encryptBytes(thumbnailBytes, spaceKey)
        val mediaStoragePath = "spaces/$spaceId/media/$mediaId.enc"
        val thumbnailStoragePath = "spaces/$spaceId/media/${mediaId}_thumb.enc"

        uploadData(
            Base64.decode(encryptedMedia.ciphertext, Base64.NO_WRAP),
            storage.reference.child(mediaStoragePath)
        ) { value -> onProgress?.invoke(value * 0.8) }
        uploadData(
            Base64.decode(encryptedThumbnail.ciphertext, Base64.NO_WRAP),
            storage.reference.child(thumbnailStoragePath)
        ) { value -> onProgress?.invoke(0.8 + (value * 0.2)) }

        return EncryptedMediaUploadResult(
            metadata = EncryptedMediaMetadata(
                mediaId = mediaId,
                mediaType = mediaType,
                storagePath = mediaStoragePath,
                thumbnailStoragePath = thumbnailStoragePath,
                encryptionVersion = "aes-gcm-v1",
                nonce = encryptedMedia.nonce,
                thumbnailNonce = encryptedThumbnail.nonce,
                mimeType = mimeType,
                fileSize = fullImageBytes.size.toLong(),
                width = bitmap.width,
                height = bitmap.height,
                duration = null,
                createdAt = Date(),
                uploadedBy = uploadedBy
            )
        )
    }

    suspend fun uploadVideo(
        context: Context,
        spaceId: String,
        mediaId: String,
        originalBytes: ByteArray,
        mimeType: String,
        uploadedBy: String,
        onProgress: ((Double) -> Unit)? = null
    ): EncryptedMediaUploadResult {
        val storage = FirebaseStorage.getInstance()
        val spaceKey = ensureSpaceKey(context, spaceId)
        val sourceFile = writeTempMediaFile(
            context = context,
            bytes = originalBytes,
            fileName = "spaces-upload-$mediaId",
            mimeType = mimeType
        )
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
            val thumbnailBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalStateException("Unable to create a thumbnail for this video.")
            val thumbnailBytes = compressBitmap(thumbnailBitmap, maxDimension = 960, quality = 72)
            val encryptedMedia = encryptionService.encryptBytes(originalBytes, spaceKey)
            val encryptedThumbnail = encryptionService.encryptBytes(thumbnailBytes, spaceKey)
            val mediaStoragePath = "spaces/$spaceId/media/$mediaId.enc"
            val thumbnailStoragePath = "spaces/$spaceId/media/${mediaId}_thumb.enc"

            uploadData(
                Base64.decode(encryptedMedia.ciphertext, Base64.NO_WRAP),
                storage.reference.child(mediaStoragePath)
            ) { value -> onProgress?.invoke(value * 0.85) }
            uploadData(
                Base64.decode(encryptedThumbnail.ciphertext, Base64.NO_WRAP),
                storage.reference.child(thumbnailStoragePath)
            ) { value -> onProgress?.invoke(0.85 + (value * 0.15)) }

            return EncryptedMediaUploadResult(
                metadata = EncryptedMediaMetadata(
                    mediaId = mediaId,
                    mediaType = MediaType.Video,
                    storagePath = mediaStoragePath,
                    thumbnailStoragePath = thumbnailStoragePath,
                    encryptionVersion = "aes-gcm-v1",
                    nonce = encryptedMedia.nonce,
                    thumbnailNonce = encryptedThumbnail.nonce,
                    mimeType = mimeType,
                    fileSize = originalBytes.size.toLong(),
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull()
                        ?.div(1000.0),
                    createdAt = Date(),
                    uploadedBy = uploadedBy
                )
            )
        } finally {
            retriever.release()
            sourceFile.delete()
        }
    }

    suspend fun uploadFile(
        context: Context,
        spaceId: String,
        storagePath: String,
        originalBytes: ByteArray,
        mimeType: String,
        uploadedBy: String,
        onProgress: ((Double) -> Unit)? = null
    ): EncryptedMediaUploadResult {
        val storage = FirebaseStorage.getInstance()
        val fileName = storagePath.substringAfterLast('/')
        val mediaId = fileName.substringBeforeLast('.', "").ifBlank { fileName.ifBlank { "file" } }
        val spaceKey = ensureSpaceKey(context, spaceId)
        val encryptedMedia = encryptionService.encryptBytes(originalBytes, spaceKey)

        uploadData(
            Base64.decode(encryptedMedia.ciphertext, Base64.NO_WRAP),
            storage.reference.child(storagePath),
            onProgress
        )

        return EncryptedMediaUploadResult(
            metadata = EncryptedMediaMetadata(
                mediaId = mediaId,
                mediaType = mediaTypeForMimeType(mimeType),
                storagePath = storagePath,
                thumbnailStoragePath = null,
                encryptionVersion = "aes-gcm-v1",
                nonce = encryptedMedia.nonce,
                thumbnailNonce = null,
                mimeType = mimeType,
                fileSize = originalBytes.size.toLong(),
                createdAt = Date(),
                uploadedBy = uploadedBy
            )
        )
    }

    suspend fun loadThumbnailBytes(context: Context, media: SpaceMedia): ByteArray {
        val metadata = media.metadata ?: throw IllegalStateException("Media metadata is incomplete.")
        return loadDecryptedBytes(
            context = context,
            spaceId = media.spaceId,
            storagePath = metadata.thumbnailStoragePath,
            nonce = metadata.thumbnailNonce,
            cacheKey = "thumb:${metadata.mediaId}"
        )
    }

    suspend fun loadFullMediaBytes(context: Context, media: SpaceMedia): ByteArray {
        val metadata = media.metadata ?: throw IllegalStateException("Media metadata is incomplete.")
        return loadDecryptedBytes(
            context = context,
            spaceId = media.spaceId,
            storagePath = metadata.storagePath,
            nonce = metadata.nonce,
            cacheKey = "full:${metadata.mediaId}"
        )
    }

    suspend fun loadFileBytes(
        context: Context,
        spaceId: String,
        storagePath: String,
        nonce: String
    ): ByteArray {
        return loadDecryptedBytes(
            context = context,
            spaceId = spaceId,
            storagePath = storagePath,
            nonce = nonce,
            cacheKey = "file:$storagePath"
        )
    }

    suspend fun saveImageToGallery(context: Context, imageBytes: ByteArray, mediaId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "spaces_$mediaId.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Spaces")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, contentValues)
                ?: error("Unable to create a media store entry.")

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(imageBytes)
                outputStream.flush()
            } ?: error("Unable to open the image output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            "Image saved to Pictures/Spaces."
        }.getOrElse { error ->
            error.localizedMessage ?: "Unable to save this image."
        }
    }

    suspend fun saveVideoToGallery(
        context: Context,
        videoBytes: ByteArray,
        mediaId: String,
        mimeType: String
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "spaces_$mediaId.${fileExtensionForMimeType(mimeType)}")
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Spaces")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, contentValues)
                ?: error("Unable to create a media store entry.")

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(videoBytes)
                outputStream.flush()
            } ?: error("Unable to open the video output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            "Video saved to Movies/Spaces."
        }.getOrElse { error ->
            error.localizedMessage ?: "Unable to save this video."
        }
    }

    suspend fun saveFileToDownloads(
        context: Context,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Spaces")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, contentValues)
                ?: error("Unable to create a downloads entry.")

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(fileBytes)
                outputStream.flush()
            } ?: error("Unable to open the file output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            "File saved to Downloads/Spaces."
        }.getOrElse { error ->
            error.localizedMessage ?: "Unable to save this file."
        }
    }

    fun shareFile(context: Context, bytes: ByteArray, fileName: String, extension: String): File {
        val file = File(context.cacheDir, "$fileName.$extension")
        file.writeBytes(bytes)
        return file
    }

    fun writeTempMediaFile(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String
    ): File {
        val file = File(context.cacheDir, "$fileName.${fileExtensionForMimeType(mimeType)}")
        file.writeBytes(bytes)
        return file
    }

    suspend fun deleteStorageObjects(metadata: EncryptedMediaMetadata) {
        val storage = FirebaseStorage.getInstance()
        deleteObject(storage.reference.child(metadata.storagePath))
        metadata.thumbnailStoragePath?.let { deleteObject(storage.reference.child(it)) }
        decryptedCache.remove("full:${metadata.mediaId}")
        decryptedCache.remove("thumb:${metadata.mediaId}")
    }

    private suspend fun ensureSpaceKey(context: Context, spaceId: String): ByteArray {
        encryptionService.cachedSpaceKey(spaceId)?.let { return it }
        val firestore = FirebaseFirestore.getInstance()
        val reference = firestore.collection("spaces").document(spaceId).collection("encryption").document("key")
        val existingSnapshot = runCatching { getDocument(reference) }.getOrNull()
        val existingKeyBase64 = existingSnapshot?.data?.get("keyBase64") as? String
        if (!existingKeyBase64.isNullOrBlank()) {
            val key = encryptionService.decodeSpaceKey(existingKeyBase64)
            encryptionService.cacheSpaceKey(spaceId, key)
            return key
        }

        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before loading media.")
        val generatedKeyBase64 = encryptionService.generateSpaceKeyBase64()
        runCatching {
            setData(
                reference,
                mapOf(
                    "keyVersion" to "aes-gcm-v1",
                    "keyBase64" to generatedKeyBase64,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "createdBy" to session.uid
                )
            )
        }
        val createdSnapshot = getDocument(reference)
        val createdKeyBase64 = createdSnapshot.data?.get("keyBase64") as? String
            ?: throw IllegalStateException("Unable to load media encryption key.")
        val key = encryptionService.decodeSpaceKey(createdKeyBase64)
        encryptionService.cacheSpaceKey(spaceId, key)
        return key
    }

    private suspend fun loadDecryptedBytes(
        context: Context,
        spaceId: String?,
        storagePath: String?,
        nonce: String?,
        cacheKey: String
    ): ByteArray {
        if (spaceId.isNullOrBlank() || storagePath.isNullOrBlank() || nonce.isNullOrBlank()) {
            throw IllegalStateException("Media metadata is incomplete.")
        }
        decryptedCache[cacheKey]?.let { return it }
        val encryptedBytes = downloadBytes(
            FirebaseStorage.getInstance().reference.child(storagePath),
            24L * 1024L * 1024L
        )
        val key = ensureSpaceKey(context, spaceId)
        val decrypted = encryptionService.decryptBytes(
            ciphertext = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
            nonce = nonce,
            spaceKey = key
        )
        decryptedCache[cacheKey] = decrypted
        return decrypted
    }

    private fun compressBitmap(bitmap: Bitmap, maxDimension: Int, quality: Int): ByteArray {
        val scaled = scaleBitmap(bitmap, maxDimension)
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largestDimension.toFloat()
        val width = (bitmap.width * scale).toInt()
        val height = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun fileExtensionForMimeType(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "video/quicktime" -> "mov"
            "video/mp4" -> "mp4"
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/heic" -> "heic"
            "text/plain" -> "txt"
            "application/json" -> "json"
            "text/csv" -> "csv"
            else -> "jpg"
        }
    }

    private fun mediaTypeForMimeType(mimeType: String): com.arcinteractive.spaces.data.model.MediaType {
        val normalized = mimeType.lowercase()
        return when {
            normalized.startsWith("image/") -> com.arcinteractive.spaces.data.model.MediaType.Photo
            normalized.startsWith("video/") -> com.arcinteractive.spaces.data.model.MediaType.Video
            else -> com.arcinteractive.spaces.data.model.MediaType.File
        }
    }

    private suspend fun uploadData(
        data: ByteArray,
        reference: StorageReference,
        onProgress: ((Double) -> Unit)?
    ) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val task = reference.putBytes(data)
            task.addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
            task.addOnProgressListener { snapshot ->
                val total = snapshot.totalByteCount.toDouble()
                val transferred = snapshot.bytesTransferred.toDouble()
                if (total > 0) {
                    onProgress?.invoke(transferred / total)
                }
            }
        }
    }

    private suspend fun downloadBytes(reference: StorageReference, maxSize: Long): ByteArray {
        return suspendCancellableCoroutine { continuation ->
            reference.getBytes(maxSize)
                .addOnSuccessListener { bytes -> continuation.resume(bytes) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun deleteObject(reference: StorageReference) {
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.delete()
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun getDocument(reference: DocumentReference): DocumentSnapshot {
        return suspendCancellableCoroutine { continuation ->
            reference.get()
                .addOnSuccessListener { snapshot -> continuation.resume(snapshot) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun setData(reference: DocumentReference, data: Map<String, Any>) {
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.set(data)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }
}

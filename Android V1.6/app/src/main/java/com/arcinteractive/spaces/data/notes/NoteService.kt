package com.arcinteractive.spaces.data.notes

import android.content.Context
import android.util.Base64
import com.arcinteractive.spaces.data.model.*
import com.arcinteractive.spaces.data.spaces.EncryptionService
import com.arcinteractive.spaces.data.spaces.SpaceService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import java.util.Date
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

class NoteService {
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val encryption = EncryptionService()
    private val spaces = SpaceService()

    fun listen(context: Context, space: Space, onUpdate: (Result<List<SpaceNote>>) -> Unit): ListenerRegistration =
        firestore.collection("spaces").document(space.id).collection("notes").orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener onUpdate(Result.failure(error))
                val docs = snapshot?.documents.orEmpty()
                if (docs.isEmpty()) return@addSnapshotListener onUpdate(Result.success(emptyList()))
                Thread {
                    runCatching {
                        val key = kotlinx.coroutines.runBlocking { spaces.encryptionKeyForModuleData(context, space.id) }
                        docs.map { mapNote(it, space.id, key) }
                    }.also(onUpdate)
                }.start()
            }

    fun listenComments(context: Context, space: Space, noteId: String, onUpdate: (Result<List<SpaceNoteComment>>) -> Unit): ListenerRegistration =
        firestore.collection("spaces").document(space.id).collection("notes").document(noteId).collection("comments").orderBy("createdAt")
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener onUpdate(Result.failure(error))
                val docs = snapshot?.documents.orEmpty()
                if (docs.isEmpty()) return@addSnapshotListener onUpdate(Result.success(emptyList()))
                Thread {
                    runCatching {
                        val key = kotlinx.coroutines.runBlocking { spaces.encryptionKeyForModuleData(context, space.id) }
                        docs.map { doc ->
                            val json = JSONObject(encryption.decryptText(doc.getString("ciphertext").orEmpty(), doc.getString("nonce").orEmpty(), null, key))
                            SpaceNoteComment(doc.id, noteId, doc.getString("authorId").orEmpty(), json.getString("authorName"), json.getString("body"), doc.getTimestamp("createdAt")?.toDate() ?: Date())
                        }
                    }.also(onUpdate)
                }.start()
            }

    suspend fun save(context: Context, space: Space, note: SpaceNote) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in to use Notes.")
        val isNew = note.createdBy.isBlank()
        val permission = if (isNew) SpacePermission.CreateNotes else if (note.createdBy == uid) SpacePermission.EditOwnNotes else SpacePermission.EditAnyNotes
        check(spaces.canPerform(context, space, permission)) { "You do not have permission to change this Note." }
        val json = JSONObject().put("title", note.title).put("markdown", note.markdown)
            .put("attachments", JSONArray(note.attachments.map(::attachmentJson)))
            .put("links", JSONArray(note.links.map(::linkJson)))
        val encrypted = encryption.encryptText(json.toString(), spaces.encryptionKeyForModuleData(context, space.id))
        awaitSet(firestore.collection("spaces").document(space.id).collection("notes").document(note.id), mapOf(
            "ciphertext" to encrypted.ciphertext, "nonce" to encrypted.nonce,
            "createdBy" to if (isNew) uid else note.createdBy,
            "createdAt" to if (isNew) FieldValue.serverTimestamp() else note.createdAt,
            "updatedAt" to FieldValue.serverTimestamp(), "encryptionVersion" to "space-module-key-v1"
        ))
        if (isNew) {
            spaces.recordModuleActivity(
                context, ActivityType.NoteCreated, space, "created a Note", note.title,
                note.id, ActivityTargetType.Notes
            )
        }
        if (!isNew) {
            val recipients = spaces.mentionedMemberIds(context, note.markdown, space)
            spaces.recordTargetedModuleNotification(
                context, recipients, "note", space, "mentioned you in a Note", note.title,
                note.id, ActivityTargetType.Notes
            )
        }
    }

    suspend fun delete(context: Context, space: Space, note: SpaceNote) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        check(spaces.canPerform(context, space, if (note.createdBy == uid) SpacePermission.DeleteOwnNotes else SpacePermission.DeleteAnyNotes))
        note.attachments.forEach { runCatching { deleteTrackedStorage(context, space.id, it.storagePath) } }
        val ref = firestore.collection("spaces").document(space.id).collection("notes").document(note.id)
        awaitGet(ref.collection("comments")).documents.forEach { awaitDelete(it.reference) }
        awaitDelete(ref)
    }

    suspend fun addComment(context: Context, space: Space, note: SpaceNote, body: String, authorName: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in to comment.")
        val json = JSONObject().put("authorName", authorName).put("body", body)
        val encrypted = encryption.encryptText(json.toString(), spaces.encryptionKeyForModuleData(context, space.id))
        awaitSet(firestore.collection("spaces").document(space.id).collection("notes").document(note.id).collection("comments").document(), mapOf(
            "ciphertext" to encrypted.ciphertext, "nonce" to encrypted.nonce, "authorId" to uid,
            "createdAt" to FieldValue.serverTimestamp(), "encryptionVersion" to "space-module-key-v1"
        ))
        val commentRecipients = (spaces.mentionedMemberIds(context, body, space) + note.createdBy).distinct()
        spaces.recordTargetedModuleNotification(
            context, commentRecipients, "note", space, "commented on your Note", note.title,
            note.id, ActivityTargetType.Notes
        )
    }

    suspend fun recordView(spaceId: String, noteId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        awaitSet(firestore.collection("spaces").document(spaceId).collection("notePreferences").document(uid).collection("notes").document(noteId), mapOf(
            "lastViewedAt" to FieldValue.serverTimestamp(), "viewCount" to FieldValue.increment(1)
        ))
    }

    fun listenViewPreferences(spaceId: String, onUpdate: (Map<String, NoteViewPreference>) -> Unit): ListenerRegistration? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return firestore.collection("spaces").document(spaceId).collection("notePreferences")
            .document(uid).collection("notes").addSnapshotListener { snapshot, _ ->
                onUpdate(snapshot?.documents.orEmpty().associate { document ->
                    document.id to NoteViewPreference(
                        document.getTimestamp("lastViewedAt")?.toDate() ?: Date(0),
                        document.getLong("viewCount")?.toInt() ?: 0
                    )
                })
            }
    }

    fun listenManualOrder(spaceId: String, onUpdate: (List<String>) -> Unit): ListenerRegistration? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return firestore.collection("spaces").document(spaceId).collection("notePreferences")
            .document(uid).addSnapshotListener { snapshot, _ ->
                onUpdate((snapshot?.get("manualOrder") as? List<*>)?.filterIsInstance<String>().orEmpty())
            }
    }

    suspend fun saveManualOrder(spaceId: String, ids: List<String>) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        awaitSet(
            firestore.collection("spaces").document(spaceId).collection("notePreferences").document(uid),
            mapOf("manualOrder" to ids, "updatedAt" to FieldValue.serverTimestamp())
        )
    }

    suspend fun upload(context: Context, space: Space, noteId: String, bytes: ByteArray, name: String, mimeType: String, isMedia: Boolean): SpaceNoteAttachment {
        require(bytes.size.toLong() <= 1024L * 1024L * 1024L) { "Each upload is limited to 1 GB." }
        val organizations = com.arcinteractive.spaces.data.organization.OrganizationService()
        val organizationId = organizations.reserveStorage(context, space.id, bytes.size.toLong())
        val id = UUID.randomUUID().toString()
        val encrypted = encryption.encryptBytes(bytes, spaces.encryptionKeyForModuleData(context, space.id))
        val path = "spaces/${space.id}/notes/$noteId/attachments/$id.enc"
        try {
            awaitUpload(path, Base64.decode(encrypted.ciphertext, Base64.NO_WRAP), bytes.size.toLong())
        } catch (error: Throwable) {
            organizations.releaseStorage(context, organizationId, bytes.size.toLong())
            throw error
        }
        return SpaceNoteAttachment(id, name, mimeType, path, encrypted.nonce, isMedia)
    }

    suspend fun download(context: Context, space: Space, attachment: SpaceNoteAttachment): ByteArray {
        val bytes = awaitDownload(attachment.storagePath)
        return encryption.decryptBytes(Base64.encodeToString(bytes, Base64.NO_WRAP), attachment.nonce, spaces.encryptionKeyForModuleData(context, space.id))
    }

    private fun mapNote(doc: DocumentSnapshot, spaceId: String, key: ByteArray): SpaceNote {
        val json = JSONObject(encryption.decryptText(doc.getString("ciphertext").orEmpty(), doc.getString("nonce").orEmpty(), null, key))
        return SpaceNote(doc.id, spaceId, json.getString("title"), json.optString("markdown"),
            json.optJSONArray("attachments").objects().map(::mapAttachment), json.optJSONArray("links").objects().map(::mapLink),
            doc.getString("createdBy").orEmpty(), doc.getTimestamp("createdAt")?.toDate() ?: Date(), doc.getTimestamp("updatedAt")?.toDate() ?: Date())
    }
    private fun linkJson(v: SpaceLinkAttachment) = JSONObject().put("id", v.id).put("moduleType", v.moduleType.name).put("targetId", v.targetId).put("title", v.title).put("subtitle", v.subtitle).put("icon", v.icon)
    private fun mapLink(v: JSONObject) = SpaceLinkAttachment(v.getString("id"), SpaceLinkModuleType.valueOf(v.getString("moduleType")), v.getString("targetId"), v.getString("title"), v.optString("subtitle").ifBlank { null }, v.optString("icon"))
    private fun attachmentJson(v: SpaceNoteAttachment) = JSONObject().put("id", v.id).put("name", v.name).put("mimeType", v.mimeType).put("storagePath", v.storagePath).put("nonce", v.nonce).put("isMedia", v.isMedia)
    private fun mapAttachment(v: JSONObject) = SpaceNoteAttachment(v.getString("id"), v.getString("name"), v.getString("mimeType"), v.getString("storagePath"), v.getString("nonce"), v.optBoolean("isMedia"))
    private fun JSONArray?.objects() = (0 until (this?.length() ?: 0)).map { this!!.getJSONObject(it) }
    private suspend fun awaitSet(ref: DocumentReference, data: Map<String, Any>) = suspendCancellableCoroutine<Unit> { c -> ref.set(data, SetOptions.merge()).addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException) }
    private suspend fun awaitDelete(ref: DocumentReference) = suspendCancellableCoroutine<Unit> { c -> ref.delete().addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException) }
    private suspend fun awaitGet(ref: CollectionReference) = suspendCancellableCoroutine<QuerySnapshot> { c -> ref.get().addOnSuccessListener { c.resume(it) {} }.addOnFailureListener(c::resumeWithException) }
    private suspend fun awaitUpload(path: String, bytes: ByteArray, chargedBytes: Long) = suspendCancellableCoroutine<Unit> { c ->
        val metadata = com.google.firebase.storage.StorageMetadata.Builder().setCustomMetadata("organizationChargedBytes", chargedBytes.toString()).build()
        storage.reference.child(path).putBytes(bytes, metadata).addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException)
    }
    private suspend fun awaitDownload(path: String) = suspendCancellableCoroutine<ByteArray> { c -> storage.reference.child(path).getBytes(250L * 1024 * 1024).addOnSuccessListener { c.resume(it) {} }.addOnFailureListener(c::resumeWithException) }
    private suspend fun awaitStorageDelete(path: String) = suspendCancellableCoroutine<Unit> { c -> storage.reference.child(path).delete().addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException) }
    private suspend fun deleteTrackedStorage(context: Context, spaceId: String, path: String) {
        val reference = storage.reference.child(path)
        val charged = suspendCancellableCoroutine<Long> { c -> reference.metadata
            .addOnSuccessListener { c.resume(it.getCustomMetadata("organizationChargedBytes")?.toLongOrNull() ?: 0L) {} }
            .addOnFailureListener { c.resume(0L) {} } }
        awaitStorageDelete(path)
        com.arcinteractive.spaces.data.organization.OrganizationService().releaseStorageForSpace(context, spaceId, charged)
    }
}

data class NoteViewPreference(val lastViewedAt: Date, val viewCount: Int)

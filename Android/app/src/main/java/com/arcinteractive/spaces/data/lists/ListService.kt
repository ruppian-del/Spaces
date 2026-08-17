package com.arcinteractive.spaces.data.lists

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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resumeWithException

class ListService {
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val encryption = EncryptionService()
    private val spaces = SpaceService()

    fun listenToLists(context: Context, space: Space, onUpdate: (Result<List<SpaceList>>) -> Unit): ListenerRegistration {
        return firestore.collection("spaces").document(space.id).collection("lists")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener onUpdate(Result.failure(error))
                val documents = snapshot?.documents.orEmpty()
                if (documents.isEmpty()) {
                    onUpdate(Result.success(emptyList()))
                    return@addSnapshotListener
                }
                Thread {
                    runCatching {
                        val key = kotlinx.coroutines.runBlocking { spaces.encryptionKeyForModuleData(context, space.id) }
                        documents.map { mapList(it, space.id, key) }
                    }.also(onUpdate)
                }.start()
            }
    }

    fun listenToItems(context: Context, space: Space, listId: String, onUpdate: (Result<List<SpaceListItem>>) -> Unit): ListenerRegistration {
        return firestore.collection("spaces").document(space.id).collection("lists").document(listId).collection("items")
            .orderBy("order")
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener onUpdate(Result.failure(error))
                val documents = snapshot?.documents.orEmpty()
                if (documents.isEmpty()) {
                    onUpdate(Result.success(emptyList()))
                    return@addSnapshotListener
                }
                Thread {
                    runCatching {
                        val key = kotlinx.coroutines.runBlocking { spaces.encryptionKeyForModuleData(context, space.id) }
                        documents.map { mapItem(it, listId, key) }
                    }.also(onUpdate)
                }.start()
            }
    }

    suspend fun saveList(context: Context, space: Space, list: SpaceList) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in to use Lists.")
        val isNew = list.createdBy.isBlank()
        val permission = if (isNew) SpacePermission.CreateLists
            else if (list.createdBy == uid) SpacePermission.EditOwnLists else SpacePermission.EditAnyLists
        check(spaces.canPerform(context, space, permission)) { "You do not have permission to change this List." }
        val payload = JSONObject()
            .put("title", list.title)
            .put("sections", JSONArray(list.sections.map { JSONObject().put("id", it.id).put("title", it.title).put("order", it.order) }))
            .put("links", JSONArray(list.links.map(::linkJson)))
        val encrypted = encryption.encryptText(payload.toString(), spaces.encryptionKeyForModuleData(context, space.id))
        awaitSet(
            firestore.collection("spaces").document(space.id).collection("lists").document(list.id),
            mapOf(
                "ciphertext" to encrypted.ciphertext, "nonce" to encrypted.nonce,
                "createdBy" to if (isNew) uid else list.createdBy,
                "createdAt" to if (isNew) FieldValue.serverTimestamp() else list.createdAt,
                "updatedAt" to FieldValue.serverTimestamp(), "encryptionVersion" to "space-member-key-v1"
            )
        )
        val validSectionIds = list.sections.map { it.id }.toSet()
        awaitGet(firestore.collection("spaces").document(space.id).collection("lists").document(list.id).collection("items"))
            .documents
            .filter { document -> document.getString("sectionId")?.let { it !in validSectionIds } == true }
            .forEach { document ->
                awaitUpdate(document.reference, mapOf("sectionId" to null))
            }
        if (isNew) {
            spaces.recordModuleActivity(
                context, ActivityType.ListCreated, space, "created a List", list.title,
                list.id, ActivityTargetType.Lists
            )
        }
    }

    suspend fun saveItem(context: Context, space: Space, list: SpaceList, item: SpaceListItem) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in to use Lists.")
        val permission = if (list.createdBy == uid) SpacePermission.EditOwnLists else SpacePermission.EditAnyLists
        check(spaces.canPerform(context, space, permission)) { "You do not have permission to change this List." }
        val payload = JSONObject()
            .put("title", item.title).put("notes", item.notes)
            .put("assignedMemberIds", JSONArray(item.assignedMemberIds.toList()))
            .put("attachments", JSONArray(item.attachments.map(::attachmentJson)))
            .put("links", JSONArray(item.links.map(::linkJson)))
        val encrypted = encryption.encryptText(payload.toString(), spaces.encryptionKeyForModuleData(context, space.id))
        awaitSet(
            firestore.collection("spaces").document(space.id).collection("lists").document(list.id).collection("items").document(item.id),
            mapOf(
                "ciphertext" to encrypted.ciphertext, "nonce" to encrypted.nonce,
                "isCompleted" to item.isCompleted, "dueDate" to item.dueDate,
                "sectionId" to item.sectionId, "order" to item.order,
                "createdBy" to item.createdBy.ifBlank { uid },
                "createdAt" to if (item.createdBy.isBlank()) FieldValue.serverTimestamp() else item.createdAt,
                "updatedAt" to FieldValue.serverTimestamp(), "encryptionVersion" to "space-member-key-v1"
            )
        )
        val recipients = (spaces.mentionedMemberIds(context, "${item.title}\n${item.notes}", space) + item.assignedMemberIds).distinct()
        spaces.recordTargetedModuleNotification(
            context, recipients, "list", space, "mentioned you in a List", list.title,
            list.id, ActivityTargetType.Lists
        )
    }

    suspend fun deleteItem(context: Context, space: Space, list: SpaceList, item: SpaceListItem) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val permission = if (list.createdBy == uid) SpacePermission.EditOwnLists else SpacePermission.EditAnyLists
        check(spaces.canPerform(context, space, permission))
        item.attachments.forEach { runCatching { awaitDeleteStorage(it.storagePath) } }
        awaitDelete(firestore.collection("spaces").document(space.id).collection("lists").document(list.id).collection("items").document(item.id))
    }

    suspend fun deleteList(context: Context, space: Space, list: SpaceList) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val permission = if (list.createdBy == uid) SpacePermission.DeleteOwnLists else SpacePermission.DeleteAnyLists
        check(spaces.canPerform(context, space, permission))
        val ref = firestore.collection("spaces").document(space.id).collection("lists").document(list.id)
        val items = awaitGet(ref.collection("items"))
        val key = spaces.encryptionKeyForModuleData(context, space.id)
        items.documents.forEach { document ->
            runCatching { mapItem(document, list.id, key) }.getOrNull()?.attachments?.forEach {
                runCatching { awaitDeleteStorage(it.storagePath) }
            }
            awaitDelete(document.reference)
        }
        awaitDelete(ref)
    }

    suspend fun uploadAttachment(context: Context, space: Space, listId: String, bytes: ByteArray, name: String, mimeType: String, isMedia: Boolean): SpaceListItemAttachment {
        val id = UUID.randomUUID().toString()
        val encrypted = encryption.encryptBytes(bytes, spaces.encryptionKeyForModuleData(context, space.id))
        val path = "spaces/${space.id}/lists/$listId/attachments/$id.enc"
        awaitUpload(path, Base64.decode(encrypted.ciphertext, Base64.NO_WRAP))
        return SpaceListItemAttachment(id, name, mimeType, path, encrypted.nonce, isMedia)
    }

    suspend fun downloadAttachment(context: Context, space: Space, attachment: SpaceListItemAttachment): ByteArray {
        val encrypted = awaitDownload(attachment.storagePath)
        return encryption.decryptBytes(
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
            attachment.nonce,
            spaces.encryptionKeyForModuleData(context, space.id)
        )
    }

    private fun mapList(doc: DocumentSnapshot, spaceId: String, key: ByteArray): SpaceList {
        val json = JSONObject(encryption.decryptText(doc.getString("ciphertext").orEmpty(), doc.getString("nonce").orEmpty(), null, key))
        val sections = json.optJSONArray("sections").objects().map {
            SpaceListSection(it.getString("id"), it.getString("title"), it.optInt("order"))
        }.sortedBy { it.order }
        return SpaceList(
            doc.id, spaceId, json.getString("title"), sections,
            json.optJSONArray("links").objects().map(::mapLink),
            doc.getString("createdBy").orEmpty(), doc.getTimestamp("createdAt")?.toDate() ?: Date(),
            doc.getTimestamp("updatedAt")?.toDate() ?: Date()
        )
    }

    private fun mapItem(doc: DocumentSnapshot, listId: String, key: ByteArray): SpaceListItem {
        val json = JSONObject(encryption.decryptText(doc.getString("ciphertext").orEmpty(), doc.getString("nonce").orEmpty(), null, key))
        return SpaceListItem(
            doc.id, listId, json.getString("title"), json.optString("notes"),
            doc.getBoolean("isCompleted") ?: false,
            json.optJSONArray("assignedMemberIds").strings().toSet(),
            doc.getTimestamp("dueDate")?.toDate(), doc.getString("sectionId"), doc.getLong("order")?.toInt() ?: 0,
            json.optJSONArray("attachments").objects().map(::mapAttachment),
            json.optJSONArray("links").objects().map(::mapLink),
            doc.getString("createdBy").orEmpty(), doc.getTimestamp("createdAt")?.toDate() ?: Date(),
            doc.getTimestamp("updatedAt")?.toDate() ?: Date()
        )
    }

    private fun linkJson(link: SpaceLinkAttachment) = JSONObject().put("id", link.id).put("moduleType", link.moduleType.name)
        .put("targetId", link.targetId).put("title", link.title).put("subtitle", link.subtitle).put("icon", link.icon)
    private fun mapLink(json: JSONObject) = SpaceLinkAttachment(
        json.getString("id"), runCatching { SpaceLinkModuleType.valueOf(json.getString("moduleType")) }.getOrDefault(SpaceLinkModuleType.Files),
        json.getString("targetId"), json.getString("title"), json.optString("subtitle").ifBlank { null }, json.optString("icon")
    )
    private fun attachmentJson(a: SpaceListItemAttachment) = JSONObject().put("id", a.id).put("name", a.name).put("mimeType", a.mimeType)
        .put("storagePath", a.storagePath).put("nonce", a.nonce).put("isMedia", a.isMedia)
    private fun mapAttachment(j: JSONObject) = SpaceListItemAttachment(j.getString("id"), j.getString("name"), j.getString("mimeType"), j.getString("storagePath"), j.getString("nonce"), j.optBoolean("isMedia"))
    private fun JSONArray?.objects() = (0 until (this?.length() ?: 0)).map { this!!.getJSONObject(it) }
    private fun JSONArray?.strings() = (0 until (this?.length() ?: 0)).map { this!!.getString(it) }

    private suspend fun awaitSet(ref: DocumentReference, data: Map<String, Any?>) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
        ref.set(data).addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException)
    }
    private suspend fun awaitDelete(ref: DocumentReference) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
        ref.delete().addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException)
    }
    private suspend fun awaitUpdate(ref: DocumentReference, data: Map<String, Any?>) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
        ref.update(data).addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException)
    }
    private suspend fun awaitGet(ref: CollectionReference) = kotlinx.coroutines.suspendCancellableCoroutine<QuerySnapshot> { c ->
        ref.get().addOnSuccessListener { c.resume(it) {} }.addOnFailureListener(c::resumeWithException)
    }
    private suspend fun awaitUpload(path: String, bytes: ByteArray) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
        storage.reference.child(path).putBytes(bytes).addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException)
    }
    private suspend fun awaitDeleteStorage(path: String) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
        storage.reference.child(path).delete().addOnSuccessListener { c.resume(Unit) {} }.addOnFailureListener(c::resumeWithException)
    }
    private suspend fun awaitDownload(path: String) = kotlinx.coroutines.suspendCancellableCoroutine<ByteArray> { c ->
        storage.reference.child(path).getBytes(250L * 1024L * 1024L)
            .addOnSuccessListener { c.resume(it) {} }
            .addOnFailureListener(c::resumeWithException)
    }
}

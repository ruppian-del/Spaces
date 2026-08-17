package com.arcinteractive.spaces.data.spaces

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class SpaceNotificationPreference(
    val allEnabled: Boolean = true,
    val categories: Map<String, Boolean> = emptyMap()
) {
    fun isEnabled(category: String): Boolean = allEnabled && (categories[category] ?: true)
}

object SpaceNotificationCategory {
    const val Announcements = "announcements"
    const val Rooms = "rooms"
    const val Notes = "notes"
    const val Lists = "lists"
    const val Events = "events"
    const val Polls = "polls"
    const val MediaAndFiles = "mediaAndFiles"
    const val CommentsAndReplies = "commentsAndReplies"
    const val Mentions = "mentions"
    const val Assignments = "assignments"
}

class SpaceNotificationPreferencesService {
    suspend fun load(context: Context): Map<String, SpaceNotificationPreference> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyMap()
        if (FirebaseApp.getApps(context.applicationContext).isEmpty()) return emptyMap()
        val snapshot = suspendCancellableCoroutine { continuation ->
            FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener(continuation::resumeWithException)
        }
        return decode(snapshot.get("spaceNotificationSettings"))
    }

    suspend fun save(context: Context, spaceId: String, preference: SpaceNotificationPreference) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (FirebaseApp.getApps(context.applicationContext).isEmpty()) return
        val reference = FirebaseFirestore.getInstance().collection("users").document(uid)
        val snapshot = suspendCancellableCoroutine { continuation ->
            reference.get().addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener(continuation::resumeWithException)
        }
        val settings = (snapshot.get("spaceNotificationSettings") as? Map<*, *>)
            ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
            ?.toMap()?.toMutableMap() ?: mutableMapOf()
        settings[spaceId] = mapOf(
            "allEnabled" to preference.allEnabled,
            "categories" to preference.categories
        )
        suspendCancellableCoroutine<Unit> { continuation ->
            reference.set(
                mapOf(
                    "spaceNotificationSettings" to settings,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener(continuation::resumeWithException)
        }
    }

    companion object {
        fun decode(rawValue: Any?): Map<String, SpaceNotificationPreference> =
            (rawValue as? Map<*, *>)?.mapNotNull { (key, value) ->
                val spaceId = key as? String ?: return@mapNotNull null
                when (value) {
                    is Boolean -> spaceId to SpaceNotificationPreference(allEnabled = value)
                    is Map<*, *> -> {
                        val categories = (value["categories"] as? Map<*, *>)
                            ?.mapNotNull { (category, enabled) ->
                                (category as? String)?.let { it to (enabled as? Boolean ?: true) }
                            }?.toMap().orEmpty()
                        spaceId to SpaceNotificationPreference(
                            allEnabled = value["allEnabled"] as? Boolean ?: true,
                            categories = categories
                        )
                    }
                    else -> null
                }
            }?.toMap().orEmpty()
    }
}

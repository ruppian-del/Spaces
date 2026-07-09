package com.arcinteractive.spaces.data.auth

import android.content.Context
import com.google.firebase.firestore.SetOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PushTokenService(
    private val authService: AuthService = AuthService(),
    private val deviceIdentityService: DeviceIdentityService = DeviceIdentityService()
) {
    suspend fun syncCurrentToken(context: Context) {
        val session = authService.currentSession(context) ?: return
        val firestore = firestoreOrNull(context) ?: return
        val token = awaitFcmToken() ?: return

        disablePreviousBindingIfNeeded(
            context = context,
            firestore = firestore,
            currentUserId = session.uid,
            token = token
        )

        saveToken(
            firestore = firestore,
            userId = session.uid,
            token = token,
            deviceId = deviceIdentityService.currentDeviceId(context),
            enabled = true
        )
        context.pushPreferences().edit()
            .putString(KEY_LAST_BOUND_USER_ID, session.uid)
            .putString(KEY_CURRENT_TOKEN, token)
            .apply()
    }

    suspend fun handleNewToken(context: Context, token: String) {
        if (token.isBlank()) return
        val firestore = firestoreOrNull(context) ?: return
        val session = authService.currentSession(context)
        context.pushPreferences().edit().putString(KEY_CURRENT_TOKEN, token).apply()
        if (session != null) {
            disablePreviousBindingIfNeeded(
                context = context,
                firestore = firestore,
                currentUserId = session.uid,
                token = token
            )
            saveToken(
                firestore = firestore,
                userId = session.uid,
                token = token,
                deviceId = deviceIdentityService.currentDeviceId(context),
                enabled = true
            )
            context.pushPreferences().edit().putString(KEY_LAST_BOUND_USER_ID, session.uid).apply()
        }
    }

    suspend fun disableCurrentTokenForSignedOutUser(context: Context) {
        val previousUserId = context.pushPreferences().getString(KEY_LAST_BOUND_USER_ID, null)?.trim().orEmpty()
        val token = context.pushPreferences().getString(KEY_CURRENT_TOKEN, null)?.trim().orEmpty()
        val firestore = firestoreOrNull(context)
        if (previousUserId.isNotEmpty() && token.isNotEmpty() && firestore != null) {
            disableTokenForUser(firestore, previousUserId, token, deviceIdentityService.currentDeviceId(context))
        }
        context.pushPreferences().edit().remove(KEY_LAST_BOUND_USER_ID).apply()
    }

    suspend fun setCurrentTokenEnabled(context: Context, enabled: Boolean) {
        val session = authService.currentSession(context) ?: throw IllegalStateException("Sign in before updating notifications.")
        val token = context.pushPreferences().getString(KEY_CURRENT_TOKEN, null)?.trim().orEmpty()
        if (token.isEmpty()) {
            throw IllegalStateException("Push token unavailable.")
        }
        val firestore = firestoreOrNull(context) ?: throw IllegalStateException("Firestore is not configured yet.")
        disablePreviousBindingIfNeeded(
            context = context,
            firestore = firestore,
            currentUserId = session.uid,
            token = token
        )
        saveToken(
            firestore = firestore,
            userId = session.uid,
            token = token,
            deviceId = deviceIdentityService.currentDeviceId(context),
            enabled = enabled
        )
        if (enabled) {
            context.pushPreferences().edit().putString(KEY_LAST_BOUND_USER_ID, session.uid).apply()
        }
    }

    private suspend fun disablePreviousBindingIfNeeded(
        context: Context,
        firestore: FirebaseFirestore,
        currentUserId: String,
        token: String
    ) {
        val previousUserId = context.pushPreferences().getString(KEY_LAST_BOUND_USER_ID, null)?.trim().orEmpty()
        if (previousUserId.isNotEmpty() && previousUserId != currentUserId) {
            disableTokenForUser(firestore, previousUserId, token, deviceIdentityService.currentDeviceId(context))
        }
    }

    private suspend fun awaitFcmToken(): String? {
        return try {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { continuation.resume(it?.trim().orEmpty().ifEmpty { null }) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun saveToken(
        firestore: FirebaseFirestore,
        userId: String,
        token: String,
        deviceId: String,
        enabled: Boolean
    ) {
        val tokenId = tokenDocumentId(token)
        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.collection("users")
                .document(userId)
                .collection("pushTokens")
                .document(tokenId)
                .set(
                    mapOf(
                        "id" to tokenId,
                        "userId" to userId,
                        "token" to token,
                        "platform" to "android",
                        "deviceId" to deviceId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "enabled" to enabled
                    ),
                    SetOptions.merge()
                )
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private suspend fun disableTokenForUser(
        firestore: FirebaseFirestore,
        userId: String,
        token: String,
        deviceId: String
    ) {
        saveToken(
            firestore = firestore,
            userId = userId,
            token = token,
            deviceId = deviceId,
            enabled = false
        )
    }

    private fun firestoreOrNull(context: Context): FirebaseFirestore? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }

    private fun tokenDocumentId(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun Context.pushPreferences() =
        getSharedPreferences("spaces_push_tokens", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_LAST_BOUND_USER_ID = "last_bound_user_id"
        const val KEY_CURRENT_TOKEN = "current_token"
    }
}

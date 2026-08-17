package com.arcinteractive.spaces.data.auth

import android.content.Context
import com.arcinteractive.spaces.data.model.LinkedProvider
import com.arcinteractive.spaces.data.model.AccountDevice
import com.arcinteractive.spaces.data.model.BlockedUser
import com.arcinteractive.spaces.data.model.PushTokenRecord
import com.arcinteractive.spaces.data.model.UserProfile
import com.arcinteractive.spaces.data.spaces.EncryptionService
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UserProfileService(
    private val encryptionService: EncryptionService = EncryptionService(),
    private val deviceIdentityService: DeviceIdentityService = DeviceIdentityService()
) {
    suspend fun fetchUserProfile(context: Context, uid: String): UserProfile? {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val snapshot = suspendCancellableCoroutine { continuation ->
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        if (!snapshot.exists()) return null

        val data = snapshot.data ?: return null
        val providers = (data["providers"] as? List<*>)?.mapNotNull { raw ->
            (raw as? String)?.let { label ->
                LinkedProvider.entries.firstOrNull { it.label == label }
            }
        }.orEmpty()
        val blockedUsers = (data["blockedUsers"] as? List<*>)?.mapNotNull { raw ->
            val entry = raw as? Map<*, *> ?: return@mapNotNull null
            val uidValue = (entry["uid"] as? String)?.trim().orEmpty()
            if (uidValue.isEmpty()) return@mapNotNull null

            BlockedUser(
                id = uidValue,
                uid = uidValue,
                displayName = (entry["displayName"] as? String)?.trim().orEmpty().ifBlank { "Blocked User" },
                emojiAvatar = (entry["emojiAvatar"] as? String)?.trim().orEmpty().ifBlank { "🚫" },
                blockedAt = (entry["blockedAt"] as? com.google.firebase.Timestamp)?.toDate()
            )
        }?.sortedByDescending { it.blockedAt?.time ?: Long.MIN_VALUE }.orEmpty()

        return UserProfile(
            id = snapshot.id,
            uid = data["uid"] as? String ?: snapshot.id,
            displayName = data["displayName"] as? String ?: "Spaces User",
            emojiAvatar = (data["emojiAvatar"] as? String).orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" },
            statusMessage = data["status"] as? String ?: "",
            email = data["email"] as? String,
            phoneNumber = data["phoneNumber"] as? String,
            linkedProviders = providers,
            blockedUsers = blockedUsers
        )
    }

    suspend fun createUserProfile(
        context: Context,
        session: AuthSession,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String
    ): UserProfile {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val trimmedDisplayName = displayName.trim()
        require(trimmedDisplayName.isNotEmpty()) { "Display Name is required." }

        val resolvedEmoji = emojiAvatar.trim().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }.take(2)
        val trimmedStatus = statusMessage.trim()
        val identity = prepareCurrentDeviceIdentity(context, session)
        val data = hashMapOf<String, Any?>(
            "uid" to session.uid,
            "displayName" to trimmedDisplayName,
            "emojiAvatar" to resolvedEmoji,
            "status" to trimmedStatus,
            "e2eePublicKey" to identity.publicKey,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "email" to session.email,
            "phoneNumber" to session.phoneNumber,
            "providers" to session.providers.map { it.label }
        )

        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.collection("users").document(session.uid).set(data)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return UserProfile(
            id = session.uid,
            uid = session.uid,
            displayName = trimmedDisplayName,
            emojiAvatar = resolvedEmoji,
            statusMessage = trimmedStatus,
            email = session.email,
            phoneNumber = session.phoneNumber,
            linkedProviders = session.providers
        )
    }

    suspend fun updateUserProfile(
        context: Context,
        session: AuthSession,
        displayName: String,
        emojiAvatar: String,
        statusMessage: String
    ): UserProfile {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val trimmedDisplayName = displayName.trim()
        require(trimmedDisplayName.isNotEmpty()) { "Display Name is required." }

        val resolvedEmoji = emojiAvatar.trim().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" }.take(2)
        val trimmedStatus = statusMessage.trim()

        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.collection("users").document(session.uid).set(
                mapOf(
                    "displayName" to trimmedDisplayName,
                    "emojiAvatar" to resolvedEmoji,
                    "status" to trimmedStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return UserProfile(
            id = session.uid,
            uid = session.uid,
            displayName = trimmedDisplayName,
            emojiAvatar = resolvedEmoji,
            statusMessage = trimmedStatus,
            email = session.email,
            phoneNumber = session.phoneNumber,
            linkedProviders = session.providers
        )
    }

    suspend fun syncAuthSessionIfProfileExists(
        context: Context,
        session: AuthSession
    ): UserProfile? {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val identity = prepareCurrentDeviceIdentity(context, session)
        val snapshot = suspendCancellableCoroutine { continuation ->
            firestore.collection("users").document(session.uid).get()
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        if (!snapshot.exists()) return null

        val data = snapshot.data.orEmpty()
        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.collection("users").document(session.uid).set(
                mapOf(
                    "email" to session.email,
                    "phoneNumber" to session.phoneNumber,
                    "providers" to session.providers.map { it.label },
                    "e2eePublicKey" to identity.publicKey,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return UserProfile(
            id = snapshot.id,
            uid = data["uid"] as? String ?: snapshot.id,
            displayName = data["displayName"] as? String ?: session.displayName,
            emojiAvatar = (data["emojiAvatar"] as? String).orEmpty().ifBlank { "\uD83E\uDDD1\u200D\uD83D\uDCBB" },
            statusMessage = data["status"] as? String ?: "",
            email = session.email,
            phoneNumber = session.phoneNumber,
            linkedProviders = session.providers
        )
    }

    fun ensureEncryptionIdentity(session: AuthSession): String {
        return encryptionService.ensurePublicKey(session.uid)
    }

    fun currentDeviceId(context: Context): String {
        return deviceIdentityService.currentDeviceId(context)
    }

    suspend fun prepareCurrentDeviceIdentity(
        context: Context,
        session: AuthSession
    ): DeviceEncryptionIdentity {
        val deviceId = currentDeviceId(context)
        val platform = deviceIdentityService.currentPlatform()
        val publicKey = ensureEncryptionIdentity(session)

        val firestore = firestoreOrNull(context)
        if (firestore != null) {
            runCatching {
                suspendCancellableCoroutine<Unit> { continuation ->
                    firestore.collection("users").document(session.uid).collection("devices").document(deviceId).set(
                        mapOf(
                            "userId" to session.uid,
                            "deviceId" to deviceId,
                            "publicKey" to publicKey,
                            "platform" to platform,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "lastActiveAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                        .addOnSuccessListener { continuation.resume(Unit) }
                        .addOnFailureListener { error -> continuation.resumeWithException(error) }
                }
            }
            // Fall back to the legacy user-level key path when device-key rules are not deployed yet.
        }

        return DeviceEncryptionIdentity(deviceId = deviceId, platform = platform, publicKey = publicKey)
    }

    suspend fun fetchEncryptionPublicKeys(
        context: Context,
        uid: String
    ): List<DeviceEncryptionIdentity> {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val identities = runCatching {
            val devicesSnapshot = suspendCancellableCoroutine { continuation ->
                firestore.collection("users").document(uid).collection("devices").get()
                    .addOnSuccessListener { result -> continuation.resume(result) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }

            devicesSnapshot.documents.mapNotNull { document ->
                val data = document.data ?: return@mapNotNull null
                val publicKey = (data["publicKey"] as? String)?.trim().orEmpty()
                if (publicKey.isEmpty()) return@mapNotNull null
                val deviceId = (data["deviceId"] as? String)?.trim().orEmpty().ifBlank { document.id }
                val platform = (data["platform"] as? String)?.trim().orEmpty().ifBlank { "unknown" }
                DeviceEncryptionIdentity(deviceId = deviceId, platform = platform, publicKey = publicKey)
            }.toMutableList()
        }.getOrElse { mutableListOf() }

        val legacyPublicKey = fetchEncryptionPublicKey(context, uid, null)
        if (legacyPublicKey != null) {
            val legacyIdentity = DeviceEncryptionIdentity(deviceId = "__legacy__", platform = "legacy", publicKey = legacyPublicKey)
            if (!identities.contains(legacyIdentity)) {
                identities.add(legacyIdentity)
            }
        }

        return identities
    }

    suspend fun fetchEncryptionPublicKey(context: Context, uid: String, deviceId: String?): String? {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        if (deviceId != null && deviceId.isNotBlank() && deviceId != "__legacy__") {
            val deviceKey = runCatching {
                val snapshot = suspendCancellableCoroutine { continuation ->
                    firestore.collection("users").document(uid).collection("devices").document(deviceId).get()
                        .addOnSuccessListener { result -> continuation.resume(result) }
                        .addOnFailureListener { error -> continuation.resumeWithException(error) }
                }
                snapshot.data?.get("publicKey") as? String
            }.getOrNull()
            if (!deviceKey.isNullOrBlank()) {
                return deviceKey
            }
        }

        val snapshot = suspendCancellableCoroutine { continuation ->
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return snapshot.data?.get("e2eePublicKey") as? String
    }

    suspend fun fetchDevices(context: Context, uid: String): List<AccountDevice> {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val snapshot = suspendCancellableCoroutine { continuation ->
            firestore.collection("users").document(uid).collection("devices").get()
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return snapshot.documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            if ((data["removed"] as? Boolean) == true) return@mapNotNull null
            val deviceId = (data["deviceId"] as? String)?.trim().orEmpty().ifBlank { document.id }
            AccountDevice(
                id = document.id,
                deviceId = deviceId,
                platform = (data["platform"] as? String)?.trim().orEmpty().ifBlank { "unknown" },
                publicKey = (data["publicKey"] as? String)?.trim()?.ifEmpty { null },
                createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate(),
                lastActiveAt = (data["lastActiveAt"] as? com.google.firebase.Timestamp)?.toDate()
            )
        }.sortedByDescending { it.lastActiveAt?.time ?: Long.MIN_VALUE }
    }

    suspend fun fetchPushTokens(context: Context, uid: String): List<PushTokenRecord> {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val snapshot = suspendCancellableCoroutine { continuation ->
            firestore.collection("users").document(uid).collection("pushTokens").get()
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

        return snapshot.documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            if ((data["removed"] as? Boolean) == true) return@mapNotNull null
            val token = (data["token"] as? String)?.trim().orEmpty()
            if (token.isEmpty()) return@mapNotNull null

            PushTokenRecord(
                id = document.id,
                userId = (data["userId"] as? String) ?: uid,
                token = token,
                platform = (data["platform"] as? String)?.trim().orEmpty().ifBlank { "unknown" },
                deviceId = (data["deviceId"] as? String)?.trim()?.ifEmpty { null },
                enabled = (data["enabled"] as? Boolean) ?: false,
                createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate(),
                updatedAt = (data["updatedAt"] as? com.google.firebase.Timestamp)?.toDate()
            )
        }.sortedByDescending { it.updatedAt?.time ?: Long.MIN_VALUE }
    }

    suspend fun removeDevice(
        context: Context,
        uid: String,
        device: AccountDevice,
        relatedPushTokens: List<PushTokenRecord>
    ) {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val deviceReference = firestore.collection("users").document(uid).collection("devices").document(device.id)

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                deviceReference.delete()
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
        } catch (_: Throwable) {
            device.publicKey?.takeIf { key -> key.isNotBlank() }?.let { publicKey ->
                suspendCancellableCoroutine<Unit> { continuation ->
                    deviceReference.set(
                        mapOf(
                            "userId" to uid,
                            "deviceId" to device.deviceId,
                            "platform" to device.platform,
                            "publicKey" to publicKey,
                            "removed" to true,
                            "removedAt" to FieldValue.serverTimestamp(),
                            "lastActiveAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                        .addOnSuccessListener { continuation.resume(Unit) }
                        .addOnFailureListener { error -> continuation.resumeWithException(error) }
                }
            }
        }

        relatedPushTokens.forEach { token ->
            val tokenReference = firestore.collection("users").document(uid).collection("pushTokens").document(token.id)

            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    tokenReference.delete()
                        .addOnSuccessListener { continuation.resume(Unit) }
                        .addOnFailureListener { error -> continuation.resumeWithException(error) }
                }
            } catch (_: Throwable) {
                runCatching {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        tokenReference.set(
                            mapOf(
                                "id" to token.id,
                                "userId" to uid,
                                "token" to token.token,
                                "platform" to token.platform,
                                "deviceId" to token.deviceId,
                                "enabled" to false,
                                "removed" to true,
                                "removedAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                            .addOnSuccessListener { continuation.resume(Unit) }
                            .addOnFailureListener { error -> continuation.resumeWithException(error) }
                    }
                }
            }
        }
    }

    suspend fun updateBlockedUsers(context: Context, uid: String, blockedUsers: List<BlockedUser>) {
        val firestore = firestoreOrNull(context)
            ?: throw IllegalStateException("Firestore is not configured yet.")

        val payload = blockedUsers.map { user ->
            mapOf(
                "uid" to user.uid,
                "displayName" to user.displayName,
                "emojiAvatar" to user.emojiAvatar,
                "blockedAt" to (user.blockedAt?.let { com.google.firebase.Timestamp(it) } ?: com.google.firebase.Timestamp.now())
            )
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.collection("users").document(uid).set(
                mapOf(
                    "blockedUsers" to payload,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    private fun firestoreOrNull(context: Context): FirebaseFirestore? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    }
}

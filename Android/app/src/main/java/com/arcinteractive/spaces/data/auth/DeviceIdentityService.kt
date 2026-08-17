package com.arcinteractive.spaces.data.auth

import android.content.Context
import java.util.UUID

data class DeviceEncryptionIdentity(
    val deviceId: String,
    val platform: String,
    val publicKey: String
)

class DeviceIdentityService {
    fun currentDeviceId(context: Context): String {
        val preferences = context.getSharedPreferences("spaces_device_identity", Context.MODE_PRIVATE)
        val existing = preferences.getString("device_id", null)?.trim().orEmpty()
        if (existing.isNotEmpty()) {
            return existing
        }

        val newId = "android-${UUID.randomUUID().toString().lowercase()}"
        preferences.edit().putString("device_id", newId).apply()
        return newId
    }

    fun currentPlatform(): String {
        return "android"
    }
}

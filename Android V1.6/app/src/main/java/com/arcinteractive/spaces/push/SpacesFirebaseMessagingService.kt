package com.arcinteractive.spaces.push

import android.Manifest
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arcinteractive.spaces.MainActivity
import com.arcinteractive.spaces.R
import com.arcinteractive.spaces.data.auth.PushTokenService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SpacesFirebaseMessagingService : FirebaseMessagingService() {
    private val pushTokenService = PushTokenService()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            pushTokenService.handleNewToken(applicationContext, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        showForegroundNotification(message)
    }

    private fun showForegroundNotification(message: RemoteMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = message.notification?.title?.trim().orEmpty().ifEmpty {
            message.data[MainActivity.EXTRA_NOTIFICATION_TYPE]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.replaceFirstChar { it.uppercase() }
                ?: getString(R.string.app_name)
        }
        val body = message.notification?.body?.trim().orEmpty().ifEmpty {
            message.data[MainActivity.EXTRA_SPACE_ID]?.trim().orEmpty().ifEmpty {
                getString(R.string.push_notification_fallback_body)
            }
        }
        val badgeCount = message.data["badgeCount"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NOTIFICATION_ID, message.data[MainActivity.EXTRA_NOTIFICATION_ID])
            putExtra(MainActivity.EXTRA_NOTIFICATION_TYPE, message.data[MainActivity.EXTRA_NOTIFICATION_TYPE])
            putExtra(MainActivity.EXTRA_SPACE_ID, message.data[MainActivity.EXTRA_SPACE_ID])
            putExtra(MainActivity.EXTRA_TARGET_ID, message.data[MainActivity.EXTRA_TARGET_ID])
            putExtra(MainActivity.EXTRA_TARGET_TYPE, message.data[MainActivity.EXTRA_TARGET_TYPE])
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            message.data[MainActivity.EXTRA_NOTIFICATION_ID]?.hashCode() ?: System.currentTimeMillis().toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, getString(R.string.push_notification_channel_id))
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(badgeCount)
            .build()

        NotificationManagerCompat.from(this).notify(
            message.data[MainActivity.EXTRA_NOTIFICATION_ID]?.hashCode() ?: notification.hashCode(),
            notification
        )
    }
}

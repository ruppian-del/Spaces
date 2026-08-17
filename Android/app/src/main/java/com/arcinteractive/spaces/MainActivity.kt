package com.arcinteractive.spaces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.arcinteractive.spaces.data.spaces.InviteLink
import com.arcinteractive.spaces.ui.navigation.AppViewModel
import com.arcinteractive.spaces.ui.navigation.SpacesApp
import com.arcinteractive.spaces.ui.theme.SpacesTheme

class MainActivity : FragmentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannelIfNeeded()
        handleIntent(intent)
        setContent {
            SpacesTheme {
                SpacesApp(appViewModel = appViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        appViewModel.handleInviteCode(InviteLink.parse(intent?.data))
        appViewModel.handleNotificationNavigation(
            notificationId = intent?.getStringExtra(EXTRA_NOTIFICATION_ID),
            type = intent?.getStringExtra(EXTRA_NOTIFICATION_TYPE),
            spaceId = intent?.getStringExtra(EXTRA_SPACE_ID),
            targetId = intent?.getStringExtra(EXTRA_TARGET_ID),
            targetType = intent?.getStringExtra(EXTRA_TARGET_TYPE)
        )
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channelId = getString(R.string.push_notification_channel_id)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            getString(R.string.push_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.push_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_NOTIFICATION_TYPE = "type"
        const val EXTRA_SPACE_ID = "spaceId"
        const val EXTRA_TARGET_ID = "targetId"
        const val EXTRA_TARGET_TYPE = "targetType"
    }
}

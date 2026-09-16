package com.backgroundlocation.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.backgroundlocation.NotificationDefaults

/**
 * Posts the local notification shown when [LocationSyncManager] auth-blocks after a 401.
 * Mirrors `com.backgroundlocation.GeofenceNotificationHelper`'s channel-creation pattern.
 */
object SyncNotificationHelper {

    private const val CHANNEL_ID = "location_sync_channel"
    private const val CHANNEL_NAME = "Location Sync"
    private const val NOTIFICATION_ID = 20000

    fun showAuthBlockedNotification(context: Context) {
        createChannelIfNeeded(context)

        val smallIcon = NotificationDefaults.getSmallIcon(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Tracking sync paused")
            .setContentText("Tracking sync paused — open the app to re-authenticate")
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when background location sync needs attention"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}

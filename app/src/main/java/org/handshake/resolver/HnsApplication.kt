package org.handshake.resolver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HnsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Handshake Resolver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live sync status and DNS resolver state"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "hns_resolver_channel"
        const val NOTIFICATION_ID = 1001
    }
}

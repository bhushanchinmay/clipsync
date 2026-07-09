package com.clipsync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Application class for ClipSync.
 * 
 * This is the first class instantiated when the app process starts.
 * We use it to create the notification channel required for the foreground service.
 * Android 8.0+ (API 26+) requires notification channels before showing any notification.
 */
class ClipSyncApp : Application() {
    companion object {
        /** Channel ID used for the foreground service notification */
        const val CHANNEL_ID = "clipsync_service"
        /** Human-readable channel name shown in system notification settings */
        const val CHANNEL_NAME = "ClipSync Service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Creates the notification channel for the foreground service.
     * 
     * IMPORTANCE_LOW means the notification will show in the shade but won't make
     * a sound or vibrate — appropriate for a persistent status indicator.
     * 
     * This method is idempotent — calling it multiple times is safe.
     * If the channel already exists, the system ignores the call.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows ClipSync connection status and sync activity"
            setShowBadge(false) // Don't show a badge on the app icon
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

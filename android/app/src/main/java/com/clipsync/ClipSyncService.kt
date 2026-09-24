package com.clipsync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.clipsync.data.ClipboardDatabase
import com.clipsync.data.ClipboardEntry
import kotlinx.coroutines.*

/**
 * Foreground service that orchestrates clipboard sync.
 *
 * This service is the central coordinator of ClipSync. It:
 * 1. Discovers the Mac daemon via mDNS (NsdHelper)
 * 2. Connects via TCP when found (TcpClient)
 * 3. Receives clipboard text from the Mac
 * 4. Sets the Android clipboard (via ClipboardHelper)
 * 5. Saves received text to Room database for history
 * 6. Shows persistent notification with connection status
 *
 * FOREGROUND SERVICE:
 * Android requires long-running services to show a persistent notification.
 * This prevents the system from killing our service to reclaim memory.
 * The notification also shows the user what's happening.
 *
 * The service type is "dataSync" (declared in the manifest and passed to
 * startForeground() on Android 14+). Note: apps targeting Android 15 (API 35)
 * get a 6-hour daily limit on dataSync services.
 */
class ClipSyncService : Service(), NsdHelper.NsdCallback, TcpClient.TcpCallback {
    companion object {
        private const val TAG = "ClipSyncService"
        private const val NOTIFICATION_ID = 1

        /**
         * LiveData for status updates, observed by MainActivity.
         * Using a companion object is the simplest way to share state
         * between a Service and an Activity without Bound Service complexity.
         */
        val statusLiveData = MutableLiveData("Initializing...")
        
        @Volatile
        var isRunning = false
    }

    private lateinit var nsdHelper: NsdHelper
    private lateinit var tcpClient: TcpClient
    private lateinit var db: ClipboardDatabase

    /** mDNS name of the service we are currently connected (or connecting) to */
    @Volatile
    private var currentServiceName: String? = null

    /** Coroutine scope tied to the service lifecycle */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Service Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Initialize database
        db = ClipboardDatabase.getInstance(this)

        // Initialize network helpers
        nsdHelper = NsdHelper(this, this)
        tcpClient = TcpClient(this)

        // Start as a foreground service with an initial "searching" notification
        startForegroundWithNotification("Searching for ClipSync server...")

        // Begin mDNS discovery
        nsdHelper.startDiscovery()
    }

    /**
     * Called when the service is started via startService() or startForegroundService().
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        if (intent?.action == "ACTION_STOP") {
            // Stop the foreground service and the service itself
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null // Not a bound service

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        isRunning = false
        // Clean up all resources
        tcpClient.disconnect()
        nsdHelper.stopDiscovery()
        serviceScope.cancel()
        statusLiveData.postValue("Service stopped")
        super.onDestroy()
    }

    // ── NSD Callbacks ─────────────────────────────────────────────────────────

    /**
     * Called when NsdHelper resolves a ClipSync service on the network.
     * We now have the IP and port to connect to.
     */
    override fun onServiceResolved(serviceName: String, host: String, port: Int) {
        Log.i(TAG, "Service resolved: $host:$port")
        currentServiceName = serviceName
        // The same service is often reported more than once (e.g. per network
        // interface). Don't tear down a working connection to the same address.
        if (tcpClient.isTargeting(host, port)) {
            Log.d(TAG, "Already connected to $host:$port, ignoring")
            return
        }
        updateNotification("Connecting to $host:$port...")
        statusLiveData.postValue("Connecting to $host:$port...")
        tcpClient.connect(host, port)
    }

    /**
     * Called when the Mac daemon disappears from the network.
     * This could mean the Mac went to sleep, left the network, or the daemon stopped.
     */
    override fun onServiceLost(serviceName: String) {
        // Ignore other ClipSync services we aren't connected to
        if (serviceName != currentServiceName) return
        Log.w(TAG, "Service lost: $serviceName")
        currentServiceName = null
        updateNotification("Server lost, searching...")
        statusLiveData.postValue("Server lost, searching...")
        tcpClient.disconnect()
    }

    // ── TCP Callbacks ─────────────────────────────────────────────────────────

    /**
     * Called when a complete clipboard text is received from the Mac daemon.
     *
     * Two things happen:
     * 1. Set the Android clipboard (via transparent activity trick)
     * 2. Save to Room database for history
     */
    override fun onTextReceived(text: String) {
        Log.d(TAG, "Text received: ${text.length} chars")

        // Set the system clipboard
        val copied = ClipboardHelper.setClipboard(this, text)

        // Save to database for history (on background thread)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val entry = ClipboardEntry(
                    preview = text.take(500),           // First 500 chars for list display
                    fullText = text,                     // Full text for copying
                    timestamp = System.currentTimeMillis(),
                    charCount = text.length
                )
                db.clipboardDao().insert(entry)
                // Limit to the last 20 entries
                db.clipboardDao().keepLatest20()
                Log.d(TAG, "Saved to database and trimmed history")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to database", e)
            }
        }

        // Update UI
        if (copied) {
            val preview = if (text.length > 50) text.take(50) + "..." else text
            updateNotification("Received: $preview")
            statusLiveData.postValue("Connected \u2022 Last: ${text.length} chars")
        } else {
            updateNotification("Couldn't copy ${text.length} chars \u2014 saved to history")
            statusLiveData.postValue("Connected \u2022 Last copy failed (${text.length} chars)")
        }
    }

    /**
     * Called when the Mac sent text too large for the Android clipboard.
     * The text was discarded by TcpClient, so it isn't saved to history either.
     */
    override fun onTextTooLarge(byteCount: Long) {
        Log.w(TAG, "Text too large for clipboard: $byteCount bytes")
        val kb = byteCount / 1024
        updateNotification("Skipped ${kb} KB copy \u2014 too large for the Android clipboard")
        statusLiveData.postValue("Connected \u2022 Last: skipped (${kb} KB, too large)")
    }

    /**
     * Called when the TCP connection status changes.
     * Updates both the notification and the LiveData for the UI.
     */
    override fun onStatusChanged(status: String) {
        Log.d(TAG, "Status: $status")
        updateNotification(status)
        statusLiveData.postValue(status)
    }

    // ── Notification Management ──────────────────────────────────────────────

    /**
     * Start the service in the foreground with a persistent notification.
     *
     * Android 14 (API 34) requires specifying the foreground service type
     * in startForeground(). We use FOREGROUND_SERVICE_TYPE_DATA_SYNC
     * which matches our manifest declaration.
     */
    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: must specify foreground service type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Update the existing notification with new status text.
     * Uses the same NOTIFICATION_ID so it replaces the existing notification.
     */
    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Build the foreground service notification.
     *
     * - Uses IMPORTANCE_LOW channel (no sound/vibration)
     * - Ongoing = true (can't be swiped away)
     * - Tapping opens MainActivity
     * - Shows current connection status
     */
    private fun buildNotification(contentText: String): Notification {
        // PendingIntent to open MainActivity when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ClipSyncApp.CHANNEL_ID)
            .setContentTitle("ClipSync")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)              // Can't be dismissed
            .setPriority(NotificationCompat.PRIORITY_LOW) // No sound
            .setSilent(true)               // Extra safety: no sound/vibration
            .build()
    }
}

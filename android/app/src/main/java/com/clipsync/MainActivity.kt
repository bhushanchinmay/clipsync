package com.clipsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.clipsync.data.ClipboardDatabase
import com.clipsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Main activity showing the ClipSync status and clipboard history.
 *
 * Layout:
 * ┌──────────────────────────────────────┐
 * │  ● Connected to 192.168.1.5:8090    │
 * │  [Stop Service]                      │
 * ├──────────────────────────────────────┤
 * │  Clipboard History                   │
 * ├──────────────────────────────────────┤
 * │  Hello world...                      │
 * │  14:23:05 Jul 09         42 chars    │
 * ├──────────────────────────────────────┤
 * │  Lorem ipsum dolor sit amet...       │
 * │  14:20:12 Jul 09      1,234 chars    │
 * └──────────────────────────────────────┘
 *
 * The status is updated via ClipSyncService.statusLiveData (a companion object
 * LiveData that the service posts to). This is the simplest way to share state
 * between a Service and Activity without using a Bound Service.
 *
 * Tapping a history entry copies its full text to the clipboard.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ClipboardHistoryAdapter
    private var isServiceRunning = false

    /**
     * Permission request launcher for POST_NOTIFICATIONS.
     * Android 13+ (API 33+) requires runtime permission to show notifications.
     * We need this for the foreground service notification.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startClipSyncService()
        } else {
            Toast.makeText(
                this,
                "Notification permission needed for background sync",
                Toast.LENGTH_LONG
            ).show()
            // Start anyway — service works without notification on older Android
            startClipSyncService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupStatusObserver()
        setupToggleButton()
        observeHistory()
        requestNotificationPermissionAndStart()
    }

    /**
     * Set up the RecyclerView with a LinearLayoutManager and our adapter.
     * When an item is tapped, copy its full text to the clipboard.
     */
    private fun setupRecyclerView() {
        adapter = ClipboardHistoryAdapter { entry ->
            // Tapping copies the entry to clipboard
            // We need the full text, so fetch it from the database
            lifecycleScope.launch {
                // Room runs suspend queries off the main thread
                val fullEntry = ClipboardDatabase.getInstance(this@MainActivity)
                    .clipboardDao().getById(entry.id)
                if (fullEntry != null) {
                    val copied = ClipboardHelper.setClipboard(this@MainActivity, fullEntry.fullText)
                    Toast.makeText(
                        this@MainActivity,
                        if (copied) "Copied to clipboard" else "Couldn't copy to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    /**
     * Observe the service status LiveData and update the UI.
     * The status dot color changes based on the status text:
     * - Green: Connected
     * - Red: Disconnected/Error
     * - Yellow: Searching/Connecting
     */
    private fun setupStatusObserver() {
        ClipSyncService.statusLiveData.observe(this) { status ->
            // Keep the status text updated
            binding.statusText.text = status

            // Change dot color based on connection state
            if (status.contains("Connected")) {
                binding.statusDot.setColorFilter(
                    ContextCompat.getColor(this, R.color.status_connected)
                )
            } else if (status.contains("stopped") || status.contains("Failed")) {
                binding.statusDot.setColorFilter(
                    ContextCompat.getColor(this, R.color.status_disconnected)
                )
            } else {
                binding.statusDot.setColorFilter(
                    ContextCompat.getColor(this, R.color.status_connecting)
                )
            }
            
            // Sync button state
            if (ClipSyncService.isRunning) {
                binding.toggleButton.text = getString(R.string.stop_service)
            } else {
                binding.toggleButton.text = getString(R.string.start_service)
            }
        }
    }

    /**
     * Set up the start/stop toggle button.
     * Starting the service uses ContextCompat.startForegroundService()
     * which works on all API levels.
     */
    private fun setupToggleButton() {
        binding.toggleButton.setOnClickListener {
            if (ClipSyncService.isRunning) {
                val intent = Intent(this, ClipSyncService::class.java).apply {
                    action = "ACTION_STOP"
                }
                startService(intent)
                binding.toggleButton.text = getString(R.string.start_service)
                binding.statusText.text = "Service stopped"
                binding.statusDot.setColorFilter(
                    ContextCompat.getColor(this, R.color.status_disconnected)
                )
            } else {
                startClipSyncService()
            }
        }
    }

    /**
     * Observe clipboard history from the Room database via LiveData.
     * LiveData automatically updates the UI when new entries are inserted.
     */
    private fun observeHistory() {
        ClipboardDatabase.getInstance(this).clipboardDao().getAllLive().observe(this) { entries ->
            adapter.submitList(entries)
        }
    }

    /**
     * Request notification permission on Android 13+ before starting the service.
     * On older versions, notifications don't require runtime permission.
     */
    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: check if we already have the permission
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startClipSyncService()
            } else {
                // Request the permission — result handled in notificationPermissionLauncher
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Pre-Android 13: no runtime permission needed
            startClipSyncService()
        }
    }

    /**
     * Start the ClipSync foreground service.
     * Uses ContextCompat.startForegroundService() for backward compatibility.
     */
    private fun startClipSyncService() {
        val intent = Intent(this, ClipSyncService::class.java)
        ContextCompat.startForegroundService(this, intent)
        
        binding.toggleButton.text = getString(R.string.stop_service)
    }
}

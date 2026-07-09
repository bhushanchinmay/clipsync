package com.clipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * A fully transparent, invisible activity that sets the system clipboard.
 *
 * WHY THIS EXISTS:
 * Android 10+ (API 29+) prevents background services from accessing the clipboard.
 * This activity briefly comes to the foreground (completely invisible to the user),
 * performs the clipboard operation, and immediately finishes.
 *
 * LIFECYCLE:
 * 1. ClipSyncService receives text → calls ClipboardHelper.setClipboard()
 * 2. ClipboardHelper stores text in pendingText, launches this activity
 * 3. This activity reads pendingText, calls setPrimaryClip(), finishes
 * 4. Total visible time: ~0ms (transparent theme, no animations)
 *
 * MANIFEST CONFIGURATION:
 * - Theme.ClipSync.Transparent: fully transparent window
 * - excludeFromRecents: doesn't appear in recent apps
 * - noHistory: not added to the activity back stack
 * - taskAffinity="": runs in its own task to not interfere with MainActivity
 */
class TransparentClipboardActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "TransparentClipboard"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView needed — the window is fully transparent

        val text = ClipboardHelper.pendingText
        if (text != null) {
            try {
                // Get the system clipboard service
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                // Create a ClipData with our text
                // "ClipSync" is the label shown in clipboard managers
                val clip = ClipData.newPlainText("ClipSync", text)
                // Set the system clipboard — this works because we're in the foreground
                clipboard.setPrimaryClip(clip)
                Log.d(TAG, "Clipboard set successfully (${text.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set clipboard", e)
            } finally {
                // Always clear the pending text to free memory
                ClipboardHelper.clearPending()
            }
        } else {
            Log.w(TAG, "No pending text to copy")
        }

        finish()
    }

    override fun onPause() {
        super.onPause()
        // Safety net: if the activity is paused before finishing (edge case),
        // make sure we still finish to avoid leaving a phantom activity
        if (!isFinishing) {
            finish()
        }
    }

    override fun finish() {
        super.finish()
        // Suppress all transition animations — the user should never see this activity
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

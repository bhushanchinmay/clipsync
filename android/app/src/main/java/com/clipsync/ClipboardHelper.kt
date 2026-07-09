package com.clipsync

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Helper to set the system clipboard from a background service.
 *
 * THE PROBLEM:
 * Android 10+ (API 29+) blocks background apps from reading or writing the clipboard.
 * Our foreground service runs in the background, so it cannot call
 * ClipboardManager.setPrimaryClip() directly — the system silently ignores it.
 *
 * THE SOLUTION:
 * We launch a transparent (invisible) activity that briefly comes to the foreground,
 * sets the clipboard, and immediately finishes. The user never sees it.
 *
 * WHY NOT USE INTENT EXTRAS?
 * Intent extras are limited by the Binder transaction size (~1MB). Since ClipSync
 * supports texts with 20M+ characters, we store the text in a static variable
 * instead. The TransparentClipboardActivity reads from this variable.
 */
object ClipboardHelper {
    private const val TAG = "ClipboardHelper"

    /**
     * Holds the text to be copied to the clipboard.
     * Read by TransparentClipboardActivity.onCreate().
     *
     * @Volatile ensures visibility across threads — the service writes this
     * on a background thread, and the activity reads it on the main thread.
     */
    @Volatile
    var pendingText: String? = null
        private set

    /**
     * Sets the system clipboard by launching an invisible activity.
     *
     * Flow:
     * 1. Store text in [pendingText]
     * 2. Launch TransparentClipboardActivity
     * 3. Activity reads [pendingText], calls setPrimaryClip(), finishes
     *
     * @param context Application or service context
     * @param text The text to copy to the clipboard
     */
    fun setClipboard(context: Context, text: String) {
        Log.d(TAG, "Queuing clipboard text (${text.length} chars)")
        pendingText = text

        val intent = Intent(context, TransparentClipboardActivity::class.java).apply {
            // NEW_TASK: required when starting an activity from a non-activity context
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // NO_ANIMATION: prevent any visual transition
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            // EXCLUDE_FROM_RECENTS: don't show in the recent apps list
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        context.startActivity(intent)
    }

    /**
     * Clears the pending text after it has been copied.
     * Called by TransparentClipboardActivity to free the memory.
     */
    fun clearPending() {
        pendingText = null
    }
}

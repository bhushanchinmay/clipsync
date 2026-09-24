package com.clipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * Helper to set the system clipboard from the background service.
 *
 * Android 10+ (API 29+) only restricts *reading* the clipboard from the
 * background. Writing is always allowed, so the service can call
 * ClipboardManager.setPrimaryClip() directly — no foreground activity needed.
 * (Launching an activity from the background would itself be blocked by the
 * Android 10+ background activity start restrictions.)
 *
 * SIZE LIMIT:
 * setPrimaryClip() sends the text to the system clipboard service over Binder,
 * whose transaction buffer is ~1MB (the text is sent as UTF-16). Texts larger
 * than [MAX_TEXT_BYTES] of UTF-8 are therefore never delivered by TcpClient.
 */
object ClipboardHelper {
    private const val TAG = "ClipboardHelper"

    /**
     * Largest UTF-8 body (in bytes) that we try to put on the clipboard.
     * UTF-8 bytes >= UTF-16 chars, so this is at most ~800KB over Binder.
     */
    const val MAX_TEXT_BYTES = 400_000L

    /**
     * Sets the system clipboard.
     *
     * @param context Application or service context
     * @param text The text to copy to the clipboard
     * @return true if the clipboard was set, false if the system rejected it
     */
    fun setClipboard(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ClipSync", text))
            Log.d(TAG, "Clipboard set (${text.length} chars)")
            true
        } catch (e: Exception) {
            // e.g. TransactionTooLargeException wrapped in a RuntimeException
            Log.e(TAG, "Failed to set clipboard (${text.length} chars)", e)
            false
        }
    }
}

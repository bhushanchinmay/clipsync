package com.clipsync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single clipboard history entry.
 *
 * Room is an abstraction layer over SQLite. Each @Entity becomes a SQLite table,
 * and each property becomes a column. Room generates all the SQL at compile time
 * through KSP (Kotlin Symbol Processing).
 *
 * DESIGN DECISIONS:
 * - We store both 'preview' (first 500 chars) and 'fullText' separately.
 *   The preview is loaded in list queries to avoid loading potentially huge
 *   texts (20M+ chars) into memory just to show a list.
 * - 'charCount' is stored rather than computed because text.length on a
 *   20M char string is O(1) in Kotlin, but we want it available without
 *   loading fullText from the database.
 * - 'timestamp' is stored as Long (Unix millis) for easy sorting and comparison.
 *   Room doesn't have a native Date type, and Long is more efficient.
 */
@Entity(tableName = "clipboard_entries")
data class ClipboardEntry(
    /**
     * Auto-generated primary key.
     * Room assigns this automatically when inserting with id=0.
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * First 500 characters of the clipboard text.
     * Used for displaying in the history list without loading the full text.
     * 500 chars is enough for meaningful preview while keeping list queries fast.
     */
    val preview: String,

    /**
     * The complete clipboard text, potentially millions of characters.
     * SQLite's TEXT type has no practical size limit (up to 2^31 bytes).
     * This field is only loaded when the user taps an entry to copy it.
     */
    val fullText: String,

    /**
     * Unix timestamp in milliseconds when this entry was received.
     * Used for sorting (newest first) and for cleanup of old entries.
     */
    val timestamp: Long,

    /**
     * Total character count of the full text.
     * Stored separately so we can display "1,234 chars" in the list
     * without loading the full text into memory.
     */
    val charCount: Int
)

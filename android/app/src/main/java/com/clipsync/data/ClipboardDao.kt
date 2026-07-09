package com.clipsync.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object (DAO) for clipboard entries.
 *
 * Room generates the implementation of these methods at compile time using KSP.
 * The generated code handles:
 * - SQL statement preparation and execution
 * - Cursor-to-object mapping
 * - LiveData invalidation when data changes
 * - Thread management (suspend functions run on the caller's dispatcher)
 *
 * IMPORTANT: LiveData-returning queries automatically run on a background thread
 * and notify observers on the main thread. Suspend functions run on whatever
 * dispatcher the caller uses (typically Dispatchers.IO).
 */
@Dao
interface ClipboardDao {

    /**
     * Insert a new clipboard entry.
     *
     * OnConflictStrategy.REPLACE: if an entry with the same primary key exists,
     * replace it. In practice, since we use autoGenerate, conflicts are rare.
     *
     * This is a suspend function — it must be called from a coroutine.
     * Room runs the actual SQL on a background thread automatically.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntry)

    /**
     * Get all entries sorted by newest first, returning only preview data.
     *
     * KEY OPTIMIZATION: This query does NOT select 'fullText'.
     * For entries with millions of characters, loading fullText for every
     * list item would consume enormous amounts of memory. Instead, we project
     * into ClipboardEntryPreview which only contains the fields needed for display.
     *
     * Returns LiveData so the RecyclerView automatically updates when new
     * entries are inserted or deleted. Room handles the observer pattern internally.
     */
    @Query("SELECT id, preview, timestamp, charCount FROM clipboard_entries ORDER BY timestamp DESC")
    fun getAllLive(): LiveData<List<ClipboardEntryPreview>>

    /**
     * Get a single entry by ID, including the full text.
     * Used when the user taps a history entry to copy the complete content.
     *
     * Returns null if the entry has been deleted.
     */
    @Query("SELECT * FROM clipboard_entries WHERE id = :id")
    suspend fun getById(id: Long): ClipboardEntry?

    /**
     * Delete entries older than the given timestamp.
     * Used for periodic cleanup — e.g., delete entries older than 7 days:
     *   deleteOlderThan(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
     */
    @Query("DELETE FROM clipboard_entries WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    /**
     * Get the total count of stored entries.
     * Useful for debugging and statistics.
     */
    @Query("DELETE FROM clipboard_entries WHERE id NOT IN (SELECT id FROM clipboard_entries ORDER BY timestamp DESC LIMIT 20)")
    suspend fun keepLatest20()
    
    @Query("DELETE FROM clipboard_entries")
    suspend fun clearAll(): Int
}

/**
 * Projection data class for list display.
 *
 * Room maps SQL query columns to this class by matching property names.
 * By not including 'fullText', we avoid loading potentially huge strings
 * into memory just to display a list of entries.
 *
 * This is a standard Kotlin data class — no Room annotations needed.
 * Room matches the SELECT column names to the constructor parameter names.
 */
data class ClipboardEntryPreview(
    val id: Long,
    val preview: String,
    val timestamp: Long,
    val charCount: Int
)

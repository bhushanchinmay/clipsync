package com.clipsync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for storing clipboard history.
 *
 * Room is Google's recommended SQLite abstraction for Android. It provides:
 * - Compile-time SQL verification (catches typos in queries)
 * - Automatic mapping between SQLite rows and Kotlin objects
 * - LiveData integration for reactive UI updates
 * - Coroutine support for async database operations
 *
 * SINGLETON PATTERN:
 * We use the singleton pattern to ensure only one database instance exists
 * per process. Multiple Room instances pointing to the same database file
 * would cause locking issues and wasted resources.
 *
 * SCHEMA VERSION:
 * Version 1 = initial schema. If we add/modify tables later, we increment
 * the version and provide a Migration object (or use fallbackToDestructiveMigration
 * during development to just recreate the database).
 */
@Database(
    entities = [ClipboardEntry::class],
    version = 1,
    // exportSchema = false skips generating a JSON schema file
    // (useful for CI but not needed for this simple app)
    exportSchema = false
)
abstract class ClipboardDatabase : RoomDatabase() {

    /**
     * Room generates the implementation of this DAO at compile time.
     * The generated class contains all the SQL logic for our queries.
     */
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        /**
         * @Volatile ensures this variable is never cached thread-locally.
         * All reads and writes go directly to main memory, so all threads
         * see the latest value immediately.
         */
        @Volatile
        private var INSTANCE: ClipboardDatabase? = null

        /**
         * Get or create the singleton database instance.
         *
         * Uses double-checked locking for thread safety:
         * 1. First check (outside synchronized): fast path for the common case
         *    where the instance already exists. No lock needed.
         * 2. Second check (inside synchronized): prevents two threads from
         *    both creating an instance simultaneously.
         *
         * The database file is stored at: /data/data/com.clipsync/databases/clipsync_database
         *
         * @param context Any context (we use applicationContext to prevent leaks)
         */
        fun getInstance(context: Context): ClipboardDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipboardDatabase::class.java,
                    "clipsync_database"
                )
                // During development: if we change the schema without incrementing
                // the version, just recreate the database instead of crashing.
                // In production, you'd use proper migrations instead.
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}

package com.clipsync.`data`

import androidx.lifecycle.LiveData
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class ClipboardDao_Impl(
  __db: RoomDatabase,
) : ClipboardDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfClipboardEntry: EntityInsertAdapter<ClipboardEntry>
  init {
    this.__db = __db
    this.__insertAdapterOfClipboardEntry = object : EntityInsertAdapter<ClipboardEntry>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `clipboard_entries` (`id`,`preview`,`fullText`,`timestamp`,`charCount`) VALUES (nullif(?, 0),?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ClipboardEntry) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.preview)
        statement.bindText(3, entity.fullText)
        statement.bindLong(4, entity.timestamp)
        statement.bindLong(5, entity.charCount.toLong())
      }
    }
  }

  public override suspend fun insert(entry: ClipboardEntry): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfClipboardEntry.insert(_connection, entry)
  }

  public override fun getAllLive(): LiveData<List<ClipboardEntryPreview>> {
    val _sql: String = "SELECT id, preview, timestamp, charCount FROM clipboard_entries ORDER BY timestamp DESC"
    return __db.invalidationTracker.createLiveData(arrayOf("clipboard_entries"), false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = 0
        val _columnIndexOfPreview: Int = 1
        val _columnIndexOfTimestamp: Int = 2
        val _columnIndexOfCharCount: Int = 3
        val _result: MutableList<ClipboardEntryPreview> = mutableListOf()
        while (_stmt.step()) {
          val _item: ClipboardEntryPreview
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPreview: String
          _tmpPreview = _stmt.getText(_columnIndexOfPreview)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpCharCount: Int
          _tmpCharCount = _stmt.getLong(_columnIndexOfCharCount).toInt()
          _item = ClipboardEntryPreview(_tmpId,_tmpPreview,_tmpTimestamp,_tmpCharCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getById(id: Long): ClipboardEntry? {
    val _sql: String = "SELECT * FROM clipboard_entries WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPreview: Int = getColumnIndexOrThrow(_stmt, "preview")
        val _columnIndexOfFullText: Int = getColumnIndexOrThrow(_stmt, "fullText")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfCharCount: Int = getColumnIndexOrThrow(_stmt, "charCount")
        val _result: ClipboardEntry?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPreview: String
          _tmpPreview = _stmt.getText(_columnIndexOfPreview)
          val _tmpFullText: String
          _tmpFullText = _stmt.getText(_columnIndexOfFullText)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpCharCount: Int
          _tmpCharCount = _stmt.getLong(_columnIndexOfCharCount).toInt()
          _result = ClipboardEntry(_tmpId,_tmpPreview,_tmpFullText,_tmpTimestamp,_tmpCharCount)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOlderThan(olderThan: Long) {
    val _sql: String = "DELETE FROM clipboard_entries WHERE timestamp < ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, olderThan)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun keepLatest20() {
    val _sql: String = "DELETE FROM clipboard_entries WHERE id NOT IN (SELECT id FROM clipboard_entries ORDER BY timestamp DESC LIMIT 20)"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clearAll(): Int {
    val _sql: String = "DELETE FROM clipboard_entries"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}

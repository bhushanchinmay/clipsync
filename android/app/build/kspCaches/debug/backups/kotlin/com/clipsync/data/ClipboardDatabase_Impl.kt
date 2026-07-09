package com.clipsync.`data`

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class ClipboardDatabase_Impl : ClipboardDatabase() {
  private val _clipboardDao: Lazy<ClipboardDao> = lazy {
    ClipboardDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(1, "2fa7ce16f7dbfa721588c7672f13e894", "cae83dd773a0cbbd1d5e2508c86f8726") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `clipboard_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `preview` TEXT NOT NULL, `fullText` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `charCount` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '2fa7ce16f7dbfa721588c7672f13e894')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `clipboard_entries`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
        val _columnsClipboardEntries: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsClipboardEntries.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsClipboardEntries.put("preview", TableInfo.Column("preview", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsClipboardEntries.put("fullText", TableInfo.Column("fullText", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsClipboardEntries.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsClipboardEntries.put("charCount", TableInfo.Column("charCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysClipboardEntries: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesClipboardEntries: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoClipboardEntries: TableInfo = TableInfo("clipboard_entries", _columnsClipboardEntries, _foreignKeysClipboardEntries, _indicesClipboardEntries)
        val _existingClipboardEntries: TableInfo = read(connection, "clipboard_entries")
        if (!_infoClipboardEntries.equals(_existingClipboardEntries)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |clipboard_entries(com.clipsync.data.ClipboardEntry).
              | Expected:
              |""".trimMargin() + _infoClipboardEntries + """
              |
              | Found:
              |""".trimMargin() + _existingClipboardEntries)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "clipboard_entries")
  }

  public override fun clearAllTables() {
    super.performClear(false, "clipboard_entries")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(ClipboardDao::class, ClipboardDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun clipboardDao(): ClipboardDao = _clipboardDao.value
}

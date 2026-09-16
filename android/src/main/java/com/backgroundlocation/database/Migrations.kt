package com.backgroundlocation.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations for [LocationDatabase]. Registered via
 * `Room.databaseBuilder(...).addMigrations(...)` - there is no destructive
 * fallback, so every version bump must add a migration here.
 */
object Migrations {

  /**
   * v1 -> v2: adds sync bookkeeping columns to `locations` (see
   * `docs/upstream-storage.md` and `LocationEntity`):
   * - `syncState` (TEXT NOT NULL, default `'PENDING'`)
   * - `attempts` (INTEGER NOT NULL, default `0`)
   * - `lastAttemptAt` (INTEGER, nullable - no default needed)
   * - `clientId` (TEXT NOT NULL, default `''`) - existing rows are then
   *   backfilled with a unique value. SQLite's `ALTER TABLE ... ADD COLUMN`
   *   forbids a non-constant `DEFAULT` expression (e.g. `randomblob()`), so
   *   the column is added with a placeholder default first and backfilled
   *   in a separate `UPDATE`, where `randomblob()` is evaluated once per
   *   row. `lower(hex(randomblob(16)))` is not RFC-4122 UUID-shaped, but
   *   the requirement is only "unique-ish" for pre-existing rows; new rows
   *   get a real UUIDv4 `clientId` from `LocationEntity`'s Kotlin default.
   *
   * Also adds an index on `syncState` (`getPendingBatch` filters on it).
   *
   * The literal default values below must stay in sync with the
   * `@ColumnInfo(defaultValue = ...)` annotations on `LocationEntity`,
   * which Room's schema validation (see `MigrationTestHelper` in
   * `LocationDatabaseMigrationTest`) checks against the actual migrated
   * schema.
   */
  val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE locations ADD COLUMN syncState TEXT NOT NULL DEFAULT 'PENDING'")
      db.execSQL("ALTER TABLE locations ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE locations ADD COLUMN lastAttemptAt INTEGER")
      db.execSQL("ALTER TABLE locations ADD COLUMN clientId TEXT NOT NULL DEFAULT ''")
      db.execSQL("UPDATE locations SET clientId = lower(hex(randomblob(16))) WHERE clientId = ''")
      db.execSQL("CREATE INDEX IF NOT EXISTS index_locations_syncState ON locations (syncState)")
    }
  }
}

package com.backgroundlocation.sync

/**
 * Sync lifecycle states for a persisted `LocationEntity` row.
 *
 * Stored as `TEXT` in the `locations.syncState` column (schema v2, see
 * `LocationDatabase`/`Migrations.MIGRATION_1_2`). A row starts `PENDING`
 * when written by [com.backgroundlocation.LocationStorage], moves to
 * `SYNCING` while an upload attempt is in flight, and lands on `SYNCED`
 * once the upload is acknowledged. `SYNCING` rows are reverted back to
 * `PENDING` on upload failure/cancellation, or reset in bulk on crash
 * recovery at startup - see `LocationDao.revertSyncingToPending` /
 * `LocationDao.resetAllSyncingToPending`.
 *
 * These are plain string constants (not a Kotlin `enum class`) so they can
 * be embedded directly as SQL literals in `@Query` annotations, which must
 * be compile-time constant strings - Room/KSP cannot resolve an enum's
 * name from a `@Query` string. Any `LocationDao` query that hardcodes
 * `'PENDING'`, `'SYNCING'`, or `'SYNCED'` must be kept in sync with the
 * values here.
 */
object SyncState {
  const val PENDING = "PENDING"
  const val SYNCING = "SYNCING"
  const val SYNCED = "SYNCED"
}

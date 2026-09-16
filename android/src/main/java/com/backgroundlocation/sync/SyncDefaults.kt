package com.backgroundlocation.sync

/**
 * Base retention defaults for the sync bookkeeping added to the `locations` table in schema v2.
 *
 * As of Phase 3b (see [SyncConfigStore]), these constants are only *seed values* - the runtime
 * source of truth for `maxQueueRows`/`maxAgeDays` is [SyncConfigStore], whose getters fall back
 * to these constants until/unless a caller (e.g. a future JS settings surface) overrides them.
 * [com.backgroundlocation.LocationStorage]'s retention cleanup reads from [SyncConfigStore], not
 * from here directly, so there is exactly one place retention config is read from at runtime.
 */
object SyncDefaults {
  /**
   * Hard cap on the number of rows kept in `locations`. Enforced by
   * [com.backgroundlocation.database.LocationDao.trimQueueToMax], which
   * deletes the oldest `SYNCED` rows first and only falls back to deleting
   * the oldest `PENDING` rows (un-uploaded fixes - a deliberate data-loss
   * guard against unbounded storage growth) if still over cap once every
   * eligible `SYNCED` row has been removed.
   */
  const val MAX_QUEUE_ROWS = 10_000

  /**
   * Age, in days, after which a `SYNCED` row becomes eligible for deletion
   * via [com.backgroundlocation.database.LocationDao.deleteSyncedOlderThan].
   * Measured against the fix's own `timestamp` column (when the GPS fix
   * was recorded), not when it was synced.
   */
  const val RETENTION_DAYS = 7

  /** [RETENTION_DAYS] expressed in milliseconds, for direct use against epoch-millis columns. */
  const val RETENTION_MILLIS: Long = RETENTION_DAYS * 24L * 60L * 60L * 1000L
}

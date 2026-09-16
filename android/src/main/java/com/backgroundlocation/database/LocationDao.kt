package com.backgroundlocation.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for location operations
 */
@Dao
interface LocationDao {
  
  /**
   * Insert a new location
   */
  @Insert
  suspend fun insert(location: LocationEntity): Long

  /**
   * Batch insert multiple locations for performance
   */
  @Insert
  suspend fun insertAll(locations: List<LocationEntity>): List<Long>

  /**
   * Get all locations for a specific trip
   */
  @Query("SELECT * FROM locations WHERE tripId = :tripId ORDER BY timestamp ASC")
  suspend fun getLocationsByTripId(tripId: String): List<LocationEntity>
  
  /**
   * Get all locations for a specific trip as Flow (reactive)
   */
  @Query("SELECT * FROM locations WHERE tripId = :tripId ORDER BY timestamp ASC")
  fun getLocationsByTripIdFlow(tripId: String): Flow<List<LocationEntity>>
  
  /**
   * Get count of locations for a trip
   */
  @Query("SELECT COUNT(*) FROM locations WHERE tripId = :tripId")
  suspend fun getLocationCount(tripId: String): Int
  
  /**
   * Delete all locations for a specific trip
   */
  @Query("DELETE FROM locations WHERE tripId = :tripId")
  suspend fun deleteLocationsByTripId(tripId: String): Int
  
  /**
   * Delete all locations
   */
  @Query("DELETE FROM locations")
  suspend fun deleteAllLocations(): Int
  
  /**
   * Get all unique trip IDs
   */
  @Query("SELECT DISTINCT tripId FROM locations")
  suspend fun getAllTripIds(): List<String>

  // --- Sync bookkeeping (schema v2) ---------------------------------------
  // The literal 'PENDING'/'SYNCING'/'SYNCED' strings below must match
  // com.backgroundlocation.sync.SyncState - @Query values must be
  // compile-time constant SQL, so they can't reference that object directly.

  /**
   * Next batch of not-yet-uploaded fixes, oldest first.
   */
  @Query("SELECT * FROM locations WHERE syncState = 'PENDING' ORDER BY timestamp ASC LIMIT :limit")
  suspend fun getPendingBatch(limit: Int): List<LocationEntity>

  /**
   * Marks rows as upload-in-flight. Does not touch `attempts`/`lastAttemptAt` -
   * see [incrementAttempts].
   */
  @Query("UPDATE locations SET syncState = 'SYNCING' WHERE id IN (:ids)")
  suspend fun markSyncing(ids: List<Long>)

  /**
   * Marks rows as successfully uploaded and records the attempt time.
   */
  @Query("UPDATE locations SET syncState = 'SYNCED', lastAttemptAt = :attemptedAt WHERE id IN (:ids)")
  suspend fun markSynced(ids: List<Long>, attemptedAt: Long)

  /**
   * Reverts an in-flight upload back to PENDING (e.g. on upload failure).
   * Scoped to rows currently SYNCING, so it can't accidentally regress an
   * already-SYNCED row if the caller's id set is stale.
   */
  @Query("UPDATE locations SET syncState = 'PENDING' WHERE id IN (:ids) AND syncState = 'SYNCING'")
  suspend fun revertSyncingToPending(ids: List<Long>)

  /**
   * Crash recovery: resets *every* SYNCING row back to PENDING, regardless
   * of id. Intended to be called once at the uploader's startup, in case
   * the process died mid-upload and left rows stuck in SYNCING with no
   * in-memory record of their ids. Returns the number of rows reset.
   */
  @Query("UPDATE locations SET syncState = 'PENDING' WHERE syncState = 'SYNCING'")
  suspend fun resetAllSyncingToPending(): Int

  /**
   * Records an upload attempt (success or failure) without changing syncState.
   */
  @Query("UPDATE locations SET attempts = attempts + 1, lastAttemptAt = :attemptedAt WHERE id IN (:ids)")
  suspend fun incrementAttempts(ids: List<Long>, attemptedAt: Long)

  /** Number of not-yet-uploaded fixes. */
  @Query("SELECT COUNT(*) FROM locations WHERE syncState = 'PENDING'")
  suspend fun countPending(): Int

  /** Reactive version of [countPending], for UI/diagnostics. */
  @Query("SELECT COUNT(*) FROM locations WHERE syncState = 'PENDING'")
  fun countPendingFlow(): Flow<Int>

  // --- Retention (see com.backgroundlocation.sync.SyncDefaults) -----------

  /**
   * Deletes SYNCED rows whose fix `timestamp` is older than [cutoffMillis].
   * Returns the number of rows deleted.
   */
  @Query("DELETE FROM locations WHERE syncState = 'SYNCED' AND timestamp < :cutoffMillis")
  suspend fun deleteSyncedOlderThan(cutoffMillis: Long): Int

  /** Total row count, used by [trimQueueToMax] to decide whether trimming is needed. */
  @Query("SELECT COUNT(*) FROM locations")
  suspend fun countAll(): Int

  /** Deletes at most [limit] of the oldest SYNCED rows. Returns the number of rows deleted. */
  @Query(
    "DELETE FROM locations WHERE id IN (" +
      "SELECT id FROM locations WHERE syncState = 'SYNCED' ORDER BY timestamp ASC LIMIT :limit" +
      ")"
  )
  suspend fun deleteOldestSynced(limit: Int): Int

  /** Deletes at most [limit] of the oldest PENDING rows. Returns the number of rows deleted. */
  @Query(
    "DELETE FROM locations WHERE id IN (" +
      "SELECT id FROM locations WHERE syncState = 'PENDING' ORDER BY timestamp ASC LIMIT :limit" +
      ")"
  )
  suspend fun deleteOldestPending(limit: Int): Int

  /**
   * Trims `locations` down to at most [maxRows] total rows, if over cap.
   * Deletes the oldest SYNCED rows first; only if still over cap after
   * removing every eligible SYNCED row does it fall back to deleting the
   * oldest PENDING rows - a deliberate data-loss guard (un-uploaded fixes
   * are lost) against unbounded storage growth when a device has been
   * offline/unsynced for a very long time. Returns the total number of
   * rows deleted.
   */
  @Transaction
  suspend fun trimQueueToMax(maxRows: Int): Int {
    val over = countAll() - maxRows
    if (over <= 0) return 0

    val deletedSynced = deleteOldestSynced(over)
    val stillOver = over - deletedSynced
    val deletedPending = if (stillOver > 0) deleteOldestPending(stillOver) else 0

    return deletedSynced + deletedPending
  }
}


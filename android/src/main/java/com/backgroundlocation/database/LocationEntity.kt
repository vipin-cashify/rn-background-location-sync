package com.backgroundlocation.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import com.backgroundlocation.sync.SyncState
import java.util.UUID

/**
 * Room entity for storing location data
 * Indexed by tripId for efficient queries
 */
@Entity(
  tableName = "locations",
  indices = [Index(value = ["tripId"]), Index(value = ["syncState"])]
)
data class LocationEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,

  val tripId: String,
  val latitude: Double,
  val longitude: Double,
  val timestamp: Long,

  // Optional fields
  val accuracy: Float? = null,
  val altitude: Double? = null,
  val speed: Float? = null,
  val bearing: Float? = null,
  val verticalAccuracyMeters: Float? = null,
  val speedAccuracyMetersPerSecond: Float? = null,
  val bearingAccuracyDegrees: Float? = null,
  val elapsedRealtimeNanos: Long? = null,
  val provider: String? = null,
  val isFromMockProvider: Boolean? = null,

  // Sync bookkeeping (schema v2 - see database.Migrations.MIGRATION_1_2).
  // The Kotlin defaults below apply at the single construction point
  // (LocationStorage.saveLocation), so every newly-saved fix gets a fresh
  // PENDING syncState and a fresh UUIDv4 clientId without that call site
  // having to know about sync bookkeeping at all.

  /** One of [SyncState.PENDING]/[SyncState.SYNCING]/[SyncState.SYNCED]. */
  @ColumnInfo(defaultValue = "'PENDING'") // must match SyncState.PENDING
  val syncState: String = SyncState.PENDING,

  /** Number of upload attempts made for this row so far. */
  @ColumnInfo(defaultValue = "0")
  val attempts: Int = 0,

  /** Epoch millis of the most recent upload attempt (sync or increment), or null if never attempted. */
  val lastAttemptAt: Long? = null,

  /** Stable client-generated id for this fix, used for idempotent upload/dedupe server-side. */
  @ColumnInfo(defaultValue = "''") // pre-v2 rows are backfilled by the migration; new rows get a real UUID here
  val clientId: String = UUID.randomUUID().toString()
)


package com.backgroundlocation.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.backgroundlocation.sync.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the sync bookkeeping added to `locations`/`LocationDao` in schema
 * v2: default syncState/clientId on insert, the PENDING/SYNCING/SYNCED
 * transitions, crash recovery, and retention. Uses an in-memory Room DB
 * (via [LocationDatabase.getInMemoryInstance]) under Robolectric, so real
 * SQL runs against a real (if ephemeral) SQLite connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocationDaoSyncTest {

    private lateinit var db: LocationDatabase
    private lateinit var dao: LocationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = LocationDatabase.getInMemoryInstance(context)
        dao = db.locationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun location(
        tripId: String = "trip-1",
        timestamp: Long = 0L,
        syncState: String = SyncState.PENDING
    ) = LocationEntity(
        tripId = tripId,
        latitude = 1.0,
        longitude = 2.0,
        timestamp = timestamp,
        syncState = syncState
    )

    @Test
    fun `insert defaults to PENDING with zero attempts, null lastAttemptAt, and a UUIDv4 clientId`() = runTest {
        dao.insert(
            LocationEntity(tripId = "trip-1", latitude = 1.0, longitude = 2.0, timestamp = 1000L)
        )

        val rows = dao.getPendingBatch(10)
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(SyncState.PENDING, row.syncState)
        assertEquals(0, row.attempts)
        assertNull(row.lastAttemptAt)

        val uuidV4 = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )
        assertTrue("clientId '${row.clientId}' is not UUIDv4-shaped", row.clientId.matches(uuidV4))
    }

    @Test
    fun `insert generates a distinct clientId per row`() = runTest {
        dao.insert(location(timestamp = 1L))
        dao.insert(location(timestamp = 2L))

        val ids = dao.getPendingBatch(10).map { it.clientId }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `getPendingBatch orders oldest first and respects limit`() = runTest {
        dao.insertAll(
            listOf(
                location(timestamp = 3000L),
                location(timestamp = 1000L),
                location(timestamp = 2000L)
            )
        )

        val batch = dao.getPendingBatch(2)

        assertEquals(listOf(1000L, 2000L), batch.map { it.timestamp })
    }

    @Test
    fun `getPendingBatch excludes SYNCING and SYNCED rows`() = runTest {
        dao.insertAll(
            listOf(
                location(timestamp = 1000L, syncState = SyncState.SYNCING),
                location(timestamp = 2000L, syncState = SyncState.SYNCED),
                location(timestamp = 3000L, syncState = SyncState.PENDING)
            )
        )

        val batch = dao.getPendingBatch(10)

        assertEquals(listOf(3000L), batch.map { it.timestamp })
    }

    @Test
    fun `markSyncing moves rows out of the pending batch`() = runTest {
        val id = dao.insert(location())

        dao.markSyncing(listOf(id))

        assertTrue(dao.getPendingBatch(10).isEmpty())
    }

    @Test
    fun `markSynced sets SYNCED and records lastAttemptAt`() = runTest {
        val id = dao.insert(location(tripId = "t"))
        dao.markSyncing(listOf(id))

        dao.markSynced(listOf(id), attemptedAt = 5000L)

        val row = dao.getLocationsByTripId("t").single()
        assertEquals(SyncState.SYNCED, row.syncState)
        assertEquals(5000L, row.lastAttemptAt)
        assertEquals(0, dao.countPending())
    }

    @Test
    fun `revertSyncingToPending only reverts rows still SYNCING`() = runTest {
        val syncing = dao.insert(location(tripId = "t", timestamp = 1L))
        val alreadySynced = dao.insert(location(tripId = "t", timestamp = 2L))
        dao.markSyncing(listOf(syncing, alreadySynced))
        dao.markSynced(listOf(alreadySynced), attemptedAt = 10L)

        dao.revertSyncingToPending(listOf(syncing, alreadySynced))

        val byId = dao.getLocationsByTripId("t").associateBy { it.id }
        assertEquals(SyncState.PENDING, byId.getValue(syncing).syncState)
        assertEquals(SyncState.SYNCED, byId.getValue(alreadySynced).syncState)
    }

    @Test
    fun `resetAllSyncingToPending resets every SYNCING row and returns the count`() = runTest {
        val a = dao.insert(location(tripId = "t", timestamp = 1L))
        val b = dao.insert(location(tripId = "t", timestamp = 2L))
        dao.insert(location(tripId = "t", timestamp = 3L)) // stays PENDING, untouched
        dao.markSyncing(listOf(a, b))

        val resetCount = dao.resetAllSyncingToPending()

        assertEquals(2, resetCount)
        assertEquals(3, dao.countPending())
    }

    @Test
    fun `incrementAttempts increments attempts and updates lastAttemptAt without changing syncState`() = runTest {
        val id = dao.insert(location(tripId = "t"))

        dao.incrementAttempts(listOf(id), attemptedAt = 42L)
        dao.incrementAttempts(listOf(id), attemptedAt = 84L)

        val row = dao.getLocationsByTripId("t").single()
        assertEquals(2, row.attempts)
        assertEquals(84L, row.lastAttemptAt)
        assertEquals(SyncState.PENDING, row.syncState)
    }

    @Test
    fun `countPending reflects only PENDING rows`() = runTest {
        dao.insert(location(timestamp = 1L, syncState = SyncState.PENDING))
        dao.insert(location(timestamp = 2L, syncState = SyncState.SYNCING))
        dao.insert(location(timestamp = 3L, syncState = SyncState.SYNCED))

        assertEquals(1, dao.countPending())
    }

    @Test
    fun `countPendingFlow emits the updated pending count as rows are inserted`() = runTest {
        dao.countPendingFlow().test {
            assertEquals(0, awaitItem())
            dao.insert(location(timestamp = 1L))
            assertEquals(1, awaitItem())
            dao.insert(location(timestamp = 2L))
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteSyncedOlderThan deletes only SYNCED rows past the cutoff`() = runTest {
        val oldSynced = dao.insert(location(tripId = "t", timestamp = 1000L, syncState = SyncState.SYNCED))
        val newSynced = dao.insert(location(tripId = "t", timestamp = 9000L, syncState = SyncState.SYNCED))
        val oldPending = dao.insert(location(tripId = "t", timestamp = 1000L, syncState = SyncState.PENDING))

        val deleted = dao.deleteSyncedOlderThan(cutoffMillis = 5000L)

        assertEquals(1, deleted)
        val remaining = dao.getLocationsByTripId("t").map { it.id }.toSet()
        assertFalse(oldSynced in remaining)
        assertTrue(newSynced in remaining)
        assertTrue(oldPending in remaining)
    }

    @Test
    fun `trimQueueToMax is a no-op at or under cap`() = runTest {
        dao.insert(location(tripId = "t", timestamp = 1L))
        dao.insert(location(tripId = "t", timestamp = 2L))

        val deleted = dao.trimQueueToMax(2)

        assertEquals(0, deleted)
        assertEquals(2, dao.getLocationsByTripId("t").size)
    }

    @Test
    fun `trimQueueToMax deletes oldest SYNCED rows before touching PENDING rows`() = runTest {
        val oldestSynced = dao.insert(location(tripId = "t", timestamp = 1L, syncState = SyncState.SYNCED))
        val newerSynced = dao.insert(location(tripId = "t", timestamp = 2L, syncState = SyncState.SYNCED))
        val pending1 = dao.insert(location(tripId = "t", timestamp = 3L, syncState = SyncState.PENDING))
        val pending2 = dao.insert(location(tripId = "t", timestamp = 4L, syncState = SyncState.PENDING))

        // 4 rows, cap 3 -> exactly 1 over: only the oldest SYNCED row should go.
        val deleted = dao.trimQueueToMax(3)

        assertEquals(1, deleted)
        val remaining = dao.getLocationsByTripId("t").map { it.id }.toSet()
        assertFalse(oldestSynced in remaining)
        assertTrue(newerSynced in remaining)
        assertTrue(pending1 in remaining)
        assertTrue(pending2 in remaining)
    }

    @Test
    fun `trimQueueToMax falls back to deleting oldest PENDING rows once SYNCED rows run out`() = runTest {
        val synced = dao.insert(location(tripId = "t", timestamp = 1L, syncState = SyncState.SYNCED))
        val oldestPending = dao.insert(location(tripId = "t", timestamp = 2L, syncState = SyncState.PENDING))
        val pending2 = dao.insert(location(tripId = "t", timestamp = 3L, syncState = SyncState.PENDING))
        val pending3 = dao.insert(location(tripId = "t", timestamp = 4L, syncState = SyncState.PENDING))

        // 4 rows, cap 2 -> 2 over: the single SYNCED row plus the oldest remaining PENDING row.
        val deleted = dao.trimQueueToMax(2)

        assertEquals(2, deleted)
        val remaining = dao.getLocationsByTripId("t").map { it.id }.toSet()
        assertFalse(synced in remaining)
        assertFalse(oldestPending in remaining)
        assertTrue(pending2 in remaining)
        assertTrue(pending3 in remaining)
    }
}

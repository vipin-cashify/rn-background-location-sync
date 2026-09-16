package com.backgroundlocation.sync

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.backgroundlocation.database.LocationDao
import com.backgroundlocation.database.LocationDatabase
import com.backgroundlocation.database.LocationEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Covers the task brief's req-4 test list for [LocationSyncManager]: happy-path batch draining
 * with envelope-shape assertions, 500 failure/backoff/retry with clientId stability, 401
 * auth-block + notification + no-op-until-cleared, single-flight, and the no-syncUrl no-op.
 *
 * Uses a real in-memory Room DB ([LocationDatabase.getInMemoryInstance]), a real `MockWebServer`
 * (so request bodies/headers are asserted against what OkHttp actually sent, not a mock), a real
 * [SyncConfigStore] (plain SharedPreferences, real under Robolectric), and a [FakeTokenStore]
 * (see its kdoc for why - `EncryptedTokenStoreTest` covers the real `TokenStore` impl).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocationSyncManagerTest {

    private lateinit var context: Context
    private lateinit var db: LocationDatabase
    private lateinit var dao: LocationDao
    private lateinit var server: MockWebServer
    private lateinit var configStore: SyncConfigStore
    private lateinit var tokenStore: FakeTokenStore
    private var currentTime = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = LocationDatabase.getInMemoryInstance(context)
        dao = db.locationDao()
        server = MockWebServer()
        server.start()

        configStore = SyncConfigStore(context)
        configStore.syncUrl = server.url("/sync").toString()
        tokenStore = FakeTokenStore(
            initialToken = "test-token",
            initialSsoToken = "test-sso-token",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun manager(batchSize: Int = SyncConfigStore.DEFAULT_BATCH_SIZE): LocationSyncManager {
        configStore.batchSize = batchSize
        return LocationSyncManager(
            context = context,
            locationDao = dao,
            configStore = configStore,
            tokenStore = tokenStore,
            clock = { currentTime }
        )
    }

    private suspend fun seed(count: Int, startTimestamp: Long = 1_000L): List<Long> {
        val entities = (0 until count).map { i ->
            LocationEntity(
                tripId = "trip-1",
                latitude = 28.61,
                longitude = 77.20,
                timestamp = startTimestamp + i,
                accuracy = 12.5f,
                speed = 0f,
                bearing = 0f,
                altitude = 216.0,
                provider = "fused",
                isFromMockProvider = false
            )
        }
        return dao.insertAll(entities)
    }

    private fun seedRow(timestamp: Long, provider: String?): LocationEntity =
        LocationEntity(
            tripId = "trip-1",
            latitude = 28.61,
            longitude = 77.20,
            timestamp = timestamp,
            accuracy = 12.5f,
            speed = 0f,
            bearing = 0f,
            altitude = 216.0,
            provider = provider,
            isFromMockProvider = false
        )

    // The wire payload no longer carries clientId (the sales-tracker API derives identity from
    // the sso token), so "which rows did this request contain" is keyed on the recordedAt epoch
    // millis instead - unique per seeded row here (startTimestamp + i) and stable across retries.
    private fun requestRecordedAts(json: String): List<Long> {
        val logs = JSONObject(json).getJSONArray("logs")
        return (0 until logs.length()).map { logs.getJSONObject(it).getLong("recordedAt") }
    }

    // --- happy path ----------------------------------------------------------------------

    @Test
    fun `happy path drains 12 pending rows in 3 batches of 5, all SYNCED, correct order`() = runTest {
        seed(12)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200)) }
        configStore.extraHeaders = mapOf("X-Tenant" to "acme")

        val result = manager(batchSize = 5).syncNow("test")

        assertEquals(12, result.uploaded)
        assertFalse(result.failed)
        assertEquals(0, result.pendingAfter)
        assertEquals(3, server.requestCount)
        assertEquals(0, dao.countPending())

        val allRecordedAts = mutableListOf<Long>()
        repeat(3) { i ->
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            // sales-tracker auth: two custom headers, NOT the standard Authorization.
            assertEquals("Bearer test-token", recorded.getHeader("x-authorization"))
            assertEquals("test-sso-token", recorded.getHeader("x-sso-token"))
            assertNull(recorded.getHeader("Authorization"))
            assertEquals("acme", recorded.getHeader("X-Tenant"))
            assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))

            val body = JSONObject(recorded.body.readUtf8())
            val logs = body.getJSONArray("logs")
            assertEquals(if (i < 2) 5 else 2, logs.length())
            for (j in 0 until logs.length()) {
                val log = logs.getJSONObject(j)
                assertEquals("RECORD", log.getString("punchType"))
                assertEquals(28.61, log.getDouble("lat"), 0.0001)
                assertEquals(77.20, log.getDouble("long"), 0.0001)
                assertEquals("FUSED", log.getString("locationType"))
                // recordedAt is epoch millis (a number), and the dropped A3 fields are absent.
                assertTrue(log.get("recordedAt") is Number)
                assertFalse(log.has("clientId"))
                assertFalse(log.has("lng"))
                assertFalse(log.has("accuracy"))
                assertFalse(log.has("provider"))
                assertFalse(log.has("isMocked"))
                allRecordedAts.add(log.getLong("recordedAt"))
            }
        }
        assertEquals(12, allRecordedAts.toSet().size)
    }

    @Test
    fun `recordedAt is sent as the raw epoch-millis timestamp`() = runTest {
        dao.insert(
            LocationEntity(
                tripId = "trip-1",
                latitude = 28.4485476,
                longitude = 77.0414017,
                timestamp = 1_789_368_041_125L,
                accuracy = 12.5f,
                speed = 0f,
                bearing = 0f,
                altitude = 216.0,
                provider = "gps",
                isFromMockProvider = false
            )
        )
        server.enqueue(MockResponse().setResponseCode(200))

        manager().syncNow("test")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val log = body.getJSONArray("logs").getJSONObject(0)
        assertEquals(1_789_368_041_125L, log.getLong("recordedAt"))
        assertEquals(77.0414017, log.getDouble("long"), 0.0000001)
        assertEquals("GPS", log.getString("locationType"))
    }

    @Test
    fun `locationType reflects the real provider, falling back to GPS when absent`() = runTest {
        dao.insertAll(
            listOf(
                seedRow(1L, provider = "network"),
                seedRow(2L, provider = null),
            )
        )
        server.enqueue(MockResponse().setResponseCode(200))

        manager().syncNow("test")

        val logs = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("logs")
        val byRecordedAt = (0 until logs.length())
            .map { logs.getJSONObject(it) }
            .associateBy { it.getLong("recordedAt") }
        assertEquals("NETWORK", byRecordedAt.getValue(1L).getString("locationType"))
        assertEquals("GPS", byRecordedAt.getValue(2L).getString("locationType"))
    }

    @Test
    fun `locationType is MOCK for a mocked fix regardless of provider`() = runTest {
        dao.insert(seedRow(1L, provider = "gps").copy(isFromMockProvider = true))
        server.enqueue(MockResponse().setResponseCode(200))

        manager().syncNow("test")

        val log = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("logs").getJSONObject(0)
        assertEquals("MOCK", log.getString("locationType"))
    }

    // --- 500 failure / backoff / retry ---------------------------------------------------

    @Test
    fun `500 reverts the batch to PENDING, increments attempts, stops the loop, and gates an immediate retry`() = runTest {
        val ids = seed(3)
        server.enqueue(MockResponse().setResponseCode(500))

        val mgr = manager()
        val result1 = mgr.syncNow("test")

        assertTrue(result1.failed)
        assertEquals(500, result1.httpCode)
        assertEquals(0, result1.uploaded)
        assertEquals(3, dao.countPending())
        assertEquals(1, server.requestCount)

        val pendingRows = dao.getPendingBatch(10).associateBy { it.id }
        ids.forEach { id ->
            val row = pendingRows[id]
            assertNotNull("row $id should still be present/PENDING", row)
            assertEquals(1, row!!.attempts)
        }

        // Immediate retry is gated by the backoff window - no new HTTP request.
        val result2 = mgr.syncNow("test")
        assertEquals("BACKOFF_ACTIVE", result2.error)
        assertFalse(result2.failed)
        assertEquals(1, server.requestCount)

        // Advance past the 30s backoff window; server now accepts.
        currentTime += 31_000L
        server.enqueue(MockResponse().setResponseCode(200))
        val result3 = mgr.syncNow("test")

        assertFalse(result3.failed)
        assertEquals(3, result3.uploaded)
        assertEquals(0, dao.countPending())
        assertEquals(2, server.requestCount)

        // The retried request must resend the SAME rows as the failed attempt (an unacknowledged
        // batch is retried verbatim). recordedAt is unique per row here, so it identifies the set.
        val failedRows = requestRecordedAts(server.takeRequest().body.readUtf8())
        val retryRows = requestRecordedAts(server.takeRequest().body.readUtf8())
        assertEquals(failedRows.toSet(), retryRows.toSet())
        assertEquals(3, retryRows.toSet().size)
    }

    @Test
    fun `IOException (connection failure) reverts the batch and stops the loop the same as an HTTP failure`() = runTest {
        seed(2)
        server.shutdown() // any request now fails with a connection-refused IOException

        val result = manager().syncNow("test")

        assertTrue(result.failed)
        assertNull(result.httpCode)
        assertNotNull(result.error)
        assertEquals(2, dao.countPending())
    }

    // --- 401 auth-block --------------------------------------------------------------------

    @Test
    fun `401 auth-blocks, reverts the batch, posts a notification, and no-ops until cleared`() = runTest {
        seed(3)
        server.enqueue(MockResponse().setResponseCode(401))

        val mgr = manager()
        val result1 = mgr.syncNow("test")

        assertTrue(result1.failed)
        assertEquals(401, result1.httpCode)
        assertEquals(3, dao.countPending())
        assertTrue(configStore.authBlocked)
        assertEquals(1, server.requestCount)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = Shadows.shadowOf(notificationManager)
        assertEquals(1, shadowNm.size())

        // Further attempts no-op while auth-blocked - no additional HTTP request.
        val result2 = mgr.syncNow("test")
        assertEquals("AUTH_BLOCKED", result2.error)
        assertTrue(result2.failed)
        assertEquals(1, server.requestCount)

        // Clearing the block (e.g. via a fresh token) allows syncing again.
        mgr.clearAuthBlocked()
        server.enqueue(MockResponse().setResponseCode(200))
        val result3 = mgr.syncNow("test")

        assertFalse(result3.failed)
        assertEquals(3, result3.uploaded)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `setToken with a non-blank token persists it and clears authBlocked`() = runTest {
        seed(1)
        server.enqueue(MockResponse().setResponseCode(401))
        val mgr = manager()
        mgr.syncNow("test")
        assertTrue(configStore.authBlocked)

        mgr.setToken("fresh-token")

        assertFalse(configStore.authBlocked)
        assertEquals("fresh-token", tokenStore.getToken())
    }

    // --- single-flight -----------------------------------------------------------------------

    @Test
    fun `two concurrent syncNow calls result in exactly one HTTP pass`() = runTest {
        seed(12)
        // First response is slow enough to keep the run genuinely in-flight while the second
        // caller arrives. A generous surplus of extra 200s is queued so that if single-flight
        // were broken (two independent passes), the test fails on the requestCount assertion
        // instead of hanging on an unqueued MockWebServer response.
        server.enqueue(MockResponse().setResponseCode(200).setBodyDelay(200, TimeUnit.MILLISECONDS))
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }

        val mgr = manager(batchSize = 5)

        val d1 = async { mgr.syncNow("a") }
        val d2 = async { mgr.syncNow("b") }
        val r1 = d1.await()
        val r2 = d2.await()

        assertEquals(r1, r2)
        assertEquals(12, r1.uploaded)
        assertEquals(3, server.requestCount)
    }

    // --- no syncUrl configured ---------------------------------------------------------------

    @Test
    fun `no syncUrl configured is a no-op without crashing`() = runTest {
        configStore.syncUrl = null
        seed(3)

        val result = manager().syncNow("test")

        assertEquals(0, result.uploaded)
        assertFalse(result.failed)
        assertEquals("NO_SYNC_URL", result.error)
        assertEquals(3, result.pendingAfter)
        assertEquals(0, server.requestCount)
        assertEquals(3, dao.countPending())
    }

    // --- crash recovery ----------------------------------------------------------------------

    @Test
    fun `crash recovery resets stale SYNCING rows back to PENDING before the first real attempt`() = runTest {
        val id = dao.insert(
            LocationEntity(tripId = "t", latitude = 1.0, longitude = 2.0, timestamp = 1000L)
        )
        dao.markSyncing(listOf(id))
        assertEquals(0, dao.countPending()) // simulates a crash mid-upload, stuck SYNCING

        configStore.syncUrl = null // exercise the no-op path; recovery must still run first
        manager().syncNow("test")

        assertEquals(1, dao.countPending())
    }

    @Test
    fun `crash recovery retries on the next syncNow after an initial failure (does not latch done on failure)`() = runTest {
        val id = dao.insert(
            LocationEntity(tripId = "t", latitude = 1.0, longitude = 2.0, timestamp = 1000L)
        )
        dao.markSyncing(listOf(id))
        assertEquals(0, dao.countPending())

        val flakyDao = FlakyOnceDao(dao)
        configStore.syncUrl = null // no-op path; recovery must still be attempted regardless
        val mgr = LocationSyncManager(
            context = context,
            locationDao = flakyDao,
            configStore = configStore,
            tokenStore = tokenStore,
            clock = { currentTime }
        )

        mgr.syncNow("first") // resetAllSyncingToPending() throws once, swallowed - must not latch "done"
        assertEquals(0, dao.countPending())
        assertEquals(1, flakyDao.resetCallCount)

        mgr.syncNow("second") // retried, succeeds this time
        assertEquals(1, dao.countPending())
        assertEquals(2, flakyDao.resetCallCount)
    }

    // --- non-IOException Throwable from the batch step (e.g. malformed config) -----------------

    @Test
    fun `a malformed syncUrl throws synchronously from OkHttp - reverts the batch, arms backoff, clears isSyncing`() = runTest {
        val ids = seed(3)
        // No scheme: Request.Builder().url(...) throws IllegalArgumentException synchronously,
        // before any network I/O - this must be handled exactly like an HTTP/network failure.
        configStore.syncUrl = "myhost.com/sync"

        val mgr = manager()
        val result1 = mgr.syncNow("test")

        assertTrue(result1.failed)
        assertNull(result1.httpCode)
        assertNotNull(result1.error)
        assertEquals(0, server.requestCount) // never reached the network

        assertEquals(3, dao.countPending())
        val pendingRows = dao.getPendingBatch(10).associateBy { it.id }
        ids.forEach { id ->
            val row = pendingRows[id]
            assertNotNull("row $id should be back to PENDING", row)
            assertEquals(1, row!!.attempts)
        }

        assertFalse(mgr.statusFlow.value.isSyncing)

        // backoff armed - an immediate retry is gated, same as any other failure.
        val result2 = mgr.syncNow("test")
        assertEquals("BACKOFF_ACTIVE", result2.error)
        assertEquals(0, server.requestCount)
    }

    // --- single-flight ownership survives a cancelled awaiter -----------------------------------

    @Test
    fun `cancelling an awaiting caller mid-run does not let a second syncNow start a concurrent HTTP pass`() = runTest {
        seed(12)
        // Slow first response keeps the run genuinely in-flight (real I/O on Dispatchers.IO)
        // while the first caller is cancelled and a second caller arrives.
        server.enqueue(MockResponse().setResponseCode(200).setBodyDelay(300, TimeUnit.MILLISECONDS))
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }

        val mgr = manager(batchSize = 5)

        val firstCaller = launch { mgr.syncNow("a") }
        runCurrent() // let firstCaller reach its suspension point (awaiting the in-flight Deferred)
        firstCaller.cancelAndJoin() // cancel the AWAITER only - the run keeps executing in mgr's own scope

        val secondResult = mgr.syncNow("b") // must join the SAME still-in-flight run, not start a new one

        assertEquals(12, secondResult.uploaded)
        assertEquals(3, server.requestCount) // exactly one HTTP pass total, not two
    }

    // --- gated no-op runs must not clobber the last real result ---------------------------------

    @Test
    fun `a gated no-op run (backoff-active) does not overwrite the last real result`() = runTest {
        seed(3)
        server.enqueue(MockResponse().setResponseCode(500))
        val mgr = manager()

        val realResult = mgr.syncNow("test") // real failed attempt - sets lastResult
        assertEquals("HTTP_500", realResult.error)
        assertEquals(realResult, mgr.lastResult)

        val gatedResult = mgr.syncNow("test") // BACKOFF_ACTIVE gated no-op
        assertEquals("BACKOFF_ACTIVE", gatedResult.error)

        // lastResult must still be the real failure, not the gated no-op.
        assertEquals(realResult, mgr.lastResult)
    }

    /**
     * [LocationDao] delegate whose [resetAllSyncingToPending] throws once, then behaves normally -
     * used to verify [LocationSyncManager]'s crash recovery retries on a later `syncNow` instead
     * of permanently giving up after one failure (Kotlin interface delegation via `by delegate`
     * forwards every other method unchanged).
     */
    private class FlakyOnceDao(private val delegate: LocationDao) : LocationDao by delegate {
        var resetCallCount = 0
            private set

        override suspend fun resetAllSyncingToPending(): Int {
            resetCallCount++
            if (resetCallCount == 1) throw RuntimeException("simulated crash-recovery failure")
            return delegate.resetAllSyncingToPending()
        }
    }
}

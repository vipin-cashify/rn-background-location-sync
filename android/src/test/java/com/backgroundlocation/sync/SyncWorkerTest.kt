package com.backgroundlocation.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.backgroundlocation.database.LocationDatabase
import com.backgroundlocation.database.LocationEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [SyncWorker]'s self-cancel logic (trigger c's "safety net" from the task brief): a real
 * [SyncWorker] instance (via `androidx.work:work-testing`'s [TestListenableWorkerBuilder], not a
 * hand-replicated mock) is run against a real `WorkManager` instance
 * ([WorkManagerTestInitHelper.initializeTestWorkManager] - a programmatic init needing no
 * manifest/ContentProvider), backed by a real in-process [LocationDatabase] and a
 * [LocationSyncManager] wired to a `MockWebServer`.
 *
 * Only the "queue drained AND tracking inactive -> self-cancel" branch is covered here. The
 * complementary "stays enqueued while `LocationService.isRunning` is true" branch is implemented
 * exactly as designed in `SyncWorker.doWork()` but isn't independently unit-tested: flipping
 * that flag true requires a real `LocationService` lifecycle (its setter is private to that
 * class), and standing one up under Robolectric just to pin one boolean was judged disproportionate
 * machinery for this task - `LocationService.isRunning` defaults to (and, absent that lifecycle,
 * stays) false throughout this test class anyway, which is exactly the state this test needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LocationDatabase.clearInstance()
        LocationSyncManagerHolder.clearInstance()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        LocationSyncManagerHolder.clearInstance()
        LocationDatabase.clearInstance()
    }

    @Test
    fun `doWork syncs the queue then cancels its own periodic work once empty and tracking is inactive`() = runTest {
        val dao = LocationDatabase.getInstance(context).locationDao()
        runBlocking {
            dao.insert(LocationEntity(tripId = "t", latitude = 1.0, longitude = 2.0, timestamp = 1000L))
        }
        server.enqueue(MockResponse().setResponseCode(200))

        val configStore = SyncConfigStore(context).apply { syncUrl = server.url("/sync").toString() }
        val manager = LocationSyncManager(
            context = context,
            configStore = configStore,
            tokenStore = FakeTokenStore("t")
        )
        LocationSyncManagerHolder.setInstance(manager)
        SyncWorker.enqueuePeriodic(context)

        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, server.requestCount)
        assertEquals(0, manager.pendingCount())

        val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertTrue(
            "expected the unique periodic work to be cancelled (or gone), was: ${workInfos.map { it.state }}",
            workInfos.isEmpty() || workInfos.all { it.state == WorkInfo.State.CANCELLED }
        )
    }
}

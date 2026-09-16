package com.backgroundlocation

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests the boot-restart-of-tracking gap fix.
 *
 * BootCompletedReceiver.onReceive() itself calls goAsync(), which requires a real
 * BroadcastReceiver dispatch (not available under plain JUnit + mockk with the Android
 * stub jar). The recovery logic is therefore extracted into `restoreAfterBoot`, which is
 * what these tests exercise directly - matching the pattern RecoveryWorkerTest uses of
 * testing `doWork()` directly rather than the WorkManager scheduling machinery around it.
 */
class BootCompletedReceiverTest {

    private lateinit var context: Context
    private lateinit var geofenceManager: GeofenceManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context

        geofenceManager = mockk(relaxed = true)
        coEvery { geofenceManager.restoreGeofences() } just Runs

        mockkObject(GeofenceManagerHolder)
        every { GeofenceManagerHolder.getInstance(any()) } returns geofenceManager

        mockkObject(RecoveryWorker.Companion)
        every { RecoveryWorker.scheduleRecovery(any()) } just Runs

        mockkConstructor(LocationStorage::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `resumes tracking and restores geofences when tracking was active at boot`() = runTest {
        coEvery {
            anyConstructed<LocationStorage>().getTrackingStateAsync()
        } returns LocationStorage.TrackingState(
            isActive = true,
            tripId = "trip-boot-001",
            options = TrackingOptions()
        )

        BootCompletedReceiver().restoreAfterBoot(context)

        coVerify(exactly = 1) { geofenceManager.restoreGeofences() }
        verify(exactly = 1) { RecoveryWorker.scheduleRecovery(context) }
    }

    @Test
    fun `does nothing for tracking when isActive is false or there is no tracking_state row`() = runTest {
        // A single case covers both "a tracking_state row exists but isActive=false" and
        // "there is no tracking_state row at all (empty database)": LocationStorage.
        // getTrackingStateAsync() already collapses both to the exact same TrackingState
        // (isActive=false, tripId=null) - see LocationStorage.kt:248-285, where a null DAO
        // read (`entity == null`, i.e. no row) and an explicit isActive=false row both
        // produce this identical value. There is no distinct signal BootCompletedReceiver
        // could observe to tell those two cases apart, so a separate mock for "no row"
        // would only duplicate this one.
        coEvery {
            anyConstructed<LocationStorage>().getTrackingStateAsync()
        } returns LocationStorage.TrackingState(
            isActive = false,
            tripId = null,
            options = null
        )

        BootCompletedReceiver().restoreAfterBoot(context)

        coVerify(exactly = 1) { geofenceManager.restoreGeofences() }
        verify(exactly = 0) { RecoveryWorker.scheduleRecovery(any()) }
    }

    @Test
    fun `does not crash and does not schedule recovery when the database is unavailable`() = runTest {
        coEvery {
            anyConstructed<LocationStorage>().getTrackingStateAsync()
        } throws IllegalStateException("database not available")

        // Must not throw.
        BootCompletedReceiver().restoreAfterBoot(context)

        verify(exactly = 0) { RecoveryWorker.scheduleRecovery(any()) }
    }

    @Test
    fun `tracking recovery failure does not prevent geofence restoration`() = runTest {
        coEvery {
            anyConstructed<LocationStorage>().getTrackingStateAsync()
        } throws IllegalStateException("database not available")

        BootCompletedReceiver().restoreAfterBoot(context)

        coVerify(exactly = 1) { geofenceManager.restoreGeofences() }
    }
}

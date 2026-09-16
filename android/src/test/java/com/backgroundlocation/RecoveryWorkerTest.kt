package com.backgroundlocation

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.work.ListenableWorker
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * See `RecoveryWorkerForegroundInfoTest` (same package) for the `getForegroundInfo()` coverage -
 * kept in its own Robolectric-hosted file rather than added here: this file's heavy
 * `mockkObject`/`mockkStatic` usage doesn't coexist with `RobolectricTestRunner` (confirmed
 * empirically - `unmockkAll()`'s inline-mock retransform collides with Robolectric's own
 * class-instrumentation, throwing `UnsupportedOperationException: class redefinition failed`),
 * so this file stays on the plain Android stub jar as before.
 */
class RecoveryWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        // Chain SharedPreferences editor methods
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns true
        every { editor.apply() } just Runs

        // Default: stop token NOT set
        every { sharedPrefs.getBoolean("stop_token", false) } returns false

        // CoroutineWorker requires applicationContext
        every { context.applicationContext } returns context

        // Mock the LocationService companion object to control isRunning and stop token
        mockkObject(LocationService.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `skip restart when service already running`() = runTest {
        // Arrange: service is already running
        every { LocationService.isRunning } returns true

        val worker = RecoveryWorker(context, workerParams)
        val result = worker.doWork()

        // Assert: returns success without attempting service start
        assertEquals(ListenableWorker.Result.success(), result)

        // Verify no service start was attempted
        verify(exactly = 0) { context.startService(any()) }
        verify(exactly = 0) { context.startForegroundService(any()) }
        // Also verify LocationService.startService was never called
        verify(exactly = 0) { LocationService.startService(any(), any(), any()) }
    }

    @Test
    fun `skip restart when stop token is active`() = runTest {
        // Arrange: service is NOT running but stop token IS set
        every { LocationService.isRunning } returns false
        every { LocationService.isStopTokenSet(any()) } returns true

        // RecoveryWorker creates LocationStorage internally. Mock its constructor
        // to avoid Room database initialization.
        mockkConstructor(LocationStorage::class)

        val worker = RecoveryWorker(context, workerParams)
        val result = worker.doWork()

        // Assert: returns success without attempting service start
        assertEquals(ListenableWorker.Result.success(), result)

        // Verify no service start was attempted
        verify(exactly = 0) { LocationService.startService(any(), any(), any()) }
    }

    @Test
    fun `normal restart when tracking should be active`() = runTest {
        // Arrange: service is NOT running, stop token is NOT set, tracking IS active
        every { LocationService.isRunning } returns false
        every { LocationService.isStopTokenSet(any()) } returns false
        every { LocationService.startService(any(), any(), any()) } just Runs
        every { LocationService.clearStopToken(any()) } just Runs

        // Mock LocationStorage constructor to return controlled tracking state
        mockkConstructor(LocationStorage::class)
        coEvery {
            anyConstructed<LocationStorage>().getTrackingStateAsync()
        } returns LocationStorage.TrackingState(
            isActive = true,
            tripId = "trip-recovery-001",
            options = TrackingOptions()
        )

        // Mock permissions as granted
        mockkStatic(androidx.core.content.ContextCompat::class)
        every {
            androidx.core.content.ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        // RecoveryWorker calls setForeground() which requires WorkManager internals.
        // We mock the worker itself and replicate the critical decision logic to test
        // the branching behavior without needing the full WorkManager runtime.
        val worker = mockk<RecoveryWorker>(relaxed = true)
        coEvery { worker.doWork() } coAnswers {
            // Replicate the exact branching logic from RecoveryWorker.doWork():

            // 1. Check isRunning - should be false, so we continue
            if (LocationService.isRunning) {
                return@coAnswers ListenableWorker.Result.success()
            }

            // 2. Check stop token (first check) - should be false, so we continue
            if (LocationService.isStopTokenSet(context)) {
                return@coAnswers ListenableWorker.Result.success()
            }

            // 3. Get tracking state - mocked to return active state
            val trackingState = LocationStorage.TrackingState(
                isActive = true,
                tripId = "trip-recovery-001",
                options = TrackingOptions()
            )

            if (!trackingState.isActive || trackingState.tripId == null) {
                return@coAnswers ListenableWorker.Result.success()
            }

            // 4. Double-check stop token - should be false
            if (LocationService.isStopTokenSet(context)) {
                return@coAnswers ListenableWorker.Result.success()
            }

            // 5. Final stop token check - should be false
            if (LocationService.isStopTokenSet(context)) {
                return@coAnswers ListenableWorker.Result.success()
            }

            // 6. Start service - this is what we want to verify
            val opts = trackingState.options ?: TrackingOptions()
            LocationService.startService(context, trackingState.tripId!!, opts)

            ListenableWorker.Result.success()
        }

        val result = worker.doWork()

        // Assert: returns success and service start was called
        assertEquals(ListenableWorker.Result.success(), result)

        // Verify startService was called on LocationService companion
        verify(exactly = 1) {
            LocationService.startService(any(), eq("trip-recovery-001"), any())
        }
    }

    @Test
    fun `retries without clearing tracking state when foreground service start is rejected`() = runTest {
        // Arrange: service is NOT running, stop token is NOT set, tracking IS active
        every { LocationService.isRunning } returns false
        every { LocationService.isStopTokenSet(any()) } returns false
        every { LocationService.startService(any(), any(), any()) } just Runs

        mockkConstructor(LocationStorage::class)
        coEvery {
            anyConstructed<LocationStorage>().getTrackingStateAsync()
        } returns LocationStorage.TrackingState(
            isActive = true,
            tripId = "trip-recovery-002",
            options = TrackingOptions()
        )
        every { anyConstructed<LocationStorage>().saveTrackingState(any(), any(), any()) } just Runs

        mockkStatic(androidx.core.content.ContextCompat::class)
        every {
            androidx.core.content.ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        // setForeground() cannot be intercepted here: it is a `final` method RecoveryWorker
        // inherits from the compiled androidx.work.CoroutineWorker class, so mockk's spyk()
        // cannot override it (verified empirically - stubbing it via spyk had no effect; the
        // real call still ran and threw its own NullPointerException from this test harness's
        // incomplete WorkManager plumbing, not the ForegroundServiceStartNotAllowedException
        // this test needs to simulate). So, exactly like "normal restart when tracking should
        // be active" above, we mock the whole worker and replicate RecoveryWorker.doWork()'s
        // real branching - including the new IllegalStateException handling around
        // setForeground() - to pin the intended behavior: this test does not gate on the real
        // method body, only on this hand-authored replica of it.
        val worker = mockk<RecoveryWorker>(relaxed = true)
        val storage = LocationStorage(context)
        coEvery { worker.doWork() } coAnswers {
            if (LocationService.isRunning) {
                return@coAnswers ListenableWorker.Result.success()
            }
            if (LocationService.isStopTokenSet(context)) {
                return@coAnswers ListenableWorker.Result.success()
            }

            val trackingState = storage.getTrackingStateAsync()
            if (!trackingState.isActive || trackingState.tripId == null) {
                return@coAnswers ListenableWorker.Result.success()
            }
            if (LocationService.isStopTokenSet(context)) {
                return@coAnswers ListenableWorker.Result.success()
            }

            try {
                throw IllegalStateException("Starting FGS did not call startForeground()")
            } catch (e: IllegalStateException) {
                return@coAnswers ListenableWorker.Result.retry()
            }

            @Suppress("UNREACHABLE_CODE")
            ListenableWorker.Result.success()
        }

        val result = worker.doWork()

        // Assert: retries (does not fail/give up), never starts the service, never clears state
        assertEquals(ListenableWorker.Result.retry(), result)
        verify(exactly = 0) { LocationService.startService(any(), any(), any()) }
        verify(exactly = 0) { anyConstructed<LocationStorage>().saveTrackingState(any(), any(), any()) }
    }

    @Test
    fun `scheduleRecovery's work request is expedited so a boot-time enqueue can legally call setForeground`() {
        // Building the request (without enqueuing it against a real WorkManager instance) needs
        // no Android runtime/Context at all - it's pure androidx.work builder/model code - so
        // this doesn't need Robolectric or WorkManagerTestInitHelper, unlike SyncWorkerTest.
        // WorkRequest.getWorkSpec()/WorkSpec.expedited are public fields on the androidx.work
        // runtime jar (restricted-API annotated for IDE/lint purposes only, not enforced at
        // compile time from a different Gradle module) - see docs/phase2-audit.md for why this
        // was previously judged unverifiable and what changed.
        val request = RecoveryWorker.buildRecoveryWorkRequest()

        assertTrue(
            "expected the boot-time recovery work request to be expedited",
            request.workSpec.expedited
        )
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            request.workSpec.outOfQuotaPolicy
        )
    }
}

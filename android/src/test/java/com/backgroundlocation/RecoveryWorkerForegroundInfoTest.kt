package com.backgroundlocation

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [RecoveryWorker.getForegroundInfo] - added alongside [RecoveryWorker.buildRecoveryWorkRequest]
 * marking the boot-time recovery request expedited (see that function's kdoc): expedited
 * `CoroutineWorker`s must override `getForegroundInfo()`, since on API < 31 WorkManager promotes
 * ALL expedited work to a real foreground service automatically using whatever it returns - the
 * base `CoroutineWorker.getForegroundInfo()` throws `IllegalStateException("Not implemented")`,
 * which would otherwise crash every recovery attempt on those devices.
 *
 * Kept in its own file, hosted by [RobolectricTestRunner] rather than added to the plain-JUnit
 * `RecoveryWorkerTest`: `getForegroundInfo()` calls the real (private) `createRecoveryNotification()`,
 * which builds a real `android.app.Notification` via `NotificationCompat.Builder` - under this
 * module's plain Android stub jar (`testOptions.unitTests.returnDefaultValues = true`,
 * `RecoveryWorkerTest`'s convention), that chain NPEs because `Notification.Builder`'s setter
 * methods return `null` instead of `this` (confirmed empirically). Robolectric's real
 * framework-class shadows fix that - but `RecoveryWorkerTest`'s own `mockkObject`/`mockkStatic`
 * usage does *not* coexist with `RobolectricTestRunner` in this Kotlin/Gradle setup (confirmed
 * empirically: `unmockkAll()`'s inline-mock retransform collides with Robolectric's class
 * instrumentation, throwing `UnsupportedOperationException: class redefinition failed`), so this
 * one test lives in its own Robolectric-only file instead, using a real (Robolectric) Context
 * rather than a mockk fake and no static/object mocking at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecoveryWorkerForegroundInfoTest {

    @Test
    fun `getForegroundInfo returns a ForegroundInfo with the location foreground-service type`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val worker = RecoveryWorker(context, workerParams)

        val info = worker.getForegroundInfo()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, info.foregroundServiceType)
    }
}

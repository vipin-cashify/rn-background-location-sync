package com.backgroundlocation.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.backgroundlocation.LocationService
import java.util.concurrent.TimeUnit

/**
 * Safety-net periodic sync (trigger c in the task brief): runs every 15 minutes (WorkManager's
 * minimum periodic interval), network-constrained, so PENDING rows still get uploaded even if
 * the location-insert and connectivity triggers were both missed (e.g. the app was killed and
 * only `RecoveryWorker`/boot restart brought tracking back without a fresh network transition).
 *
 * Self-cancel logic lives entirely in [doWork] per the task brief: after each run, if the queue
 * is empty *and* tracking is not currently active, this cancels its own unique periodic work.
 * While tracking is active, the periodic work is left enqueued regardless of pending count
 * (new locations keep arriving); `LocationService` does not explicitly cancel it on stop -
 * that decision is deferred entirely to this worker's own next scheduled run.
 */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val manager = LocationSyncManagerHolder.getInstance(applicationContext)

        manager.syncNow("worker")

        val pending = manager.pendingCount()
        if (pending == 0 && !LocationService.isRunning) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
        }

        return Result.success()
    }

    companion object {
        /** Public (not private) so tests can assert against `WorkManager`'s unique-work state directly. */
        const val WORK_NAME = "location_sync_worker"
        private const val INTERVAL_MINUTES = 15L

        /** Enqueues (or no-ops if already enqueued) the periodic safety-net sync. */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Cancels the periodic safety-net sync, if enqueued. Exposed for tests/diagnostics. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

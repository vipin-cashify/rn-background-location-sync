package com.backgroundlocation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for safely recovering tracking sessions
 * Respects Android 12+ background start restrictions
 */
class RecoveryWorker(
  context: Context,
  workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

  /**
   * Required override now that [buildRecoveryWorkRequest] marks this worker's request as
   * expedited: on API < 31, WorkManager promotes ALL expedited work to a real foreground service
   * automatically, using whatever this returns, *before* [doWork] runs - the base
   * `CoroutineWorker.getForegroundInfo()` throws `IllegalStateException("Not implemented")`,
   * which would otherwise crash every recovery attempt on those devices. On API 31+ this override
   * is typically not needed for that specific promotion (expedited jobs run via `JobScheduler`
   * without an automatic foreground service), but [doWork] still separately calls `setForeground()`
   * itself mid-run on those versions for the reason explained in that call's own comment - the two
   * mechanisms are complementary, not redundant, and this override doesn't change that call or its
   * existing non-fatal rejection handling in any way.
   */
  override suspend fun getForegroundInfo(): ForegroundInfo =
    ForegroundInfo(
      RECOVERY_NOTIFICATION_ID,
      createRecoveryNotification(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    )

  override suspend fun doWork(): Result {
    // Skip recovery if the service is already running.
    // Avoids duplicate onStartCommand() which would accumulate location callbacks.
    if (LocationService.isRunning) {
      android.util.Log.d(TAG, "Service already running, skipping recovery")
      return Result.success()
    }

    val storage = LocationStorage(applicationContext)

    return try {
      // CRITICAL: Check stop token FIRST
      // If user explicitly called stopTracking(), we must NOT restart
      if (LocationService.isStopTokenSet(applicationContext)) {
        android.util.Log.d(TAG, "Stop token is set - user explicitly stopped tracking, skipping recovery")
        return Result.success()
      }

      val trackingState = storage.getTrackingStateAsync()

      // Only proceed if tracking is actually active with a valid tripId
      if (!trackingState.isActive || trackingState.tripId == null) {
        android.util.Log.d(TAG, "No active tracking session, skipping recovery")
        return Result.success()
      }

      // Double-check stop token again after reading tracking state
      // This handles the race condition where stopTracking() was called between the two checks
      if (LocationService.isStopTokenSet(applicationContext)) {
        android.util.Log.d(TAG, "Stop token set during state check - aborting recovery")
        return Result.success()
      }

      // Check permissions before attempting restart
      if (!hasRequiredPermissions()) {
        android.util.Log.w(TAG, "Permissions revoked, clearing tracking state")
        // Clear tracking state if permissions revoked
        storage.saveTrackingState(null, false)
        return Result.success()
      }

      // Use setForeground to safely start foreground service from background
      // This creates SystemForegroundService with location type
      val notification = createRecoveryNotification()
      try {
        setForeground(ForegroundInfo(
          RECOVERY_NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ))
      } catch (e: IllegalStateException) {
        // On API 31+ this is commonly android.app.ForegroundServiceStartNotAllowedException
        // (a subclass of IllegalStateException): the process has no active foreground-service
        // -start exemption at the moment doWork() actually runs. This is an EXPECTED possible
        // outcome when this worker is scheduled from BootCompletedReceiver - a plain,
        // non-expedited OneTimeWorkRequest is not guaranteed to inherit the BOOT_COMPLETED
        // exemption by the time WorkManager/JobScheduler executes it (see
        // docs/phase2-audit.md, "Chosen boot-start path"). Retry via WorkManager's own
        // backoff WITHOUT falling through to the generic catch below, so this specific,
        // recoverable-later condition never exhausts MAX_RETRY_ATTEMPTS and clears
        // tracking_state - the session should stay resumable whenever an exemption becomes
        // available again (e.g. the user opens the app).
        android.util.Log.w(
          TAG,
          "setForeground() rejected (foreground service start not allowed right now) - " +
            "retrying later via WorkManager backoff without clearing tracking state",
          e
        )
        return Result.retry()
      }

      // Final stop token check before starting service
      if (LocationService.isStopTokenSet(applicationContext)) {
        android.util.Log.d(TAG, "Stop token set just before service start - aborting recovery")
        return Result.success()
      }

      // Now safe to start the actual service
      val options = trackingState.options ?: TrackingOptions()
      LocationService.startService(applicationContext, trackingState.tripId, options)

      android.util.Log.d(TAG, "Successfully recovered tracking session: ${trackingState.tripId}")

      Result.success()
    } catch (e: Exception) {
      android.util.Log.e(TAG, "Recovery failed", e)

      // Don't retry indefinitely
      if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
        android.util.Log.e(TAG, "Max retry attempts reached, clearing tracking state")
        storage.saveTrackingState(null, false)
        return Result.failure()
      }

      Result.retry()
    }
  }

  private fun hasRequiredPermissions(): Boolean {
    val fineLocation = ContextCompat.checkSelfPermission(
      applicationContext,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val coarseLocation = ContextCompat.checkSelfPermission(
      applicationContext,
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(
        applicationContext,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }

    return fineLocation && coarseLocation && backgroundLocation
  }

  private fun createRecoveryNotification(): android.app.Notification {
    // Create notification channel for Android 8+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
      val channel = android.app.NotificationChannel(
        CHANNEL_ID,
        "Background Location",
        android.app.NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Background location tracking"
        setShowBadge(false)
      }
      notificationManager.createNotificationChannel(channel)
    }

    // Create minimal notification for setForeground
    val smallIcon = NotificationDefaults.getSmallIcon(applicationContext)

    return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setContentTitle("Recovering location tracking...")
      .setContentText("Restarting background location service")
      .setSmallIcon(smallIcon)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  companion object {
    private const val TAG = "RecoveryWorker"
    private const val RECOVERY_NOTIFICATION_ID = 2
    private const val CHANNEL_ID = "background_location_channel"
    private const val MAX_RETRY_ATTEMPTS = 3

    /**
     * Builds the [OneTimeWorkRequest] enqueued by [scheduleRecovery]. Factored out (rather than
     * inlined into [scheduleRecovery]) so a unit test can assert on the built request's
     * `WorkSpec` directly - building a request needs no live `WorkManager`/`Context` at all,
     * unlike enqueuing one.
     *
     * The request is **expedited** (`setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`): expedited
     * work is explicitly designed to carry a temporary foreground-service-start allowance, which
     * is exactly what [doWork]'s `setForeground()` call needs when this worker is scheduled from
     * `BootCompletedReceiver` - a strictly background context with no other exemption to lend it
     * (see docs/phase2-audit.md, "Residual uncertainty in this reasoning", for the full analysis
     * this addresses). The `RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback policy (rather than the
     * other option, `DROP_WORK_REQUEST`) is deliberate: if the app is out of its expedited-job
     * quota, the work should still run as ordinary (non time-sensitive) work rather than being
     * dropped outright - recovering tracking late is still far better than not recovering it at
     * all. This doesn't change the existing backoff policy
     * or unique-work semantics (`ExistingWorkPolicy.KEEP` in [scheduleRecovery]) - those are
     * orthogonal to expedited status.
     */
    internal fun buildRecoveryWorkRequest(): OneTimeWorkRequest {
      val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .build()

      return OneTimeWorkRequestBuilder<RecoveryWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(
          BackoffPolicy.EXPONENTIAL,
          WorkRequest.MIN_BACKOFF_MILLIS,
          TimeUnit.MILLISECONDS
        )
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    }

    /**
     * Schedules recovery work with exponential backoff
     */
    fun scheduleRecovery(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniqueWork(
          "location_recovery",
          ExistingWorkPolicy.KEEP, // Don't duplicate if already scheduled
          buildRecoveryWorkRequest()
        )
    }

    /**
     * Cancels any pending recovery work
     */
    fun cancelRecovery(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork("location_recovery")
    }
  }
}

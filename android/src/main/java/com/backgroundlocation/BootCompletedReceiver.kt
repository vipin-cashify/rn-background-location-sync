package com.backgroundlocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that restores state after device reboot:
 * 1. Re-registers geofences - GeofencingClient registrations are cleared on reboot,
 *    so they must be restored from Room persistence.
 * 2. Resumes location tracking if [TrackingStateEntity] (table `tracking_state`) says
 *    tracking was active when the device rebooted.
 *
 * Tracking is resumed via [RecoveryWorker.scheduleRecovery] rather than by starting
 * [LocationService] directly. A BOOT_COMPLETED receiver runs with no foreground activity,
 * and on Android 15+ (targetSdk 35+) foreground-service-launch restrictions further limit
 * which FGS types - including "location" - a BOOT_COMPLETED receiver may start directly
 * (BFSL: background foreground service location restrictions). RecoveryWorker's WorkManager
 * job already implements the safe recovery path for exactly this situation (isRunning/
 * stop-token/permission checks, then `setForeground()` promotion before starting
 * [LocationService]) - the same path [BackgroundLocationModule] already reuses on API 31+
 * in `onHostResume()`. Reusing it here avoids inventing a second service-start mechanism.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed - restoring geofences and tracking state")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                restoreAfterBoot(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Performs the boot-recovery work. Extracted from [onReceive] so it is unit-testable
     * directly - `goAsync()`/`PendingResult` require a real BroadcastReceiver dispatch and
     * cannot be exercised under plain JUnit + the Android stub jar used by this module's
     * unit tests (see build.gradle `testOptions.unitTests.returnDefaultValues`).
     */
    internal suspend fun restoreAfterBoot(context: Context) {
        restoreGeofences(context)
        resumeTrackingIfActive(context)
    }

    private suspend fun restoreGeofences(context: Context) {
        try {
            val manager = GeofenceManagerHolder.getInstance(context)
            manager.restoreGeofences()
            Log.d(TAG, "Geofences restored after boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore geofences after boot", e)
        }
    }

    /**
     * Resumes location tracking after boot if the persisted tracking state says it was
     * active. Must not crash if the database is empty or unavailable:
     * [LocationStorage.getTrackingStateAsync] already catches its own errors and returns
     * an inactive state, and this function wraps the call again as defense in depth against
     * failures constructing [LocationStorage] itself.
     */
    private suspend fun resumeTrackingIfActive(context: Context) {
        try {
            val storage = LocationStorage(context)
            val trackingState = storage.getTrackingStateAsync()

            if (trackingState.isActive && trackingState.tripId != null) {
                Log.d(
                    TAG,
                    "Active tracking session found after boot (tripId=${trackingState.tripId}) - scheduling recovery"
                )
                RecoveryWorker.scheduleRecovery(context)
            } else {
                Log.d(TAG, "No active tracking session after boot - nothing to resume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check tracking state after boot", e)
        }
    }
}

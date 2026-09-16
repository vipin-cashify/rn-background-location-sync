package com.backgroundlocation.sync

/**
 * Outcome of one [LocationSyncManager.syncNow] call (i.e. one single-flight run, which may
 * itself involve multiple HTTP requests as it drains the PENDING queue in batches).
 *
 * @property uploaded total rows successfully marked SYNCED across every batch in this run.
 * @property failed true if the run stopped early due to a network/HTTP error or an auth block.
 *   Also true for the auth-blocked no-op case (`error = "AUTH_BLOCKED"`) - a previous 401 is
 *   still in effect, so nothing was attempted, but that's still a failure state a status UI
 *   should surface as such. False for a clean drain, and false for the other two "nothing to do"
 *   no-op cases (no syncUrl configured, backoff window active) - these aren't failures, just a
 *   deliberate skip. `error` still identifies *why* nothing happened in every no-op case.
 * @property httpCode the HTTP status code that caused the run to stop (401, a 4xx≠401, or a
 *   5xx). Null on a clean drain or when no HTTP request was ever made (no syncUrl / IOException
 *   before any response / gated no-op).
 * @property error short machine-readable reason when nothing was uploaded or the run stopped
 *   early (e.g. `"NO_SYNC_URL"`, `"AUTH_BLOCKED"`, `"BACKOFF_ACTIVE"`, `"HTTP_500"`, an
 *   `IOException` message). Null on a clean drain.
 * @property pendingAfter `countPending()` measured at the end of the run.
 * @property timestamp epoch millis (via the manager's injected clock) when the run finished.
 */
data class SyncResult(
    val uploaded: Int,
    val failed: Boolean,
    val httpCode: Int?,
    val error: String?,
    val pendingAfter: Int,
    val timestamp: Long
)

/**
 * Live status snapshot for a future JS surface (next task's TurboModule) - see
 * [LocationSyncManager.statusFlow].
 */
data class SyncStatus(
    val isSyncing: Boolean,
    val authBlocked: Boolean,
    val lastResult: SyncResult?
)

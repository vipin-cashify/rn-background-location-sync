package com.backgroundlocation.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.backgroundlocation.database.LocationDao
import com.backgroundlocation.database.LocationDatabase
import com.backgroundlocation.database.LocationEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bulk-uploads PENDING `locations` rows to a configured HTTP endpoint ([SyncConfigStore.syncUrl]).
 * Pure native, driven entirely by persisted config/state - no TurboModule/JS surface here (next
 * task builds one on top of this class's public API).
 *
 * Construct via [LocationSyncManagerHolder.getInstance] in production - the constructor's
 * non-`context` parameters all have production defaults. Tests override [tokenStore] (an
 * in-memory fake; see kdoc on [TokenStore] for why), [httpClient]/`configStore.syncUrl` (pointed
 * at a `MockWebServer`), and [clock] (deterministic timestamps for backoff assertions).
 *
 * ### Single-flight
 * [syncNow] guards a single in-flight [Deferred] with a [Mutex]: concurrent callers observe the
 * *same* run and get the same [SyncResult] rather than starting a second HTTP pass.
 *
 * ### Batch loop
 * Each run repeatedly reads up to [SyncConfigStore.batchSize] PENDING rows (oldest first),
 * marks them SYNCING, POSTs them, and on a 2xx marks them SYNCED and loops again - until the
 * queue is drained. On any failure the loop reverts the in-flight batch back to PENDING and
 * stops (later batches are left untouched for the *next* run): it never marks a batch SYNCED
 * without a 2xx response, and it never leaves rows stuck in SYNCING when the loop exits.
 *
 * ### Failure handling
 * - `IOException` / 5xx / 4xx≠401: batch reverted to PENDING, `attempts` incremented, and a
 *   process-lifetime backoff gate (`nextAttemptAt`) blocks further attempts until it elapses -
 *   see [BACKOFF_SCHEDULE_MS]. A success resets the counter.
 * - 401: batch reverted to PENDING, [SyncConfigStore.authBlocked] is persisted `true`, a local
 *   notification is posted (via [SyncNotificationHelper]), and every subsequent `syncNow` is a
 *   no-op until [clearAuthBlocked]/[setToken] clears the flag.
 */
class LocationSyncManager(
    context: Context,
    private val locationDao: LocationDao = LocationDatabase.getInstance(context).locationDao(),
    private val configStore: SyncConfigStore = SyncConfigStore(context),
    private val tokenStore: TokenStore = PrefsTokenStore(context),
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(SyncRequestBody::class.java)

    private val mutex = Mutex()
    @Volatile private var inFlight: Deferred<SyncResult>? = null

    private val recoveryDone = AtomicBoolean(false)

    @Volatile private var consecutiveFailures = 0
    @Volatile private var nextAttemptAtMs = 0L

    private val _statusFlow = MutableStateFlow(
        SyncStatus(isSyncing = false, authBlocked = configStore.authBlocked, lastResult = null)
    )

    /** Live status for a future JS surface. */
    val statusFlow: StateFlow<SyncStatus> = _statusFlow.asStateFlow()

    /** Last completed [SyncResult], or null if [syncNow] has never returned yet. */
    val lastResult: SyncResult?
        get() = _statusFlow.value.lastResult

    /**
     * Runs (or awaits an already-running) upload pass. See the class kdoc for the full
     * failure/backoff/auth-block semantics.
     *
     * Ownership of clearing [inFlight] belongs to the *producing* coroutine (the `scope.async`
     * below), not to whichever caller happens to be awaiting it: an awaiting caller can be
     * cancelled independently of the run it's observing (e.g. a `WorkManager`-cancelled
     * `SyncWorker`), and a cancelled caller's own `finally` can still complete a fast,
     * non-suspending `Mutex` acquisition even after cancellation - clearing `inFlight` while the
     * run is genuinely still executing in [scope], which would let a fresh `syncNow` start a
     * second concurrent HTTP pass over the same rows. The producer's `finally` runs to
     * completion regardless of any caller's cancellation, since `scope.async` is rooted in this
     * manager's own long-lived [scope], not the caller's.
     */
    suspend fun syncNow(reason: String): SyncResult {
        val deferred = mutex.withLock {
            inFlight ?: scope.async {
                try {
                    runSyncLoop(reason)
                } finally {
                    mutex.withLock { inFlight = null }
                }
            }.also { inFlight = it }
        }
        return deferred.await()
    }

    /**
     * Fire-and-forget entry point for the location-insert/connectivity/worker triggers: skips
     * (without even entering the single-flight path) when there's clearly no network, otherwise
     * launches [syncNow] on this manager's background scope. The authoritative backoff/no-op
     * gates still live inside [runSyncLoop] - this is purely a cheap early-out so, e.g., every
     * single location insert while offline doesn't spawn a coroutine for nothing.
     */
    fun triggerSyncIfDue(reason: String) {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "triggerSyncIfDue($reason): no network, skipping")
            return
        }
        scope.launch {
            try {
                syncNow(reason)
            } catch (e: Exception) {
                Log.e(TAG, "triggerSyncIfDue($reason) failed", e)
            }
        }
    }

    /** Not-yet-uploaded row count, for callers (e.g. [SyncWorker]) deciding whether to self-cancel. */
    suspend fun pendingCount(): Int = locationDao.countPending()

    /**
     * Clears the persisted 401 auth-block and resets the failure backoff. Exposed separately
     * from [setToken] since a caller may want to retry without necessarily having a new token
     * (e.g. a user-initiated retry).
     */
    fun clearAuthBlocked() {
        configStore.authBlocked = false
        consecutiveFailures = 0
        nextAttemptAtMs = 0L
        _statusFlow.value = _statusFlow.value.copy(authBlocked = false)
    }

    /**
     * Recommended entry point for the next task's TurboModule: persists the token via
     * [tokenStore] and, for a non-blank token, also clears [SyncConfigStore.authBlocked] (a
     * successful re-auth implies the previous 401 is resolved) - see [clearAuthBlocked].
     */
    fun setToken(token: String?) {
        tokenStore.setToken(token)
        if (!token.isNullOrBlank()) {
            clearAuthBlocked()
        }
    }

    /**
     * Sets (or clears, with null/blank) the `x-sso-token` value. Like [setToken], a non-blank
     * value clears an existing 401 auth-block, since a fresh SSO token is a plausible fix for a
     * prior auth failure.
     */
    fun setSsoToken(token: String?) {
        tokenStore.setSsoToken(token)
        if (!token.isNullOrBlank()) {
            clearAuthBlocked()
        }
    }

    // --- Core loop -----------------------------------------------------------------------

    private suspend fun runSyncLoop(reason: String): SyncResult {
        ensureRecovered()
        _statusFlow.value = _statusFlow.value.copy(isSyncing = true)

        var result: SyncResult? = null
        try {
            result = performRun(reason)
            return result
        } finally {
            // In a `finally` (rather than immediately after `performRun`) so `isSyncing` is
            // always cleared even if something above throws unexpectedly - `performRun` itself
            // is designed to never throw (every reachable failure, including a non-IOException
            // Throwable from `postBatch`, is caught and turned into a SyncResult), but this is a
            // deliberate belt-and-suspenders guard against `_statusFlow` latching `isSyncing`
            // true forever.
            val finished = result
            // A gated no-op (no syncUrl / auth-blocked / backoff-active) short-circuited before
            // attempting any real work - it must not clobber the last *real* attempt's result,
            // which a status UI needs to keep showing (e.g. the last actual failure reason).
            val isGatedNoOp = finished != null && finished.error in GATED_NO_OP_ERRORS
            _statusFlow.value = _statusFlow.value.copy(
                isSyncing = false,
                authBlocked = configStore.authBlocked,
                lastResult = if (finished != null && !isGatedNoOp) finished else _statusFlow.value.lastResult
            )
        }
    }

    private suspend fun performRun(reason: String): SyncResult {
        val now = clock()
        val syncUrl = configStore.syncUrl

        if (syncUrl.isNullOrBlank()) {
            Log.w(TAG, "syncNow($reason): no syncUrl configured, skipping (no-op)")
            return noOpResult(now, error = ERROR_NO_SYNC_URL)
        }

        if (configStore.authBlocked) {
            Log.w(TAG, "syncNow($reason): auth-blocked, skipping until token reset")
            return noOpResult(now, error = ERROR_AUTH_BLOCKED, failed = true)
        }

        if (now < nextAttemptAtMs) {
            Log.d(TAG, "syncNow($reason): backoff active until $nextAttemptAtMs, skipping")
            return noOpResult(now, error = ERROR_BACKOFF_ACTIVE)
        }

        val batchSize = configStore.batchSize
        val token = tokenStore.getToken()
        val ssoToken = tokenStore.getSsoToken()
        val extraHeaders = configStore.extraHeaders

        var uploaded = 0

        while (true) {
            val batch = locationDao.getPendingBatch(batchSize)
            if (batch.isEmpty()) break

            val ids = batch.map { it.id }
            locationDao.markSyncing(ids)

            val outcome = try {
                postBatch(syncUrl, batch, token, ssoToken, extraHeaders)
            } catch (e: CancellationException) {
                // Never swallow cancellation - let it propagate untouched. (This is a genuine
                // cancellation of this manager's own `scope`, e.g. process shutdown - not an
                // awaiting caller being cancelled, which no longer reaches here at all now that
                // single-flight ownership belongs to the producer; see `syncNow`'s kdoc. Any rows
                // left SYNCING here are picked up by crash recovery on next startup.)
                throw e
            } catch (e: Throwable) {
                // Broad on purpose (not just IOException): `postBatch` can also throw
                // synchronously from bad-but-JS-reachable config, e.g.
                // `IllegalArgumentException` from `Request.Builder().url(syncUrl)` on a
                // scheme-less URL, or from `.header(k, v)` on an invalid extra-header
                // name/value. Any of these must revert+backoff exactly like a network/HTTP
                // failure - otherwise the batch is left stranded SYNCING (invisible to
                // `countPending`/`trimQueueToMax`, and `SyncWorker` would see `pending == 0` and
                // wrongly self-cancel) and `applyFailureBackoff` never runs, so every subsequent
                // trigger stalls trying (and failing) the same way.
                val attemptedAt = clock()
                locationDao.revertSyncingToPending(ids)
                locationDao.incrementAttempts(ids, attemptedAt)
                applyFailureBackoff(attemptedAt)
                Log.e(TAG, "syncNow($reason): batch failed (${e::class.java.simpleName}), reverted ${ids.size} row(s) and stopped", e)
                return finishResult(
                    uploaded,
                    failed = true,
                    httpCode = null,
                    error = e.message ?: e::class.java.simpleName ?: "UNKNOWN_ERROR",
                    timestamp = attemptedAt
                )
            }

            when {
                outcome.code in 200..299 -> {
                    val attemptedAt = clock()
                    locationDao.markSynced(ids, attemptedAt)
                    uploaded += ids.size
                    resetBackoff()
                }
                outcome.code == 401 -> {
                    val attemptedAt = clock()
                    locationDao.revertSyncingToPending(ids)
                    locationDao.incrementAttempts(ids, attemptedAt)
                    configStore.authBlocked = true
                    SyncNotificationHelper.showAuthBlockedNotification(appContext)
                    Log.w(TAG, "syncNow($reason): 401, auth-blocked and stopped")
                    return finishResult(uploaded, failed = true, httpCode = 401, error = "HTTP_401", timestamp = attemptedAt)
                }
                else -> {
                    val attemptedAt = clock()
                    locationDao.revertSyncingToPending(ids)
                    locationDao.incrementAttempts(ids, attemptedAt)
                    applyFailureBackoff(attemptedAt)
                    Log.e(TAG, "syncNow($reason): HTTP ${outcome.code}, reverted ${ids.size} row(s) and stopped")
                    return finishResult(uploaded, failed = true, httpCode = outcome.code, error = "HTTP_${outcome.code}", timestamp = attemptedAt)
                }
            }
        }

        resetBackoff()
        return finishResult(uploaded, failed = false, httpCode = null, error = null, timestamp = clock())
    }

    private suspend fun finishResult(uploaded: Int, failed: Boolean, httpCode: Int?, error: String?, timestamp: Long): SyncResult =
        SyncResult(
            uploaded = uploaded,
            failed = failed,
            httpCode = httpCode,
            error = error,
            pendingAfter = locationDao.countPending(),
            timestamp = timestamp
        )

    private suspend fun noOpResult(timestamp: Long, error: String, failed: Boolean = false): SyncResult =
        SyncResult(
            uploaded = 0,
            failed = failed,
            httpCode = null,
            error = error,
            pendingAfter = locationDao.countPending(),
            timestamp = timestamp
        )

    private class HttpOutcome(val code: Int)

    private suspend fun postBatch(
        syncUrl: String,
        batch: List<LocationEntity>,
        token: String?,
        ssoToken: String?,
        extraHeaders: Map<String, String>
    ): HttpOutcome = withContext(Dispatchers.IO) {
        val payload = SyncRequestBody(batch.map { it.toSyncPayload() })
        val json = requestAdapter.toJson(payload)
        val body = json.toRequestBody(JSON_MEDIA_TYPE)

        // Content-Type is deliberately NOT set as an explicit header here: `body` (via
        // `JSON_MEDIA_TYPE`) already carries "application/json; charset=utf-8", which OkHttp
        // writes onto the wire from the RequestBody itself - an explicit duplicate header risks
        // a conflicting/duplicated Content-Type line.
        val requestBuilder = Request.Builder()
            .url(syncUrl)
            .post(body)

        // The sales-tracker API authenticates with two headers: `x-authorization: Bearer <token>`
        // (the client/role token, the one the app's Save-Token UI feeds) and `x-sso-token: <token>`
        // (the raw user SSO JWT, no "Bearer" prefix - the server derives agent identity from it).
        // Both are read from the encrypted TokenStore so background uploads work with JS dead.
        if (!token.isNullOrBlank()) {
            requestBuilder.header("x-authorization", "Bearer $token")
        }
        if (!ssoToken.isNullOrBlank()) {
            requestBuilder.header("x-sso-token", ssoToken)
        }
        // Static app-identity headers (x-app-version / x-app-module / x-app-installer /
        // cache-control) come through here from `configureSync`. These must not collide with the
        // two auth headers above; the app's sync-config only puts app-identity headers here.
        extraHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            HttpOutcome(response.code)
        }
    }

    /**
     * Crash recovery, run once (per manager instance) at the start of the first [runSyncLoop].
     * The "done" flag is only latched on *success*: since this is only ever called from within
     * `runSyncLoop`, which single-flight already guarantees never runs concurrently with itself,
     * a plain get-then-set (no CAS needed) is safe here - if `resetAllSyncingToPending()` throws,
     * the flag stays false so the *next* `syncNow` retries the reset, instead of permanently
     * giving up and leaving crash-stranded SYNCING rows stuck forever.
     */
    private suspend fun ensureRecovered() {
        if (recoveryDone.get()) return
        try {
            val count = locationDao.resetAllSyncingToPending()
            if (count > 0) {
                Log.w(TAG, "Crash recovery: reset $count SYNCING row(s) back to PENDING")
            }
            recoveryDone.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Crash recovery failed, will retry on the next syncNow", e)
        }
    }

    private fun applyFailureBackoff(nowMs: Long) {
        val delayMs = BACKOFF_SCHEDULE_MS[minOf(consecutiveFailures, BACKOFF_SCHEDULE_MS.lastIndex)]
        consecutiveFailures++
        nextAttemptAtMs = nowMs + delayMs
    }

    private fun resetBackoff() {
        consecutiveFailures = 0
        nextAttemptAtMs = 0L
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            // Never let a connectivity-check failure block a sync attempt.
            true
        }
    }

    companion object {
        private const val TAG = "LocationSyncManager"

        private const val ERROR_NO_SYNC_URL = "NO_SYNC_URL"
        private const val ERROR_AUTH_BLOCKED = "AUTH_BLOCKED"
        private const val ERROR_BACKOFF_ACTIVE = "BACKOFF_ACTIVE"

        /**
         * `error` values that mean "short-circuited before attempting any real work" - used by
         * [runSyncLoop] to avoid clobbering [SyncStatus.lastResult] with a gated no-op instead of
         * the last real attempt's outcome.
         */
        private val GATED_NO_OP_ERRORS = setOf(ERROR_NO_SYNC_URL, ERROR_AUTH_BLOCKED, ERROR_BACKOFF_ACTIVE)

        /** [30s, 1m, 5m, 15m, 15m, ...] - index clamps at the last entry for repeated failures. */
        private val BACKOFF_SCHEDULE_MS = longArrayOf(
            TimeUnit.SECONDS.toMillis(30),
            TimeUnit.MINUTES.toMillis(1),
            TimeUnit.MINUTES.toMillis(5),
            TimeUnit.MINUTES.toMillis(15)
        )

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

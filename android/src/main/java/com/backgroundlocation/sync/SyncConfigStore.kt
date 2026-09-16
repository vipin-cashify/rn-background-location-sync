package com.backgroundlocation.sync

import android.content.Context
import org.json.JSONObject

/**
 * Plain (non-encrypted) SharedPreferences-backed configuration for the bulk-upload sync layer
 * ([LocationSyncManager]).
 *
 * This is the **single source of truth** for retention/upload config at runtime: defaults are
 * seeded from [SyncDefaults]'s constants, but any value written here (e.g. by a future JS
 * settings surface) overrides that seed. [com.backgroundlocation.LocationStorage]'s retention
 * cleanup reads `maxQueueRows`/`maxAgeDays` from this store rather than from [SyncDefaults]
 * directly, so there is exactly one place retention config is read from at runtime.
 *
 * Not encrypted (unlike [TokenStore]) - nothing stored here is a secret: a sync endpoint URL,
 * batch size, retention window, and non-sensitive extra headers.
 */
class SyncConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Bulk-upload endpoint. Sync is a no-op (with a logged warning) while this is null/blank. */
    var syncUrl: String?
        get() = prefs.getString(KEY_SYNC_URL, null)
        set(value) { prefs.edit().putString(KEY_SYNC_URL, value).apply() }

    /**
     * Max rows read from the PENDING queue per upload request. Coerced to `[1, 999]` on read:
     * - A stored 0 or negative value would make `getPendingBatch(limit)` always return an empty
     *   list, which `LocationSyncManager` reads as "queue drained" - a silent, permanent no-op
     *   that would never actually upload anything.
     * - A stored value above 999 risks exceeding SQLite's default host-parameter limit
     *   (`SQLITE_MAX_VARIABLE_NUMBER`, 999 on the SQLite ~3.9 builds shipped down to this
     *   module's `minSdk` 24) the first time a batch's row ids are bound as `IN (:ids)`
     *   parameters (e.g. `markSyncing`/`markSynced`/`revertSyncingToPending`), which would throw
     *   `android.database.sqlite.SQLiteException` instead of uploading anything.
     */
    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE).coerceIn(1, MAX_BATCH_SIZE)
        set(value) { prefs.edit().putInt(KEY_BATCH_SIZE, value).apply() }

    /** Hard cap on total `locations` rows - see [com.backgroundlocation.database.LocationDao.trimQueueToMax]. */
    var maxQueueRows: Int
        get() = prefs.getInt(KEY_MAX_QUEUE_ROWS, SyncDefaults.MAX_QUEUE_ROWS)
        set(value) { prefs.edit().putInt(KEY_MAX_QUEUE_ROWS, value).apply() }

    /** Age (days) after which a SYNCED row becomes eligible for deletion. */
    var maxAgeDays: Int
        get() = prefs.getInt(KEY_MAX_AGE_DAYS, SyncDefaults.RETENTION_DAYS)
        set(value) { prefs.edit().putInt(KEY_MAX_AGE_DAYS, value).apply() }

    /** [maxAgeDays] expressed in milliseconds, for direct use against epoch-millis columns. */
    val maxAgeMillis: Long
        get() = maxAgeDays.toLong() * 24L * 60L * 60L * 1000L

    /** Extra headers sent on every bulk-upload request, in addition to `Authorization`/`Content-Type`. */
    var extraHeaders: Map<String, String>
        get() = decodeHeaders(prefs.getString(KEY_EXTRA_HEADERS, null))
        set(value) { prefs.edit().putString(KEY_EXTRA_HEADERS, encodeHeaders(value)).apply() }

    /**
     * Set by [LocationSyncManager] when the server responds 401; while true, `syncNow` is a
     * no-op until cleared (see [LocationSyncManager.clearAuthBlocked]/[LocationSyncManager.setToken]).
     */
    var authBlocked: Boolean
        get() = prefs.getBoolean(KEY_AUTH_BLOCKED, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTH_BLOCKED, value).apply() }

    companion object {
        private const val PREFS_FILE = "cashify_location_sync_config"

        private const val KEY_SYNC_URL = "sync_url"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_MAX_QUEUE_ROWS = "max_queue_rows"
        private const val KEY_MAX_AGE_DAYS = "max_age_days"
        private const val KEY_EXTRA_HEADERS = "extra_headers_json"
        private const val KEY_AUTH_BLOCKED = "auth_blocked"

        const val DEFAULT_BATCH_SIZE = 500

        /** Upper bound for [batchSize] - see its kdoc for the SQLite host-parameter reasoning. */
        private const val MAX_BATCH_SIZE = 999

        private fun encodeHeaders(headers: Map<String, String>): String {
            val json = JSONObject()
            headers.forEach { (k, v) -> json.put(k, v) }
            return json.toString()
        }

        private fun decodeHeaders(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val obj = JSONObject(json)
                val result = LinkedHashMap<String, String>()
                obj.keys().forEach { key -> result[key] = obj.getString(key) }
                result
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}

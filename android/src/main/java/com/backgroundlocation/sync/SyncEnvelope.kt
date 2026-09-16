package com.backgroundlocation.sync

import com.backgroundlocation.database.LocationEntity

/**
 * Wire format for the Cashify sales-tracker bulk-upload request body:
 * ```json
 * { "logs": [ { "punchType": "RECORD", "lat": 28.4485476, "long": 77.0414017,
 *     "recordedAt": 1789368041125, "locationType": "GPS" } ] }
 * ```
 *
 * Notes on the mapping (see [toSyncPayload]):
 * - `long` (not `lng`) is the server's longitude key.
 * - `recordedAt` is epoch milliseconds as a number — `LocationEntity.timestamp` is already
 *   stored in that unit, so it is sent verbatim (no ISO-8601 conversion).
 * - `punchType` is currently always `"RECORD"` (the only punch type this POC emits).
 * - `locationType` is `"MOCK"` when the fix is from a mock/spoofed provider
 *   ([LocationEntity.isFromMockProvider]); otherwise it reflects the *real* provider of the fix
 *   (see [toLocationType]). This preserves the integrating app's server-side spoof detection.
 * - The server derives the agent/user identity from the `x-sso-token` header, so no client /
 *   device / agent id is sent in the body. `clientId` is still kept on the local row for queue
 *   bookkeeping and local idempotency; it is deliberately not on the wire.
 */
data class SyncLocationPayload(
    val punchType: String,
    val lat: Double,
    val long: Double,
    val recordedAt: Long,
    val locationType: String
)

data class SyncRequestBody(
    val logs: List<SyncLocationPayload>
)

/** Punch type sent for every location fix in this POC. */
private const val PUNCH_TYPE_RECORD = "RECORD"

/**
 * Maps the stored Android `Location.getProvider()` string to the server's `locationType` enum.
 * The value reflects the real source of the fix (per product requirement — not hardcoded):
 * the fused client typically reports `"fused"`, a raw GPS fix `"gps"`, etc. Unknown providers
 * are passed through uppercased; a genuinely absent provider falls back to `"GPS"`.
 */
internal fun toLocationType(provider: String?): String = when (provider?.lowercase()) {
    null, "" -> "GPS"
    "gps" -> "GPS"
    "network" -> "NETWORK"
    "fused" -> "FUSED"
    "passive" -> "PASSIVE"
    else -> provider.uppercase()
}

/**
 * Maps a persisted [LocationEntity] row to the sales-tracker wire payload.
 */
fun LocationEntity.toSyncPayload(): SyncLocationPayload = SyncLocationPayload(
    punchType = PUNCH_TYPE_RECORD,
    lat = latitude,
    long = longitude,
    recordedAt = timestamp,
    locationType = if (isFromMockProvider == true) "MOCK" else toLocationType(provider)
)

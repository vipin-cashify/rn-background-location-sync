import Foundation

/// Sync bookkeeping state for a stored location row. Mirrors the Android `SyncState`.
enum SyncState {
  static let pending = "PENDING"
  static let syncing = "SYNCING"
  static let synced = "SYNCED"
}

/// Defaults for the sync layer (mirrors Android `SyncDefaults`/`SyncConfigStore`).
enum SyncDefaults {
  static let batchSize = 500
  static let maxBatchSize = 999 // SQLite-parity cap; harmless on Core Data but kept for contract symmetry.
  static let maxQueueRows = 10_000
  static let maxAgeDays = 7
  /// Minimum spacing between persisted fixes (iOS has no timer-based updates, so continuous
  /// `distanceFilter: 0` updates are throttled to ~this so the queue matches the ~2-min contract).
  static let insertThrottleSeconds: Double = 110
}

/// Machine-readable reasons for a no-op / failed run (mirrors Android's error strings).
enum SyncError {
  static let noSyncUrl = "NO_SYNC_URL"
  static let authBlocked = "AUTH_BLOCKED"
  static let backoffActive = "BACKOFF_ACTIVE"
}

/// Outcome of one `syncNow` run. Rendered to a `[String: Any]` dictionary for the ObjC/JS bridge
/// (matches the TS `SyncResult` shape: uploaded, failed, httpCode?, error?, pendingAfter, timestamp).
struct SyncResult {
  let uploaded: Int
  let failed: Bool
  let httpCode: Int?
  let error: String?
  let pendingAfter: Int
  let timestamp: Double // epoch ms

  /// Bridge payload. `httpCode`/`error` are written as explicit `NSNull` when nil so the JS side's
  /// `=== null` checks hold (matching the Android module's `putNull`).
  func toDictionary(pending: Int? = nil, authBlocked: Bool? = nil) -> [String: Any] {
    var dict: [String: Any] = [
      "uploaded": uploaded,
      "failed": failed,
      "httpCode": httpCode as Any? ?? NSNull(),
      "error": error as Any? ?? NSNull(),
      "pendingAfter": pendingAfter,
      "timestamp": timestamp,
    ]
    // The event payload adds `pending` (alias) + `authBlocked`, matching the Android event.
    if let pending = pending { dict["pending"] = pending }
    if let authBlocked = authBlocked { dict["authBlocked"] = authBlocked }
    return dict
  }
}

/// Maps the stored provider string to the server's `locationType` enum (mirrors Android
/// `toLocationType`): reflects the real fix source, uppercase; `GPS` when absent.
func syncLocationType(fromProvider provider: String?) -> String {
  switch provider?.lowercased() {
  case .none, .some(""): return "GPS"
  case "gps": return "GPS"
  case "network": return "NETWORK"
  case "fused": return "FUSED"
  case "passive": return "PASSIVE"
  // iOS tags mock/simulated fixes as provider "simulated"; the mock signal is not part of this
  // contract's locationType enum, so normalize it to GPS rather than sending "SIMULATED".
  case "simulated": return "GPS"
  default: return provider!.uppercased()
  }
}

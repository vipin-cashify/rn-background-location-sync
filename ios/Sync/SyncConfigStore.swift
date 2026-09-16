import Foundation

/// Persists the bulk-upload sync configuration (mirrors Android `SyncConfigStore`).
///
/// Backed by a `UserDefaults` suite namespaced to this fork, so the native sync layer can read
/// the config even when JS is dead (e.g. a BGTaskScheduler run after the app was terminated).
/// The auth tokens themselves live in the Keychain (`TokenStore`), not here.
@objc public class SyncConfigStore: NSObject {

  @objc public static let shared = SyncConfigStore()

  private let defaults: UserDefaults

  private override init() {
    self.defaults = UserDefaults(suiteName: SyncConfigStore.suiteName) ?? .standard
    super.init()
  }

  /// Bulk endpoint. `nil`/empty means sync is a no-op (logged) until configured.
  var syncUrl: String? {
    get { defaults.string(forKey: Keys.syncUrl) }
    set { defaults.set(newValue, forKey: Keys.syncUrl) }
  }

  /// Rows per upload request. Coerced to `[1, maxBatchSize]` on read (a 0/negative value would
  /// otherwise make the drain loop a silent permanent no-op).
  var batchSize: Int {
    get {
      guard defaults.object(forKey: Keys.batchSize) != nil else { return SyncDefaults.batchSize }
      return min(max(defaults.integer(forKey: Keys.batchSize), 1), SyncDefaults.maxBatchSize)
    }
    set { defaults.set(newValue, forKey: Keys.batchSize) }
  }

  var maxQueueRows: Int {
    get {
      guard defaults.object(forKey: Keys.maxQueueRows) != nil else { return SyncDefaults.maxQueueRows }
      return max(defaults.integer(forKey: Keys.maxQueueRows), 1)
    }
    set { defaults.set(newValue, forKey: Keys.maxQueueRows) }
  }

  var maxAgeDays: Int {
    get {
      guard defaults.object(forKey: Keys.maxAgeDays) != nil else { return SyncDefaults.maxAgeDays }
      return max(defaults.integer(forKey: Keys.maxAgeDays), 1)
    }
    set { defaults.set(newValue, forKey: Keys.maxAgeDays) }
  }

  /// Extra static headers (JSON-encoded), e.g. x-app-version / x-app-module. Must NOT include the
  /// two auth headers (those come from `TokenStore` via `LocationSyncManager`).
  var extraHeaders: [String: String] {
    get {
      guard let json = defaults.string(forKey: Keys.extraHeaders),
            let data = json.data(using: .utf8),
            let map = try? JSONSerialization.jsonObject(with: data) as? [String: String] else {
        return [:]
      }
      return map
    }
    set {
      if let data = try? JSONSerialization.data(withJSONObject: newValue),
         let json = String(data: data, encoding: .utf8) {
        defaults.set(json, forKey: Keys.extraHeaders)
      }
    }
  }

  /// Whether a prior 401 has paused sync until a fresh token is set. Persisted so it survives
  /// process death (mirrors Android's persisted `authBlocked`).
  var authBlocked: Bool {
    get { defaults.bool(forKey: Keys.authBlocked) }
    set { defaults.set(newValue, forKey: Keys.authBlocked) }
  }

  /// Sets the config from a JS `configureSync` call (headers arrive JSON-encoded, like Android).
  @objc public func configure(syncUrl: String, batchSize: NSNumber?, maxQueueRows: NSNumber?,
                              maxAgeDays: NSNumber?, headersJson: String?) {
    self.syncUrl = syncUrl
    if let b = batchSize { self.batchSize = b.intValue }
    if let q = maxQueueRows { self.maxQueueRows = q.intValue }
    if let a = maxAgeDays { self.maxAgeDays = a.intValue }
    if let h = headersJson, let data = h.data(using: .utf8),
       let map = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
      self.extraHeaders = map
    }
  }

  private static let suiteName = "com.backgroundlocation.sync.config"

  private enum Keys {
    static let syncUrl = "sync_url"
    static let batchSize = "batch_size"
    static let maxQueueRows = "max_queue_rows"
    static let maxAgeDays = "max_age_days"
    static let extraHeaders = "extra_headers"
    static let authBlocked = "auth_blocked"
  }
}

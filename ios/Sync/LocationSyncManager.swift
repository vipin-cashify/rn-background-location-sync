import Foundation
import Network

/// Native bulk-upload sync layer for iOS (mirrors Android `LocationSyncManager`).
///
/// Drains PENDING location rows to the sales-tracker bulk endpoint in batches, marking rows
/// SYNCED only on a 2xx response. Runs with JS dead (foreground/suspended, reachability-restored,
/// and BGTaskScheduler paths all funnel through `performSync`). Single-flight via a serial queue.
@objc public class LocationSyncManager: NSObject {

  @objc public static let shared = LocationSyncManager()

  /// Bridge hook: called after every run with the result payload (SyncResult fields + `pending` +
  /// `authBlocked`). The native layer never depends on this being set.
  @objc public var onSyncStatusChanged: (([String: Any]) -> Void)?

  private let config = SyncConfigStore.shared
  private let tokens = TokenStore.shared
  private let storage = LocationStorage.shared

  private let syncQueue = DispatchQueue(label: "com.backgroundlocation.sync", qos: .utility)
  private let stateLock = NSLock()

  // Backoff state (process-lifetime; mirrors Android).
  private var consecutiveFailures = 0
  private var nextAttemptAtMs: Double = 0
  private var recoveryDone = false
  private var passQueued = false
  private var bgTaskRegistered = false
  private var bootstrapped = false

  private lazy var pathMonitor = NWPathMonitor()
  private var monitorStarted = false
  private let httpTimeout: TimeInterval = 30

  private static let backoffScheduleMs: [Double] = [30_000, 60_000, 300_000, 900_000]
  /// BGTaskScheduler identifier — the app must also list this in Info.plist
  /// `BGTaskSchedulerPermittedIdentifiers` and register it at launch (see `registerBackgroundTask`).
  @objc public static let backgroundTaskIdentifier = "com.backgroundlocation.sync.refresh"

  private override init() { super.init() }

  // MARK: - Config / tokens (called from the bridge)

  @objc public func configure(syncUrl: String, batchSize: NSNumber?, maxQueueRows: NSNumber?,
                              maxAgeDays: NSNumber?, headersJson: String?) {
    config.configure(syncUrl: syncUrl, batchSize: batchSize, maxQueueRows: maxQueueRows,
                     maxAgeDays: maxAgeDays, headersJson: headersJson)
  }

  @objc public func setAuthToken(_ token: String) {
    tokens.authToken = token
    if !token.isEmpty { clearAuthBlocked() }
  }

  @objc public func setSsoToken(_ token: String) {
    tokens.ssoToken = token
    if !token.isEmpty { clearAuthBlocked() }
  }

  private func clearAuthBlocked() {
    // Also reset the failure backoff (matches Android): a fresh token is the user's "retry", so
    // the next sync should not stay silently gated by a stale backoff window from a prior failure.
    stateLock.lock()
    config.authBlocked = false
    consecutiveFailures = 0
    nextAttemptAtMs = 0
    stateLock.unlock()
  }

  // MARK: - Bootstrap

  /// Wires the JS-independent sync triggers: the post-insert hook, connectivity monitoring, and the
  /// background safety-net task. Idempotent. Called from the native tracking-start AND recovery
  /// (SLC-resurrection) paths so sync works even when the app was relaunched in the background
  /// without JS ever starting.
  @objc public func bootstrap() {
    stateLock.lock()
    if bootstrapped { stateLock.unlock(); return }
    bootstrapped = true
    stateLock.unlock()

    storage.onLocationsPersisted = { [weak self] in
      self?.triggerSyncIfDue(reason: "location-insert")
    }
    startNetworkMonitoring()
    scheduleBackgroundTask()

    // Run retention once per process, independent of a clean drain (matches Android's
    // once-per-start call). Ensures the 10k-row / 7-day cap is enforced even when the app launches
    // with a backlog and can't achieve a clean drain (offline / no syncUrl / persistent failures).
    // Dispatched (not inline) since bootstrap() runs on a caller's queue.
    syncQueue.async { [weak self] in
      guard let self = self else { return }
      self.storage.runRetention(maxRows: self.config.maxQueueRows,
                                maxAgeMillis: Double(self.config.maxAgeDays) * 24 * 60 * 60 * 1000)
    }
  }

  // MARK: - Triggers

  /// Post-insert / reachability trigger. Coalesces bursts (skips if a pass is already queued) and
  /// respects the backoff gate inside `performSync`.
  @objc public func triggerSyncIfDue(reason: String) {
    stateLock.lock()
    if passQueued { stateLock.unlock(); return }
    passQueued = true
    stateLock.unlock()
    syncQueue.async { [weak self] in
      guard let self = self else { return }
      self.stateLock.lock(); self.passQueued = false; self.stateLock.unlock()
      _ = self.performSync(reason: reason)
    }
  }

  /// JS `forceSync()`: always runs a pass (still respects auth-block/backoff inside) and returns
  /// the result via completion.
  @objc public func forceSync(completion: @escaping ([String: Any]) -> Void) {
    syncQueue.async { [weak self] in
      guard let self = self else { return }
      let result = self.performSync(reason: "force")
      completion(result.toDictionary(pending: result.pendingAfter, authBlocked: self.config.authBlocked))
    }
  }

  @objc public func pendingCount() -> Int { return storage.countPending() }

  /// Starts reachability monitoring (call when tracking starts). Idempotent.
  @objc public func startNetworkMonitoring() {
    guard !monitorStarted else { return }
    monitorStarted = true
    pathMonitor.pathUpdateHandler = { [weak self] path in
      if path.status == .satisfied { self?.triggerSyncIfDue(reason: "connectivity") }
    }
    pathMonitor.start(queue: syncQueue)
  }

  @objc public func stopNetworkMonitoring() {
    guard monitorStarted else { return }
    pathMonitor.cancel()
    monitorStarted = false
  }

  // MARK: - Core drain

  private func nowMs() -> Double { return Date().timeIntervalSince1970 * 1000 }

  /// One drain run. Returns the outcome and emits the status event. Runs on `syncQueue`.
  private func performSync(reason: String) -> SyncResult {
    ensureRecovered()

    guard let syncUrl = config.syncUrl, !syncUrl.isEmpty else {
      return finish(SyncResult(uploaded: 0, failed: false, httpCode: nil,
                               error: SyncError.noSyncUrl, pendingAfter: storage.countPending(),
                               timestamp: nowMs()), gated: true)
    }

    stateLock.lock(); let blocked = config.authBlocked; let gateUntil = nextAttemptAtMs; stateLock.unlock()
    if blocked {
      return finish(SyncResult(uploaded: 0, failed: true, httpCode: nil,
                               error: SyncError.authBlocked, pendingAfter: storage.countPending(),
                               timestamp: nowMs()), gated: true)
    }
    if nowMs() < gateUntil {
      return finish(SyncResult(uploaded: 0, failed: false, httpCode: nil,
                               error: SyncError.backoffActive, pendingAfter: storage.countPending(),
                               timestamp: nowMs()), gated: true)
    }

    guard let url = URL(string: syncUrl) else {
      // Malformed URL is a failure, not a crash: arm backoff so we don't spin. (Matches Android's
      // broad-throwable revert path — no rows were marked SYNCING yet here.)
      applyFailureBackoff()
      return finish(SyncResult(uploaded: 0, failed: true, httpCode: nil, error: "BAD_URL",
                               pendingAfter: storage.countPending(), timestamp: nowMs()), gated: false)
    }

    let batchSize = config.batchSize
    let token = tokens.authToken
    let ssoToken = tokens.ssoToken
    let extraHeaders = config.extraHeaders
    var uploaded = 0
    var lastFirstId: Int64 = -1

    while true {
      let batch = storage.fetchPendingBatch(limit: batchSize)
      if batch.isEmpty { break }
      let ids = batch.compactMap { $0["id"] as? Int64 }.map { NSNumber(value: $0) }
      // No-progress guard: if the same first row is re-fetched after a 2xx (e.g. markSynced failed
      // to persist), break instead of re-POSTing the same batch forever.
      let firstId = batch.first?["id"] as? Int64 ?? -1
      if firstId == lastFirstId {
        NSLog("[BackgroundLocation] Sync made no progress on batch \(firstId); stopping to avoid a re-upload loop")
        break
      }
      lastFirstId = firstId
      storage.markSyncing(ids: ids)

      let outcome = postBatch(url: url, batch: batch, token: token, ssoToken: ssoToken, extraHeaders: extraHeaders)

      switch outcome {
      case .success(let code) where (200...299).contains(code):
        storage.markSynced(ids: ids, attemptedAt: nowMs())
        uploaded += batch.count
        // Reset backoff on every successful batch (matches Android), not just a full clean drain.
        stateLock.lock(); consecutiveFailures = 0; nextAttemptAtMs = 0; stateLock.unlock()
        continue
      case .success(401):
        storage.revertSyncingToPending(ids: ids)
        storage.incrementAttempts(ids: ids, attemptedAt: nowMs())
        stateLock.lock(); config.authBlocked = true; stateLock.unlock()
        postAuthBlockedNotification()
        return finish(SyncResult(uploaded: uploaded, failed: true, httpCode: 401,
                                 error: "HTTP_401", pendingAfter: storage.countPending(),
                                 timestamp: nowMs()), gated: false)
      case .success(let code):
        storage.revertSyncingToPending(ids: ids)
        storage.incrementAttempts(ids: ids, attemptedAt: nowMs())
        applyFailureBackoff()
        return finish(SyncResult(uploaded: uploaded, failed: true, httpCode: code,
                                 error: "HTTP_\(code)", pendingAfter: storage.countPending(),
                                 timestamp: nowMs()), gated: false)
      case .failure(let message):
        storage.revertSyncingToPending(ids: ids)
        storage.incrementAttempts(ids: ids, attemptedAt: nowMs())
        applyFailureBackoff()
        return finish(SyncResult(uploaded: uploaded, failed: true, httpCode: nil,
                                 error: message, pendingAfter: storage.countPending(),
                                 timestamp: nowMs()), gated: false)
      }
    }

    // Clean drain: reset backoff and run retention.
    stateLock.lock(); consecutiveFailures = 0; nextAttemptAtMs = 0; stateLock.unlock()
    storage.runRetention(maxRows: config.maxQueueRows,
                         maxAgeMillis: Double(config.maxAgeDays) * 24 * 60 * 60 * 1000)
    return finish(SyncResult(uploaded: uploaded, failed: false, httpCode: nil, error: nil,
                             pendingAfter: storage.countPending(), timestamp: nowMs()), gated: false)
  }

  private enum HttpOutcome { case success(Int); case failure(String) }

  private func postBatch(url: URL, batch: [[String: Any]], token: String?, ssoToken: String?,
                         extraHeaders: [String: String]) -> HttpOutcome {
    var logs: [[String: Any]] = []
    for row in batch {
      guard let latStr = row["latitude"] as? String, let lat = Double(latStr),
            let lngStr = row["longitude"] as? String, let lng = Double(lngStr),
            let ts = row["timestamp"] as? Double else { continue }
      logs.append([
        "punchType": "RECORD",
        "lat": lat,
        "long": lng,
        "recordedAt": Int64(ts),
        "locationType": (row["isFromMockProvider"] as? Bool == true)
          ? "MOCK"
          : syncLocationType(fromProvider: row["provider"] as? String),
      ])
    }

    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = httpTimeout
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    if let token = token, !token.isEmpty {
      request.setValue("Bearer \(token)", forHTTPHeaderField: "x-authorization")
    }
    if let ssoToken = ssoToken, !ssoToken.isEmpty {
      request.setValue(ssoToken, forHTTPHeaderField: "x-sso-token")
    }
    for (k, v) in extraHeaders { request.setValue(v, forHTTPHeaderField: k) }

    do {
      request.httpBody = try JSONSerialization.data(withJSONObject: ["logs": logs])
    } catch {
      return .failure("SERIALIZE_ERROR")
    }

    let sem = DispatchSemaphore(value: 0)
    var result: HttpOutcome = .failure("UNKNOWN")
    let task = URLSession.shared.dataTask(with: request) { _, response, error in
      if let error = error {
        result = .failure((error as NSError).code == NSURLErrorTimedOut ? "TIMEOUT" : "NETWORK_ERROR")
      } else if let http = response as? HTTPURLResponse {
        result = .success(http.statusCode)
      } else {
        result = .failure("NO_RESPONSE")
      }
      sem.signal()
    }
    task.resume()
    sem.wait()
    return result
  }

  private func applyFailureBackoff() {
    stateLock.lock()
    let idx = min(consecutiveFailures, LocationSyncManager.backoffScheduleMs.count - 1)
    nextAttemptAtMs = nowMs() + LocationSyncManager.backoffScheduleMs[idx]
    consecutiveFailures += 1
    stateLock.unlock()
  }

  /// Crash recovery, run once per process before the first drain.
  private func ensureRecovered() {
    stateLock.lock(); let done = recoveryDone; stateLock.unlock()
    if done { return }
    let count = storage.resetAllSyncingToPending()
    if count > 0 { NSLog("[BackgroundLocation] Reset \(count) stranded SYNCING rows to PENDING") }
    stateLock.lock(); recoveryDone = true; stateLock.unlock()
  }

  private func finish(_ result: SyncResult, gated: Bool) -> SyncResult {
    // Don't emit an event for a gated no-op (no syncUrl / auth-blocked-again / backoff-active): it
    // would overwrite the JS status UI's last *real* result/error with a non-event (matches
    // Android's "don't clobber lastResult on gated no-ops"). forceSync still returns the gated
    // result directly via its completion, so a manual tap still shows the user why nothing happened.
    if !gated {
      let payload = result.toDictionary(pending: result.pendingAfter, authBlocked: config.authBlocked)
      onSyncStatusChanged?(payload)
    }
    return result
  }

  private func postAuthBlockedNotification() {
    SyncNotificationHelper.postAuthBlocked()
  }

  // MARK: - Background task (safety net)

  /// Registers the BGAppRefreshTask handler. Must be called from the app's
  /// `didFinishLaunchingWithOptions` (iOS requires task registration before launch completes).
  @objc public func registerBackgroundTask() {
    stateLock.lock(); bgTaskRegistered = true; stateLock.unlock()
    BackgroundTaskScheduler.register(identifier: LocationSyncManager.backgroundTaskIdentifier) { [weak self] task in
      guard let self = self else { task.setTaskCompleted(success: true); return }
      self.scheduleBackgroundTask() // chain the next one

      // Guard against double-completion: the OS may fire expirationHandler (time budget exhausted)
      // while performSync is still blocked on its per-batch URLSession semaphore. We complete the
      // task at most once; any in-flight upload finishes on syncQueue and its rows settle
      // (SYNCED / reverted) correctly regardless, with startup crash-recovery as the backstop.
      let completedLock = NSLock()
      var completed = false
      let complete: (Bool) -> Void = { success in
        completedLock.lock(); defer { completedLock.unlock() }
        if completed { return }
        completed = true
        task.setTaskCompleted(success: success)
      }
      task.expirationHandler = { complete(false) }
      self.syncQueue.async {
        let result = self.performSync(reason: "bgtask")
        complete(!result.failed)
      }
    }
  }

  /// Schedules the next safety-net refresh (~15 min). No-op until `registerBackgroundTask` has run
  /// (iOS rejects a submit for an unregistered identifier), so callers can invoke it freely.
  @objc public func scheduleBackgroundTask() {
    stateLock.lock(); let registered = bgTaskRegistered; stateLock.unlock()
    guard registered else { return }
    BackgroundTaskScheduler.schedule(identifier: LocationSyncManager.backgroundTaskIdentifier,
                                     earliestAfter: 15 * 60)
  }
}

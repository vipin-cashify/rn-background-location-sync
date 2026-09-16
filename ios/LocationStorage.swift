import Foundation
import CoreData

@objc public class LocationStorage: NSObject {
  @objc public static let shared = LocationStorage()

  private let batchSize = 10
  private let batchTimeoutSeconds: TimeInterval = 5.0
  private let queue = DispatchQueue(label: "com.backgroundlocation.storage", qos: .userInitiated)

  private var buffer: [LocationBufferEntry] = []
  private var batchTimer: DispatchSourceTimer?
  private var nextId: Int64 = 1

  /// Timestamp (epoch ms) of the last fix accepted into the queue. iOS delivers continuous
  /// updates (`distanceFilter: 0`) with no timer, so inserts are throttled to one per
  /// `insertThrottleMs` to match the desired capture cadence.
  private var lastAcceptedTimestampMs: Double = 0

  /// Minimum spacing (ms) between persisted fixes. Defaults to `SyncDefaults.insertThrottleSeconds`
  /// but is overridden from the tracking `updateInterval` (see `setInsertThrottleMs`) so iOS
  /// matches the integrating app's configured interval instead of a hardcoded value.
  private var insertThrottleMs: Double = SyncDefaults.insertThrottleSeconds * 1000

  /// Sets the insert throttle from the tracking interval (ms). Called when tracking starts. A
  /// non-positive value falls back to the default throttle.
  @objc public func setInsertThrottleMs(_ ms: Double) {
    queue.async { [weak self] in
      guard let self = self else { return }
      self.insertThrottleMs = ms > 0 ? ms : SyncDefaults.insertThrottleSeconds * 1000
    }
  }

  /// Called on the storage queue after a flush persists one or more new PENDING rows. The sync
  /// layer sets this to trigger an opportunistic upload (decoupled so this file has no hard
  /// dependency on `LocationSyncManager`).
  @objc public var onLocationsPersisted: (() -> Void)?

  private override init() {
    super.init()
    loadNextId()
    startBatchTimer()
  }

  // MARK: - Location Persistence

  @objc public func saveLocation(
    tripId: String,
    latitude: String,
    longitude: String,
    timestamp: Double,
    accuracy: Double,
    altitude: Double,
    speed: Double,
    bearing: Double,
    verticalAccuracyMeters: Double,
    speedAccuracyMetersPerSecond: Double,
    bearingAccuracyDegrees: Double,
    provider: String?,
    isFromMockProvider: NSNumber?
  ) {
    queue.async { [weak self] in
      guard let self = self else { return }

      // Throttle: drop fixes that arrive sooner than the contract cadence. iOS has no
      // timer-based updates, so continuous `distanceFilter: 0` updates would otherwise flood
      // the queue. The first fix (lastAccepted == 0) is always kept. Note: this is a
      // process-global gate keyed on the fix timestamp (not arrival time) and is not reset on
      // stop/start, so the first fix of a trip restarted within the throttle window, and any
      // out-of-order/duplicate-timestamp fix, is dropped. Wall-clock always advances, so it can
      // never drop fixes permanently — acceptable for the POC's ~2-min cadence.
      if self.lastAcceptedTimestampMs > 0,
         timestamp - self.lastAcceptedTimestampMs < self.insertThrottleMs {
        return
      }
      self.lastAcceptedTimestampMs = timestamp

      let entry = LocationBufferEntry(
        id: self.nextId,
        tripId: tripId,
        latitude: latitude,
        longitude: longitude,
        timestamp: timestamp,
        accuracy: accuracy,
        altitude: altitude,
        speed: speed,
        bearing: bearing,
        verticalAccuracyMeters: verticalAccuracyMeters,
        speedAccuracyMetersPerSecond: speedAccuracyMetersPerSecond,
        bearingAccuracyDegrees: bearingAccuracyDegrees,
        provider: provider,
        isFromMockProvider: isFromMockProvider?.boolValue
      )
      self.nextId += 1
      self.buffer.append(entry)

      if self.buffer.count >= self.batchSize {
        self.flushBuffer()
      }
    }
  }

  @objc public func getLocations(tripId: String) -> [[String: Any]] {
    return queue.sync { [weak self] in
      guard let self = self else { return [] }

      self.flushBuffer()

      let context = CoreDataStack.shared.newBackgroundContext()
      var result: [[String: Any]] = []

      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
        request.predicate = NSPredicate(format: "tripId == %@", tripId)
        request.sortDescriptors = [NSSortDescriptor(key: "timestamp", ascending: true)]

        do {
          let entities = try context.fetch(request)
          result = entities.compactMap { entity -> [String: Any]? in
            // Data integrity: skip entries missing required fields
            guard let lat = entity.value(forKey: "latitude") as? String,
                  let lng = entity.value(forKey: "longitude") as? String,
                  !lat.isEmpty, !lng.isEmpty,
                  let timestamp = entity.value(forKey: "timestamp") as? Double,
                  timestamp > 0 else {
              NSLog("[BackgroundLocation] Skipped corrupt location entry during read")
              return nil
            }
            return self.entityToDict(entity)
          }
        } catch {
          NSLog("[BackgroundLocation] Failed to fetch locations: \(error)")
        }
      }

      return result
    }
  }

  @objc public func clearTrip(tripId: String) {
    queue.async { [weak self] in
      guard let self = self else { return }

      // Remove from buffer
      self.buffer.removeAll { $0.tripId == tripId }

      // Remove from Core Data
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSFetchRequestResult>(entityName: "LocationEntity")
        request.predicate = NSPredicate(format: "tripId == %@", tripId)
        let deleteRequest = NSBatchDeleteRequest(fetchRequest: request)

        do {
          try context.execute(deleteRequest)
          try context.save()
        } catch {
          NSLog("[BackgroundLocation] Failed to clear trip: \(error)")
        }
      }
    }
  }

  @objc public func forceFlush() {
    queue.sync {
      flushBuffer()
    }
  }

  // MARK: - Tracking State Persistence

  @objc public func saveTrackingState(tripId: String?, isActive: Bool, options: TrackingOptions?) {
    queue.async { [weak self] in
      guard self != nil else { return }

      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "TrackingStateEntity")
        request.predicate = NSPredicate(format: "id == %d", 1)

        do {
          let results = try context.fetch(request)
          let entity: NSManagedObject

          if let existing = results.first {
            entity = existing
          } else {
            guard let entityDescription = NSEntityDescription.entity(forEntityName: "TrackingStateEntity", in: context) else { return }
            entity = NSManagedObject(entity: entityDescription, insertInto: context)
            entity.setValue(Int16(1), forKey: "id")
          }

          entity.setValue(isActive, forKey: "isActive")
          entity.setValue(tripId, forKey: "tripId")
          entity.setValue(options?.accuracy, forKey: "accuracy")
          entity.setValue(options?.distanceFilter?.doubleValue ?? 0.0, forKey: "distanceFilter")
          entity.setValue(options?.updateInterval?.doubleValue ?? 0.0, forKey: "updateInterval")
          entity.setValue(options?.foregroundOnly?.boolValue, forKey: "foregroundOnly")

          try context.save()
        } catch {
          NSLog("[BackgroundLocation] Failed to save tracking state: \(error)")
        }
      }
    }
  }

  @objc public func saveTrackingStateSync(tripId: String?, isActive: Bool, options: TrackingOptions?) {
    queue.sync {
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "TrackingStateEntity")
        request.predicate = NSPredicate(format: "id == %d", 1)

        do {
          let results = try context.fetch(request)
          let entity: NSManagedObject

          if let existing = results.first {
            entity = existing
          } else {
            guard let entityDescription = NSEntityDescription.entity(forEntityName: "TrackingStateEntity", in: context) else { return }
            entity = NSManagedObject(entity: entityDescription, insertInto: context)
            entity.setValue(Int16(1), forKey: "id")
          }

          entity.setValue(isActive, forKey: "isActive")
          entity.setValue(tripId, forKey: "tripId")
          entity.setValue(options?.accuracy, forKey: "accuracy")
          entity.setValue(options?.distanceFilter?.doubleValue ?? 0.0, forKey: "distanceFilter")
          entity.setValue(options?.updateInterval?.doubleValue ?? 0.0, forKey: "updateInterval")
          entity.setValue(options?.foregroundOnly?.boolValue, forKey: "foregroundOnly")

          try context.save()
        } catch {
          NSLog("[BackgroundLocation] Failed to save tracking state sync: \(error)")
        }
      }
    }
  }

  @objc public func getTrackingState() -> [String: Any] {
    return queue.sync {
      var result: [String: Any] = ["isActive": false]

      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "TrackingStateEntity")
        request.predicate = NSPredicate(format: "id == %d", 1)

        do {
          if let entity = try context.fetch(request).first {
            result["isActive"] = entity.value(forKey: "isActive") as? Bool ?? false
            result["tripId"] = entity.value(forKey: "tripId")
            result["accuracy"] = entity.value(forKey: "accuracy")
            result["distanceFilter"] = entity.value(forKey: "distanceFilter")
            result["updateInterval"] = entity.value(forKey: "updateInterval")
            result["foregroundOnly"] = entity.value(forKey: "foregroundOnly")
          }
        } catch {
          NSLog("[BackgroundLocation] Failed to get tracking state: \(error)")
        }
      }

      return result
    }
  }

  // MARK: - Stop Token (Crash Recovery)

  private static let stopTokenKey = "bg_location_stop_token"
  private static let stopTokenTimestampKey = "bg_location_stop_token_ts"
  private static let stopTokenWindowSeconds: TimeInterval = 60.0

  @objc public func setStopToken() {
    UserDefaults.standard.set(true, forKey: LocationStorage.stopTokenKey)
    UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: LocationStorage.stopTokenTimestampKey)
    UserDefaults.standard.synchronize()
  }

  @objc public func clearStopToken() {
    UserDefaults.standard.removeObject(forKey: LocationStorage.stopTokenKey)
    UserDefaults.standard.removeObject(forKey: LocationStorage.stopTokenTimestampKey)
    UserDefaults.standard.synchronize()
  }

  @objc public func hasValidStopToken() -> Bool {
    guard UserDefaults.standard.bool(forKey: LocationStorage.stopTokenKey) else {
      return false
    }

    let tokenTimestamp = UserDefaults.standard.double(forKey: LocationStorage.stopTokenTimestampKey)
    guard tokenTimestamp > 0 else {
      return true
    }

    let elapsed = Date().timeIntervalSince1970 - tokenTimestamp
    return elapsed <= LocationStorage.stopTokenWindowSeconds
  }

  // MARK: - Restart Loop Detection

  private static let restartCountKey = "bg_location_restart_count"
  private static let restartWindowStartKey = "bg_location_restart_window_start"
  private static let maxRestartsPerHour = 5
  private static let restartWindowSeconds: TimeInterval = 3600.0

  @objc public func canAttemptRecovery() -> Bool {
    let count = UserDefaults.standard.integer(forKey: LocationStorage.restartCountKey)
    let windowStart = UserDefaults.standard.double(forKey: LocationStorage.restartWindowStartKey)
    let now = Date().timeIntervalSince1970

    if windowStart > 0 && (now - windowStart) > LocationStorage.restartWindowSeconds {
      // Window expired, reset counter
      UserDefaults.standard.set(0, forKey: LocationStorage.restartCountKey)
      UserDefaults.standard.set(now, forKey: LocationStorage.restartWindowStartKey)
      UserDefaults.standard.synchronize()
      return true
    }

    return count < LocationStorage.maxRestartsPerHour
  }

  @objc public func recordRecoveryAttempt() {
    let count = UserDefaults.standard.integer(forKey: LocationStorage.restartCountKey)
    let windowStart = UserDefaults.standard.double(forKey: LocationStorage.restartWindowStartKey)
    let now = Date().timeIntervalSince1970

    if windowStart == 0 {
      UserDefaults.standard.set(now, forKey: LocationStorage.restartWindowStartKey)
    }

    UserDefaults.standard.set(count + 1, forKey: LocationStorage.restartCountKey)
    UserDefaults.standard.synchronize()
  }

  @objc public func resetRecoveryCounter() {
    UserDefaults.standard.set(0, forKey: LocationStorage.restartCountKey)
    UserDefaults.standard.removeObject(forKey: LocationStorage.restartWindowStartKey)
    UserDefaults.standard.synchronize()
  }

  // MARK: - Sync queue access (used by LocationSyncManager)

  /// Oldest-first PENDING rows, up to `limit`, as wire-building dictionaries. Flushes the buffer
  /// first so freshly-captured fixes are included. Each dict carries the fields the uploader
  /// needs: `id` (Int64, the mark key), `clientId`, `latitude`/`longitude` (String), `timestamp`
  /// (Double ms), `provider` (String?).
  @objc public func fetchPendingBatch(limit: Int) -> [[String: Any]] {
    return queue.sync {
      flushBuffer()
      var rows: [[String: Any]] = []
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
        request.predicate = NSPredicate(format: "syncState == %@", SyncState.pending)
        request.sortDescriptors = [NSSortDescriptor(key: "timestamp", ascending: true)]
        request.fetchLimit = limit
        do {
          for entity in try context.fetch(request) {
            guard let id = entity.value(forKey: "id") as? Int64,
                  let lat = entity.value(forKey: "latitude") as? String,
                  let lng = entity.value(forKey: "longitude") as? String,
                  let ts = entity.value(forKey: "timestamp") as? Double else { continue }
            rows.append([
              "id": id,
              "clientId": entity.value(forKey: "clientId") as? String ?? "",
              "latitude": lat,
              "longitude": lng,
              "timestamp": ts,
              "provider": entity.value(forKey: "provider") as Any,
              "isFromMockProvider": (entity.value(forKey: "isFromMockProvider") as? Bool) ?? false,
            ])
          }
        } catch {
          NSLog("[BackgroundLocation] fetchPendingBatch failed: \(error)")
        }
      }
      return rows
    }
  }

  @objc public func markSyncing(ids: [NSNumber]) { updateState(ids: ids, to: SyncState.syncing) }

  @objc public func markSynced(ids: [NSNumber], attemptedAt: Double) {
    updateState(ids: ids, to: SyncState.synced, lastAttemptAt: attemptedAt)
  }

  /// Reverts only rows still in SYNCING (state-guarded, so a row another pass already marked
  /// SYNCED is never demoted — mirrors Android's guarded revert).
  @objc public func revertSyncingToPending(ids: [NSNumber]) {
    updateState(ids: ids, to: SyncState.pending, from: SyncState.syncing)
  }

  @objc public func incrementAttempts(ids: [NSNumber], attemptedAt: Double) {
    guard !ids.isEmpty else { return }
    queue.sync {
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
        request.predicate = NSPredicate(format: "id IN %@", ids)
        do {
          for entity in try context.fetch(request) {
            let current = (entity.value(forKey: "attempts") as? Int32) ?? 0
            entity.setValue(current + 1, forKey: "attempts")
            entity.setValue(attemptedAt, forKey: "lastAttemptAt")
          }
          try context.save()
        } catch {
          NSLog("[BackgroundLocation] incrementAttempts failed: \(error)")
        }
      }
    }
  }

  /// Crash recovery: any row stuck SYNCING from a killed process is reset to PENDING. Returns the
  /// number reset.
  @objc public func resetAllSyncingToPending() -> Int {
    return queue.sync {
      var count = 0
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
        request.predicate = NSPredicate(format: "syncState == %@", SyncState.syncing)
        do {
          let rows = try context.fetch(request)
          for entity in rows { entity.setValue(SyncState.pending, forKey: "syncState") }
          count = rows.count
          if count > 0 { try context.save() }
        } catch {
          NSLog("[BackgroundLocation] resetAllSyncingToPending failed: \(error)")
        }
      }
      return count
    }
  }

  @objc public func countPending() -> Int {
    return queue.sync {
      flushBuffer()
      var count = 0
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSFetchRequestResult>(entityName: "LocationEntity")
        request.predicate = NSPredicate(format: "syncState == %@", SyncState.pending)
        count = (try? context.count(for: request)) ?? 0
      }
      return count
    }
  }

  /// Retention: delete SYNCED rows older than the cutoff, then, if still over the row cap, delete
  /// the oldest SYNCED rows first and only then the oldest PENDING rows (a deliberate data-loss
  /// guard — mirrors Android's `trimQueueToMax`).
  @objc public func runRetention(maxRows: Int, maxAgeMillis: Double) {
    queue.sync {
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        do {
          let cutoff = Date().timeIntervalSince1970 * 1000 - maxAgeMillis
          let ageReq = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
          ageReq.predicate = NSPredicate(format: "syncState == %@ AND timestamp < %f", SyncState.synced, cutoff)
          for entity in try context.fetch(ageReq) { context.delete(entity) }

          let total = try context.count(for: NSFetchRequest<NSFetchRequestResult>(entityName: "LocationEntity"))
          var over = total - maxRows
          if over > 0 {
            // Oldest SYNCED first.
            over = try deleteOldest(state: SyncState.synced, upTo: over, context: context)
            if over > 0 {
              _ = try deleteOldest(state: SyncState.pending, upTo: over, context: context)
            }
          }
          try context.save()
        } catch {
          NSLog("[BackgroundLocation] runRetention failed: \(error)")
        }
      }
    }
  }

  /// Deletes up to `upTo` oldest rows in `state`; returns the remaining count still to delete.
  private func deleteOldest(state: String, upTo: Int, context: NSManagedObjectContext) throws -> Int {
    guard upTo > 0 else { return 0 }
    let request = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
    request.predicate = NSPredicate(format: "syncState == %@", state)
    request.sortDescriptors = [NSSortDescriptor(key: "timestamp", ascending: true)]
    request.fetchLimit = upTo
    let rows = try context.fetch(request)
    for entity in rows { context.delete(entity) }
    return upTo - rows.count
  }

  /// Shared state transition by id list, optionally guarded on a current state.
  private func updateState(ids: [NSNumber], to newState: String, from currentState: String? = nil,
                           lastAttemptAt: Double? = nil) {
    guard !ids.isEmpty else { return }
    queue.sync {
      let context = CoreDataStack.shared.newBackgroundContext()
      context.performAndWait {
        let request = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
        if let currentState = currentState {
          request.predicate = NSPredicate(format: "id IN %@ AND syncState == %@", ids, currentState)
        } else {
          request.predicate = NSPredicate(format: "id IN %@", ids)
        }
        do {
          for entity in try context.fetch(request) {
            entity.setValue(newState, forKey: "syncState")
            if let ts = lastAttemptAt { entity.setValue(ts, forKey: "lastAttemptAt") }
          }
          try context.save()
        } catch {
          NSLog("[BackgroundLocation] updateState(\(newState)) failed: \(error)")
        }
      }
    }
  }

  // MARK: - Cleanup

  @objc public func cleanup() {
    queue.async { [weak self] in
      self?.batchTimer?.cancel()
      self?.batchTimer = nil
      self?.flushBuffer()
    }
  }

  // MARK: - Private

  private func loadNextId() {
    let context = CoreDataStack.shared.newBackgroundContext()
    context.performAndWait {
      let request = NSFetchRequest<NSManagedObject>(entityName: "LocationEntity")
      request.sortDescriptors = [NSSortDescriptor(key: "id", ascending: false)]
      request.fetchLimit = 1

      do {
        if let lastEntity = try context.fetch(request).first,
           let lastId = lastEntity.value(forKey: "id") as? Int64 {
          self.nextId = lastId + 1
        }
      } catch {
        NSLog("[BackgroundLocation] Failed to load next id: \(error)")
      }
    }
  }

  private func startBatchTimer() {
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now() + batchTimeoutSeconds, repeating: batchTimeoutSeconds)
    timer.setEventHandler { [weak self] in
      self?.flushBuffer()
    }
    timer.resume()
    batchTimer = timer
  }

  /// Must be called on `queue`
  private func flushBuffer() {
    guard !buffer.isEmpty else { return }

    let entriesToFlush = buffer
    buffer.removeAll()
    var persisted = false

    let context = CoreDataStack.shared.newBackgroundContext()
    context.performAndWait {
      guard let entityDescription = NSEntityDescription.entity(forEntityName: "LocationEntity", in: context) else {
        NSLog("[BackgroundLocation] Failed to get LocationEntity description")
        return
      }

      for entry in entriesToFlush {
        let entity = NSManagedObject(entity: entityDescription, insertInto: context)
        entity.setValue(entry.id, forKey: "id")
        entity.setValue(entry.tripId, forKey: "tripId")
        entity.setValue(entry.latitude, forKey: "latitude")
        entity.setValue(entry.longitude, forKey: "longitude")
        entity.setValue(entry.timestamp, forKey: "timestamp")
        entity.setValue(entry.accuracy, forKey: "accuracy")
        entity.setValue(entry.altitude, forKey: "altitude")
        entity.setValue(entry.speed, forKey: "speed")
        entity.setValue(entry.bearing, forKey: "bearing")
        entity.setValue(entry.verticalAccuracyMeters, forKey: "verticalAccuracyMeters")
        entity.setValue(entry.speedAccuracyMetersPerSecond, forKey: "speedAccuracyMetersPerSecond")
        entity.setValue(entry.bearingAccuracyDegrees, forKey: "bearingAccuracyDegrees")
        entity.setValue(entry.provider, forKey: "provider")

        if let isMock = entry.isFromMockProvider {
          entity.setValue(NSNumber(value: isMock), forKey: "isFromMockProvider")
        }

        // Sync bookkeeping (mirrors Android): new rows start PENDING with a fresh clientId.
        // clientId is kept locally for bookkeeping only — it is NOT sent on the wire.
        entity.setValue(SyncState.pending, forKey: "syncState")
        entity.setValue(Int32(0), forKey: "attempts")
        entity.setValue(UUID().uuidString, forKey: "clientId")
      }

      do {
        try context.save()
        persisted = true
      } catch {
        NSLog("[BackgroundLocation] Failed to flush location buffer: \(error)")
      }
    }

    // Opportunistic sync trigger after new rows land (guarded by the sync layer's own backoff /
    // network checks). Outside the Core Data block so a trigger exception can't affect the save.
    if persisted {
      onLocationsPersisted?()
    }
  }

  private func entityToDict(_ entity: NSManagedObject) -> [String: Any] {
    var dict: [String: Any] = [:]

    dict["latitude"] = entity.value(forKey: "latitude") as? String ?? "0"
    dict["longitude"] = entity.value(forKey: "longitude") as? String ?? "0"
    dict["timestamp"] = entity.value(forKey: "timestamp") as? Double ?? 0

    let accuracy = entity.value(forKey: "accuracy") as? Double ?? -1
    if accuracy >= 0 {
      dict["accuracy"] = accuracy
    }

    dict["altitude"] = entity.value(forKey: "altitude") as? Double ?? 0

    let speed = entity.value(forKey: "speed") as? Double ?? -1
    if speed >= 0 {
      dict["speed"] = speed
    }

    let bearing = entity.value(forKey: "bearing") as? Double ?? -1
    if bearing >= 0 {
      dict["bearing"] = bearing
    }

    let verticalAccuracy = entity.value(forKey: "verticalAccuracyMeters") as? Double ?? -1
    if verticalAccuracy >= 0 {
      dict["verticalAccuracyMeters"] = verticalAccuracy
    }

    let speedAccuracy = entity.value(forKey: "speedAccuracyMetersPerSecond") as? Double ?? -1
    if speedAccuracy >= 0 {
      dict["speedAccuracyMetersPerSecond"] = speedAccuracy
    }

    let bearingAccuracy = entity.value(forKey: "bearingAccuracyDegrees") as? Double ?? -1
    if bearingAccuracy >= 0 {
      dict["bearingAccuracyDegrees"] = bearingAccuracy
    }

    if let isMock = entity.value(forKey: "isFromMockProvider") as? Bool {
      dict["isFromMockProvider"] = isMock
      dict["provider"] = isMock ? "simulated" : "gps"
    } else {
      dict["provider"] = entity.value(forKey: "provider") as? String ?? "gps"
    }

    return dict
  }
}

// MARK: - Buffer Entry

private struct LocationBufferEntry {
  let id: Int64
  let tripId: String
  let latitude: String
  let longitude: String
  let timestamp: Double
  let accuracy: Double
  let altitude: Double
  let speed: Double
  let bearing: Double
  let verticalAccuracyMeters: Double
  let speedAccuracyMetersPerSecond: Double
  let bearingAccuracyDegrees: Double
  let provider: String?
  let isFromMockProvider: Bool?
}

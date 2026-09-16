import Foundation
import BackgroundTasks

/// Thin wrapper over `BGTaskScheduler` for the sync safety-net task (mirrors Android's 15-minute
/// WorkManager job). iOS requires the handler to be registered before the app finishes launching,
/// so `register` is expected to be called from `didFinishLaunchingWithOptions`.
enum BackgroundTaskScheduler {

  static func register(identifier: String, handler: @escaping (BGTask) -> Void) {
    BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
      handler(task)
    }
  }

  static func schedule(identifier: String, earliestAfter seconds: TimeInterval) {
    let request = BGAppRefreshTaskRequest(identifier: identifier)
    request.earliestBeginDate = Date(timeIntervalSinceNow: seconds)
    do {
      try BGTaskScheduler.shared.submit(request)
    } catch {
      // Common in the simulator / when Background App Refresh is disabled — non-fatal for the POC.
      NSLog("[BackgroundLocation] Could not schedule background sync task: \(error)")
    }
  }
}

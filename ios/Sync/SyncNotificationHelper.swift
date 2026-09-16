import Foundation
import UserNotifications

/// Posts the local "sync paused" notification when the server rejects the tokens with a 401
/// (mirrors Android's auth-block notification).
enum SyncNotificationHelper {
  private static let authBlockedId = "com.backgroundlocation.sync.auth-blocked"

  static func postAuthBlocked() {
    let center = UNUserNotificationCenter.current()
    let content = UNMutableNotificationContent()
    content.title = "Tracking sync paused"
    content.body = "Open the app to re-authenticate."
    content.sound = nil
    // Immediate delivery; if notification permission was never granted this is a silent no-op,
    // which is fine — the app's in-app auth-blocked banner still surfaces the state.
    let request = UNNotificationRequest(identifier: authBlockedId, content: content, trigger: nil)
    center.add(request, withCompletionHandler: nil)
  }
}

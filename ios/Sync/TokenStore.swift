import Foundation

/// Persists the two bulk-upload auth credentials in a plain `UserDefaults` suite namespaced to
/// this fork: the `x-authorization` bearer token and the `x-sso-token`.
///
/// Deliberately NOT the Keychain: the sync layer must read these natively while JS is dead
/// (background / terminated uploads), and the integrating app already keeps the same tokens in
/// plain storage (AsyncStorage). Plain `UserDefaults` is native-readable with no Keychain
/// dependency. The app pushes tokens in via `setAuthToken`/`setSsoToken`; nothing is read back
/// from JS at upload time.
@objc public class TokenStore: NSObject {

  @objc public static let shared = TokenStore()

  private let defaults: UserDefaults

  private override init() {
    self.defaults = UserDefaults(suiteName: TokenStore.suiteName) ?? .standard
    super.init()
  }

  /// The `x-authorization` bearer token (sent as `Bearer <token>`).
  var authToken: String? {
    get { defaults.string(forKey: Keys.auth) }
    set { write(Keys.auth, newValue) }
  }

  /// The `x-sso-token` value (raw JWT, sent without a `Bearer` prefix).
  var ssoToken: String? {
    get { defaults.string(forKey: Keys.sso) }
    set { write(Keys.sso, newValue) }
  }

  private func write(_ key: String, _ value: String?) {
    if let value = value, !value.isEmpty {
      defaults.set(value, forKey: key)
    } else {
      defaults.removeObject(forKey: key)
    }
  }

  private static let suiteName = "com.backgroundlocation.sync.token"

  private enum Keys {
    static let auth = "auth_token"
    static let sso = "sso_token"
  }
}

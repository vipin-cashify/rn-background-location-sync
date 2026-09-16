package com.backgroundlocation.sync

/**
 * Persists the bearer token used to authenticate bulk-upload requests.
 *
 * An interface (rather than a concrete class directly) so tests can substitute an in-memory
 * fake ([FakeTokenStore]) instead of the real [PrefsTokenStore]. Tokens are pushed in from JS
 * via the module's `setAuthToken`/`setSsoToken`; the sync layer reads them natively at upload
 * time (including while JS is dead), so they live in a plain native prefs file, not the Keychain.
 */
interface TokenStore {
    /** The `x-authorization` bearer token (client/role token). */
    fun getToken(): String?
    fun setToken(token: String?)

    /** The `x-sso-token` value (raw user SSO JWT, sent without a "Bearer" prefix). */
    fun getSsoToken(): String?
    fun setSsoToken(token: String?)
}

package com.backgroundlocation.sync

/**
 * In-memory [TokenStore] fake for tests. Real production token storage is
 * [EncryptedTokenStore] (androidx.security-crypto's `EncryptedSharedPreferences`, backed by the
 * Android Keystore) - see `EncryptedTokenStoreTest` for the real implementation's own
 * construction/round-trip coverage under Robolectric. `LocationSyncManagerTest` uses this fake
 * instead, since what it exercises is `LocationSyncManager`'s upload/backoff/auth-block logic,
 * not `TokenStore`'s persistence mechanism.
 */
class FakeTokenStore(
    initialToken: String? = null,
    initialSsoToken: String? = null,
) : TokenStore {
    private var token: String? = initialToken
    private var ssoToken: String? = initialSsoToken

    override fun getToken(): String? = token

    override fun setToken(token: String?) {
        this.token = token
    }

    override fun getSsoToken(): String? = ssoToken

    override fun setSsoToken(token: String?) {
        this.ssoToken = token
    }
}

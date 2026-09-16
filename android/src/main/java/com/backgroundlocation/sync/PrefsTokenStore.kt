package com.backgroundlocation.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * [TokenStore] backed by a plain [SharedPreferences] file namespaced to this fork.
 *
 * Deliberately NOT encrypted: the sync layer must read the tokens natively while JS is dead
 * (background / terminated uploads), and the integrating app already keeps these same tokens in
 * plain storage (AsyncStorage). Plain prefs are native-readable with no Keychain/Keystore
 * dependency. The app pushes tokens in via `setAuthToken`/`setSsoToken`; nothing is read back
 * from JS at upload time.
 */
class PrefsTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    override fun setToken(token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    override fun getSsoToken(): String? = prefs.getString(KEY_SSO_TOKEN, null)

    override fun setSsoToken(token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(KEY_SSO_TOKEN) else putString(KEY_SSO_TOKEN, token)
        }.apply()
    }

    companion object {
        private const val PREFS_FILE = "cashify_location_sync_token"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_SSO_TOKEN = "sso_token"
    }
}

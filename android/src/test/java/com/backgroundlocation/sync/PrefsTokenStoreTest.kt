package com.backgroundlocation.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real [PrefsTokenStore] (plain [android.content.SharedPreferences]). Unlike the
 * previous EncryptedSharedPreferences implementation, this round-trips cleanly under Robolectric
 * (no AndroidKeyStore needed), so we can assert real persistence semantics here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PrefsTokenStoreTest {

    private fun newStore(): PrefsTokenStore =
        PrefsTokenStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `auth and sso tokens round-trip independently`() {
        val store = newStore()
        store.setToken("auth-1")
        store.setSsoToken("sso-1")
        assertEquals("auth-1", store.getToken())
        assertEquals("sso-1", store.getSsoToken())
    }

    @Test
    fun `values persist across instances (same prefs file)`() {
        newStore().setToken("persisted-auth")
        newStore().setSsoToken("persisted-sso")
        val fresh = newStore()
        assertEquals("persisted-auth", fresh.getToken())
        assertEquals("persisted-sso", fresh.getSsoToken())
    }

    @Test
    fun `blank or null clears the stored value`() {
        val store = newStore()
        store.setToken("auth-1"); store.setSsoToken("sso-1")
        store.setToken(""); store.setSsoToken(null)
        assertNull(store.getToken())
        assertNull(store.getSsoToken())
    }
}

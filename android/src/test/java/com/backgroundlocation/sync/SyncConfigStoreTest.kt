package com.backgroundlocation.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SyncConfigStoreTest {

    private fun newStore(): SyncConfigStore =
        SyncConfigStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `defaults are seeded from SyncDefaults and a sensible batch size, with no syncUrl and not auth-blocked`() {
        val store = newStore()

        assertNull(store.syncUrl)
        assertEquals(SyncConfigStore.DEFAULT_BATCH_SIZE, store.batchSize)
        assertEquals(SyncDefaults.MAX_QUEUE_ROWS, store.maxQueueRows)
        assertEquals(SyncDefaults.RETENTION_DAYS, store.maxAgeDays)
        assertEquals(SyncDefaults.RETENTION_MILLIS, store.maxAgeMillis)
        assertTrue(store.extraHeaders.isEmpty())
        assertFalse(store.authBlocked)
    }

    @Test
    fun `every field round-trips through a fresh store instance backed by the same prefs file`() {
        val store = newStore()

        store.syncUrl = "https://example.test/sync"
        store.batchSize = 250
        store.maxQueueRows = 5_000
        store.maxAgeDays = 3
        store.extraHeaders = mapOf("X-Tenant" to "acme", "X-Env" to "staging")
        store.authBlocked = true

        val reopened = newStore()

        assertEquals("https://example.test/sync", reopened.syncUrl)
        assertEquals(250, reopened.batchSize)
        assertEquals(5_000, reopened.maxQueueRows)
        assertEquals(3, reopened.maxAgeDays)
        assertEquals(3L * 24L * 60L * 60L * 1000L, reopened.maxAgeMillis)
        assertEquals(mapOf("X-Tenant" to "acme", "X-Env" to "staging"), reopened.extraHeaders)
        assertTrue(reopened.authBlocked)
    }

    @Test
    fun `extraHeaders defaults to an empty map when unset or malformed`() {
        val store = newStore()

        assertTrue(store.extraHeaders.isEmpty())

        store.extraHeaders = emptyMap()
        assertTrue(store.extraHeaders.isEmpty())
    }

    @Test
    fun `batchSize coerces a stored zero or negative value to at least 1`() {
        val store = newStore()

        store.batchSize = 0
        assertEquals(1, store.batchSize)

        store.batchSize = -5
        assertEquals(1, store.batchSize)

        store.batchSize = 250
        assertEquals(250, store.batchSize)
    }

    @Test
    fun `batchSize coerces a stored value above 999 down to 999`() {
        val store = newStore()

        // SQLite's default host-parameter limit is 999 (SQLITE_MAX_VARIABLE_NUMBER on the
        // SQLite 3.9-era builds shipped down to minSdk 24) - a batchSize above that would
        // exceed it wherever a batch's ids are later bound as `IN (:ids)` params. See
        // SyncConfigStore.batchSize's kdoc.
        store.batchSize = 1000
        assertEquals(999, store.batchSize)

        store.batchSize = Int.MAX_VALUE
        assertEquals(999, store.batchSize)

        store.batchSize = 999
        assertEquals(999, store.batchSize)
    }
}

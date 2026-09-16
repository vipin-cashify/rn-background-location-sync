package com.backgroundlocation.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real migration coverage for [Migrations.MIGRATION_1_2]: creates a v1
 * database (matching the exported schema at
 * `android/schemas/com.backgroundlocation.database.LocationDatabase/1.json`),
 * seeds a v1-shaped row, runs the migration, and validates the result
 * against the exported v2 schema (via `runMigrationsAndValidate`, which
 * fails if the migrated schema doesn't match what `LocationEntity`/
 * `LocationDatabase` declare - this is what keeps the
 * `@ColumnInfo(defaultValue = ...)` annotations honest).
 *
 * Runs under Robolectric (real SQLite, no destructive fallback) rather
 * than being skipped: `MigrationTestHelper` + Robolectric is a supported,
 * documented combination (Robolectric provides the `Instrumentation` used
 * by the helper and a real native SQLite implementation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocationDatabaseMigrationTest {

    private val testDbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LocationDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migrate1To2 adds sync columns with correct defaults, backfills clientId, and creates the syncState index`() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO locations (tripId, latitude, longitude, timestamp) " +
                    "VALUES ('trip-1', 12.5, 77.5, 1000)"
            )
            execSQL(
                "INSERT INTO locations (tripId, latitude, longitude, timestamp) " +
                    "VALUES ('trip-1', 12.6, 77.6, 2000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 2, true, Migrations.MIGRATION_1_2)

        migrated.query("SELECT id, syncState, attempts, lastAttemptAt, clientId FROM locations ORDER BY timestamp ASC")
            .use { cursor ->
                val clientIds = mutableSetOf<String>()
                var rowCount = 0
                while (cursor.moveToNext()) {
                    rowCount++
                    assertEquals("PENDING", cursor.getString(cursor.getColumnIndexOrThrow("syncState")))
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("attempts")))
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("lastAttemptAt")))
                    val clientId = cursor.getString(cursor.getColumnIndexOrThrow("clientId"))
                    assertTrue("backfilled clientId should be non-blank", clientId.isNotBlank())
                    clientIds.add(clientId)
                }
                assertEquals(2, rowCount)
                assertEquals("backfilled clientIds should be unique per row", 2, clientIds.size)
            }

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_locations_syncState'"
        ).use { cursor ->
            assertTrue("expected index_locations_syncState to exist after migration", cursor.moveToFirst())
        }
    }

    @Test
    fun `migrate1To2 leaves pre-existing v1 columns untouched`() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO locations (tripId, latitude, longitude, timestamp, accuracy, provider) " +
                    "VALUES ('trip-2', 1.0, 2.0, 4242, 3.5, 'gps')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 2, true, Migrations.MIGRATION_1_2)

        migrated.query(
            "SELECT tripId, latitude, longitude, timestamp, accuracy, provider FROM locations WHERE tripId = 'trip-2'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("trip-2", cursor.getString(cursor.getColumnIndexOrThrow("tripId")))
            assertEquals(1.0, cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")), 0.0001)
            assertEquals(2.0, cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")), 0.0001)
            assertEquals(4242L, cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
            assertEquals(3.5, cursor.getDouble(cursor.getColumnIndexOrThrow("accuracy")), 0.0001)
            assertEquals("gps", cursor.getString(cursor.getColumnIndexOrThrow("provider")))
        }
    }
}

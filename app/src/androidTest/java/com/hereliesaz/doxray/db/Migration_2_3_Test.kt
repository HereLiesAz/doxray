package com.hereliesaz.doxray.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_2_3_Test {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate2To3_preservesIdentityRecords_addsNewTables() {
        // Create v2 DB with one row
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL("""
                INSERT INTO identity_records
                    (faceId, primaryIdentity, embedding, socialLinks, backgroundData,
                     firstSeenTimestamp, lastSeenTimestamp, encounterCount)
                VALUES ('face-1', 'Test User', X'00', '', '{}', 1000, 2000, 1)
            """.trimIndent())
            close()
        }

        // Apply the migration and re-open with the production schema
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migration_2_3)

        // Existing row survives
        migratedDb.query("SELECT faceId, primaryIdentity FROM identity_records").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("face-1", c.getString(0))
            assertEquals("Test User", c.getString(1))
        }

        // New tables exist and are empty
        migratedDb.query("SELECT COUNT(*) FROM encounters").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM audit_events").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }

        migratedDb.close()
    }
}

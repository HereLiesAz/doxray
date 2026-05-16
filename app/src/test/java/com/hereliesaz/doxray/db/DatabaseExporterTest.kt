package com.hereliesaz.doxray.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DatabaseExporterTest {

    private lateinit var db: AppDatabase
    private lateinit var exporter: DatabaseExporter

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        exporter = DatabaseExporter(db.identityDao(), db.encounterDao(), db.auditDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `export produces zip with three csvs and manifest`() = runBlocking {
        val emb = FloatArray(4) { i -> 0.1f * (i + 1) }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "f1", primaryIdentity = "Alice", embedding = emb,
            socialLinks = "https://x.com/alice", backgroundData = "{\"k\":1}",
            firstSeenTimestamp = 1000L, lastSeenTimestamp = 2000L, encounterCount = 2,
            visibleText = "ACME Corp",
        ))
        db.encounterDao().insert(Encounter(faceId = "f1", timestamp = 1000L,
            latitude = 37.7, longitude = -122.4, locationAccuracyMeters = 5f))

        val out = ByteArrayOutputStream()
        exporter.export(out)

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        assertNotNull(entries["identities.csv"])
        assertNotNull(entries["encounters.csv"])
        assertNotNull(entries["audit.csv"])
        assertNotNull(entries["manifest.json"])

        val identities = entries["identities.csv"]!!
        assertTrue("identities CSV missing header", identities.startsWith("faceId,primaryIdentity,"))
        assertTrue("identities CSV missing Alice row", identities.contains("Alice"))
        assertTrue("identities CSV missing ACME Corp", identities.contains("ACME Corp"))

        val manifest = JSONObject(entries["manifest.json"]!!)
        assertEquals(4, manifest.getInt("schemaVersion"))
        assertEquals(1, manifest.getInt("identityCount"))
    }

    @Test
    fun `embedding floats survive round-trip`() = runBlocking {
        val emb = FloatArray(8) { i -> 0.123f * (i + 1) }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "f1", primaryIdentity = "Bob", embedding = emb,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = 0L, lastSeenTimestamp = 0L, encounterCount = 1,
        ))

        val out = ByteArrayOutputStream()
        exporter.export(out)

        var csv: String? = null
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "identities.csv") {
                    csv = zis.readBytes().toString(Charsets.UTF_8)
                    break
                }
                e = zis.nextEntry
            }
        }
        assertNotNull(csv)
        val embString = emb.joinToString(",")
        assertTrue("CSV missing embedding cell, got: $csv",
            csv!!.contains("\"$embString\""))
    }
}

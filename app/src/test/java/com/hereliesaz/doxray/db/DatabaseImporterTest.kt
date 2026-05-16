package com.hereliesaz.doxray.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DatabaseImporterTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: DatabaseImporter
    private lateinit var exporter: DatabaseExporter

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        importer = DatabaseImporter(db.identityDao(), db.encounterDao(), db.auditDao())
        exporter = DatabaseExporter(db.identityDao(), db.encounterDao(), db.auditDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `round-trip preserves identity count`() = runBlocking {
        val emb = FloatArray(4) { 0.5f }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "f1", primaryIdentity = "Alice", embedding = emb,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = 100L, lastSeenTimestamp = 200L, encounterCount = 1,
        ))
        val baos = ByteArrayOutputStream()
        exporter.export(baos)

        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val fresh = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val freshImporter = DatabaseImporter(fresh.identityDao(), fresh.encounterDao(), fresh.auditDao())
        val report = freshImporter.import(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, report.identitiesImported)
        assertEquals(0, report.identitiesMalformed)
        val all = fresh.identityDao().getAllIdentities()
        assertEquals(1, all.size)
        assertEquals("Alice", all[0].primaryIdentity)
        fresh.close()
    }

    @Test
    fun `conflict on faceId skips imported identity`() = runBlocking {
        val emb = FloatArray(4) { 0.5f }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "shared", primaryIdentity = "Local-Alice", embedding = emb,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = 100L, lastSeenTimestamp = 200L, encounterCount = 5,
        ))

        val zipBytes = buildZip(
            identitiesCsv = "faceId,primaryIdentity,embedding,socialLinks,backgroundData,visibleText,firstSeenTimestamp,lastSeenTimestamp,encounterCount\n" +
                "\"shared\",\"Import-Alice\",\"" + emb.joinToString(",") + "\",\"\",\"{}\",\"\",\"50\",\"60\",\"1\"\n",
            manifestVersion = 4,
        )
        val report = importer.import(ByteArrayInputStream(zipBytes))
        assertEquals(0, report.identitiesImported)
        assertEquals(1, report.identitiesSkipped)

        val survivor = db.identityDao().getIdentityById("shared")
        assertEquals("Local-Alice", survivor?.primaryIdentity)
        assertEquals(5, survivor?.encounterCount)
    }

    @Test
    fun `manifest schemaVersion newer than current throws`() {
        val zipBytes = buildZip(identitiesCsv = "faceId,primaryIdentity\n", manifestVersion = 99)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { importer.import(ByteArrayInputStream(zipBytes)) }
        }
    }

    @Test
    fun `malformed row counted, valid row imported`() = runBlocking {
        val emb = FloatArray(4) { 0.5f }
        val csv = "faceId,primaryIdentity,embedding,socialLinks,backgroundData,visibleText,firstSeenTimestamp,lastSeenTimestamp,encounterCount\n" +
            "\"f1\",\"Ok\",\"" + emb.joinToString(",") + "\",\"\",\"{}\",\"\",\"1\",\"2\",\"1\"\n" +
            "\"f2\",\"Bad\",\"not,floats,abc\",\"\",\"{}\",\"\",\"1\",\"2\",\"1\"\n"
        val zipBytes = buildZip(identitiesCsv = csv, manifestVersion = 4)
        val report = importer.import(ByteArrayInputStream(zipBytes))
        assertEquals(1, report.identitiesImported)
        assertEquals(1, report.identitiesMalformed)
        assertNotNull(db.identityDao().getIdentityById("f1"))
    }

    private fun buildZip(identitiesCsv: String, manifestVersion: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("identities.csv"))
            OutputStreamWriter(zip).let { it.write(identitiesCsv); it.flush() }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("encounters.csv"))
            OutputStreamWriter(zip).let { it.write("id,faceId,timestamp,latitude,longitude,locationAccuracyMeters\n"); it.flush() }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("audit.csv"))
            OutputStreamWriter(zip).let { it.write("id,timestamp,type,summary,detailsJson\n"); it.flush() }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            OutputStreamWriter(zip).let { it.write("{\"schemaVersion\":$manifestVersion,\"identityCount\":0}"); it.flush() }
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}

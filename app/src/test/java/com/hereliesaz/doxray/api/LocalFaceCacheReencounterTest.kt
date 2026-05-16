package com.hereliesaz.doxray.api

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.db.IdentityRecord
import com.hereliesaz.doxray.location.LocationService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LocalFaceCacheReencounterTest {

    private lateinit var db: AppDatabase
    private lateinit var cache: LocalFaceCache

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        AuditLogger.init(db.auditDao())
        cache = LocalFaceCache(
            identityDao = db.identityDao(),
            encounterDao = db.encounterDao(),
            locationService = LocationService(ctx),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `findMatch with stale lastSeen emits REENCOUNTER audit`() = runBlocking {
        val embedding = FloatArray(128) { i -> if (i == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        val oldLastSeen = now - 2 * 60 * 60 * 1000L
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "stale", primaryIdentity = "Stale Subject", embedding = embedding,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = oldLastSeen, lastSeenTimestamp = oldLastSeen, encounterCount = 1,
        ))
        cache.loadFromDatabase()

        val match = cache.findMatch(embedding)
        assertEquals("stale", match?.faceId)

        Thread.sleep(200)
        val events = db.auditDao().observeRecent(limit = 50).first()
        val types = events.map { it.type }
        assertTrue("Expected REENCOUNTER audit, got $types", types.contains("REENCOUNTER"))
    }

    @Test
    fun `findMatch with recent lastSeen emits IDENTIFY audit not REENCOUNTER`() = runBlocking {
        val embedding = FloatArray(128) { i -> if (i == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "fresh", primaryIdentity = "Fresh Subject", embedding = embedding,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = now - 5_000, lastSeenTimestamp = now - 5_000, encounterCount = 1,
        ))
        cache.loadFromDatabase()

        val match = cache.findMatch(embedding)
        assertEquals("fresh", match?.faceId)

        Thread.sleep(200)
        val events = db.auditDao().observeRecent(limit = 50).first()
        val types = events.map { it.type }
        assertTrue("Expected IDENTIFY but not REENCOUNTER, got $types",
            types.contains("IDENTIFY") && !types.contains("REENCOUNTER"))
    }
}

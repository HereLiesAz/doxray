package com.hereliesaz.doxray.api

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.db.IdentityRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AnchorImageRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AnchorImageRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = AnchorImageRepository(db.anchorImageDao())
        runBlocking {
            db.identityDao().insertIdentity(IdentityRecord(
                faceId = "f1", primaryIdentity = "Alice",
                embedding = FloatArray(4) { 0f },
                socialLinks = "", backgroundData = "{}",
                firstSeenTimestamp = 0L, lastSeenTimestamp = 0L, encounterCount = 1,
            ))
        }
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `first call inserts the anchor`() = runBlocking {
        repo.upsert("f1", ByteArray(8) { 0x42 }, qualityScore = 0.5f)
        val saved = repo.get("f1")
        assertNotNull(saved)
        assertEquals(0.5f, saved!!.qualityScore, 0.0001f)
        assertEquals(8, saved.imageBytes.size)
    }

    @Test
    fun `higher-score call updates existing anchor`() = runBlocking {
        repo.upsert("f1", ByteArray(8) { 0x11 }, qualityScore = 0.3f)
        repo.upsert("f1", ByteArray(8) { 0x22 }, qualityScore = 0.7f)
        val saved = repo.get("f1")
        assertNotNull(saved)
        assertEquals(0.7f, saved!!.qualityScore, 0.0001f)
        assertEquals(0x22.toByte(), saved.imageBytes[0])
    }

    @Test
    fun `lower-score call keeps existing anchor`() = runBlocking {
        repo.upsert("f1", ByteArray(8) { 0x11 }, qualityScore = 0.7f)
        repo.upsert("f1", ByteArray(8) { 0x99.toByte() }, qualityScore = 0.2f)
        val saved = repo.get("f1")
        assertNotNull(saved)
        assertEquals(0.7f, saved!!.qualityScore, 0.0001f)
        assertEquals(0x11.toByte(), saved.imageBytes[0])
    }
}

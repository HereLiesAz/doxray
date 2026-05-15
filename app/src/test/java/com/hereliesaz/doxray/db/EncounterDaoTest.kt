package com.hereliesaz.doxray.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EncounterDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var encounterDao: EncounterDao
    private lateinit var identityDao: IdentityDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        encounterDao = db.encounterDao()
        identityDao = db.identityDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `inserts and observes encounters newest-first`() = runBlocking {
        identityDao.insertIdentity(IdentityRecord(
            faceId = "face-1", primaryIdentity = "Test User", embedding = FloatArray(192),
            socialLinks = "", backgroundData = "{}", firstSeenTimestamp = 1000L,
            lastSeenTimestamp = 3000L, encounterCount = 2,
        ))
        encounterDao.insert(Encounter(faceId = "face-1", timestamp = 1000L,
            latitude = 37.0, longitude = -122.0, locationAccuracyMeters = 5f))
        encounterDao.insert(Encounter(faceId = "face-1", timestamp = 3000L,
            latitude = null, longitude = null, locationAccuracyMeters = null))

        val list = encounterDao.observeByFace("face-1").first()
        assertEquals(2, list.size)
        assertEquals(3000L, list[0].timestamp)
        assertEquals(1000L, list[1].timestamp)
        assertEquals(37.0, list[1].latitude!!, 0.0001)
    }
}

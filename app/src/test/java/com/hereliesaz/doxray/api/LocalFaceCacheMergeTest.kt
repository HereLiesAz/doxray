package com.hereliesaz.doxray.api

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.location.LocationService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalFaceCacheMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var cache: LocalFaceCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Real LocationService with no permission granted in Robolectric —
        // its hasAnyLocationPermission() returns false so getLastLocation()
        // returns null without throwing. No subclass needed.
        cache = LocalFaceCache(
            identityDao = db.identityDao(),
            encounterDao = db.encounterDao(),
            locationService = LocationService(context),
        )
    }

    @After
    fun tearDown() { db.close() }

    /** Builds a 192-d unit vector along axis [axis]. Two such vectors with
     *  the same axis have cosine similarity 1.0; with different axes, 0. */
    private fun oneHot(axis: Int): FloatArray = FloatArray(192).also { it[axis] = 1f }

    /** Slight perturbation: nearly the same axis but with a small leak.
     *  Yields cosine similarity > 0.92. */
    private fun nearOneHot(axis: Int): FloatArray = FloatArray(192).also {
        it[axis] = 0.99f
        it[(axis + 1) % 192] = 0.14f
    }

    @Test
    fun `near-duplicate merges into existing dossier`() = runBlocking {
        cache.cacheIdentity(
            faceId = "face-original",
            embedding = oneHot(7),
            primaryIdentity = "Alice",
            socialLinks = listOf(),
            backgroundData = "{}",
        )

        // Reload so memoryCache contains the just-cached record
        cache.loadFromDatabase()

        cache.cacheIdentity(
            faceId = "face-duplicate",
            embedding = nearOneHot(7),
            primaryIdentity = "Alicia",
            socialLinks = listOf(),
            backgroundData = "{}",
        )

        val all = db.identityDao().getAllIdentities()
        assertEquals("merge should keep only one dossier", 1, all.size)
        assertEquals("face-original", all[0].faceId)
        assertEquals(2, all[0].encounterCount)
    }

    @Test
    fun `clearly-different embedding inserts new dossier`() = runBlocking {
        cache.cacheIdentity(
            faceId = "face-1",
            embedding = oneHot(7),
            primaryIdentity = "Alice",
            socialLinks = listOf(),
            backgroundData = "{}",
        )
        cache.loadFromDatabase()

        cache.cacheIdentity(
            faceId = "face-2",
            embedding = oneHot(100), // orthogonal
            primaryIdentity = "Bob",
            socialLinks = listOf(),
            backgroundData = "{}",
        )

        val all = db.identityDao().getAllIdentities()
        assertEquals(2, all.size)
        assertNotEquals(all[0].faceId, all[1].faceId)
    }
}

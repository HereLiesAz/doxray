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
class AuditDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var auditDao: AuditDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        auditDao = db.auditDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `observeRecent returns newest first, respects limit`() = runBlocking {
        auditDao.insert(AuditEvent(timestamp = 100L, type = "LIFECYCLE", summary = "start", detailsJson = "{}"))
        auditDao.insert(AuditEvent(timestamp = 200L, type = "API_CALL", summary = "x", detailsJson = "{}"))
        auditDao.insert(AuditEvent(timestamp = 300L, type = "IDENTIFY", summary = "match", detailsJson = "{}"))

        val all = auditDao.observeRecent(limit = 10).first()
        assertEquals(3, all.size)
        assertEquals(300L, all[0].timestamp)
        assertEquals(100L, all[2].timestamp)

        val capped = auditDao.observeRecent(limit = 2).first()
        assertEquals(2, capped.size)
        assertEquals(300L, capped[0].timestamp)
    }
}

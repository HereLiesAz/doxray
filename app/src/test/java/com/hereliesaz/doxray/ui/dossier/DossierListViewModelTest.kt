package com.hereliesaz.doxray.ui.dossier

import com.hereliesaz.doxray.db.IdentityDao
import com.hereliesaz.doxray.db.IdentityRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DossierListViewModelTest {

    private fun record(id: String, name: String, lastSeen: Long, count: Int) = IdentityRecord(
        faceId = id, primaryIdentity = name, embedding = FloatArray(192),
        socialLinks = "", backgroundData = "{}",
        firstSeenTimestamp = 0L, lastSeenTimestamp = lastSeen, encounterCount = count,
    )

    private class FakeIdentityDao(records: List<IdentityRecord>) : IdentityDao {
        private val flow = MutableStateFlow(records)
        override suspend fun getAllIdentities(): List<IdentityRecord> = flow.value
        override suspend fun getIdentityById(faceId: String): IdentityRecord? = flow.value.firstOrNull { it.faceId == faceId }
        override suspend fun insertIdentity(record: IdentityRecord): Long = 0
        override suspend fun updateIdentity(record: IdentityRecord): Int = 0
        override suspend fun recordEncounter(faceId: String, timestamp: Long): Int = 0
        override fun observeAll(): Flow<List<IdentityRecord>> = flow
        override suspend fun delete(faceId: String): Int = 0
    }

    @Test
    fun `maps identity records to dossier rows`() = runTest {
        val dao = FakeIdentityDao(listOf(
            record("a", "Alice", 2000L, 3),
            record("b", "Bob", 1000L, 1),
        ))
        val vm = DossierListViewModel(dao)
        val rows = vm.state.first { it.rows.size == 2 }.rows
        assertEquals(2, rows.size)
        assertEquals("Alice", rows[0].name)
        assertEquals(3, rows[0].encounterCount)
        assertEquals(2000L, rows[0].lastSeenMillis)
    }
}

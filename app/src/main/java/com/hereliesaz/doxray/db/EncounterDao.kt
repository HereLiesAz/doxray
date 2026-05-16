package com.hereliesaz.doxray.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(encounter: Encounter): Long

    @Query("SELECT * FROM encounters WHERE faceId = :faceId ORDER BY timestamp DESC")
    fun observeByFace(faceId: String): Flow<List<Encounter>>

    @Query("SELECT * FROM encounters WHERE faceId = :faceId ORDER BY timestamp ASC")
    suspend fun getAllByFace(faceId: String): List<Encounter>
}

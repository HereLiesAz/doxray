package com.hereliesaz.doxray.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface IdentityDao {
    
    @Query("SELECT * FROM identity_records")
    suspend fun getAllIdentities(): List<IdentityRecord>

    @Query("SELECT * FROM identity_records WHERE faceId = :faceId LIMIT 1")
    suspend fun getIdentityById(faceId: String): IdentityRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentity(record: IdentityRecord): Long

    @Update
    suspend fun updateIdentity(record: IdentityRecord): Int
    
    @Query("UPDATE identity_records SET lastSeenTimestamp = :timestamp, encounterCount = encounterCount + 1 WHERE faceId = :faceId")
    suspend fun recordEncounter(faceId: String, timestamp: Long): Int
}

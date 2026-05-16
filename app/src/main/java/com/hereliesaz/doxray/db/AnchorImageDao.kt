package com.hereliesaz.doxray.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnchorImageDao {
    @Query("SELECT * FROM anchor_images WHERE faceId = :faceId LIMIT 1")
    suspend fun getByFaceId(faceId: String): AnchorImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(anchor: AnchorImage)
}

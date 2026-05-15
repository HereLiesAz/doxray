package com.hereliesaz.doxray.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(event: AuditEvent): Long

    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AuditEvent>>
}

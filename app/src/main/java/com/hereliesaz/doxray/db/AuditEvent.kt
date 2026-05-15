package com.hereliesaz.doxray.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable record of one notable runtime event. The free-form [detailsJson]
 * carries type-specific payload; the [type] field discriminates.
 */
@Entity(tableName = "audit_events", indices = [Index("timestamp")])
data class AuditEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val summary: String,
    val detailsJson: String,
)

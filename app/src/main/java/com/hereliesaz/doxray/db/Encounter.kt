package com.hereliesaz.doxray.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per face capture that produced a match (cached or new). Linked back
 * to the [IdentityRecord] via faceId. CASCADE delete keeps the encounter
 * timeline in sync when a dossier is purged.
 */
@Entity(
    tableName = "encounters",
    foreignKeys = [ForeignKey(
        entity = IdentityRecord::class,
        parentColumns = ["faceId"],
        childColumns = ["faceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("faceId"), Index("timestamp")],
)
data class Encounter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val faceId: String,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
)

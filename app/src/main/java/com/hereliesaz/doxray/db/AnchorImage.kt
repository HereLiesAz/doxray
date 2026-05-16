package com.hereliesaz.doxray.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stored representative face crop for an identity. Best-quality wins:
 * [com.hereliesaz.doxray.api.AnchorImageRepository.upsert] only writes when
 * the new score exceeds the existing one.
 */
@Entity(
    tableName = "anchor_images",
    foreignKeys = [ForeignKey(
        entity = IdentityRecord::class,
        parentColumns = ["faceId"],
        childColumns = ["faceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("faceId")],
)
data class AnchorImage(
    @PrimaryKey val faceId: String,
    val imageBytes: ByteArray,
    val qualityScore: Float,
    val capturedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnchorImage
        if (faceId != other.faceId) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (qualityScore != other.qualityScore) return false
        if (capturedAt != other.capturedAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        result = 31 * result + qualityScore.hashCode()
        result = 31 * result + capturedAt.hashCode()
        return result
    }
}

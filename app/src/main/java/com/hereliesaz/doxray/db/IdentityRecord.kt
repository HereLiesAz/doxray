package com.hereliesaz.doxray.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity_records")
data class IdentityRecord(
    @PrimaryKey
    val faceId: String,
    val primaryIdentity: String,
    val embedding: FloatArray,
    val socialLinks: String, // Stored as a comma-separated string or JSON
    val backgroundData: String, // Stored as JSON (Phones, Addresses, Relatives, etc.)
    val firstSeenTimestamp: Long,
    val lastSeenTimestamp: Long,
    val encounterCount: Int
) {
    // Generated equals and hashCode to handle FloatArray properly
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdentityRecord

        if (faceId != other.faceId) return false
        if (primaryIdentity != other.primaryIdentity) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (socialLinks != other.socialLinks) return false
        if (backgroundData != other.backgroundData) return false
        if (firstSeenTimestamp != other.firstSeenTimestamp) return false
        if (lastSeenTimestamp != other.lastSeenTimestamp) return false
        if (encounterCount != other.encounterCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + primaryIdentity.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + socialLinks.hashCode()
        result = 31 * result + backgroundData.hashCode()
        result = 31 * result + firstSeenTimestamp.hashCode()
        result = 31 * result + lastSeenTimestamp.hashCode()
        result = 31 * result + encounterCount
        return result
    }
}

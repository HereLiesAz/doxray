package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.Encounter
import com.hereliesaz.doxray.db.EncounterDao
import com.hereliesaz.doxray.db.IdentityDao
import com.hereliesaz.doxray.db.IdentityRecord
import com.hereliesaz.doxray.location.LocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Manages the local cache of unique facial embeddings + per-encounter history.
 */
class LocalFaceCache(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val locationService: LocationService,
) {

    private val TAG = "LocalFaceCache"
    private val memoryCache = mutableListOf<IdentityRecord>()
    private val SIMILARITY_THRESHOLD = 0.85f
    private val CLUSTER_THRESHOLD = 0.92f

    suspend fun loadFromDatabase() = withContext(Dispatchers.IO) {
        val records = identityDao.getAllIdentities()
        memoryCache.clear()
        memoryCache.addAll(records)
        Log.d(TAG, "Loaded ${records.size} identities from local database.")
    }

    suspend fun findMatch(embedding: FloatArray): IdentityRecord? = withContext(Dispatchers.IO) {
        if (memoryCache.isEmpty()) return@withContext null

        var bestMatch: IdentityRecord? = null
        var highestSimilarity = 0f
        for (cached in memoryCache) {
            val similarity = calculateCosineSimilarity(embedding, cached.embedding)
            if (similarity > highestSimilarity && similarity >= SIMILARITY_THRESHOLD) {
                highestSimilarity = similarity
                bestMatch = cached
            }
        }

        if (bestMatch != null) {
            val currentTime = System.currentTimeMillis()
            identityDao.recordEncounter(bestMatch.faceId, currentTime)
            val elapsedMs = currentTime - bestMatch.lastSeenTimestamp
            val isReencounter = elapsedMs > 60 * 60 * 1000L // > 1 hour since last seen
            AuditLogger.log(
                if (isReencounter) AuditLogger.Type.REENCOUNTER else AuditLogger.Type.IDENTIFY,
                summary = if (isReencounter)
                    "Re-encountered ${bestMatch.primaryIdentity} (elapsed ${elapsedMs / 1000}s)"
                else
                    "Cache hit: ${bestMatch.primaryIdentity}",
                details = JSONObject().apply {
                    put("faceId", bestMatch.faceId)
                    put("similarity", highestSimilarity)
                    put("elapsedMs", elapsedMs)
                },
            )
            if (isReencounter) {
                Log.i(TAG, "Re-encountered ${bestMatch.primaryIdentity} (last seen ${elapsedMs / 1000}s ago)")
            }
            recordEncounter(bestMatch.faceId, currentTime)
            val index = memoryCache.indexOf(bestMatch)
            if (index != -1) {
                memoryCache[index] = bestMatch.copy(
                    lastSeenTimestamp = currentTime,
                    encounterCount = bestMatch.encounterCount + 1,
                )
            }
        }
        bestMatch
    }

    suspend fun findClusterMatch(embedding: FloatArray): IdentityRecord? = withContext(Dispatchers.IO) {
        if (memoryCache.isEmpty()) return@withContext null
        var bestMatch: IdentityRecord? = null
        var highestSimilarity = 0f
        for (cached in memoryCache) {
            val similarity = calculateCosineSimilarity(embedding, cached.embedding)
            if (similarity > highestSimilarity && similarity >= CLUSTER_THRESHOLD) {
                highestSimilarity = similarity
                bestMatch = cached
            }
        }
        bestMatch
    }

    suspend fun cacheIdentity(
        faceId: String,
        embedding: FloatArray,
        primaryIdentity: String,
        socialLinks: List<String>,
        backgroundData: String,
        visibleText: String? = null,
    ) = withContext(Dispatchers.IO) {
        // Re-id cluster check: if a near-duplicate dossier already exists,
        // record the encounter against it instead of spawning a new one.
        val cluster = findClusterMatch(embedding)
        if (cluster != null) {
            val now = System.currentTimeMillis()
            identityDao.recordEncounter(cluster.faceId, now)
            AuditLogger.log(
                AuditLogger.Type.IDENTIFY,
                summary = "Merged into existing dossier: ${cluster.primaryIdentity}",
                details = JSONObject().apply {
                    put("existingFaceId", cluster.faceId)
                    put("incomingFaceId", faceId)
                    put("primaryIdentity", primaryIdentity)
                },
            )
            recordEncounter(cluster.faceId, now)
            val index = memoryCache.indexOf(cluster)
            if (index != -1) {
                memoryCache[index] = cluster.copy(
                    lastSeenTimestamp = now,
                    encounterCount = cluster.encounterCount + 1,
                )
            }
            return@withContext
        }

        // Existing insert path — unchanged from before this task.
        Log.d(TAG, "Caching new identity: $primaryIdentity")
        val currentTime = System.currentTimeMillis()
        val record = IdentityRecord(
            faceId = faceId,
            primaryIdentity = primaryIdentity,
            embedding = embedding,
            socialLinks = socialLinks.joinToString(","),
            backgroundData = backgroundData,
            firstSeenTimestamp = currentTime,
            lastSeenTimestamp = currentTime,
            encounterCount = 1,
            visibleText = visibleText,
        )
        identityDao.insertIdentity(record)
        memoryCache.add(record)
        AuditLogger.log(
            AuditLogger.Type.IDENTIFY,
            summary = "New identity: $primaryIdentity",
            details = JSONObject().put("faceId", faceId),
        )
        recordEncounter(faceId, currentTime)
    }

    private suspend fun recordEncounter(faceId: String, timestamp: Long) {
        val location = locationService.getLastLocation()
        encounterDao.insert(Encounter(
            faceId = faceId,
            timestamp = timestamp,
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationAccuracyMeters = location?.accuracy,
        ))
    }

    private fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        return if (normA == 0f || normB == 0f) 0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}

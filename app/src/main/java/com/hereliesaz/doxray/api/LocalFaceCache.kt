package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.db.IdentityDao
import com.hereliesaz.doxray.db.IdentityRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Manages the local cache of unique facial embeddings for real-time identification.
 * Synchronizes with the persistent Room database.
 */
class LocalFaceCache(private val identityDao: IdentityDao) {

    private val TAG = "LocalFaceCache"
    
    // In-memory cache for fast real-time comparison
    private val memoryCache = mutableListOf<IdentityRecord>()

    // Cosine similarity threshold for considering two embeddings as the same person
    private val SIMILARITY_THRESHOLD = 0.85f

    /**
     * Loads the persistent database into memory.
     */
    suspend fun loadFromDatabase() = withContext(Dispatchers.IO) {
        val records = identityDao.getAllIdentities()
        memoryCache.clear()
        memoryCache.addAll(records)
        Log.d(TAG, "Loaded ${records.size} identities from local database into memory cache.")
    }

    /**
     * Searches the local cache for a matching embedding.
     */
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
            Log.d(TAG, "Local cache hit! Matched ${bestMatch.primaryIdentity} with similarity $highestSimilarity")
            // Record this encounter in the persistent database
            val currentTime = System.currentTimeMillis()
            identityDao.recordEncounter(bestMatch.faceId, currentTime)
            
            // Update in-memory cache
            val index = memoryCache.indexOf(bestMatch)
            if (index != -1) {
                memoryCache[index] = bestMatch.copy(
                    lastSeenTimestamp = currentTime, 
                    encounterCount = bestMatch.encounterCount + 1
                )
            }
        }
        return@withContext bestMatch
    }

    /**
     * Caches a new identity persistently.
     */
    suspend fun cacheIdentity(
        faceId: String, 
        embedding: FloatArray, 
        primaryIdentity: String, 
        socialLinks: List<String>,
        backgroundData: String
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Caching new identity persistently: $primaryIdentity")
        
        val currentTime = System.currentTimeMillis()
        val record = IdentityRecord(
            faceId = faceId,
            primaryIdentity = primaryIdentity,
            embedding = embedding,
            socialLinks = socialLinks.joinToString(","), // Simple serialization for now
            backgroundData = backgroundData,
            firstSeenTimestamp = currentTime,
            lastSeenTimestamp = currentTime,
            encounterCount = 1
        )
        
        // Save to DB
        identityDao.insertIdentity(record)
        
        // Add to memory
        memoryCache.add(record)
    }

    /**
     * Calculates the cosine similarity between two vectors.
     */
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

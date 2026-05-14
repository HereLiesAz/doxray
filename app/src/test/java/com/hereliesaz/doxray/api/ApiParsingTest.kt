package com.hereliesaz.doxray.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Basic local unit tests for the API layer data structures.
 */
class ApiParsingTest {

    @Test
    fun testFaceSeekResultModel() {
        val faceId = "test_user_123"
        val confidence = 0.95f
        val refUrl = "http://example.com/ref.jpg"
        
        val result = FaceSeekService.Result(faceId, confidence, refUrl)
        
        assertEquals(faceId, result.faceId)
        assertEquals(0.95f, result.confidence)
        assertEquals(refUrl, result.referenceImageUrl)
    }

    @Test
    fun testYandexSearchResultModel() {
        val identities = listOf("Alice", "Bob")
        val links = listOf("http://linkedin.com/alice", "http://twitter.com/bob")
        
        val result = YandexSearchService.Result(identities, links)
        
        assertEquals(2, result.identities.size)
        assertEquals("Alice", result.identities[0])
        assertEquals("http://twitter.com/bob", result.socialLinks[1])
    }
}

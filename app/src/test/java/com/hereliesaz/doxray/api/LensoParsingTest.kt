package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LensoParsingTest {

    @Test
    fun `parses top result from documented schema`() {
        val body = """
            {
              "results": [
                {
                  "urlList": [
                    {
                      "imageUrl": "https://cdn.example.com/face.jpg",
                      "sourceUrl": "https://example.com/profile/jane",
                      "title": "example.com"
                    }
                  ],
                  "base64Image": "abc123",
                  "confidenceScore": 87,
                  "date": "2026-04-01"
                }
              ],
              "availablePages": 3,
              "multiPage": [1, 2, 3]
            }
        """.trimIndent()

        val result = LensoResponseParser.parse(body)
        assertNotNull(result)
        assertEquals(0.87f, result!!.confidence, 0.001f)
        assertEquals("https://example.com/profile/jane", result.referenceImageUrl)
        assertEquals("example.com", result.sourceDomain)
    }

    @Test
    fun `returns null on empty results`() {
        val body = """{"results": [], "availablePages": 0, "multiPage": []}"""
        assertNull(LensoResponseParser.parse(body))
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(LensoResponseParser.parse("not json"))
        assertNull(LensoResponseParser.parse(""))
    }
}

package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PimEyesParsingTest {

    @Test
    fun `parses top result from documented schema`() {
        val body = """
            {
              "results": [
                {
                  "url": "https://example.com/profile/jane",
                  "score": 0.87,
                  "thumbnail": "https://cdn.pimeyes.com/thumb/abc123.jpg"
                },
                {
                  "url": "https://other.com/page",
                  "score": 0.55,
                  "thumbnail": "https://cdn.pimeyes.com/thumb/def456.jpg"
                }
              ]
            }
        """.trimIndent()

        val result = PimEyesResponseParser.parse(body)
        assertNotNull(result)
        assertEquals(0.87f, result!!.confidence, 0.001f)
        assertEquals("https://example.com/profile/jane", result.referenceImageUrl)
    }

    @Test
    fun `returns null on empty results`() {
        val body = """{"results": []}"""
        assertNull(PimEyesResponseParser.parse(body))
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(PimEyesResponseParser.parse("not json"))
        assertNull(PimEyesResponseParser.parse(""))
    }
}

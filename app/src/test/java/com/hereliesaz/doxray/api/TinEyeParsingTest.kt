package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TinEyeParsingTest {

    @Test
    fun `parses match list from documented schema`() {
        val body = """
            {
              "results": {
                "matches": [
                  { "image_url": "https://example.com/img/jane.jpg",
                    "domain": "example.com" },
                  { "image_url": "https://other.com/photos/jane.jpg",
                    "domain": "other.com" },
                  { "image_url": "https://example.com/img/jane.jpg",
                    "domain": "example.com" }
                ]
              }
            }
        """.trimIndent()

        val result = TinEyeResponseParser.parse(body)
        assertNotNull(result)
        // Domains as identities, deduped
        assertEquals(setOf("example.com", "other.com"), result!!.identities.toSet())
        // Image URLs as socialLinks, deduped
        assertEquals(
            setOf("https://example.com/img/jane.jpg", "https://other.com/photos/jane.jpg"),
            result.socialLinks.toSet()
        )
        assertTrue(result.identities.size == 2)
        assertTrue(result.socialLinks.size == 2)
    }

    @Test
    fun `returns null on empty matches`() {
        val body = """{"results": {"matches": []}}"""
        assertNull(TinEyeResponseParser.parse(body))
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(TinEyeResponseParser.parse("not json"))
        assertNull(TinEyeResponseParser.parse(""))
    }
}

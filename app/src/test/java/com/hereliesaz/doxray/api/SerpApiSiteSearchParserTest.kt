package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerpApiSiteSearchParserTest {

    @Test
    fun `parses top 3 organic results from documented schema`() {
        val body = """
            {
              "organic_results": [
                { "title": "Jane Doe | LinkedIn",
                  "snippet": "Senior engineer at example.com",
                  "link": "https://www.linkedin.com/in/jane-doe" },
                { "title": "Jane Doe — VP Eng",
                  "snippet": "Profile snippet two",
                  "link": "https://www.linkedin.com/in/jane-doe-2" },
                { "title": "Jane Doe — Director",
                  "snippet": "Profile snippet three",
                  "link": "https://www.linkedin.com/in/jane-doe-3" },
                { "title": "Jane Doe — fourth, ignored",
                  "snippet": "Should be dropped (limit 3)",
                  "link": "https://www.linkedin.com/in/jane-doe-4" }
              ]
            }
        """.trimIndent()

        val hits = SerpApiSiteSearchParser.parse(body)
        assertEquals(3, hits.size)
        assertEquals("Jane Doe | LinkedIn", hits[0].title)
        assertEquals("Senior engineer at example.com", hits[0].snippet)
        assertEquals("https://www.linkedin.com/in/jane-doe", hits[0].link)
        assertTrue(hits.none { it.title.contains("fourth") })
    }

    @Test
    fun `returns empty list on missing organic_results`() {
        val body = """{"search_metadata": {"status": "Success"}}"""
        assertTrue(SerpApiSiteSearchParser.parse(body).isEmpty())
    }

    @Test
    fun `returns empty list on malformed input`() {
        assertTrue(SerpApiSiteSearchParser.parse("not json").isEmpty())
        assertTrue(SerpApiSiteSearchParser.parse("").isEmpty())
    }
}

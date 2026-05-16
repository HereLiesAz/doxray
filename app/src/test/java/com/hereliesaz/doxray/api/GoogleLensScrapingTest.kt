package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GoogleLensScrapingTest {

    @Test
    fun `extracts identities and links from result html`() {
        val html = """
            <html><body>
              <div class="result">
                <a href="https://example.com/profile/jane" class="result-link">
                  <span class="result-title">Jane Doe — example.com</span>
                </a>
              </div>
              <div class="result">
                <a href="https://other.com/jane-doe" class="result-link">
                  <span class="result-title">Jane Doe – Other Site</span>
                </a>
              </div>
              <div class="result">
                <a href="https://example.com/profile/jane" class="result-link">
                  <span class="result-title">Jane Doe — example.com</span>
                </a>
              </div>
            </body></html>
        """.trimIndent()

        val result = GoogleLensScraperService.extractFromHtml(html)
        assertNotNull(result)
        assertEquals(2, result!!.identities.size)
        assertEquals(2, result.socialLinks.size)
        assertEquals(setOf("Jane Doe — example.com", "Jane Doe – Other Site"), result.identities.toSet())
        assertEquals(
            setOf("https://example.com/profile/jane", "https://other.com/jane-doe"),
            result.socialLinks.toSet(),
        )
    }

    @Test
    fun `returns null on no result anchors`() {
        val html = """<html><body><div>No results</div></body></html>"""
        assertEquals(null, GoogleLensScraperService.extractFromHtml(html))
    }
}

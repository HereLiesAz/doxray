package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubProbeParserTest {

    @Test
    fun `parses profile page with bio and counters`() {
        val html = """
            <html><body>
              <span class="p-name vcard-fullname d-block overflow-hidden">Jane Doe</span>
              <div class="p-note user-profile-bio">
                <div>Senior engineer. Open-source maintainer.</div>
              </div>
              <a href="/janedoe?tab=followers"><span class="text-bold color-fg-default">128</span></a>
              <nav aria-label="User profile">
                <a href="/janedoe?tab=repositories"><span class="Counter">42</span></a>
              </nav>
            </body></html>
        """.trimIndent()

        val profile = GitHubProbeParser.parse(html, username = "janedoe")
        assertNotNull(profile)
        assertEquals("janedoe", profile!!.username)
        assertEquals("Senior engineer. Open-source maintainer.", profile.bio)
        assertEquals(128, profile.followers)
        assertEquals(42, profile.publicRepos)
    }

    @Test
    fun `parses minimal profile with missing fields`() {
        val html = """
            <html><body>
              <span class="p-name vcard-fullname d-block overflow-hidden">Jane Doe</span>
            </body></html>
        """.trimIndent()

        val profile = GitHubProbeParser.parse(html, username = "janedoe")
        assertNotNull(profile)
        assertEquals("janedoe", profile!!.username)
        assertEquals("", profile.bio)
        assertEquals(0, profile.followers)
        assertEquals(0, profile.publicRepos)
    }

    @Test
    fun `returns null on 404 page`() {
        val html = """
            <html><body>
              <h1>404</h1>
              <p>This is not the web page you are looking for.</p>
            </body></html>
        """.trimIndent()

        assertNull(GitHubProbeParser.parse(html, username = "nonexistent"))
    }
}

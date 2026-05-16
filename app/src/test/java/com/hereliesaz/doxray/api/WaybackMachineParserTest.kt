package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WaybackMachineParserTest {

    @Test
    fun `parses closest snapshot from documented schema`() {
        val body = """
            {
              "url": "https://example.com/profile/jane",
              "archived_snapshots": {
                "closest": {
                  "available": true,
                  "url": "https://web.archive.org/web/20240115123045/https://example.com/profile/jane",
                  "timestamp": "20240115123045",
                  "status": "200"
                }
              }
            }
        """.trimIndent()

        val snap = WaybackMachineParser.parse(body, originalUrl = "https://example.com/profile/jane")
        assertNotNull(snap)
        assertEquals("https://example.com/profile/jane", snap!!.originalUrl)
        assertEquals(
            "https://web.archive.org/web/20240115123045/https://example.com/profile/jane",
            snap.archiveUrl,
        )
        assertEquals("20240115123045", snap.timestamp)
    }

    @Test
    fun `returns null when no snapshot available`() {
        val body = """
            {
              "url": "https://example.com/profile/jane",
              "archived_snapshots": {}
            }
        """.trimIndent()

        assertNull(WaybackMachineParser.parse(body, "https://example.com/profile/jane"))
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(WaybackMachineParser.parse("not json", "https://x"))
        assertNull(WaybackMachineParser.parse("", "https://x"))
    }
}

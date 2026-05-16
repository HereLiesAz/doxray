package com.hereliesaz.doxray.api

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OcrServiceTest {

    @Test
    fun `expandBbox grows centered face 2x down and 0_5x lateral`() {
        val face = Rect(400, 400, 600, 600)
        val expanded = OcrService.expandBbox(face, imageWidth = 1000, imageHeight = 1000)
        assertEquals(300, expanded.left)
        assertEquals(400, expanded.top)
        assertEquals(700, expanded.right)
        assertEquals(1000, expanded.bottom)
    }

    @Test
    fun `expandBbox clamps to frame edges`() {
        val face = Rect(400, 400, 500, 500)
        val expanded = OcrService.expandBbox(face, imageWidth = 500, imageHeight = 500)
        assertEquals(350, expanded.left)
        assertEquals(400, expanded.top)
        assertEquals(500, expanded.right)
        assertEquals(500, expanded.bottom)
    }

    @Test
    fun `extract returns null for empty image bytes`() = kotlinx.coroutines.runBlocking {
        val service = OcrService()
        val result = service.extract(ByteArray(0), Rect(0, 0, 10, 10))
        assertNull(result)
    }
}

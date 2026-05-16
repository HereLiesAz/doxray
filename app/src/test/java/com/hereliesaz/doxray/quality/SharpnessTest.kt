package com.hereliesaz.doxray.quality

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SharpnessTest {

    @Test
    fun `solid colour bitmap has near-zero variance`() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GRAY)
        val v = Sharpness.laplacianVariance(bmp)
        assertTrue("expected near-zero variance, got $v", v < 1f)
    }

    @Test
    fun `checkerboard bitmap has high variance`() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 8) {
            bmp.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
        }
        val v = Sharpness.laplacianVariance(bmp)
        assertTrue("expected high variance on checkerboard, got $v", v > 100f)
    }

    @Test
    fun `tiny bitmap returns zero`() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val v = Sharpness.laplacianVariance(bmp)
        assertTrue("expected 0 for 2x2, got $v", v == 0f)
    }
}

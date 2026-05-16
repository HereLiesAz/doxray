package com.hereliesaz.doxray.quality

import android.graphics.Bitmap

/**
 * Laplacian variance on the bitmap's luminance channel. Higher = sharper.
 * Pragmatic measure used by the face-quality gate to reject motion blur.
 * Not perfect on Bayer-mosaiced sensors but adequate for the use case.
 */
object Sharpness {
    fun laplacianVariance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 0f

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            0.299f * ((p shr 16) and 0xff) +
                0.587f * ((p shr 8) and 0xff) +
                0.114f * (p and 0xff)
        }

        val responses = FloatArray((w - 2) * (h - 2))
        var idx = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                responses[idx++] = -lum[i - w] - lum[i - 1] +
                    4f * lum[i] - lum[i + 1] - lum[i + w]
            }
        }

        var mean = 0f
        for (v in responses) mean += v
        mean /= responses.size

        var sumSq = 0f
        for (v in responses) {
            val d = v - mean
            sumSq += d * d
        }
        return sumSq / responses.size
    }
}

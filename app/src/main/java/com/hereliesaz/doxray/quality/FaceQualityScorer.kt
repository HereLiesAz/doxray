package com.hereliesaz.doxray.quality

import android.graphics.Bitmap

sealed class QualityResult {
    object Pass : QualityResult()
    data class Fail(val reasons: List<String>) : QualityResult()
}

data class FaceQualityInput(
    val faceFraction: Float,
    val eulerX: Float,
    val eulerY: Float,
    val eulerZ: Float,
    val sharpness: Float,
    val meanLuminance: Float,
)

object FaceQualityScorer {

    private const val MIN_FACE_FRAC = 0.15f
    private const val MAX_EULER_X = 30f
    private const val MAX_EULER_Y = 20f
    private const val MAX_EULER_Z = 15f
    private const val MIN_SHARPNESS = 80f
    private const val MIN_LUMINANCE = 30f
    private const val MAX_LUMINANCE = 220f

    fun score(input: FaceQualityInput): QualityResult {
        val reasons = mutableListOf<String>()
        if (input.faceFraction < MIN_FACE_FRAC)
            reasons += "too-small (${"%.2f".format(input.faceFraction)})"
        if (kotlin.math.abs(input.eulerX) > MAX_EULER_X)
            reasons += "pitch ${"%.0f".format(input.eulerX)}°"
        if (kotlin.math.abs(input.eulerY) > MAX_EULER_Y)
            reasons += "yaw ${"%.0f".format(input.eulerY)}°"
        if (kotlin.math.abs(input.eulerZ) > MAX_EULER_Z)
            reasons += "roll ${"%.0f".format(input.eulerZ)}°"
        if (input.sharpness < MIN_SHARPNESS)
            reasons += "blurry (${"%.1f".format(input.sharpness)})"
        if (input.meanLuminance < MIN_LUMINANCE)
            reasons += "too-dark (${"%.0f".format(input.meanLuminance)})"
        if (input.meanLuminance > MAX_LUMINANCE)
            reasons += "too-bright (${"%.0f".format(input.meanLuminance)})"
        return if (reasons.isEmpty()) QualityResult.Pass
        else QualityResult.Fail(reasons)
    }

    fun scoreFromBitmap(
        bitmap: Bitmap,
        faceFraction: Float,
        eulerX: Float,
        eulerY: Float,
        eulerZ: Float,
    ): QualityResult {
        val sharpness = Sharpness.laplacianVariance(bitmap)
        val luminance = meanLuminance(bitmap)
        return score(FaceQualityInput(faceFraction, eulerX, eulerY, eulerZ, sharpness, luminance))
    }

    private fun meanLuminance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (p in px) {
            sum += 0.299 * ((p shr 16) and 0xff) +
                0.587 * ((p shr 8) and 0xff) +
                0.114 * (p and 0xff)
        }
        return (sum / px.size).toFloat()
    }
}

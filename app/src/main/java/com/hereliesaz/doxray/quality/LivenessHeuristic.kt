package com.hereliesaz.doxray.quality

sealed class LivenessResult {
    object Pass : LivenessResult()
    data class Fail(val reasonDetails: String) : LivenessResult()
}

/**
 * Temporal liveness heuristic. Operates on FaceSamples collected over
 * the FaceTrackerManager 5-second focus window. Passes when at least 2
 * of the 3 indicators show variance above their threshold:
 *   - eye-open probability range (a blink)
 *   - head Euler angle range across X/Y/Z (micro-movement)
 *   - smile probability range (micro-expression)
 *
 * Photo/screen/billboard targets show ~0 variance across all three.
 */
object LivenessHeuristic {

    private const val EYE_VARIANCE_THRESHOLD = 0.10f
    private const val EULER_RANGE_DEG_THRESHOLD = 3f
    private const val SMILE_VARIANCE_THRESHOLD = 0.05f
    private const val MIN_SAMPLES = 5

    fun evaluate(samples: List<FaceSample>): LivenessResult {
        if (samples.size < MIN_SAMPLES) return LivenessResult.Fail("only ${samples.size} samples")

        val avgEyeOpens = samples
            .map { (it.leftEyeOpen + it.rightEyeOpen) / 2f }
            .filter { it >= 0f }
        val eyeVariance = if (avgEyeOpens.size < MIN_SAMPLES) 0f
            else avgEyeOpens.max() - avgEyeOpens.min()

        val xs = samples.map { it.eulerX }
        val ys = samples.map { it.eulerY }
        val zs = samples.map { it.eulerZ }
        val eulerRange = maxOf(
            xs.max() - xs.min(),
            ys.max() - ys.min(),
            zs.max() - zs.min(),
        )

        val smiles = samples.map { it.smiling }.filter { it >= 0f }
        val smileVariance = if (smiles.size < MIN_SAMPLES) 0f
            else smiles.max() - smiles.min()

        val passes = listOf(
            eyeVariance > EYE_VARIANCE_THRESHOLD,
            eulerRange > EULER_RANGE_DEG_THRESHOLD,
            smileVariance > SMILE_VARIANCE_THRESHOLD,
        ).count { it }

        return if (passes >= 2) LivenessResult.Pass
        else LivenessResult.Fail(
            "eyeVar=${"%.3f".format(eyeVariance)} " +
            "eulerRange=${"%.2f".format(eulerRange)} " +
            "smileVar=${"%.3f".format(smileVariance)}"
        )
    }
}

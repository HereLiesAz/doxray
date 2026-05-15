package com.hereliesaz.doxray.quality

/**
 * One per-frame snapshot from ML Kit Face detection, accumulated by
 * FaceTrackerManager during the 5-second focus window per tracking ID.
 *
 * Probability fields are `-1f` when ML Kit returned null (classification
 * unavailable for that frame). LivenessHeuristic ignores -1 values.
 */
data class FaceSample(
    val leftEyeOpen: Float,
    val rightEyeOpen: Float,
    val smiling: Float,
    val eulerX: Float,
    val eulerY: Float,
    val eulerZ: Float,
    val timestampMs: Long,
)

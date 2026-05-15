package com.hereliesaz.doxray.quality

import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessHeuristicTest {

    private fun sample(
        leftEye: Float = 0.9f, rightEye: Float = 0.9f, smile: Float = 0.1f,
        eulerX: Float = 0f, eulerY: Float = 0f, eulerZ: Float = 0f,
    ) = FaceSample(leftEye, rightEye, smile, eulerX, eulerY, eulerZ, timestampMs = 0L)

    @Test
    fun `empty samples fails`() {
        val result = LivenessHeuristic.evaluate(emptyList())
        assertTrue(result is LivenessResult.Fail)
    }

    @Test
    fun `below MIN_SAMPLES fails`() {
        val samples = List(3) { sample() }
        val result = LivenessHeuristic.evaluate(samples)
        assertTrue(result is LivenessResult.Fail)
    }

    @Test
    fun `static face (no variance) fails`() {
        val samples = List(10) { sample() }
        val result = LivenessHeuristic.evaluate(samples)
        assertTrue("static face must fail liveness", result is LivenessResult.Fail)
    }

    @Test
    fun `blink plus head turn passes`() {
        val samples = listOf(
            sample(leftEye = 0.95f, rightEye = 0.95f, eulerY = 0f),
            sample(leftEye = 0.90f, rightEye = 0.90f, eulerY = 2f),
            sample(leftEye = 0.10f, rightEye = 0.10f, eulerY = 4f),
            sample(leftEye = 0.20f, rightEye = 0.20f, eulerY = 5f),
            sample(leftEye = 0.85f, rightEye = 0.85f, eulerY = 6f),
            sample(leftEye = 0.95f, rightEye = 0.95f, eulerY = 6f),
        )
        val result = LivenessHeuristic.evaluate(samples)
        assertTrue("blink + head turn should pass", result is LivenessResult.Pass)
    }

    @Test
    fun `only one indicator changes fails`() {
        val samples = listOf(
            sample(leftEye = 0.95f, rightEye = 0.95f),
            sample(leftEye = 0.90f, rightEye = 0.90f),
            sample(leftEye = 0.10f, rightEye = 0.10f),
            sample(leftEye = 0.20f, rightEye = 0.20f),
            sample(leftEye = 0.85f, rightEye = 0.85f),
            sample(leftEye = 0.95f, rightEye = 0.95f),
        )
        val result = LivenessHeuristic.evaluate(samples)
        assertTrue("one channel passing isn't enough", result is LivenessResult.Fail)
    }

    @Test
    fun `eye blink plus smile change passes`() {
        val samples = listOf(
            sample(leftEye = 0.95f, rightEye = 0.95f, smile = 0.05f),
            sample(leftEye = 0.10f, rightEye = 0.10f, smile = 0.20f),
            sample(leftEye = 0.20f, rightEye = 0.20f, smile = 0.40f),
            sample(leftEye = 0.85f, rightEye = 0.85f, smile = 0.10f),
            sample(leftEye = 0.95f, rightEye = 0.95f, smile = 0.05f),
            sample(leftEye = 0.95f, rightEye = 0.95f, smile = 0.05f),
        )
        val result = LivenessHeuristic.evaluate(samples)
        assertTrue("blink + smile change should pass", result is LivenessResult.Pass)
    }
}

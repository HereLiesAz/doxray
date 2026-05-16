package com.hereliesaz.doxray.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceQualityScorerTest {

    private fun goodInput() = FaceQualityInput(
        faceFraction = 0.3f,
        eulerX = 5f, eulerY = 5f, eulerZ = 2f,
        sharpness = 200f,
        meanLuminance = 120f,
    )

    @Test
    fun `all metrics in range passes`() {
        assertEquals(QualityResult.Pass, FaceQualityScorer.score(goodInput()))
    }

    @Test
    fun `face too small fails`() {
        val r = FaceQualityScorer.score(goodInput().copy(faceFraction = 0.05f))
        assertTrue(r is QualityResult.Fail)
        assertTrue((r as QualityResult.Fail).reasons.any { it.contains("too-small") })
    }

    @Test
    fun `extreme pitch fails`() {
        val r = FaceQualityScorer.score(goodInput().copy(eulerX = 45f))
        assertTrue(r is QualityResult.Fail)
        assertTrue((r as QualityResult.Fail).reasons.any { it.contains("pitch") })
    }

    @Test
    fun `extreme yaw fails`() {
        val r = FaceQualityScorer.score(goodInput().copy(eulerY = -30f))
        assertTrue(r is QualityResult.Fail)
        assertTrue((r as QualityResult.Fail).reasons.any { it.contains("yaw") })
    }

    @Test
    fun `extreme roll fails`() {
        val r = FaceQualityScorer.score(goodInput().copy(eulerZ = 25f))
        assertTrue(r is QualityResult.Fail)
        assertTrue((r as QualityResult.Fail).reasons.any { it.contains("roll") })
    }

    @Test
    fun `blurry fails`() {
        val r = FaceQualityScorer.score(goodInput().copy(sharpness = 10f))
        assertTrue(r is QualityResult.Fail)
        assertTrue((r as QualityResult.Fail).reasons.any { it.contains("blurry") })
    }

    @Test
    fun `too dark fails`() {
        val r = FaceQualityScorer.score(goodInput().copy(meanLuminance = 10f))
        assertTrue(r is QualityResult.Fail)
        assertTrue((r as QualityResult.Fail).reasons.any { it.contains("too-dark") })
    }

    @Test
    fun `too bright fails`() {
        val r = FaceQualityScorer.score(goodInput().copy(meanLuminance = 240f))
        assertTrue(r is QualityResult.Fail)
        assertTrue((r as QualityResult.Fail).reasons.any { it.contains("too-bright") })
    }

    @Test
    fun `multiple failures reported together`() {
        val r = FaceQualityScorer.score(goodInput().copy(
            faceFraction = 0.05f, sharpness = 10f, meanLuminance = 10f,
        ))
        assertTrue(r is QualityResult.Fail)
        val reasons = (r as QualityResult.Fail).reasons
        assertTrue("expected at least 3 reasons, got ${reasons.size}: $reasons", reasons.size >= 3)
    }
}

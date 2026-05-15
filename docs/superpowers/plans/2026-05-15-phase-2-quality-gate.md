# Phase 2 Quality & Efficiency Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a liveness gate (heuristic over the 5-sec focus window), a face-quality gate (size/angle/sharpness/luminance), and a re-id clustering merge inside `LocalFaceCache.cacheIdentity` — so the pipeline stops burning API credits on billboards/photos/screens and stops fragmenting real people into multiple cache rows.

**Architecture:** Two gates wedge into the existing pipeline. Gate 1 lives inside `FaceTrackerManager` and consumes per-frame `FaceSample`s from ML Kit (with `CLASSIFICATION_MODE_ALL` enabled); evaluates `LivenessHeuristic.evaluate(samples)` at the 5-sec focus boundary. Gate 2 lives inside `LiveViewModel.processFocusedFace`; calls `FaceQualityScorer.scoreFromBitmap(...)` before invoking `EmbeddingGenerator`. Both gates audit-log a new `REJECTED` event type. `LocalFaceCache.cacheIdentity` runs a `findClusterMatch(embedding)` pre-check at the 0.92 cosine threshold; on hit, the existing dossier absorbs the encounter rather than spawning a near-duplicate `IdentityRecord`.

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.1, Compose BOM 2026.05.00, Room 2.8.4, ML Kit face-detection 16.1.7, JUnit 4, Robolectric 4.13.

**Reference:** Spec at `docs/superpowers/specs/2026-05-15-phase-2-quality-gate-design.md`.

---

## File Structure

**New files**
- `app/src/main/java/com/hereliesaz/doxray/quality/FaceSample.kt` — per-frame sample data class
- `app/src/main/java/com/hereliesaz/doxray/quality/LivenessHeuristic.kt` — `evaluate(List<FaceSample>): LivenessResult`
- `app/src/main/java/com/hereliesaz/doxray/quality/Sharpness.kt` — `laplacianVariance(Bitmap): Float`
- `app/src/main/java/com/hereliesaz/doxray/quality/FaceQualityScorer.kt` — `score(FaceQualityInput): QualityResult` + `scoreFromBitmap(...)` convenience
- `app/src/test/java/com/hereliesaz/doxray/quality/LivenessHeuristicTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/quality/FaceQualityScorerTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/quality/SharpnessTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheMergeTest.kt`

**Modified files**
- `app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt` — add `REJECTED` enum value
- `app/src/main/java/com/hereliesaz/doxray/api/FaceTrackerManager.kt` — `CLASSIFICATION_MODE_ALL`, per-tracking sample buffer, liveness gate, extended `FaceFocusListener` signature (Euler X/Y/Z)
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` — receive Euler in `onFaceFocused`, run `FaceQualityScorer` before `EmbeddingGenerator`
- `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt` — add `findClusterMatch` + merge path in `cacheIdentity`

---

## Task 1: Add `REJECTED` to `AuditLogger.Type`

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt`

- [ ] **Step 1: Add the enum value**

In `AuditLogger.kt`, locate the line:
```kotlin
    enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE }
```
Replace with:
```kotlin
    enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE, REJECTED }
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt
git commit -m "Add REJECTED to AuditLogger.Type"
```

---

## Task 2: Create `FaceSample` data class

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/quality/FaceSample.kt`

- [ ] **Step 1: Create the new directory + file**

`app/src/main/java/com/hereliesaz/doxray/quality/FaceSample.kt`:
```kotlin
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
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/quality/FaceSample.kt
git commit -m "Add FaceSample data class for the quality gate"
```

---

## Task 3: TDD `LivenessHeuristic`

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/quality/LivenessHeuristicTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/quality/LivenessHeuristic.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/quality/LivenessHeuristicTest.kt`:
```kotlin
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
        // Eye open varies 0.95 -> 0.1 (blink) AND head yaw varies by 6 deg
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
        // Only eyes vary, head is locked and no smile change
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
        // Eyes blink AND smile probability moves; head static
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.quality.LivenessHeuristicTest" --no-daemon`
Expected: compile error `Unresolved reference 'LivenessHeuristic'` (and `'LivenessResult'`).

- [ ] **Step 3: Implement `LivenessHeuristic`**

`app/src/main/java/com/hereliesaz/doxray/quality/LivenessHeuristic.kt`:
```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.quality.LivenessHeuristicTest" --no-daemon`
Expected: all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/quality/LivenessHeuristic.kt \
        app/src/test/java/com/hereliesaz/doxray/quality/LivenessHeuristicTest.kt
git commit -m "Add LivenessHeuristic with TDD (6 cases)"
```

---

## Task 4: TDD `Sharpness.laplacianVariance`

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/quality/SharpnessTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/quality/Sharpness.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/quality/SharpnessTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.quality.SharpnessTest" --no-daemon`
Expected: compile error `Unresolved reference 'Sharpness'`.

- [ ] **Step 3: Implement Sharpness**

`app/src/main/java/com/hereliesaz/doxray/quality/Sharpness.kt`:
```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.quality.SharpnessTest" --no-daemon`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/quality/Sharpness.kt \
        app/src/test/java/com/hereliesaz/doxray/quality/SharpnessTest.kt
git commit -m "Add Sharpness.laplacianVariance with TDD (Robolectric)"
```

---

## Task 5: TDD `FaceQualityScorer`

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/quality/FaceQualityScorerTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/quality/FaceQualityScorer.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/quality/FaceQualityScorerTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.quality.FaceQualityScorerTest" --no-daemon`
Expected: compile error `Unresolved reference 'FaceQualityScorer'`.

- [ ] **Step 3: Implement FaceQualityScorer**

`app/src/main/java/com/hereliesaz/doxray/quality/FaceQualityScorer.kt`:
```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.quality.FaceQualityScorerTest" --no-daemon`
Expected: all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/quality/FaceQualityScorer.kt \
        app/src/test/java/com/hereliesaz/doxray/quality/FaceQualityScorerTest.kt
git commit -m "Add FaceQualityScorer with TDD (9 cases)"
```

---

## Task 6: Wire liveness gate into `FaceTrackerManager`, extend `FaceFocusListener`

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/FaceTrackerManager.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` (signature update only, no quality gate yet)

- [ ] **Step 1: Replace `FaceTrackerManager.kt` contents**

`app/src/main/java/com/hereliesaz/doxray/api/FaceTrackerManager.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.quality.FaceSample
import com.hereliesaz.doxray.quality.LivenessHeuristic
import com.hereliesaz.doxray.quality.LivenessResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class FaceTrackerManager {

    private val TAG = "FaceTrackerManager"

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .enableTracking()
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()

    private val detector = FaceDetection.getClient(options)

    private val trackedFaces = mutableMapOf<Int, Long>()
    private val searchedFaces = mutableSetOf<Int>()
    private val samples = mutableMapOf<Int, MutableList<FaceSample>>()

    private val FOCUS_THRESHOLD_MS = 5000L
    private val MAX_SAMPLES_PER_TRACK = 30  // 5s at ~6fps; bound memory

    interface FaceFocusListener {
        fun onFaceFocused(
            imageBytes: ByteArray,
            trackingId: Int,
            faceCrop: ByteArray,
            eulerX: Float,
            eulerY: Float,
            eulerZ: Float,
        )
        fun onFaceLost(trackingId: Int)
        fun onError(e: Exception)
    }

    fun processFrame(imageBytes: ByteArray, listener: FaceFocusListener) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return
            val image = InputImage.fromBitmap(bitmap, 0)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    val currentIds = mutableSetOf<Int>()
                    val now = System.currentTimeMillis()

                    for (face in faces) {
                        val trackingId = face.trackingId ?: continue
                        currentIds.add(trackingId)

                        // Record this frame as a sample regardless of search state
                        val sample = FaceSample(
                            leftEyeOpen = face.leftEyeOpenProbability ?: -1f,
                            rightEyeOpen = face.rightEyeOpenProbability ?: -1f,
                            smiling = face.smilingProbability ?: -1f,
                            eulerX = face.headEulerAngleX,
                            eulerY = face.headEulerAngleY,
                            eulerZ = face.headEulerAngleZ,
                            timestampMs = now,
                        )
                        val list = samples.getOrPut(trackingId) { mutableListOf() }
                        list.add(sample)
                        if (list.size > MAX_SAMPLES_PER_TRACK) list.removeAt(0)

                        if (searchedFaces.contains(trackingId)) continue

                        val firstSeen = trackedFaces[trackingId]
                        if (firstSeen == null) {
                            trackedFaces[trackingId] = now
                            Log.d(TAG, "New face detected (ID: $trackingId). Starting 5-second focus timer.")
                        } else {
                            val duration = now - firstSeen
                            if (duration >= FOCUS_THRESHOLD_MS) {
                                searchedFaces.add(trackingId)

                                // [GATE 1] Liveness
                                val liveness = LivenessHeuristic.evaluate(samples[trackingId].orEmpty())
                                if (liveness is LivenessResult.Fail) {
                                    Log.d(TAG, "Liveness FAIL for ID $trackingId: ${liveness.reasonDetails}")
                                    AuditLogger.log(
                                        AuditLogger.Type.REJECTED,
                                        summary = "Liveness failed for tracked face $trackingId",
                                        details = JSONObject().apply {
                                            put("reason", "liveness")
                                            put("trackingId", trackingId)
                                            put("sampleCount", samples[trackingId]?.size ?: 0)
                                            put("breakdown", liveness.reasonDetails)
                                        },
                                    )
                                    continue
                                }
                                Log.d(TAG, "Liveness PASS for ID $trackingId. Cropping + dispatching.")

                                val faceCropBytes = cropFace(bitmap, face) ?: imageBytes
                                listener.onFaceFocused(
                                    imageBytes = imageBytes,
                                    trackingId = trackingId,
                                    faceCrop = faceCropBytes,
                                    eulerX = face.headEulerAngleX,
                                    eulerY = face.headEulerAngleY,
                                    eulerZ = face.headEulerAngleZ,
                                )
                            }
                        }
                    }

                    val removedIds = trackedFaces.keys - currentIds
                    for (id in removedIds) {
                        trackedFaces.remove(id)
                        searchedFaces.remove(id)
                        samples.remove(id)
                        listener.onFaceLost(id)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)
                    listener.onError(e)
                }
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    private fun cropFace(source: Bitmap, face: Face): ByteArray? {
        return try {
            val bbox = face.boundingBox
            val clamped = Rect(
                bbox.left.coerceIn(0, source.width),
                bbox.top.coerceIn(0, source.height),
                bbox.right.coerceIn(0, source.width),
                bbox.bottom.coerceIn(0, source.height),
            )
            if (clamped.width() <= 0 || clamped.height() <= 0) return null
            val cropped = Bitmap.createBitmap(source, clamped.left, clamped.top, clamped.width(), clamped.height())
            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Face crop failed; falling back to full frame", e)
            null
        }
    }
}
```

- [ ] **Step 2: Update LiveViewModel's anonymous `FaceFocusListener` to accept the new params**

Open `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` and locate the anonymous-object `FaceFocusListener`. The current `onFaceFocused` override looks like:
```kotlin
                        override fun onFaceFocused(focusedImageBytes: ByteArray, trackingId: Int, faceCrop: ByteArray) {
                            if (activeInvestigations.containsKey(trackingId)) return
                            appendLog("Target acquired (ID: $trackingId). Processing search...")
                            val job = viewModelScope.launch {
                                processFocusedFace(focusedImageBytes, faceCrop, trackingId)
                            }
                            activeInvestigations[trackingId] = job
                        }
```

Replace with (adding three Euler params, plumbing them into a new `processFocusedFace` signature):
```kotlin
                        override fun onFaceFocused(
                            imageBytes: ByteArray,
                            trackingId: Int,
                            faceCrop: ByteArray,
                            eulerX: Float,
                            eulerY: Float,
                            eulerZ: Float,
                        ) {
                            if (activeInvestigations.containsKey(trackingId)) return
                            appendLog("Target acquired (ID: $trackingId). Processing search...")
                            val job = viewModelScope.launch {
                                processFocusedFace(imageBytes, faceCrop, trackingId, eulerX, eulerY, eulerZ)
                            }
                            activeInvestigations[trackingId] = job
                        }
```

Also update the `processFocusedFace` declaration in the same file. Find the line:
```kotlin
    private suspend fun processFocusedFace(imageBytes: ByteArray, faceCrop: ByteArray, trackingId: Int) {
```
Replace with:
```kotlin
    private suspend fun processFocusedFace(
        imageBytes: ByteArray,
        faceCrop: ByteArray,
        trackingId: Int,
        eulerX: Float,
        eulerY: Float,
        eulerZ: Float,
    ) {
```

The new `eulerX/Y/Z` parameters are unused for now — Task 7 wires them into the quality gate. The unused-parameter warning is acceptable for one task.

- [ ] **Step 3: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL` with all existing tests still passing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/FaceTrackerManager.kt \
        app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Enable ML Kit classifications; add liveness gate to FaceTrackerManager"
```

---

## Task 7: Wire face-quality gate into `LiveViewModel.processFocusedFace`

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Add imports**

Open `LiveViewModel.kt` and add these imports near the other doxxr imports:
```kotlin
import android.graphics.BitmapFactory
import com.hereliesaz.doxray.quality.FaceQualityScorer
import com.hereliesaz.doxray.quality.QualityResult
import org.json.JSONArray
```
(`org.json.JSONObject` is already imported; this just adds `JSONArray` for the rejection reasons list.)

- [ ] **Step 2: Insert the gate at the top of `processFocusedFace`**

Find the body of `processFocusedFace`. Just inside the `try {` and BEFORE the `val embedding = embeddingGenerator.generateEmbedding(faceCrop)` line, insert:
```kotlin
            // [GATE 2] Face quality
            val cropBitmap = BitmapFactory.decodeByteArray(faceCrop, 0, faceCrop.size)
            val frameBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (cropBitmap == null || frameBitmap == null) {
                appendLog("Quality gate: bitmap decode failed for ID $trackingId; skipping.")
                return
            }
            val faceFrac = (cropBitmap.width.toFloat() * cropBitmap.height) /
                (frameBitmap.width.toFloat() * frameBitmap.height)
            val quality = FaceQualityScorer.scoreFromBitmap(
                bitmap = cropBitmap,
                faceFraction = faceFrac,
                eulerX = eulerX,
                eulerY = eulerY,
                eulerZ = eulerZ,
            )
            if (quality is QualityResult.Fail) {
                appendLog("Low-quality crop (ID: $trackingId): ${quality.reasons.joinToString(", ")}")
                AuditLogger.log(
                    AuditLogger.Type.REJECTED,
                    summary = "Low-quality crop skipped",
                    details = JSONObject().apply {
                        put("reason", "quality")
                        put("trackingId", trackingId)
                        put("failures", JSONArray(quality.reasons))
                    },
                )
                return
            }
```

- [ ] **Step 3: Verify build + existing tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. All previous tests + the new LivenessHeuristicTest, SharpnessTest, FaceQualityScorerTest pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Wire FaceQualityScorer gate into LiveViewModel.processFocusedFace"
```

---

## Task 8: TDD re-id merge in `LocalFaceCache`

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheMergeTest.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheMergeTest.kt`:
```kotlin
package com.hereliesaz.doxray.api

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.location.LocationService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalFaceCacheMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var cache: LocalFaceCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Real LocationService with no permission granted in Robolectric —
        // its hasAnyLocationPermission() returns false so getLastLocation()
        // returns null without throwing. No subclass needed.
        cache = LocalFaceCache(
            identityDao = db.identityDao(),
            encounterDao = db.encounterDao(),
            locationService = LocationService(context),
        )
    }

    @After
    fun tearDown() { db.close() }

    /** Builds a 192-d unit vector along axis [axis]. Two such vectors with
     *  the same axis have cosine similarity 1.0; with different axes, 0. */
    private fun oneHot(axis: Int): FloatArray = FloatArray(192).also { it[axis] = 1f }

    /** Slight perturbation: nearly the same axis but with a small leak.
     *  Yields cosine similarity > 0.92. */
    private fun nearOneHot(axis: Int): FloatArray = FloatArray(192).also {
        it[axis] = 0.99f
        it[(axis + 1) % 192] = 0.14f
    }

    @Test
    fun `near-duplicate merges into existing dossier`() = runBlocking {
        cache.cacheIdentity(
            faceId = "face-original",
            embedding = oneHot(7),
            primaryIdentity = "Alice",
            socialLinks = listOf(),
            backgroundData = "{}",
        )

        // Reload so memoryCache contains the just-cached record
        cache.loadFromDatabase()

        cache.cacheIdentity(
            faceId = "face-duplicate",
            embedding = nearOneHot(7),
            primaryIdentity = "Alicia",
            socialLinks = listOf(),
            backgroundData = "{}",
        )

        val all = db.identityDao().getAllIdentities()
        assertEquals("merge should keep only one dossier", 1, all.size)
        assertEquals("face-original", all[0].faceId)
        assertEquals(2, all[0].encounterCount)
        // Encounter rows: 1 from initial cache + 1 from merge insert = 2 rows
        val encounters = db.encounterDao()
            .observeByFace("face-original")
            .let { flow ->
                kotlinx.coroutines.flow.first(flow) { true }
            }
        assertEquals(2, encounters.size)
    }

    @Test
    fun `clearly-different embedding inserts new dossier`() = runBlocking {
        cache.cacheIdentity(
            faceId = "face-1",
            embedding = oneHot(7),
            primaryIdentity = "Alice",
            socialLinks = listOf(),
            backgroundData = "{}",
        )
        cache.loadFromDatabase()

        cache.cacheIdentity(
            faceId = "face-2",
            embedding = oneHot(100), // orthogonal
            primaryIdentity = "Bob",
            socialLinks = listOf(),
            backgroundData = "{}",
        )

        val all = db.identityDao().getAllIdentities()
        assertEquals(2, all.size)
        assertNotEquals(all[0].faceId, all[1].faceId)
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.first(predicate: (T) -> Boolean): T =
    kotlinx.coroutines.flow.first(this, predicate)
```

Note: the helper at the bottom is a small alias — if Kotlin's `Flow.first(predicate)` extension resolves directly from the imports, the helper is unused and you can delete it. Keep it for now to be safe.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.LocalFaceCacheMergeTest" --no-daemon`
Expected: the "near-duplicate merges" test FAILS — currently `cacheIdentity` always inserts; you'll see `expected:<1> but was:<2>`.

- [ ] **Step 3: Implement `findClusterMatch` + merge path**

Open `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`. Add a new private constant near `SIMILARITY_THRESHOLD`:
```kotlin
    private val CLUSTER_THRESHOLD = 0.92f
```

Add a new public suspend method (place it after the existing `findMatch`):
```kotlin
    suspend fun findClusterMatch(embedding: FloatArray): IdentityRecord? = withContext(Dispatchers.IO) {
        if (memoryCache.isEmpty()) return@withContext null
        var bestMatch: IdentityRecord? = null
        var highestSimilarity = 0f
        for (cached in memoryCache) {
            val similarity = calculateCosineSimilarity(embedding, cached.embedding)
            if (similarity > highestSimilarity && similarity >= CLUSTER_THRESHOLD) {
                highestSimilarity = similarity
                bestMatch = cached
            }
        }
        bestMatch
    }
```

Replace the entire `cacheIdentity` function with this version (it prepends the cluster check and keeps the existing insert path verbatim below it):

```kotlin
    suspend fun cacheIdentity(
        faceId: String,
        embedding: FloatArray,
        primaryIdentity: String,
        socialLinks: List<String>,
        backgroundData: String,
    ) = withContext(Dispatchers.IO) {
        // Re-id cluster check: if a near-duplicate dossier already exists,
        // record the encounter against it instead of spawning a new one.
        val cluster = findClusterMatch(embedding)
        if (cluster != null) {
            val now = System.currentTimeMillis()
            identityDao.recordEncounter(cluster.faceId, now)
            AuditLogger.log(
                AuditLogger.Type.IDENTIFY,
                summary = "Merged into existing dossier: ${cluster.primaryIdentity}",
                details = JSONObject().apply {
                    put("existingFaceId", cluster.faceId)
                    put("incomingFaceId", faceId)
                    put("primaryIdentity", primaryIdentity)
                },
            )
            recordEncounter(cluster.faceId, now)
            val index = memoryCache.indexOf(cluster)
            if (index != -1) {
                memoryCache[index] = cluster.copy(
                    lastSeenTimestamp = now,
                    encounterCount = cluster.encounterCount + 1,
                )
            }
            return@withContext
        }

        // Existing insert path — unchanged from before this task.
        Log.d(TAG, "Caching new identity: $primaryIdentity")
        val currentTime = System.currentTimeMillis()
        val record = IdentityRecord(
            faceId = faceId,
            primaryIdentity = primaryIdentity,
            embedding = embedding,
            socialLinks = socialLinks.joinToString(","),
            backgroundData = backgroundData,
            firstSeenTimestamp = currentTime,
            lastSeenTimestamp = currentTime,
            encounterCount = 1,
        )
        identityDao.insertIdentity(record)
        memoryCache.add(record)
        AuditLogger.log(
            AuditLogger.Type.IDENTIFY,
            summary = "New identity: $primaryIdentity",
            details = JSONObject().put("faceId", faceId),
        )
        recordEncounter(faceId, currentTime)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.LocalFaceCacheMergeTest" --no-daemon`
Expected: both tests pass.

- [ ] **Step 5: Run the full test suite to verify no regressions**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:test --no-daemon`
Expected: all existing tests still pass (ApiParsingTest, LensoParsingTest, CaptureInterceptorTest, EncounterDaoTest, AuditDaoTest, DossierListViewModelTest, plus the new LivenessHeuristicTest, SharpnessTest, FaceQualityScorerTest, and now LocalFaceCacheMergeTest).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt \
        app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheMergeTest.kt
git commit -m "Re-id clustering: merge near-duplicates in LocalFaceCache.cacheIdentity (TDD)"
```

---

## Task 9: Final verification

- [ ] **Step 1: Full build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. Total test count rises from Phase 1's 10 to **~30** tests (6 liveness + 3 sharpness + 9 quality + 2 merge + the prior 10).

- [ ] **Step 2: APK present**

Run: `ls -la app/build/outputs/apk/debug/app-debug.apk`
Expected: non-empty.

- [ ] **Step 3: verifyMetaSdk still works**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew verifyMetaSdk --no-daemon 2>&1 | grep "gh.packages.url\|DAT SDK"`
Expected: the same WARN line that's been printed in Phase 0 and Phase 1.

- [ ] **Step 4: No commit needed** — validation only.

---

## Notes for the executor

- **JDK:** local environment has JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64`. Every gradle command prefixes `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- **vfat exec bit:** the repo lives on a vfat mount so `gradlew` lacks the unix exec bit. Always invoke as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / KSP 2.3.7 / Room 2.8.4 / Compose BOM 2026.05.00 / AGP 9.2.1. Don't downgrade.
- **ML Kit field names:** `Face.leftEyeOpenProbability`, `rightEyeOpenProbability`, `smilingProbability` are nullable `Float?`. `Face.headEulerAngleX/Y/Z` are non-null `Float`. Use `?: -1f` for the probability fallbacks (matching the `FaceSample` contract that says `-1f` means "unavailable").
- **`Flow.first(predicate)`:** if the test compiles without the helper at the bottom of `LocalFaceCacheMergeTest`, remove it. The helper is a fallback for older coroutines releases.
- **Don't touch the Meta DAT stub:** the existing `app/src/stub/java/com/facebook/wearables/dat/` is from Phase 0 and stays untouched.

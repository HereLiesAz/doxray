# Phase 2 — Quality & efficiency gate

**Date:** 2026-05-15
**Status:** Approved
**Predecessor:** [Phase 1 — Dossier surface](2026-05-15-phase-1-dossier-surface-design.md)
**Successor:** Phase 3 — More face-search providers (TBD)

---

## Context

Phases 0 and 1 made the doxxr pipeline real and visible: HTTP traffic is centralised + captured, the Lenso parser matches the documented schema, and every identification now has a visible dossier + encounter timeline + audit row.

But the pipeline is still indiscriminate. Every face the camera sees triggers the full identification chain: ML Kit tracking → 5-sec focus → embedding → 4 face-search providers → 2 background scrapers. That fires on:

- **Spoofs** — billboards, posters, phone screens, photos of photos. They satisfy the 5-second focus gate because the photo doesn't move out of frame, and they pass ML Kit detection just fine. Each spoof burns paid API credits and pollutes the local face cache with another "person".
- **Bad crops** — faces at 80° head turn, motion-blurred faces, faces in shadow, faces that are 12 px wide. The embedding model produces noise that doesn't match anyone, the remote APIs return nothing, and another junk `IdentityRecord` lands in the cache.
- **Near-duplicates** — the cosine-similarity match threshold is 0.85. If lighting/angle drifts enough that the same person's embedding lands at 0.83, the cache silently creates a second `IdentityRecord` for them. Over time, one real person fragments into 5–10 rows.

Phase 2 fixes all three: a temporal liveness heuristic before the search pipeline runs, a face-quality scorer before embedding, and a re-id clustering merge inside `LocalFaceCache.cacheIdentity` that consolidates near-duplicates.

The multi-model embedding ensemble that was on the original Phase 2 plan was dropped during brainstorm — it doubles inference cost for marginal benefit once the quality gate is in place.

---

## Goals

After Phase 2:

1. **Liveness gating:** `FaceTrackerManager` enables ML Kit `CLASSIFICATION_MODE_ALL`, collects per-tracking-ID samples over the existing 5-second focus window, and only fires `onFaceFocused` when a `LivenessHeuristic` says the samples look like a real face (variance in eye-open / smile / head Euler angles).
2. **Quality gating:** `LiveViewModel.processFocusedFace` runs a `FaceQualityScorer` on the cropped face before invoking `EmbeddingGenerator`. Low-quality crops are dropped with an audit log.
3. **Re-id clustering:** `LocalFaceCache.cacheIdentity` checks for a near-duplicate (cosine similarity ≥ 0.92, above the 0.85 "seen-before" threshold) before inserting. Hit → record an encounter against the existing dossier + bump its denormalised counters; miss → insert as today.
4. **Audit visibility:** Both rejections (liveness, quality) and merges (re-id) emit `AuditLogger` events that the existing Audit Log screen renders without any UI changes.

Non-goals:

- TFLite liveness model (chose heuristic-only).
- Multi-model embedding ensemble (dropped during brainstorm).
- Tunable thresholds via `BuildConfig` (static `const val` is enough for Phase 2; future phase can promote them).
- Compose UI tests.
- Per-screen reject reasons surfaced in the dossier UI — the audit log is the visibility surface.

---

## Architecture

### Pipeline gates

```
Frame from glasses
   ↓
ML Kit detection (now with CLASSIFICATION_MODE_ALL)
   ↓ per tracking ID, collect FaceSample over the 5-sec focus window:
   ↓   leftEyeOpenProbability, rightEyeOpenProbability,
   ↓   smilingProbability, headEulerAngle{X,Y,Z}
   ↓
[GATE 1] LivenessHeuristic.evaluate(samples) →
   ↓        Pass when ≥ 2 of:
   ↓          - max(eyeOpen) - min(eyeOpen) > EYE_VARIANCE_THRESHOLD     (blink)
   ↓          - eulerRange(X|Y|Z) > EULER_RANGE_DEG_THRESHOLD            (micro-movement)
   ↓          - max(smile) - min(smile) > SMILE_VARIANCE_THRESHOLD       (micro-expression)
   ↓ fail → audit REJECTED(reason="liveness"), reset, no callback
   ↓ pass
FaceTrackerManager.onFaceFocused(originalBytes, trackingId, croppedBytes)
   ↓
LiveViewModel.processFocusedFace
   ↓
[GATE 2] FaceQualityScorer.score(face, croppedBitmap) → QualityResult.Pass | Fail(reasons)
   ↓ fail → audit REJECTED(reason="quality", details), return early
   ↓ pass
EmbeddingGenerator.generateEmbedding              (unchanged)
   ↓
LocalFaceCache.findMatch(0.85)                    (unchanged: "seen before" lookup)
   ↓ no hit
Remote face-search + Yandex + bg-scrapers         (unchanged)
   ↓
LocalFaceCache.cacheIdentity
   ↓ NEW: findClusterMatch(0.92) first
   ↓   hit  → merge: encounterDao.insert(new Encounter for existingFaceId)
   ↓                 + identityDao.recordEncounter(existingFaceId, now)
   ↓                 + AuditLogger.log(IDENTIFY, "Merged into existing dossier ${existing.name}", …)
   ↓   miss → insert new IdentityRecord (existing path)
```

### Component layout

```
com.hereliesaz.doxray.quality/                 ← new package
├── FaceSample.kt                              ← data class shared with FaceTrackerManager
├── LivenessHeuristic.kt                       ← evaluate(samples): LivenessResult
├── FaceQualityScorer.kt                       ← score(faceProps): QualityResult
└── Sharpness.kt                               ← laplacianVariance(Bitmap): Float
```

All four files are pure Kotlin (no Android types beyond `Bitmap` in `Sharpness`) so they unit-test without Robolectric. `Sharpness` is the only one that needs `Bitmap` access; its tests use a tiny stub bitmap from `android.graphics.Bitmap.createBitmap` which Robolectric provides, so its test runs under the same Robolectric pattern already established in `EncounterDaoTest`.

### Audit type addition

```kotlin
object AuditLogger {
    enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE, REJECTED }  // new value
    …
}
```

Adding a value to the enum is forward-additive: `AuditEvent.type` is stored as `String`, so older rows simply don't have `REJECTED`. No schema migration needed. The existing `AuditLogScreen` already chip-renders any `type` value verbatim — no UI work.

---

## Component specifications

### `quality/FaceSample.kt`

```kotlin
package com.hereliesaz.doxray.quality

data class FaceSample(
    val leftEyeOpen: Float,    // 0..1; -1 if classification unavailable
    val rightEyeOpen: Float,
    val smiling: Float,
    val eulerX: Float,         // pitch  (head up/down)
    val eulerY: Float,         // yaw    (head left/right)
    val eulerZ: Float,         // roll   (head tilt)
    val timestampMs: Long,
)
```

`FaceTrackerManager` builds one of these per frame from the ML Kit `Face` object. `ML Kit Face.smilingProbability` and `leftEyeOpenProbability` / `rightEyeOpenProbability` may be `null` when classification is disabled or low-confidence — convert null → `-1f` so the heuristic can ignore them without special null-handling.

### `quality/LivenessHeuristic.kt`

```kotlin
package com.hereliesaz.doxray.quality

sealed class LivenessResult {
    object Pass : LivenessResult()
    data class Fail(val reasonDetails: String) : LivenessResult()
}

object LivenessHeuristic {
    private const val EYE_VARIANCE_THRESHOLD = 0.10f
    private const val EULER_RANGE_DEG_THRESHOLD = 3f
    private const val SMILE_VARIANCE_THRESHOLD = 0.05f
    private const val MIN_SAMPLES = 5

    fun evaluate(samples: List<FaceSample>): LivenessResult {
        if (samples.size < MIN_SAMPLES) return LivenessResult.Fail("only ${samples.size} samples")

        val eyeVariance = run {
            val v = samples.map { (it.leftEyeOpen + it.rightEyeOpen) / 2f }.filter { it >= 0f }
            if (v.size < MIN_SAMPLES) 0f else v.max() - v.min()
        }
        val eulerRange = run {
            val xRange = samples.map { it.eulerX }.let { it.max() - it.min() }
            val yRange = samples.map { it.eulerY }.let { it.max() - it.min() }
            val zRange = samples.map { it.eulerZ }.let { it.max() - it.min() }
            maxOf(xRange, yRange, zRange)
        }
        val smileVariance = run {
            val v = samples.map { it.smiling }.filter { it >= 0f }
            if (v.size < MIN_SAMPLES) 0f else v.max() - v.min()
        }

        val passes = listOf(
            eyeVariance > EYE_VARIANCE_THRESHOLD,
            eulerRange > EULER_RANGE_DEG_THRESHOLD,
            smileVariance > SMILE_VARIANCE_THRESHOLD,
        ).count { it }

        return if (passes >= 2) LivenessResult.Pass
        else LivenessResult.Fail(
            "eyeVar=$eyeVariance eulerRange=$eulerRange smileVar=$smileVariance"
        )
    }
}
```

### `quality/Sharpness.kt`

```kotlin
package com.hereliesaz.doxray.quality

import android.graphics.Bitmap

/**
 * Laplacian variance on the bitmap's luminance channel. Higher = sharper.
 * Pragmatic measure, not perfect on Bayer-mosaiced sensors, but adequate
 * for quality gating against motion blur.
 */
object Sharpness {
    fun laplacianVariance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 0f
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to luminance and apply 3x3 Laplacian kernel
        val lum = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            0.299f * ((p shr 16) and 0xff) +
                0.587f * ((p shr 8) and 0xff) +
                0.114f * (p and 0xff)
        }
        val responses = ArrayList<Float>(((w - 2) * (h - 2)))
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val v = -lum[i - w] - lum[i - 1] + 4f * lum[i] - lum[i + 1] - lum[i + w]
                responses += v
            }
        }
        val mean = responses.average().toFloat()
        var sumSq = 0f
        for (v in responses) sumSq += (v - mean) * (v - mean)
        return sumSq / responses.size
    }
}
```

### `quality/FaceQualityScorer.kt`

```kotlin
package com.hereliesaz.doxray.quality

import android.graphics.Bitmap

sealed class QualityResult {
    object Pass : QualityResult()
    data class Fail(val reasons: List<String>) : QualityResult()
}

/**
 * Inputs are pre-computed by the caller (LiveViewModel) so this stays
 * trivially unit-testable without Android dependencies.
 */
data class FaceQualityInput(
    val faceFraction: Float,   // face bbox area / image area
    val eulerX: Float,
    val eulerY: Float,
    val eulerZ: Float,
    val sharpness: Float,      // Sharpness.laplacianVariance(crop)
    val meanLuminance: Float,  // 0..255 average over the face crop
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
        if (input.faceFraction < MIN_FACE_FRAC) reasons += "too-small (${"%.2f".format(input.faceFraction)})"
        if (kotlin.math.abs(input.eulerX) > MAX_EULER_X) reasons += "pitch ${input.eulerX}°"
        if (kotlin.math.abs(input.eulerY) > MAX_EULER_Y) reasons += "yaw ${input.eulerY}°"
        if (kotlin.math.abs(input.eulerZ) > MAX_EULER_Z) reasons += "roll ${input.eulerZ}°"
        if (input.sharpness < MIN_SHARPNESS) reasons += "blurry (${"%.1f".format(input.sharpness)})"
        if (input.meanLuminance < MIN_LUMINANCE) reasons += "too-dark (${"%.0f".format(input.meanLuminance)})"
        if (input.meanLuminance > MAX_LUMINANCE) reasons += "too-bright (${"%.0f".format(input.meanLuminance)})"
        return if (reasons.isEmpty()) QualityResult.Pass else QualityResult.Fail(reasons)
    }

    /**
     * Convenience helper that decodes the bitmap, computes the inputs that depend
     * on pixels (sharpness + luminance), and forwards to [score]. Caller still
     * supplies frame metadata (face fraction, Euler) from ML Kit.
     */
    fun scoreFromBitmap(
        bitmap: Bitmap,
        faceFraction: Float,
        eulerX: Float,
        eulerY: Float,
        eulerZ: Float,
    ): QualityResult {
        val sharpness = Sharpness.laplacianVariance(bitmap)
        val luminance = bitmap.meanLuminance()
        return score(FaceQualityInput(faceFraction, eulerX, eulerY, eulerZ, sharpness, luminance))
    }
}

private fun Bitmap.meanLuminance(): Float {
    val w = width; val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    var sum = 0.0
    for (p in px) {
        sum += 0.299 * ((p shr 16) and 0xff) +
            0.587 * ((p shr 8) and 0xff) +
            0.114 * (p and 0xff)
    }
    return (sum / px.size).toFloat()
}
```

### `api/FaceTrackerManager.kt` (modify)

Three changes:

1. Builder configuration becomes:
   ```kotlin
   FaceDetectorOptions.Builder()
       .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
       .enableTracking()
       .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
       .build()
   ```
2. Add a `private val samples = mutableMapOf<Int, MutableList<FaceSample>>()` parallel to `trackedFaces` / `searchedFaces`. On every frame, append the current `FaceSample` for each in-frame tracking ID. Trim the list to the last ~30 entries (~30 fps × 5 sec) to bound memory.
3. At the 5-second focus gate (existing `if (duration >= FOCUS_THRESHOLD_MS)`), call `LivenessHeuristic.evaluate(samples[trackingId].orEmpty())` before the existing `listener.onFaceFocused(...)`. On `Fail`:
   - Log audit `REJECTED` with reason+details (the heuristic's `reasonDetails` string + tracking ID + sample count).
   - Add `trackingId` to `searchedFaces` to prevent re-evaluation while the same tracker ID persists.
   - Skip the listener callback.
   On `Pass`: existing callback fires.
4. In the `removedIds` cleanup path, also clear `samples[id]`.

### `ui/live/LiveViewModel.kt` (modify)

In `processFocusedFace`, between `BitmapFactory.decodeByteArray(...)` (we'll need to decode here too) and `embeddingGenerator.generateEmbedding(faceCrop)`:

```kotlin
val cropBitmap = BitmapFactory.decodeByteArray(faceCrop, 0, faceCrop.size)
val frameBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
val faceFrac = (cropBitmap.width.toFloat() * cropBitmap.height) /
               (frameBitmap.width.toFloat() * frameBitmap.height)

// Euler angles aren't available here — FaceTrackerManager has them but doesn't pass them through.
// Solution: extend the FaceFocusListener.onFaceFocused signature to include eulerX/Y/Z.
val quality = FaceQualityScorer.scoreFromBitmap(cropBitmap, faceFrac, eulerX, eulerY, eulerZ)
when (quality) {
    is QualityResult.Fail -> {
        AuditLogger.log(
            AuditLogger.Type.REJECTED,
            "Low-quality crop skipped",
            JSONObject().apply {
                put("reason", "quality")
                put("trackingId", trackingId)
                put("failures", JSONArray(quality.reasons))
            }
        )
        return
    }
    QualityResult.Pass -> { /* continue to embedding */ }
}
```

The Euler angles need to thread through from `FaceTrackerManager`. Smallest viable change: extend `FaceFocusListener.onFaceFocused(imageBytes, trackingId, faceCrop, eulerX, eulerY, eulerZ)` and update the one callsite in `LiveViewModel`. Defaults of `0f` would lose information at the gate, so the change is real.

### `api/LocalFaceCache.kt` (modify)

Add a new private constant `CLUSTER_THRESHOLD = 0.92f` and a new method:

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

Modify `cacheIdentity` so the FIRST step is the cluster check:

```kotlin
suspend fun cacheIdentity(...) = withContext(Dispatchers.IO) {
    val cluster = findClusterMatch(embedding)
    if (cluster != null) {
        // Merge path. Order matches the existing findMatch convention
        // established in commit 360b857: DAO denormalised update first,
        // audit second (so it survives any subsequent DB failure),
        // encounter-row insert last.
        val now = System.currentTimeMillis()
        identityDao.recordEncounter(cluster.faceId, now)
        AuditLogger.log(
            AuditLogger.Type.IDENTIFY,
            "Merged into existing dossier: ${cluster.primaryIdentity}",
            JSONObject().apply {
                put("existingFaceId", cluster.faceId)
                put("incomingFaceId", faceId)
                put("primaryIdentity", primaryIdentity)
            }
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
    // Insert path (existing code unchanged)
    Log.d(TAG, "Caching new identity: $primaryIdentity")
    // ... existing body
}
```

The merge path **never** overwrites the existing record's `primaryIdentity` / `socialLinks` / `backgroundData` with the incoming values — the existing dossier wins. The audit row carries the incoming data for later forensic review if needed.

### `audit/AuditLogger.kt` (modify)

Add `REJECTED` to the enum:

```kotlin
enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE, REJECTED }
```

Single-line change.

---

## Testing

### Unit tests (`app/src/test/`)

- `LivenessHeuristicTest` — plain JUnit:
  - Empty list → Fail
  - Below `MIN_SAMPLES` → Fail
  - Static samples (a photo) — zero variance in everything → Fail
  - Sample sequence with blink → Pass
  - Sample sequence with head tilt → Pass
  - Single-channel pass (only eye blink) → Fail (needs 2 of 3)
  - Two-channel pass (eye blink + smile change) → Pass

- `FaceQualityScorerTest` — plain JUnit, all paths exercised via `FaceQualityInput`:
  - Pass case (all metrics in range)
  - Each individual fail: too-small, pitch, yaw, roll, blurry, too-dark, too-bright
  - Multi-reason fail: returns all reasons

- `SharpnessTest` — Robolectric (same pattern as `EncounterDaoTest`):
  - Solid-colour Bitmap → variance ≈ 0
  - Checkerboard Bitmap → variance > 0

- `LocalFaceCacheMergeTest` — Robolectric:
  - Insert a base identity, cache a second identity with a near-duplicate embedding (similarity > 0.92) → assert only 1 `IdentityRecord` in DB, `encounterCount = 2`, 2 `Encounter` rows pointing at the original `faceId`
  - Cache with a clearly-different embedding (similarity < 0.92) → assert 2 `IdentityRecord`s, both with their own encounters

### Manual / smoke

- Once on a real device with the debug APK installed, point the camera at a printed face (photo of a face) for 6+ seconds. Expect:
  - No remote search fires.
  - An audit row appears in the Audit Log screen with `[REJECTED]` chip and reason `liveness`.
- Then point at a real moving face for 6+ seconds. Expect:
  - Normal identification fires.
  - No `REJECTED` row.

---

## Decisions made during brainstorm

- **Multi-model embedding ensemble dropped.** Lowest-ROI item. Quality gating addresses the same underlying issue (junk embeddings) more cheaply.
- **Liveness via heuristic only.** No bundled TFLite liveness model. ~80% accuracy is the trade-off; can be upgraded later if the heuristic proves insufficient.
- **Re-id semantics = MERGE.** Cluster-matched inserts collapse into the existing dossier; the new record is never created. Encounter is still recorded against the existing faceId so the timeline stays accurate. Loser metadata is logged in the audit row for forensic recovery.
- **Audit type:** new `REJECTED` enum value (not encoded inside `IDENTIFY`). Discoverable in the existing Audit Log screen with zero UI work.

---

## Out-of-scope for future phases

- **TFLite-based liveness.** Phase 2.5 if the heuristic's accuracy is insufficient in real use. Hooks left: `LivenessHeuristic` is a single object with a stable `evaluate(samples)` signature — easy to wrap behind an interface and add a `TfliteLivenessModel` implementation later.
- **Per-build threshold overrides.** Promote the `const val`s in `LivenessHeuristic` and `FaceQualityScorer` to `BuildConfig` fields when tuning across release variants becomes desirable.
- **Quality-aware embedding.** Right now a borderline-quality crop either passes the gate (and is treated as full-confidence) or fails (and is discarded). A future enhancement could feed the quality score into the embedding-comparison threshold (degrade gracefully for marginal crops).
- **Multi-model ensemble.** Re-evaluate after Phase 2 production data shows whether match-rate is still a bottleneck.
- **Spoof reasoning surfaced in dossier UI.** Phase 2 only logs to audit. A later phase could attach a `lastRejectionReason` to the in-frame UI overlay so the operator sees why something was skipped.

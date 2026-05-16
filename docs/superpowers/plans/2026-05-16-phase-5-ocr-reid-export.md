# Phase 5 — OCR, Re-ID Surfacing, DB Export/Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OCR of the chest+head region during identification, surface cross-session re-ID hits with their own log line and audit type, and let the user export the local dossier DB to a ZIP of CSVs (and re-import a previous export with insert-only-if-new merge semantics).

**Architecture:** Three independent extensions: (1) `OcrService` invoked in parallel with the embedding step in `LiveViewModel.processFocusedFace`; (2) `LocalFaceCache.findMatch` distinguishes a re-encounter from a fresh match via elapsed-time threshold and emits a new `REENCOUNTER` audit type; (3) `DatabaseExporter` / `DatabaseImporter` round-trip the three Room tables through a ZIP using SAF (`ContentResolver.openOutputStream` / `openInputStream`) wired into `MainActivity` and triggered from new NavRail menu items.

**Tech Stack:** Kotlin 2.3.21 / Room 2.8.4 / OkHttp 5.3.2 / ML Kit `com.google.mlkit:text-recognition:16.0.1` (Latin script) / Jsoup 1.22.2 / JUnit 4 + Robolectric 4.13 / AzNavRail.

**Pre-existing infra (no work needed):** `LocalFaceCache.recordEncounter` already populates lat/lon. `DossierDetailScreen.EncounterRow` already renders the 📍 chip. `LocationService.getLastLocation()` already exists. Phase 5 plan does not touch these.

---

## Task 1: OcrService TDD

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/OcrService.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/api/OcrServiceTest.kt`
- Modify: `app/build.gradle.kts` (add `text-recognition` dep)

- [ ] **Step 1: Add ML Kit text-recognition dependency**

Edit `app/build.gradle.kts`, in the `dependencies { }` block, add (next to the existing `com.google.mlkit:face-detection` line):

```kotlin
    implementation("com.google.mlkit:text-recognition:16.0.1")
```

- [ ] **Step 2: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/OcrServiceTest.kt`:

```kotlin
package com.hereliesaz.doxray.api

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OcrServiceTest {

    @Test
    fun `expandBbox grows centered face 2x down and 0_5x lateral`() {
        // 1000x1000 frame, face at center 400x400-600x600 (200x200, centered)
        val face = Rect(400, 400, 600, 600) // w=200, h=200
        val expanded = OcrService.expandBbox(face, imageWidth = 1000, imageHeight = 1000)
        // Expansion: width grows 0.5x on each side (+100 left, +100 right);
        // height grows 2x downward only (+400 below); top unchanged.
        assertEquals(300, expanded.left)
        assertEquals(400, expanded.top)
        assertEquals(700, expanded.right)
        assertEquals(1000, expanded.bottom) // clamped (would be 600 + 400 = 1000)
    }

    @Test
    fun `expandBbox clamps to frame edges`() {
        // Face at bottom-right corner of a 500x500 frame
        val face = Rect(400, 400, 500, 500) // w=100, h=100, edge-aligned
        val expanded = OcrService.expandBbox(face, imageWidth = 500, imageHeight = 500)
        assertEquals(350, expanded.left)   // 400 - 50
        assertEquals(400, expanded.top)
        assertEquals(500, expanded.right)  // 500 + 50 clamped to 500
        assertEquals(500, expanded.bottom) // 500 + 200 clamped to 500
    }

    @Test
    fun `extract returns null for empty image bytes`() = kotlinx.coroutines.runBlocking {
        val service = OcrService()
        val result = service.extract(ByteArray(0), Rect(0, 0, 10, 10))
        assertNull(result)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.OcrServiceTest" --no-daemon`
Expected: `Unresolved reference 'OcrService'`.

- [ ] **Step 4: Implement OcrService**

`app/src/main/java/com/hereliesaz/doxray/api/OcrService.kt`:

```kotlin
package com.hereliesaz.doxray.api

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

class OcrService {

    private val TAG = "OcrService"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class OcrBlock(val text: String, val pixelHeight: Int) {
        fun toJson(): JSONObject = JSONObject()
            .put("text", text)
            .put("pixelHeight", pixelHeight)
    }

    data class OcrResult(
        val primaryLine: String,
        val allText: String,
        val blocks: List<OcrBlock>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("primaryLine", primaryLine)
            .put("allText", allText)
            .put("blocks", JSONArray(blocks.map { it.toJson() }))
    }

    suspend fun extract(imageBytes: ByteArray, faceBbox: Rect): OcrResult? {
        if (imageBytes.isEmpty()) return null
        val frame = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        val region = expandBbox(faceBbox, frame.width, frame.height)
        if (region.width() <= 0 || region.height() <= 0) return null
        val crop = try {
            Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height())
        } catch (e: Exception) {
            Log.w(TAG, "OCR crop failed", e)
            return null
        }
        return try {
            val image = InputImage.fromBitmap(crop, 0)
            val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            } ?: return null
            val blocks = text.textBlocks.map { b ->
                OcrBlock(
                    text = b.text,
                    pixelHeight = b.boundingBox?.height() ?: 0,
                )
            }
            if (blocks.isEmpty()) return null
            val primary = blocks.maxByOrNull { it.pixelHeight }!!.text
            OcrResult(
                primaryLine = primary,
                allText = blocks.joinToString("\n") { it.text },
                blocks = blocks,
            )
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed", e)
            null
        }
    }

    companion object {
        fun expandBbox(face: Rect, imageWidth: Int, imageHeight: Int): Rect {
            val w = face.width()
            val h = face.height()
            val lateral = (w * 0.5f).toInt()
            val downward = (h * 2.0f).toInt()
            return Rect(
                (face.left - lateral).coerceAtLeast(0),
                face.top.coerceAtLeast(0),
                (face.right + lateral).coerceAtMost(imageWidth),
                (face.bottom + downward).coerceAtMost(imageHeight),
            )
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.OcrServiceTest" --no-daemon`
Expected: 3/3 pass.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/hereliesaz/doxray/api/OcrService.kt \
        app/src/test/java/com/hereliesaz/doxray/api/OcrServiceTest.kt
git commit -m "Add OcrService with expanded chest+head bbox (TDD, 3 cases)"
```

---

## Task 2: Schema migration v3 → v4 (add visibleText column)

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/db/IdentityRecord.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/db/Migration_3_4.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt`

- [ ] **Step 1: Add `visibleText` to IdentityRecord**

Edit `app/src/main/java/com/hereliesaz/doxray/db/IdentityRecord.kt`. Replace the `data class IdentityRecord(...)` body (and its `equals`/`hashCode` overrides) with:

```kotlin
@Entity(tableName = "identity_records")
data class IdentityRecord(
    @PrimaryKey
    val faceId: String,
    val primaryIdentity: String,
    val embedding: FloatArray,
    val socialLinks: String,
    val backgroundData: String,
    val firstSeenTimestamp: Long,
    val lastSeenTimestamp: Long,
    val encounterCount: Int,
    val visibleText: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdentityRecord

        if (faceId != other.faceId) return false
        if (primaryIdentity != other.primaryIdentity) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (socialLinks != other.socialLinks) return false
        if (backgroundData != other.backgroundData) return false
        if (firstSeenTimestamp != other.firstSeenTimestamp) return false
        if (lastSeenTimestamp != other.lastSeenTimestamp) return false
        if (encounterCount != other.encounterCount) return false
        if (visibleText != other.visibleText) return false

        return true
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + primaryIdentity.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + socialLinks.hashCode()
        result = 31 * result + backgroundData.hashCode()
        result = 31 * result + firstSeenTimestamp.hashCode()
        result = 31 * result + lastSeenTimestamp.hashCode()
        result = 31 * result + encounterCount
        result = 31 * result + (visibleText?.hashCode() ?: 0)
        return result
    }
}
```

- [ ] **Step 2: Create Migration_3_4**

`app/src/main/java/com/hereliesaz/doxray/db/Migration_3_4.kt`:

```kotlin
package com.hereliesaz.doxray.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `visibleText` column to `identity_records` for Phase 5 OCR.
 * Existing rows get NULL — no backfill.
 */
object Migration_3_4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE identity_records ADD COLUMN visibleText TEXT")
    }
}
```

- [ ] **Step 3: Wire migration into AppDatabase**

Edit `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt`. Change:

```kotlin
@Database(entities = [IdentityRecord::class, Encounter::class, AuditEvent::class], version = 3, exportSchema = true)
```

to:

```kotlin
@Database(entities = [IdentityRecord::class, Encounter::class, AuditEvent::class], version = 4, exportSchema = true)
```

And in the `Room.databaseBuilder(...)` call, change:

```kotlin
.addMigrations(Migration_2_3)
```

to:

```kotlin
.addMigrations(Migration_2_3, Migration_3_4)
```

- [ ] **Step 4: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. Note: Room will export a new schema JSON to `app/schemas/com.hereliesaz.doxray.db.AppDatabase/4.json`.

- [ ] **Step 5: Verify tests still pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --no-daemon`
Expected: 55/55 pass (52 prior + 3 new OCR tests from Task 1).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/db/IdentityRecord.kt \
        app/src/main/java/com/hereliesaz/doxray/db/Migration_3_4.kt \
        app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt \
        app/schemas/com.hereliesaz.doxray.db.AppDatabase/4.json
git commit -m "Add visibleText column to IdentityRecord (DB v3 to v4 migration)"
```

---

## Task 3: Re-ID surfacing in LocalFaceCache.findMatch

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheReencounterTest.kt`

- [ ] **Step 1: Add REENCOUNTER to AuditLogger.Type**

Edit `app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt`. Change:

```kotlin
enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE, REJECTED }
```

to:

```kotlin
enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE, REJECTED, REENCOUNTER }
```

- [ ] **Step 2: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheReencounterTest.kt`:

```kotlin
package com.hereliesaz.doxray.api

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.db.IdentityRecord
import com.hereliesaz.doxray.location.LocationService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalFaceCacheReencounterTest {

    private lateinit var db: AppDatabase
    private lateinit var cache: LocalFaceCache

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        AuditLogger.init(db.auditDao())
        cache = LocalFaceCache(
            identityDao = db.identityDao(),
            encounterDao = db.encounterDao(),
            locationService = LocationService(ctx),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `findMatch with stale lastSeen emits REENCOUNTER audit`() = runBlocking {
        val embedding = FloatArray(128) { i -> if (i == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        val oldLastSeen = now - 2 * 60 * 60 * 1000L // 2 hours ago
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "stale", primaryIdentity = "Stale Subject", embedding = embedding,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = oldLastSeen, lastSeenTimestamp = oldLastSeen, encounterCount = 1,
        ))
        cache.loadFromDatabase()

        val match = cache.findMatch(embedding)
        assertEquals("stale", match?.faceId)

        // Audit log fires fire-and-forget; give the coroutine a moment.
        Thread.sleep(200)
        val events = runBlocking { db.auditDao().observeRecent(limit = 50).let { flow ->
            kotlinx.coroutines.flow.first(flow)
        } }
        val types = events.map { it.type }
        assertTrue("Expected REENCOUNTER audit, got $types", types.contains("REENCOUNTER"))
    }

    @Test
    fun `findMatch with recent lastSeen emits IDENTIFY audit not REENCOUNTER`() = runBlocking {
        val embedding = FloatArray(128) { i -> if (i == 0) 1f else 0f }
        val now = System.currentTimeMillis()
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "fresh", primaryIdentity = "Fresh Subject", embedding = embedding,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = now - 5_000, lastSeenTimestamp = now - 5_000, encounterCount = 1,
        ))
        cache.loadFromDatabase()

        val match = cache.findMatch(embedding)
        assertEquals("fresh", match?.faceId)

        Thread.sleep(200)
        val events = runBlocking { db.auditDao().observeRecent(limit = 50).let { flow ->
            kotlinx.coroutines.flow.first(flow)
        } }
        val types = events.map { it.type }
        assertTrue("Expected IDENTIFY but not REENCOUNTER, got $types",
            types.contains("IDENTIFY") && !types.contains("REENCOUNTER"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.LocalFaceCacheReencounterTest" --no-daemon`
Expected: stale test fails — REENCOUNTER not in audit list (because current code always logs IDENTIFY).

- [ ] **Step 4: Modify LocalFaceCache.findMatch**

Edit `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`. Find the audit-log block inside `findMatch` (after a `bestMatch` has been found):

```kotlin
            AuditLogger.log(
                AuditLogger.Type.IDENTIFY,
                summary = "Cache hit: ${bestMatch.primaryIdentity}",
                details = JSONObject().apply {
                    put("faceId", bestMatch.faceId)
                    put("similarity", highestSimilarity)
                },
            )
```

Replace with:

```kotlin
            val elapsedMs = currentTime - bestMatch.lastSeenTimestamp
            val isReencounter = elapsedMs > 60 * 60 * 1000L // > 1 hour since last seen
            AuditLogger.log(
                if (isReencounter) AuditLogger.Type.REENCOUNTER else AuditLogger.Type.IDENTIFY,
                summary = if (isReencounter)
                    "Re-encountered ${bestMatch.primaryIdentity} (elapsed ${elapsedMs / 1000}s)"
                else
                    "Cache hit: ${bestMatch.primaryIdentity}",
                details = JSONObject().apply {
                    put("faceId", bestMatch.faceId)
                    put("similarity", highestSimilarity)
                    put("elapsedMs", elapsedMs)
                },
            )
            if (isReencounter) {
                Log.i(TAG, "Re-encountered ${bestMatch.primaryIdentity} (last seen ${elapsedMs / 1000}s ago)")
            }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.LocalFaceCacheReencounterTest" --no-daemon`
Expected: 2/2 pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt \
        app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt \
        app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheReencounterTest.kt
git commit -m "Surface cross-session re-ID hits via REENCOUNTER audit type"
```

---

## Task 4: OCR call-site wiring (FaceTracker bbox + LiveViewModel + cache write)

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/FaceTrackerManager.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`

- [ ] **Step 1: Pass face bbox through FaceTrackerManager**

Edit `app/src/main/java/com/hereliesaz/doxray/api/FaceTrackerManager.kt`. Change the `FaceFocusListener` interface from:

```kotlin
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
```

to:

```kotlin
    interface FaceFocusListener {
        fun onFaceFocused(
            imageBytes: ByteArray,
            trackingId: Int,
            faceCrop: ByteArray,
            faceBbox: android.graphics.Rect,
            eulerX: Float,
            eulerY: Float,
            eulerZ: Float,
        )
        fun onFaceLost(trackingId: Int)
        fun onError(e: Exception)
    }
```

And find the `listener.onFaceFocused(...)` call inside `processFrame` (it's the one in the FOCUS_THRESHOLD_MS branch). Change:

```kotlin
                                listener.onFaceFocused(
                                    imageBytes = imageBytes,
                                    trackingId = trackingId,
                                    faceCrop = faceCropBytes,
                                    eulerX = face.headEulerAngleX,
                                    eulerY = face.headEulerAngleY,
                                    eulerZ = face.headEulerAngleZ,
                                )
```

to:

```kotlin
                                listener.onFaceFocused(
                                    imageBytes = imageBytes,
                                    trackingId = trackingId,
                                    faceCrop = faceCropBytes,
                                    faceBbox = face.boundingBox,
                                    eulerX = face.headEulerAngleX,
                                    eulerY = face.headEulerAngleY,
                                    eulerZ = face.headEulerAngleZ,
                                )
```

- [ ] **Step 2: Plumb bbox into LiveViewModel.processFocusedFace**

Edit `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`. In the imports, add (near other `android.graphics.*` imports):

```kotlin
import android.graphics.Rect
import com.hereliesaz.doxray.api.OcrService
```

In the field declarations (next to other `private val` services), add:

```kotlin
    private val ocrService = OcrService()
```

Find the `processFrame` callback's `onFaceFocused` override. Change its signature and the inner call:

```kotlin
                        override fun onFaceFocused(
                            imageBytes: ByteArray,
                            trackingId: Int,
                            faceCrop: ByteArray,
                            eulerX: Float,
                            eulerY: Float,
                            eulerZ: Float,
                        ) {
                            viewModelScope.launch {
                                processFocusedFace(imageBytes, faceCrop, trackingId, eulerX, eulerY, eulerZ)
                            }
                        }
```

to:

```kotlin
                        override fun onFaceFocused(
                            imageBytes: ByteArray,
                            trackingId: Int,
                            faceCrop: ByteArray,
                            faceBbox: Rect,
                            eulerX: Float,
                            eulerY: Float,
                            eulerZ: Float,
                        ) {
                            viewModelScope.launch {
                                processFocusedFace(imageBytes, faceCrop, faceBbox, trackingId, eulerX, eulerY, eulerZ)
                            }
                        }
```

Change `processFocusedFace` signature from:

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

to:

```kotlin
    private suspend fun processFocusedFace(
        imageBytes: ByteArray,
        faceCrop: ByteArray,
        faceBbox: Rect,
        trackingId: Int,
        eulerX: Float,
        eulerY: Float,
        eulerZ: Float,
    ) {
```

Inside `processFocusedFace`, immediately after `val embedding = embeddingGenerator.generateEmbedding(faceCrop)`, change to:

```kotlin
            val embedding = embeddingGenerator.generateEmbedding(faceCrop)
            val ocrResult = ocrService.extract(imageBytes, faceBbox)
            if (ocrResult != null) {
                appendLog("Visible text: \"${ocrResult.primaryLine}\"")
            }
```

Then find every call to `performDeepBackgroundScrape(...)` inside this function and `localFaceCache.cacheIdentity(...)` (the latter lives in `performDeepBackgroundScrape`). Modify `performDeepBackgroundScrape` to accept the OCR result. Change its signature from:

```kotlin
    private suspend fun performDeepBackgroundScrape(
        primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>,
    ) {
```

to:

```kotlin
    private suspend fun performDeepBackgroundScrape(
        primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>,
        ocrResult: OcrService.OcrResult?,
    ) {
```

Inside `performDeepBackgroundScrape`, immediately before the `localFaceCache.cacheIdentity(...)` call, add:

```kotlin
        if (ocrResult != null) {
            bgDataJson.put("ocr", ocrResult.toJson())
        }
```

And change the `localFaceCache.cacheIdentity(...)` call from:

```kotlin
        localFaceCache.cacheIdentity(
            faceId = faceId, embedding = embedding,
            primaryIdentity = primaryIdentity, socialLinks = socialLinks,
            backgroundData = bgDataJson.toString(),
        )
```

to:

```kotlin
        localFaceCache.cacheIdentity(
            faceId = faceId, embedding = embedding,
            primaryIdentity = primaryIdentity, socialLinks = socialLinks,
            backgroundData = bgDataJson.toString(),
            visibleText = ocrResult?.primaryLine,
        )
```

Update the two call sites of `performDeepBackgroundScrape` in `processFocusedFace`. Find:

```kotlin
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","))
```

Replace with:

```kotlin
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","), ocrResult)
```

And find:

```kotlin
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks)
```

Replace with:

```kotlin
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks, ocrResult)
```

- [ ] **Step 3: Add visibleText parameter to LocalFaceCache.cacheIdentity**

Edit `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`. Change the `cacheIdentity` signature from:

```kotlin
    suspend fun cacheIdentity(
        faceId: String,
        embedding: FloatArray,
        primaryIdentity: String,
        socialLinks: List<String>,
        backgroundData: String,
    ) = withContext(Dispatchers.IO) {
```

to:

```kotlin
    suspend fun cacheIdentity(
        faceId: String,
        embedding: FloatArray,
        primaryIdentity: String,
        socialLinks: List<String>,
        backgroundData: String,
        visibleText: String? = null,
    ) = withContext(Dispatchers.IO) {
```

Inside `cacheIdentity`, find the `IdentityRecord(...)` construction (in the "Existing insert path" block):

```kotlin
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
```

Add the new field:

```kotlin
        val record = IdentityRecord(
            faceId = faceId,
            primaryIdentity = primaryIdentity,
            embedding = embedding,
            socialLinks = socialLinks.joinToString(","),
            backgroundData = backgroundData,
            firstSeenTimestamp = currentTime,
            lastSeenTimestamp = currentTime,
            encounterCount = 1,
            visibleText = visibleText,
        )
```

- [ ] **Step 4: Verify full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`. All 57 tests pass (52 prior + 3 OCR + 2 reencounter).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/FaceTrackerManager.kt \
        app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt \
        app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt
git commit -m "Wire OcrService into focused-face pipeline; persist visibleText"
```

---

## Task 5: DossierDetailScreen — render visibleText + re-encountered badge

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`

- [ ] **Step 1: Render visibleText under primaryIdentity**

Edit `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`. Find the header `Column { ... }` block that renders identity name + encounter count. Change:

```kotlin
                Column {
                    Text(text = identity.primaryIdentity, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(text = "${identity.encounterCount} encounter(s)", fontSize = 14.sp)
                    Text(
                        text = "First seen: " + formatAbsolute(identity.firstSeenTimestamp),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "Last seen: " + DateUtils.getRelativeTimeSpanString(identity.lastSeenTimestamp).toString(),
                        fontSize = 12.sp,
                    )
                }
```

to:

```kotlin
                Column {
                    Text(text = identity.primaryIdentity, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val isReencountered = identity.lastSeenTimestamp - identity.firstSeenTimestamp > 24 * 60 * 60 * 1000L
                    val encounterLine = if (isReencountered)
                        "${identity.encounterCount} encounter(s) — re-encountered"
                    else
                        "${identity.encounterCount} encounter(s)"
                    Text(text = encounterLine, fontSize = 14.sp)
                    identity.visibleText?.takeIf { it.isNotBlank() }?.let { vt ->
                        Text(text = "Visible text: \"$vt\"", fontSize = 13.sp)
                    }
                    Text(
                        text = "First seen: " + formatAbsolute(identity.firstSeenTimestamp),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "Last seen: " + DateUtils.getRelativeTimeSpanString(identity.lastSeenTimestamp).toString(),
                        fontSize = 12.sp,
                    )
                }
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt
git commit -m "Show visibleText and re-encountered badge in dossier detail"
```

---

## Task 6: DatabaseExporter TDD

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/db/DatabaseExporter.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/db/DatabaseExporterTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/db/DatabaseExporterTest.kt`:

```kotlin
package com.hereliesaz.doxray.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DatabaseExporterTest {

    private lateinit var db: AppDatabase
    private lateinit var exporter: DatabaseExporter

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        exporter = DatabaseExporter(db.identityDao(), db.encounterDao(), db.auditDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `export produces zip with three csvs and manifest`() = runBlocking {
        val emb = FloatArray(4) { i -> 0.1f * (i + 1) }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "f1", primaryIdentity = "Alice", embedding = emb,
            socialLinks = "https://x.com/alice", backgroundData = "{\"k\":1}",
            firstSeenTimestamp = 1000L, lastSeenTimestamp = 2000L, encounterCount = 2,
            visibleText = "ACME Corp",
        ))
        db.encounterDao().insert(Encounter(faceId = "f1", timestamp = 1000L,
            latitude = 37.7, longitude = -122.4, locationAccuracyMeters = 5f))

        val out = ByteArrayOutputStream()
        exporter.export(out)

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        assertNotNull(entries["identities.csv"])
        assertNotNull(entries["encounters.csv"])
        assertNotNull(entries["audit.csv"])
        assertNotNull(entries["manifest.json"])

        val identities = entries["identities.csv"]!!
        assertTrue("identities CSV missing header", identities.startsWith("faceId,primaryIdentity,"))
        assertTrue("identities CSV missing Alice row", identities.contains("Alice"))
        assertTrue("identities CSV missing ACME Corp", identities.contains("ACME Corp"))

        val manifest = JSONObject(entries["manifest.json"]!!)
        assertEquals(4, manifest.getInt("schemaVersion"))
        assertEquals(1, manifest.getInt("identityCount"))
    }

    @Test
    fun `embedding floats survive round-trip`() = runBlocking {
        val emb = FloatArray(8) { i -> 0.123f * (i + 1) }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "f1", primaryIdentity = "Bob", embedding = emb,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = 0L, lastSeenTimestamp = 0L, encounterCount = 1,
        ))

        val out = ByteArrayOutputStream()
        exporter.export(out)

        val csv = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var name = zis.nextEntry?.name
            while (name != null && name != "identities.csv") {
                zis.closeEntry(); name = zis.nextEntry?.name
            }
            zis.readBytes().toString(Charsets.UTF_8)
        }
        // Embedding cell: comma-joined floats inside a quoted CSV cell
        val embString = emb.joinToString(",")
        assertTrue("CSV missing embedding cell, got: $csv",
            csv.contains("\"$embString\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.DatabaseExporterTest" --no-daemon`
Expected: `Unresolved reference 'DatabaseExporter'`.

- [ ] **Step 3: Implement DatabaseExporter**

`app/src/main/java/com/hereliesaz/doxray/db/DatabaseExporter.kt`:

```kotlin
package com.hereliesaz.doxray.db

import org.json.JSONObject
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DatabaseExporter(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val auditDao: AuditDao,
) {

    companion object {
        const val SCHEMA_VERSION = 4
    }

    suspend fun export(out: OutputStream) {
        val identities = identityDao.getAllIdentities()
        val zip = ZipOutputStream(out)

        zip.putNextEntry(ZipEntry("identities.csv"))
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write("faceId,primaryIdentity,embedding,socialLinks,backgroundData,visibleText,firstSeenTimestamp,lastSeenTimestamp,encounterCount\n")
            for (r in identities) {
                val cells = listOf(
                    r.faceId,
                    r.primaryIdentity,
                    r.embedding.joinToString(","),
                    r.socialLinks,
                    r.backgroundData,
                    r.visibleText.orEmpty(),
                    r.firstSeenTimestamp.toString(),
                    r.lastSeenTimestamp.toString(),
                    r.encounterCount.toString(),
                )
                w.write(cells.joinToString(",") { csvCell(it) })
                w.write("\n")
            }
            w.flush()
        }
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("encounters.csv"))
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write("id,faceId,timestamp,latitude,longitude,locationAccuracyMeters\n")
            for (r in identities) {
                val rows = encounterDao.getAllByFace(r.faceId)
                for (e in rows) {
                    val cells = listOf(
                        e.id.toString(),
                        e.faceId,
                        e.timestamp.toString(),
                        e.latitude?.toString().orEmpty(),
                        e.longitude?.toString().orEmpty(),
                        e.locationAccuracyMeters?.toString().orEmpty(),
                    )
                    w.write(cells.joinToString(",") { csvCell(it) })
                    w.write("\n")
                }
            }
            w.flush()
        }
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("audit.csv"))
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write("id,timestamp,type,summary,detailsJson\n")
            val events = auditDao.getAll()
            for (e in events) {
                val cells = listOf(
                    e.id.toString(),
                    e.timestamp.toString(),
                    e.type,
                    e.summary,
                    e.detailsJson,
                )
                w.write(cells.joinToString(",") { csvCell(it) })
                w.write("\n")
            }
            w.flush()
        }
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("manifest.json"))
        val manifest = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", iso8601(System.currentTimeMillis()))
            .put("identityCount", identities.size)
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write(manifest.toString())
            w.flush()
        }
        zip.closeEntry()

        zip.finish()
    }

    private fun csvCell(value: String): String {
        // RFC 4180: always quote; double up internal quotes.
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun iso8601(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
}
```

This requires two new DAO queries. Edit `app/src/main/java/com/hereliesaz/doxray/db/EncounterDao.kt`, add:

```kotlin
    @Query("SELECT * FROM encounters WHERE faceId = :faceId ORDER BY timestamp ASC")
    suspend fun getAllByFace(faceId: String): List<Encounter>
```

(next to `observeByFace`).

Edit `app/src/main/java/com/hereliesaz/doxray/db/AuditDao.kt`, add:

```kotlin
    @Query("SELECT * FROM audit_events ORDER BY timestamp ASC")
    suspend fun getAll(): List<AuditEvent>
```

(next to `observeRecent`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.DatabaseExporterTest" --no-daemon`
Expected: 2/2 pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/db/DatabaseExporter.kt \
        app/src/main/java/com/hereliesaz/doxray/db/EncounterDao.kt \
        app/src/main/java/com/hereliesaz/doxray/db/AuditDao.kt \
        app/src/test/java/com/hereliesaz/doxray/db/DatabaseExporterTest.kt
git commit -m "Add DatabaseExporter writing 3 CSVs + manifest into a ZIP"
```

---

## Task 7: DatabaseImporter TDD

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/db/DatabaseImporter.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/db/DatabaseImporterTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/db/DatabaseImporterTest.kt`:

```kotlin
package com.hereliesaz.doxray.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DatabaseImporterTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: DatabaseImporter
    private lateinit var exporter: DatabaseExporter

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        importer = DatabaseImporter(db.identityDao(), db.encounterDao(), db.auditDao())
        exporter = DatabaseExporter(db.identityDao(), db.encounterDao(), db.auditDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `round-trip preserves identity count`() = runBlocking {
        val emb = FloatArray(4) { 0.5f }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "f1", primaryIdentity = "Alice", embedding = emb,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = 100L, lastSeenTimestamp = 200L, encounterCount = 1,
        ))
        val baos = ByteArrayOutputStream()
        exporter.export(baos)

        // Fresh DB
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val fresh = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val freshImporter = DatabaseImporter(fresh.identityDao(), fresh.encounterDao(), fresh.auditDao())
        val report = freshImporter.import(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, report.identitiesImported)
        assertEquals(0, report.identitiesMalformed)
        val all = fresh.identityDao().getAllIdentities()
        assertEquals(1, all.size)
        assertEquals("Alice", all[0].primaryIdentity)
        fresh.close()
    }

    @Test
    fun `conflict on faceId skips imported identity`() = runBlocking {
        val emb = FloatArray(4) { 0.5f }
        db.identityDao().insertIdentity(IdentityRecord(
            faceId = "shared", primaryIdentity = "Local-Alice", embedding = emb,
            socialLinks = "", backgroundData = "{}",
            firstSeenTimestamp = 100L, lastSeenTimestamp = 200L, encounterCount = 5,
        ))

        // Build an import zip in-memory with a different primaryIdentity for the same faceId
        val zipBytes = buildZip(
            identitiesCsv = "faceId,primaryIdentity,embedding,socialLinks,backgroundData,visibleText,firstSeenTimestamp,lastSeenTimestamp,encounterCount\n" +
                "\"shared\",\"Import-Alice\",\"" + emb.joinToString(",") + "\",\"\",\"{}\",\"\",\"50\",\"60\",\"1\"\n",
            manifestVersion = 4,
        )
        val report = importer.import(ByteArrayInputStream(zipBytes))
        assertEquals(0, report.identitiesImported)
        assertEquals(1, report.identitiesSkipped)

        val survivor = db.identityDao().getIdentityById("shared")
        assertEquals("Local-Alice", survivor?.primaryIdentity)
        assertEquals(5, survivor?.encounterCount)
    }

    @Test
    fun `manifest schemaVersion newer than current throws`() {
        val zipBytes = buildZip(identitiesCsv = "faceId,primaryIdentity\n", manifestVersion = 99)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { importer.import(ByteArrayInputStream(zipBytes)) }
        }
    }

    @Test
    fun `malformed row counted, valid row imported`() = runBlocking {
        val emb = FloatArray(4) { 0.5f }
        val csv = "faceId,primaryIdentity,embedding,socialLinks,backgroundData,visibleText,firstSeenTimestamp,lastSeenTimestamp,encounterCount\n" +
            "\"f1\",\"Ok\",\"" + emb.joinToString(",") + "\",\"\",\"{}\",\"\",\"1\",\"2\",\"1\"\n" +
            "\"f2\",\"Bad\",\"not,floats,abc\",\"\",\"{}\",\"\",\"1\",\"2\",\"1\"\n"
        val zipBytes = buildZip(identitiesCsv = csv, manifestVersion = 4)
        val report = importer.import(ByteArrayInputStream(zipBytes))
        assertEquals(1, report.identitiesImported)
        assertEquals(1, report.identitiesMalformed)
        assertNotNull(db.identityDao().getIdentityById("f1"))
    }

    private fun buildZip(identitiesCsv: String, manifestVersion: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("identities.csv"))
            OutputStreamWriter(zip).let { it.write(identitiesCsv); it.flush() }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("encounters.csv"))
            OutputStreamWriter(zip).let { it.write("id,faceId,timestamp,latitude,longitude,locationAccuracyMeters\n"); it.flush() }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("audit.csv"))
            OutputStreamWriter(zip).let { it.write("id,timestamp,type,summary,detailsJson\n"); it.flush() }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            OutputStreamWriter(zip).let { it.write("{\"schemaVersion\":$manifestVersion,\"identityCount\":0}"); it.flush() }
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.DatabaseImporterTest" --no-daemon`
Expected: `Unresolved reference 'DatabaseImporter'`.

- [ ] **Step 3: Implement DatabaseImporter**

`app/src/main/java/com/hereliesaz/doxray/db/DatabaseImporter.kt`:

```kotlin
package com.hereliesaz.doxray.db

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class DatabaseImporter(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val auditDao: AuditDao,
) {

    private val TAG = "DatabaseImporter"

    data class Report(
        val identitiesImported: Int, val identitiesSkipped: Int, val identitiesMalformed: Int,
        val encountersImported: Int, val encountersDeduped: Int, val encountersMalformed: Int,
        val auditImported: Int, val auditDeduped: Int, val auditMalformed: Int,
    ) {
        fun summary(): String =
            "Import complete:\n" +
            "  Identities: $identitiesImported imported, $identitiesSkipped skipped, $identitiesMalformed malformed\n" +
            "  Encounters: $encountersImported imported, $encountersDeduped deduped, $encountersMalformed malformed\n" +
            "  Audit: $auditImported imported, $auditDeduped deduped, $auditMalformed malformed"
    }

    suspend fun import(input: InputStream): Report {
        val entries = readZipEntries(input)
        val manifestText = entries["manifest.json"]
            ?: throw IllegalStateException("manifest.json missing from import")
        val manifest = runCatching { JSONObject(manifestText) }.getOrElse {
            throw IllegalStateException("manifest.json malformed")
        }
        val schemaVersion = manifest.optInt("schemaVersion", -1)
        if (schemaVersion > DatabaseExporter.SCHEMA_VERSION) {
            throw IllegalStateException("Import schema v$schemaVersion newer than current v${DatabaseExporter.SCHEMA_VERSION}")
        }

        val identitiesCsv = entries["identities.csv"]
            ?: throw IllegalStateException("identities.csv missing from import")
        val encountersCsv = entries["encounters.csv"]
            ?: throw IllegalStateException("encounters.csv missing from import")
        val auditCsv = entries["audit.csv"]
            ?: throw IllegalStateException("audit.csv missing from import")

        var idsImported = 0; var idsSkipped = 0; var idsMalformed = 0
        for (row in parseCsv(identitiesCsv).drop(1)) { // drop header
            val rec = parseIdentityRow(row)
            if (rec == null) { idsMalformed++; continue }
            val existing = identityDao.getIdentityById(rec.faceId)
            if (existing != null) { idsSkipped++; continue }
            runCatching { identityDao.insertIdentity(rec) }
                .onSuccess { idsImported++ }
                .onFailure { idsMalformed++ }
        }

        var encImported = 0; var encDeduped = 0; var encMalformed = 0
        val existingEncKeys = mutableSetOf<Pair<String, Long>>()
        for (rec in identityDao.getAllIdentities()) {
            for (e in encounterDao.getAllByFace(rec.faceId)) {
                existingEncKeys.add(e.faceId to e.timestamp)
            }
        }
        for (row in parseCsv(encountersCsv).drop(1)) {
            val enc = parseEncounterRow(row)
            if (enc == null) { encMalformed++; continue }
            if ((enc.faceId to enc.timestamp) in existingEncKeys) { encDeduped++; continue }
            runCatching { encounterDao.insert(enc) }
                .onSuccess {
                    encImported++
                    existingEncKeys.add(enc.faceId to enc.timestamp)
                }
                .onFailure { encMalformed++ }
        }

        var auditImported = 0; var auditDeduped = 0; var auditMalformed = 0
        val existingAuditKeys = auditDao.getAll().map { Triple(it.timestamp, it.type, it.summary) }.toMutableSet()
        for (row in parseCsv(auditCsv).drop(1)) {
            val evt = parseAuditRow(row)
            if (evt == null) { auditMalformed++; continue }
            val key = Triple(evt.timestamp, evt.type, evt.summary)
            if (key in existingAuditKeys) { auditDeduped++; continue }
            runCatching { auditDao.insert(evt) }
                .onSuccess {
                    auditImported++
                    existingAuditKeys.add(key)
                }
                .onFailure { auditMalformed++ }
        }

        return Report(
            idsImported, idsSkipped, idsMalformed,
            encImported, encDeduped, encMalformed,
            auditImported, auditDeduped, auditMalformed,
        )
    }

    private fun readZipEntries(input: InputStream): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                out[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        return out
    }

    private fun parseIdentityRow(cells: List<String>): IdentityRecord? {
        if (cells.size < 9) return null
        return try {
            IdentityRecord(
                faceId = cells[0],
                primaryIdentity = cells[1],
                embedding = parseEmbedding(cells[2]) ?: return null,
                socialLinks = cells[3],
                backgroundData = cells[4],
                visibleText = cells[5].ifBlank { null },
                firstSeenTimestamp = cells[6].toLong(),
                lastSeenTimestamp = cells[7].toLong(),
                encounterCount = cells[8].toInt(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Identity row parse failed: ${e.message}")
            null
        }
    }

    private fun parseEncounterRow(cells: List<String>): Encounter? {
        if (cells.size < 6) return null
        return try {
            Encounter(
                id = cells[0].toLongOrNull() ?: 0,
                faceId = cells[1],
                timestamp = cells[2].toLong(),
                latitude = cells[3].toDoubleOrNull(),
                longitude = cells[4].toDoubleOrNull(),
                locationAccuracyMeters = cells[5].toFloatOrNull(),
            ).copy(id = 0) // let Room autoincrement, avoid PK collisions on import
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAuditRow(cells: List<String>): AuditEvent? {
        if (cells.size < 5) return null
        return try {
            AuditEvent(
                id = 0,
                timestamp = cells[1].toLong(),
                type = cells[2],
                summary = cells[3],
                detailsJson = cells[4],
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseEmbedding(cell: String): FloatArray? {
        if (cell.isBlank()) return null
        return try {
            cell.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Minimal RFC 4180 reader: supports quoted cells with embedded quotes ("")
     * and embedded commas/newlines. Returns rows as List<List<String>>.
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        var cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i += 2; continue }
                    c == '"' -> { inQuotes = false; i++; continue }
                    else -> { cell.append(c); i++ }
                }
            } else {
                when (c) {
                    '"' -> { inQuotes = true; i++ }
                    ',' -> { row.add(cell.toString()); cell = StringBuilder(); i++ }
                    '\n' -> { row.add(cell.toString()); rows.add(row); row = mutableListOf(); cell = StringBuilder(); i++ }
                    '\r' -> { i++ } // skip
                    else -> { cell.append(c); i++ }
                }
            }
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.DatabaseImporterTest" --no-daemon`
Expected: 4/4 pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/db/DatabaseImporter.kt \
        app/src/test/java/com/hereliesaz/doxray/db/DatabaseImporterTest.kt
git commit -m "Add DatabaseImporter with conflict-skip merge + per-row tolerance"
```

---

## Task 8: NavRail Export/Import menu items + Activity launchers

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`

- [ ] **Step 1: Read MainActivity to understand current shape**

Read `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt` first to identify how the activity sets up its `setContent { ... }` block and where to register `ActivityResultContracts.CreateDocument` / `OpenDocument` launchers.

- [ ] **Step 2: Add launchers to MainActivity**

Edit `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt`. Add to the top imports:

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.db.DatabaseExporter
import com.hereliesaz.doxray.db.DatabaseImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

Inside the `setContent { ... }` block, hoist two launcher variables and pass them down to `DoxrayNavRail`. Wrap the existing `DoxrayNavRail()` call in remembered state:

```kotlin
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val exportLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/zip"),
                    ) { uri ->
                        if (uri != null) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(ctx)
                                val exporter = DatabaseExporter(db.identityDao(), db.encounterDao(), db.auditDao())
                                ctx.contentResolver.openOutputStream(uri)?.use { exporter.export(it) }
                            }
                        }
                    }
                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(ctx)
                                val importer = DatabaseImporter(db.identityDao(), db.encounterDao(), db.auditDao())
                                ctx.contentResolver.openInputStream(uri)?.use { importer.import(it) }
                            }
                        }
                    }
                    DoxrayNavRail(
                        onExportClicked = { exportLauncher.launch("doxxr-export-${System.currentTimeMillis()}.zip") },
                        onImportClicked = { importLauncher.launch(arrayOf("application/zip")) },
                    )
```

The existing `DoxrayNavRail()` call without args is being replaced with the two-arg version. The launchers must live inside the `setContent { }` block (not `onCreate` body) — `rememberLauncherForActivityResult` is a Compose-side API. Use `LocalContext.current` to get the context.

- [ ] **Step 3: Accept callbacks in DoxrayNavRail + add menu items**

Edit `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`. Add an import:

```kotlin
import com.hereliesaz.aznavrail.dsl.azMenuItem
```

(If the existing imports already cover the DSL via wildcard, this is unneeded — verify by inspecting the import block.)

Change the function signature from:

```kotlin
@Composable
fun DoxrayNavRail() {
```

to:

```kotlin
@Composable
fun DoxrayNavRail(
    onExportClicked: () -> Unit = {},
    onImportClicked: () -> Unit = {},
) {
```

Inside the `AzHostActivityLayout { ... }` DSL block, after the existing `azRailItem(id = "audit", ...)` line, add:

```kotlin
        azMenuItem(id = "export-db", text = "Export DB", route = "export-db", onClick = onExportClicked)
        azMenuItem(id = "import-db", text = "Import DB", route = "import-db", onClick = onImportClicked)
```

These items only appear in the expanded drawer (per the AzNavRail guide), don't navigate, and fire the launchers directly.

- [ ] **Step 4: Verify full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/MainActivity.kt \
        app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt
git commit -m "Add Export DB / Import DB menu items in NavRail with SAF launchers"
```

---

## Task 9: Final verification

- [ ] **Step 1: Full build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. Total tests = 63 (52 prior + 3 OcrService + 2 LocalFaceCacheReencounter + 2 DatabaseExporter + 4 DatabaseImporter).

- [ ] **Step 2: APK present**

Run: `ls -la app/build/outputs/apk/debug/app-debug.apk`
Expected: non-empty.

- [ ] **Step 3: Test-count check**

Run: `find app/build/test-results -name "*.xml" -exec grep -h "tests=" {} \; | sed -n 's/.*tests="\([0-9]*\)".*failures="\([0-9]*\)".*errors="\([0-9]*\)".*/\1 \2 \3/p' | awk '{t+=$1; f+=$2; e+=$3} END {print "tests:", t, "failures:", f, "errors:", e}'`
Expected: `tests: 63 failures: 0 errors: 0` (may be ±1 depending on how multi-test files are split).

- [ ] **Step 4: verifyMetaSdk still works**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew verifyMetaSdk --no-daemon 2>&1 | grep "gh.packages.url\|DAT SDK"`
Expected: same WARN line as previous phases.

- [ ] **Step 5: No commit needed.** Validation only.

---

## Notes for the executor

- **JDK:** `/usr/lib/jvm/java-21-openjdk-amd64`. Prefix every gradle command with `JAVA_HOME=…`.
- **vfat exec bit:** repo is on vfat; invoke gradle as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / Room 2.8.4 / OkHttp 5.3.2 / Jsoup 1.22.2. ML Kit text-recognition `16.0.1` added in Task 1.
- **No new BuildConfig keys, no new permissions.**
- **Geo encounters are already implemented**: `LocalFaceCache.recordEncounter` already calls `locationService.getLastLocation()` and writes the result. `DossierDetailScreen.EncounterRow` already renders the chip. Phase 5 plan does not touch these.
- **Audit column name:** the entity uses `detailsJson`, not `details`. Plan and CSV use `detailsJson` consistently.
- **DAO additions in Task 6** (`EncounterDao.getAllByFace`, `AuditDao.getAll`) are required only for the exporter. Don't combine them into a different task.
- **Robolectric tests** must run with `@Config(manifest = Config.NONE)` (matches Phase 1/2 patterns).

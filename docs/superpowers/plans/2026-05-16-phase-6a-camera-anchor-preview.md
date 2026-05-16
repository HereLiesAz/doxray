# Phase 6a — Phone camera, anchor images, live preview UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Doxray usable from the device's own camera (CameraX), persist a best-quality face crop per identity, and replace the LiveScreen text log with a fullscreen camera preview surface that surfaces anchor thumbnails on match.

**Architecture:** A new `FrameSource` interface abstracts the byte-stream from any input device. Two implementations: `MetaFrameSource` (default, adapts existing `MetaGlassesManager`) and `PhoneFrameSource` (CameraX `ImageAnalysis`, throttled to ~5fps, JPEG-encoded). `LiveViewModel` exposes `inputMode: StateFlow<InputMode>`, `lastMatchFlow: StateFlow<MatchEvent?>`, and mode-swap methods. `LiveScreen` branches on `inputMode`. Anchor images persist in a new `anchor_images` Room table (DB v5) with best-quality refresh policy, surfaced via picture-in-picture overlay on live match and as a thumbnail on `DossierDetailScreen`.

**Tech Stack:** Kotlin 2.3.21 / Room 2.8.4 / CameraX 1.3.4 / ML Kit (existing) / JUnit 4 + Robolectric 4.13 / AzNavRail.

---

## Task 1: Add CameraX dependencies + FrameSource interface + MetaFrameSource adapter

**Files:**
- Modify: `app/build.gradle.kts` (add 4 CameraX deps)
- Create: `app/src/main/java/com/hereliesaz/doxray/camera/FrameSource.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/camera/MetaFrameSource.kt`

- [ ] **Step 1: Add CameraX dependencies**

Edit `app/build.gradle.kts`. In the `dependencies { }` block (next to the existing ML Kit lines), add:

```kotlin
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
```

- [ ] **Step 2: Create the FrameSource interface**

`app/src/main/java/com/hereliesaz/doxray/camera/FrameSource.kt`:

```kotlin
package com.hereliesaz.doxray.camera

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a byte-stream input device. Implementations supply
 * JPEG-encoded frames via [framesFlow]. The downstream pipeline
 * ([com.hereliesaz.doxray.api.FaceTrackerManager.processFrame]) is agnostic
 * to the underlying source.
 */
interface FrameSource {
    val framesFlow: Flow<ByteArray>
    suspend fun start()
    suspend fun stop()
}
```

- [ ] **Step 3: Create MetaFrameSource adapter**

`app/src/main/java/com/hereliesaz/doxray/camera/MetaFrameSource.kt`:

```kotlin
package com.hereliesaz.doxray.camera

import com.hereliesaz.doxray.meta.FrameListener
import com.hereliesaz.doxray.meta.MetaGlassesManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Adapter that exposes the existing [MetaGlassesManager.startVideoStream]
 * listener-based API as a [FrameSource]-compatible [Flow].
 *
 * Calling [start] connects + begins streaming; [stop] reverses both steps.
 */
class MetaFrameSource(private val manager: MetaGlassesManager) : FrameSource {

    private var listener: FrameListener? = null

    override val framesFlow: Flow<ByteArray> = callbackFlow {
        val l = FrameListener { bytes -> trySend(bytes) }
        listener = l
        manager.startVideoStream(l)
        awaitClose { manager.stopVideoStream() }
    }

    override suspend fun start() {
        manager.connect()
    }

    override suspend fun stop() {
        manager.stopVideoStream()
        manager.disconnect()
    }
}
```

If `FrameListener` is a SAM interface in the existing code, the lambda above works. If it's not (it's a `fun interface` or a class with a single method), the implementer must adapt to construct it correctly — read `app/src/main/java/com/hereliesaz/doxray/meta/MetaGlassesManager.kt` to see the listener shape.

- [ ] **Step 4: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/hereliesaz/doxray/camera/FrameSource.kt \
        app/src/main/java/com/hereliesaz/doxray/camera/MetaFrameSource.kt
git commit -m "Add CameraX deps + FrameSource interface + MetaFrameSource adapter"
```

---

## Task 2: PhoneFrameSource TDD (frame throttle + flip camera state)

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/camera/PhoneFrameSource.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/camera/PhoneFrameSourceTest.kt`

The CameraX initialization itself cannot be exercised under Robolectric. The tests target the testable surface: frame throttling and flip-state toggling. The CameraX bind / `bindToLifecycle` call is exercised only via `assembleDebug`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/camera/PhoneFrameSourceTest.kt`:

```kotlin
package com.hereliesaz.doxray.camera

import android.app.Application
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PhoneFrameSourceTest {

    @Test
    fun `throttles rapid onFrame calls to one per 200ms`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val src = PhoneFrameSource(ctx, FakeLifecycleOwner())

        // Two rapid synthetic calls within the throttle window
        val now = 1_000L
        val accepted1 = src.tryEmitForTest(makeJpeg(), now)
        val accepted2 = src.tryEmitForTest(makeJpeg(), now + 100)
        val accepted3 = src.tryEmitForTest(makeJpeg(), now + 250)

        assertTrue("First call should pass throttle", accepted1)
        assertFalse("Second call within 200ms should be dropped", accepted2)
        assertTrue("Call after 200ms should pass throttle", accepted3)
    }

    @Test
    fun `flipCamera toggles useFrontCamera flag`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val src = PhoneFrameSource(ctx, FakeLifecycleOwner())
        assertFalse("Default is rear-facing", src.useFrontCameraForTest())
        src.flipCamera()
        assertTrue("After flip is front-facing", src.useFrontCameraForTest())
        src.flipCamera()
        assertFalse("After second flip back to rear", src.useFrontCameraForTest())
    }

    private fun makeJpeg(): ByteArray = ByteArray(8) { 0xFF.toByte() }

    private class FakeLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle = registry
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.camera.PhoneFrameSourceTest" --no-daemon`
Expected: `Unresolved reference 'PhoneFrameSource'`.

- [ ] **Step 3: Implement PhoneFrameSource**

`app/src/main/java/com/hereliesaz/doxray/camera/PhoneFrameSource.kt`:

```kotlin
package com.hereliesaz.doxray.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.annotation.VisibleForTesting
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * CameraX-backed [FrameSource]. Binds an [ImageAnalysis] use case to the
 * given [lifecycleOwner], JPEG-encodes incoming YUV frames, and emits them
 * through [framesFlow] throttled to ~5fps.
 */
class PhoneFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : FrameSource {

    private val TAG = "PhoneFrameSource"
    private val THROTTLE_MS = 200L

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _framesFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 1)
    override val framesFlow: Flow<ByteArray> = _framesFlow.asSharedFlow()

    private var lastEmittedMs: Long = 0L
    private var useFrontCamera: Boolean = false

    @Volatile private var provider: ProcessCameraProvider? = null
    val previewUseCase: Preview = Preview.Builder().build()

    override suspend fun start() {
        try {
            val p = ProcessCameraProvider.getInstance(context).get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
                .also { it.setAnalyzer(executor, ::onFrame) }
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            try {
                p.bindToLifecycle(lifecycleOwner, selector, analysis, previewUseCase)
            } catch (e: Exception) {
                // Fall back to the other camera if the selected one isn't available
                val fallback = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                p.bindToLifecycle(lifecycleOwner, fallback, analysis, previewUseCase)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CameraX bind failed", e)
            throw e
        }
    }

    override suspend fun stop() {
        provider?.unbindAll()
        provider = null
    }

    fun flipCamera() {
        useFrontCamera = !useFrontCamera
        // Caller is responsible for stop()/start() cycle after flip
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastEmittedMs < THROTTLE_MS) return
            lastEmittedMs = now
            val jpeg = imageProxyToJpeg(image, quality = 85) ?: return
            scope.launch { _framesFlow.emit(jpeg) }
        } finally {
            image.close()
        }
    }

    private fun imageProxyToJpeg(image: ImageProxy, quality: Int): ByteArray? {
        return try {
            val nv21 = yuv420ToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "JPEG encode failed", e)
            null
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        return nv21
    }

    @VisibleForTesting
    internal fun tryEmitForTest(bytes: ByteArray, fakeNow: Long): Boolean {
        if (fakeNow - lastEmittedMs < THROTTLE_MS) return false
        lastEmittedMs = fakeNow
        scope.launch { _framesFlow.emit(bytes) }
        return true
    }

    @VisibleForTesting
    internal fun useFrontCameraForTest(): Boolean = useFrontCamera
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.camera.PhoneFrameSourceTest" --no-daemon`
Expected: 2/2 pass.

- [ ] **Step 5: Verify assembleDebug picks up CameraX**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL` (this is the real CameraX symbol-resolution check — the test path stays in the testable region).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/camera/PhoneFrameSource.kt \
        app/src/test/java/com/hereliesaz/doxray/camera/PhoneFrameSourceTest.kt
git commit -m "Add PhoneFrameSource with frame throttle + flip camera (TDD, 2 cases)"
```

---

## Task 3: AnchorImage entity + DAO + Migration v4→v5

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/db/AnchorImage.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/db/AnchorImageDao.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/db/Migration_4_5.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt` (version 5, register migration, expose DAO, add to entities)

- [ ] **Step 1: Create AnchorImage entity**

`app/src/main/java/com/hereliesaz/doxray/db/AnchorImage.kt`:

```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stored representative face crop for an identity. Best-quality wins:
 * [com.hereliesaz.doxray.api.AnchorImageRepository.upsert] only writes when
 * the new score exceeds the existing one.
 */
@Entity(
    tableName = "anchor_images",
    foreignKeys = [ForeignKey(
        entity = IdentityRecord::class,
        parentColumns = ["faceId"],
        childColumns = ["faceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("faceId")],
)
data class AnchorImage(
    @PrimaryKey val faceId: String,
    val imageBytes: ByteArray,
    val qualityScore: Float,
    val capturedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnchorImage
        if (faceId != other.faceId) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (qualityScore != other.qualityScore) return false
        if (capturedAt != other.capturedAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        result = 31 * result + qualityScore.hashCode()
        result = 31 * result + capturedAt.hashCode()
        return result
    }
}
```

- [ ] **Step 2: Create AnchorImageDao**

`app/src/main/java/com/hereliesaz/doxray/db/AnchorImageDao.kt`:

```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnchorImageDao {
    @Query("SELECT * FROM anchor_images WHERE faceId = :faceId LIMIT 1")
    suspend fun getByFaceId(faceId: String): AnchorImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(anchor: AnchorImage)
}
```

- [ ] **Step 3: Create Migration_4_5**

`app/src/main/java/com/hereliesaz/doxray/db/Migration_4_5.kt`:

```kotlin
package com.hereliesaz.doxray.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `anchor_images` table for Phase 6a. One row per identity.
 * Cascade delete keeps the table in sync with the parent.
 */
object Migration_4_5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS anchor_images (
                faceId TEXT NOT NULL PRIMARY KEY,
                imageBytes BLOB NOT NULL,
                qualityScore REAL NOT NULL,
                capturedAt INTEGER NOT NULL,
                FOREIGN KEY(faceId) REFERENCES identity_records(faceId) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_anchor_images_faceId ON anchor_images(faceId)")
    }
}
```

- [ ] **Step 4: Wire into AppDatabase**

Edit `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt`. Change:

```kotlin
@Database(entities = [IdentityRecord::class, Encounter::class, AuditEvent::class], version = 4, exportSchema = true)
```

to:

```kotlin
@Database(entities = [IdentityRecord::class, Encounter::class, AuditEvent::class, AnchorImage::class], version = 5, exportSchema = true)
```

Add the DAO accessor:

```kotlin
    abstract fun anchorImageDao(): AnchorImageDao
```

In the `Room.databaseBuilder(...)` call, change:

```kotlin
.addMigrations(Migration_2_3, Migration_3_4)
```

to:

```kotlin
.addMigrations(Migration_2_3, Migration_3_4, Migration_4_5)
```

- [ ] **Step 5: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`. All 65 tests pass (63 prior + 2 PhoneFrameSource). A schema JSON `app/schemas/com.hereliesaz.doxray.db.AppDatabase/5.json` is generated.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/db/AnchorImage.kt \
        app/src/main/java/com/hereliesaz/doxray/db/AnchorImageDao.kt \
        app/src/main/java/com/hereliesaz/doxray/db/Migration_4_5.kt \
        app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt \
        app/schemas/com.hereliesaz.doxray.db.AppDatabase/5.json
git commit -m "Add anchor_images table with Room v4 to v5 migration"
```

---

## Task 4: AnchorImageRepository TDD

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/AnchorImageRepository.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/api/AnchorImageRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/AnchorImageRepositoryTest.kt`:

```kotlin
package com.hereliesaz.doxray.api

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.doxray.db.AnchorImage
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.db.IdentityRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AnchorImageRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AnchorImageRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = AnchorImageRepository(db.anchorImageDao())
        // FK requires the parent row to exist
        runBlocking {
            db.identityDao().insertIdentity(IdentityRecord(
                faceId = "f1", primaryIdentity = "Alice",
                embedding = FloatArray(4) { 0f },
                socialLinks = "", backgroundData = "{}",
                firstSeenTimestamp = 0L, lastSeenTimestamp = 0L, encounterCount = 1,
            ))
        }
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `first call inserts the anchor`() = runBlocking {
        repo.upsert("f1", ByteArray(8) { 0x42 }, qualityScore = 0.5f)
        val saved = repo.get("f1")
        assertNotNull(saved)
        assertEquals(0.5f, saved!!.qualityScore, 0.0001f)
        assertEquals(8, saved.imageBytes.size)
    }

    @Test
    fun `higher-score call updates existing anchor`() = runBlocking {
        repo.upsert("f1", ByteArray(8) { 0x11 }, qualityScore = 0.3f)
        repo.upsert("f1", ByteArray(8) { 0x22 }, qualityScore = 0.7f)
        val saved = repo.get("f1")
        assertNotNull(saved)
        assertEquals(0.7f, saved!!.qualityScore, 0.0001f)
        assertEquals(0x22.toByte(), saved.imageBytes[0])
    }

    @Test
    fun `lower-score call keeps existing anchor`() = runBlocking {
        repo.upsert("f1", ByteArray(8) { 0x11 }, qualityScore = 0.7f)
        repo.upsert("f1", ByteArray(8) { 0x99 }, qualityScore = 0.2f)
        val saved = repo.get("f1")
        assertNotNull(saved)
        assertEquals(0.7f, saved!!.qualityScore, 0.0001f)
        assertEquals(0x11.toByte(), saved.imageBytes[0])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.AnchorImageRepositoryTest" --no-daemon`
Expected: `Unresolved reference 'AnchorImageRepository'`.

- [ ] **Step 3: Implement AnchorImageRepository**

`app/src/main/java/com/hereliesaz/doxray/api/AnchorImageRepository.kt`:

```kotlin
package com.hereliesaz.doxray.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hereliesaz.doxray.db.AnchorImage
import com.hereliesaz.doxray.db.AnchorImageDao
import java.io.ByteArrayOutputStream

/**
 * Persists a single representative face crop per identity. Best-quality wins:
 * a new upsert only overwrites when [qualityScore] exceeds the stored row's
 * score. Skips writes that would exceed 1 MB after re-compression.
 */
class AnchorImageRepository(private val dao: AnchorImageDao) {

    private val TAG = "AnchorImageRepository"
    private val MAX_BYTES = 1_048_576

    suspend fun upsert(faceId: String, imageBytes: ByteArray, qualityScore: Float) {
        val existing = dao.getByFaceId(faceId)
        if (existing != null && qualityScore <= existing.qualityScore) return
        val bytes = ensureUnderMax(imageBytes) ?: run {
            Log.w(TAG, "Anchor for $faceId exceeds $MAX_BYTES bytes; skipping")
            return
        }
        dao.upsert(AnchorImage(
            faceId = faceId,
            imageBytes = bytes,
            qualityScore = qualityScore,
            capturedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun get(faceId: String): AnchorImage? = dao.getByFaceId(faceId)

    private fun ensureUnderMax(bytes: ByteArray): ByteArray? {
        if (bytes.size <= MAX_BYTES) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray().takeIf { it.size <= MAX_BYTES }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.AnchorImageRepositoryTest" --no-daemon`
Expected: 3/3 pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/AnchorImageRepository.kt \
        app/src/test/java/com/hereliesaz/doxray/api/AnchorImageRepositoryTest.kt
git commit -m "Add AnchorImageRepository with best-quality refresh (TDD, 3 cases)"
```

---

## Task 5: Hook anchor upsert into LiveViewModel + quality score derivation

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Add the repository field**

In `LiveViewModel`, near the other repository/service fields, add:

```kotlin
    private val anchorImageRepository = AnchorImageRepository(appDatabase.anchorImageDao())
```

Add the import:

```kotlin
import com.hereliesaz.doxray.api.AnchorImageRepository
import kotlin.math.abs
```

- [ ] **Step 2: Derive a quality score and upsert in processFocusedFace**

Inside `processFocusedFace`, the existing quality-gate block is:

```kotlin
            if (quality is QualityResult.Fail) {
                appendLog("Low-quality crop (ID: $trackingId): ${quality.reasons.joinToString(", ")}")
                AuditLogger.log(...)
                return
            }
```

Immediately after that block (so we know the crop passed the quality gate), compute the anchor score:

```kotlin
            val anchorScore = faceFrac * (1f - (abs(eulerY) / 90f).coerceAtMost(1f))
```

Then, on the **cached match** branch — find the line `appendLog("Cached Match: ...")` and immediately after the `if (cachedMatch != null) {` block's first lines, insert:

```kotlin
                anchorImageRepository.upsert(cachedMatch.faceId, faceCrop, anchorScore)
```

(Place this before the `if (cachedMatch.backgroundData == "{}" ...)` branch — it should run for every cache hit regardless of whether the bg scrape resumes.)

On the **new-identity** branch — inside `performDeepBackgroundScrape`, immediately before the `localFaceCache.cacheIdentity(...)` call, add a new parameter that performs the anchor upsert. The cleanest place is at the call site (`processFocusedFace` after `performDeepBackgroundScrape` returns), because at that point the new `faceId` exists in `identity_records`.

Actually, simpler: pass the anchor data into `performDeepBackgroundScrape` and do the upsert there, *after* `cacheIdentity` (so the FK exists). Modify the existing `performDeepBackgroundScrape` signature from:

```kotlin
    private suspend fun performDeepBackgroundScrape(
        primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>,
        ocrResult: OcrService.OcrResult?,
    ) {
```

to:

```kotlin
    private suspend fun performDeepBackgroundScrape(
        primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>,
        ocrResult: OcrService.OcrResult?,
        faceCrop: ByteArray, anchorScore: Float,
    ) {
```

Inside the function, after the `localFaceCache.cacheIdentity(...)` call completes, add:

```kotlin
        anchorImageRepository.upsert(faceId, faceCrop, anchorScore)
```

Update both existing call sites of `performDeepBackgroundScrape` in `processFocusedFace`. Find:

```kotlin
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","), ocrResult)
```

Replace with:

```kotlin
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","), ocrResult, faceCrop, anchorScore)
```

And:

```kotlin
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks, ocrResult)
```

Replace with:

```kotlin
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks, ocrResult, faceCrop, anchorScore)
```

- [ ] **Step 3: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`. All 68 tests pass (65 prior + 3 AnchorImageRepository).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Hook anchor image upsert into focused-face pipeline"
```

---

## Task 6: InputMode + MatchEvent state + mode-swap methods in LiveViewModel

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Add imports**

```kotlin
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.hereliesaz.doxray.camera.FrameSource
import com.hereliesaz.doxray.camera.MetaFrameSource
import com.hereliesaz.doxray.camera.PhoneFrameSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

(Some of these may already be present from prior phases — only add missing ones.)

- [ ] **Step 2: Add state types**

At the top of `LiveViewModel.kt` (alongside the existing `data class LiveUiState(...)`), add:

```kotlin
enum class InputMode { META, PHONE }

data class MatchEvent(
    val identityName: String,
    val anchorImageBytes: ByteArray?,
    val firedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MatchEvent
        if (identityName != other.identityName) return false
        if (anchorImageBytes != null) {
            if (other.anchorImageBytes == null) return false
            if (!anchorImageBytes.contentEquals(other.anchorImageBytes)) return false
        } else if (other.anchorImageBytes != null) return false
        if (firedAtMs != other.firedAtMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = identityName.hashCode()
        result = 31 * result + (anchorImageBytes?.contentHashCode() ?: 0)
        result = 31 * result + firedAtMs.hashCode()
        return result
    }
}
```

- [ ] **Step 3: Add fields**

Inside `LiveViewModel`, near the other state flows:

```kotlin
    private val _inputMode = MutableStateFlow(InputMode.META)
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    private val _lastMatchFlow = MutableStateFlow<MatchEvent?>(null)
    val lastMatchFlow: StateFlow<MatchEvent?> = _lastMatchFlow.asStateFlow()

    private val _permissionRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val permissionRequest: SharedFlow<Unit> = _permissionRequest.asSharedFlow()

    private var clearMatchJob: Job? = null

    private val metaFrameSource by lazy { MetaFrameSource(metaGlassesManager) }
    private var phoneFrameSource: PhoneFrameSource? = null
    private var currentSource: FrameSource? = null
```

`PhoneFrameSource` needs a `LifecycleOwner`. The ViewModel itself isn't a `LifecycleOwner`. Pass it in at construction time from the Activity — change the `LiveViewModel` constructor from:

```kotlin
class LiveViewModel(application: Application) : AndroidViewModel(application) {
```

to (note nullable default — Task 11 supplies a non-null value via the factory and tightens this signature):

```kotlin
class LiveViewModel(
    application: Application,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
) : AndroidViewModel(application) {
```

The default keeps the existing `viewModel<LiveViewModel>()` call in NavRail building until Task 11 wires the factory. With a non-null default we'd break runtime construction; with no default we'd break the existing call site immediately.

In `switchToPhone()` (defined in Step 4 below), guard the null case:

```kotlin
                val owner = lifecycleOwner ?: run {
                    appendLog("Phone camera requires Activity lifecycle owner; not yet wired.")
                    return@launch
                }
                val src = phoneFrameSource ?: PhoneFrameSource(getApplication(), owner).also { phoneFrameSource = it }
```

Task 11 removes both the default and the null guard.

- [ ] **Step 4: Add methods**

Inside `LiveViewModel`:

```kotlin
    fun requestPhoneCamera() {
        val granted = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            switchToPhone()
        } else {
            viewModelScope.launch { _permissionRequest.emit(Unit) }
        }
    }

    fun onCameraPermissionGranted() {
        switchToPhone()
    }

    fun switchToMeta() {
        viewModelScope.launch {
            currentSource?.stop()
            metaFrameSource.start()
            currentSource = metaFrameSource
            _inputMode.value = InputMode.META
        }
    }

    private fun switchToPhone() {
        viewModelScope.launch {
            try {
                currentSource?.stop()
                val src = phoneFrameSource ?: PhoneFrameSource(getApplication(), lifecycleOwner).also { phoneFrameSource = it }
                src.start()
                currentSource = src
                _inputMode.value = InputMode.PHONE
            } catch (e: Exception) {
                appendLog("Camera unavailable: ${e.message}")
                _inputMode.value = InputMode.META
            }
        }
    }

    fun flipPhoneCamera() {
        val src = phoneFrameSource ?: return
        src.flipCamera()
        viewModelScope.launch {
            src.stop()
            src.start()
        }
    }

    fun previewUseCase(): androidx.camera.core.Preview? = phoneFrameSource?.previewUseCase

    private fun emitMatchEvent(name: String, faceId: String) {
        viewModelScope.launch {
            val anchor = anchorImageRepository.get(faceId)?.imageBytes
            _lastMatchFlow.value = MatchEvent(name, anchor, System.currentTimeMillis())
            clearMatchJob?.cancel()
            clearMatchJob = viewModelScope.launch {
                delay(8_000L)
                _lastMatchFlow.value = null
            }
        }
    }
```

- [ ] **Step 5: Call emitMatchEvent at the cached-match site**

In `processFocusedFace`, find:

```kotlin
                if (cachedMatch != null) {
                    appendLog("Cached Match: ${cachedMatch.primaryIdentity}. Encounters: ${cachedMatch.encounterCount}.")
```

Immediately after `appendLog`, add:

```kotlin
                    emitMatchEvent(cachedMatch.primaryIdentity, cachedMatch.faceId)
```

For the new-identity branch, do the same — find the existing `appendLog("Identity correlated: $primaryIdentity")` and right after it (or after `performDeepBackgroundScrape` returns), add:

```kotlin
                emitMatchEvent(primaryIdentity, faceId)
```

(Place it after `performDeepBackgroundScrape` so the anchor row already exists.)

- [ ] **Step 6: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. The nullable `lifecycleOwner` default added in Step 3 keeps the existing `viewModel<LiveViewModel>()` call site in NavRail working until Task 11 wires the proper factory.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Add InputMode + MatchEvent state and mode-swap methods to LiveViewModel"
```

---

## Task 7: LiveScreen mode-branch refactor

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt`

Extract the current text-log surface into a private `MetaLiveSurface` composable and add a `PhoneLiveSurface` stub that will be filled in later tasks.

- [ ] **Step 1: Replace the file with the new shape**

`app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt`:

```kotlin
package com.hereliesaz.doxray.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LiveScreen(viewModel: LiveViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inputMode by viewModel.inputMode.collectAsStateWithLifecycle()
    val match by viewModel.lastMatchFlow.collectAsStateWithLifecycle()

    when (inputMode) {
        InputMode.META -> MetaLiveSurface(
            isConnected = state.isConnected,
            logLines = state.logLines,
            onConnect = { viewModel.connect() },
            onDisconnect = { viewModel.disconnect() },
        )
        InputMode.PHONE -> PhoneLiveSurface(
            logLines = state.logLines,
            match = match,
            onFlip = { viewModel.flipPhoneCamera() },
        )
    }
}

@Composable
private fun MetaLiveSurface(
    isConnected: Boolean,
    logLines: List<String>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            text = if (isConnected) "Status: Connected to Glasses" else "Status: Disconnected",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onConnect, enabled = !isConnected) { Text("Connect") }
            Button(onClick = onDisconnect, enabled = isConnected) { Text("Disconnect") }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Recent Activity Log:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logLines) { line ->
                Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PhoneLiveSurface(
    logLines: List<String>,
    match: MatchEvent?,
    onFlip: () -> Unit,
) {
    // Layered surface: camera preview (Task 8), anchor PiP overlay (Task 9),
    // log gradient (Task 9), flip button (Task 9). For now, a placeholder
    // so the mode branch compiles.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            text = "Phone camera mode (preview not yet wired)",
            color = Color.White,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt
git commit -m "Branch LiveScreen by InputMode; extract MetaLiveSurface; stub PhoneLiveSurface"
```

---

## Task 8: CameraPreview composable + wire Preview use case

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/live/CameraPreview.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt` (use CameraPreview inside PhoneLiveSurface)

- [ ] **Step 1: Create CameraPreview composable**

`app/src/main/java/com/hereliesaz/doxray/ui/live/CameraPreview.kt`:

```kotlin
package com.hereliesaz.doxray.ui.live

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts a CameraX [PreviewView] inside Compose. The caller supplies the
 * [Preview] use case (built by [com.hereliesaz.doxray.camera.PhoneFrameSource]).
 * On null [previewUseCase], renders nothing (caller should branch).
 */
@Composable
fun CameraPreview(
    previewUseCase: Preview?,
    modifier: Modifier = Modifier,
) {
    if (previewUseCase == null) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                previewUseCase.setSurfaceProvider(view.surfaceProvider)
            }
        },
        update = { view ->
            previewUseCase.setSurfaceProvider(view.surfaceProvider)
        },
    )
}
```

- [ ] **Step 2: Wire it into PhoneLiveSurface**

Edit `LiveScreen.kt`. Change the `PhoneLiveSurface` body (currently a placeholder Box) to receive a `previewUseCase: androidx.camera.core.Preview?` parameter, and lay out the camera view filling the box:

```kotlin
@Composable
private fun PhoneLiveSurface(
    previewUseCase: androidx.camera.core.Preview?,
    logLines: List<String>,
    match: MatchEvent?,
    onFlip: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            previewUseCase = previewUseCase,
            modifier = Modifier.fillMaxSize(),
        )
        // Anchor PiP + log gradient + flip button: Task 9.
    }
}
```

And in the top-level `LiveScreen`, update the call:

```kotlin
        InputMode.PHONE -> PhoneLiveSurface(
            previewUseCase = viewModel.previewUseCase(),
            logLines = state.logLines,
            match = match,
            onFlip = { viewModel.flipPhoneCamera() },
        )
```

- [ ] **Step 3: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/CameraPreview.kt \
        app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt
git commit -m "Add CameraPreview composable and wire Preview use case in PhoneLiveSurface"
```

---

## Task 9: AnchorPipOverlay + translucent log overlay + flip button

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/live/AnchorPipOverlay.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt` (layer overlays on top of preview)

- [ ] **Step 1: Create AnchorPipOverlay composable**

`app/src/main/java/com/hereliesaz/doxray/ui/live/AnchorPipOverlay.kt`:

```kotlin
package com.hereliesaz.doxray.ui.live

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image

@Composable
fun AnchorPipOverlay(match: MatchEvent?, modifier: Modifier = Modifier) {
    if (match == null) return
    val bitmap = remember(match.firedAtMs) {
        match.anchorImageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    Column(modifier.padding(12.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = match.identityName,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(4.dp)).background(Color.DarkGray))
        }
        Box(modifier = Modifier.background(Color.Black.copy(alpha = 0.6f))) {
            Text(
                text = match.identityName,
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
```

- [ ] **Step 2: Add log overlay + flip button + AnchorPipOverlay to PhoneLiveSurface**

Edit `LiveScreen.kt`. Add these imports at the top:

```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
```

Replace `PhoneLiveSurface` body with the layered version:

```kotlin
@Composable
private fun PhoneLiveSurface(
    previewUseCase: androidx.camera.core.Preview?,
    logLines: List<String>,
    match: MatchEvent?,
    onFlip: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            previewUseCase = previewUseCase,
            modifier = Modifier.fillMaxSize(),
        )
        AnchorPipOverlay(
            match = match,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                )
                .padding(12.dp),
        ) {
            Column {
                logLines.takeLast(4).forEach { line ->
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        IconButton(
            onClick = onFlip,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            Text(text = "⟳", color = Color.White, fontSize = 20.sp)
        }
    }
}
```

- [ ] **Step 3: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/AnchorPipOverlay.kt \
        app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt
git commit -m "Add AnchorPipOverlay + translucent log overlay + flip button to PhoneLiveSurface"
```

---

## Task 10: NavRail Camera/Glasses toggle

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`

The toggle reads `inputMode` and calls one of two callbacks based on current state. The host (MainActivity, Task 11) supplies the wiring.

- [ ] **Step 1: Add parameters and the rail item**

Edit `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`. Change the function signature from:

```kotlin
@Composable
fun DoxrayNavRail(
    onExportClicked: () -> Unit = {},
    onImportClicked: () -> Unit = {},
) {
```

to:

```kotlin
@Composable
fun DoxrayNavRail(
    inputMode: com.hereliesaz.doxray.ui.live.InputMode = com.hereliesaz.doxray.ui.live.InputMode.META,
    onSwapInputClicked: () -> Unit = {},
    onExportClicked: () -> Unit = {},
    onImportClicked: () -> Unit = {},
) {
```

Inside the `AzHostActivityLayout { ... }` DSL block, after the existing `azRailItem(id = "audit", ...)` line and before the `azMenuItem` entries for Export/Import DB, add:

```kotlin
        azRailItem(
            id = "input-mode",
            text = if (inputMode == com.hereliesaz.doxray.ui.live.InputMode.META) "Camera" else "Glasses",
            route = "swap-input",
        )
```

The `azRailItem` API in this project doesn't expose `onClick` for navigation items — it routes by `route` string. To handle non-navigation taps we use a sentinel route. In `AzNavHost { composable(...) }` blocks, add a no-op composable at the bottom of the existing composables:

```kotlin
                composable("swap-input") {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        onSwapInputClicked()
                        navController.popBackStack()
                    }
                }
```

This fires the callback then pops itself so the user lands back where they were.

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. (MainActivity still passes the default no-op `onSwapInputClicked`; wired for real in Task 11.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt
git commit -m "Add Camera/Glasses toggle rail item in NavRail"
```

---

## Task 11: MainActivity LiveViewModel hoisting + camera permission launcher

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt` (drop the per-route VM acquisition for LIVE; consume from CompositionLocal)
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/live/LocalLiveViewModel.kt`

Single source of truth: MainActivity owns the `LiveViewModel`, provides it through a `CompositionLocal`. Both NavRail and LiveScreen consume from the same provider.

- [ ] **Step 1: Create the CompositionLocal**

`app/src/main/java/com/hereliesaz/doxray/ui/live/LocalLiveViewModel.kt`:

```kotlin
package com.hereliesaz.doxray.ui.live

import androidx.compose.runtime.compositionLocalOf

val LocalLiveViewModel = compositionLocalOf<LiveViewModel> {
    error("LocalLiveViewModel not provided")
}
```

- [ ] **Step 2: Hoist LiveViewModel in MainActivity**

Edit `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt`. Add imports:

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hereliesaz.doxray.ui.live.LiveViewModel
import com.hereliesaz.doxray.ui.live.LocalLiveViewModel
```

Inside the `setContent { ... }` block, after `val ctx = LocalContext.current` and before the `DoxrayNavRail(...)` call, hoist:

```kotlin
                    val activity = this@MainActivity
                    val liveVm: LiveViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                LiveViewModel(application, activity)
                            }
                        },
                    )
                    val cameraPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (granted) liveVm.onCameraPermissionGranted()
                        else liveVm.appendLog("Camera permission denied; phone mode unavailable.")
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        liveVm.permissionRequest.collect {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                    val inputMode by liveVm.inputMode.collectAsState()
```

(Add the import `import androidx.compose.runtime.collectAsState` and `import androidx.compose.runtime.getValue` if not present.)

Wrap the `DoxrayNavRail(...)` call in the CompositionLocal provider, and pass the new args:

```kotlin
                    CompositionLocalProvider(LocalLiveViewModel provides liveVm) {
                        DoxrayNavRail(
                            inputMode = inputMode,
                            onSwapInputClicked = {
                                if (inputMode == com.hereliesaz.doxray.ui.live.InputMode.META) {
                                    liveVm.requestPhoneCamera()
                                } else {
                                    liveVm.switchToMeta()
                                }
                            },
                            onExportClicked = { exportLauncher.launch("doxxr-export-${System.currentTimeMillis()}.zip") },
                            onImportClicked = { importLauncher.launch(arrayOf("application/zip")) },
                        )
                    }
```

`LiveViewModel.appendLog` may currently be private. Make it `internal` so MainActivity can call it. Find the existing definition (search for `private fun appendLog`) and change `private fun appendLog` → `internal fun appendLog`.

- [ ] **Step 3: Drop the per-route VM acquisition in NavRail**

Edit `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`. In the `AzNavHost { composable(Destinations.LIVE) { ... } }` block, change:

```kotlin
                composable(Destinations.LIVE) {
                    val vm: LiveViewModel = viewModel()
                    LiveScreen(viewModel = vm)
                }
```

to:

```kotlin
                composable(Destinations.LIVE) {
                    val vm = com.hereliesaz.doxray.ui.live.LocalLiveViewModel.current
                    LiveScreen(viewModel = vm)
                }
```

(Add `import com.hereliesaz.doxray.ui.live.LocalLiveViewModel` if you prefer the short name; either form is fine.)

- [ ] **Step 4: Remove the nullable default on LiveViewModel.lifecycleOwner (if added in Task 6)**

If Task 6 introduced a nullable default on the `lifecycleOwner` parameter (the "If the build fails" branch), revert it now — the factory in Step 2 above supplies a non-null `LifecycleOwner`. Change:

```kotlin
class LiveViewModel(
    application: Application,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
) : AndroidViewModel(application) {
```

back to:

```kotlin
class LiveViewModel(
    application: Application,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) : AndroidViewModel(application) {
```

And in `switchToPhone`, drop the null guard — `lifecycleOwner` is now always non-null.

- [ ] **Step 5: Verify build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`. All 68 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/MainActivity.kt \
        app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt \
        app/src/main/java/com/hereliesaz/doxray/ui/live/LocalLiveViewModel.kt \
        app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Hoist LiveViewModel in MainActivity; wire permission launcher; share via CompositionLocal"
```

---

## Task 12: DossierDetail anchor thumbnail

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt` (pass anchorImageDao to VM factory)

- [ ] **Step 1: Add anchorBytes to DossierDetailUiState and load it**

Edit `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt`. Change:

```kotlin
data class DossierDetailUiState(
    val identity: IdentityRecord? = null,
    val encounters: List<Encounter> = emptyList(),
    val socialLinks: List<String> = emptyList(),
    val backgroundData: JSONObject = JSONObject(),
)
```

to:

```kotlin
data class DossierDetailUiState(
    val identity: IdentityRecord? = null,
    val encounters: List<Encounter> = emptyList(),
    val socialLinks: List<String> = emptyList(),
    val backgroundData: JSONObject = JSONObject(),
    val anchorBytes: ByteArray? = null,
)
```

Change the constructor from:

```kotlin
class DossierDetailViewModel(
    private val faceId: String,
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
) : ViewModel() {
```

to:

```kotlin
class DossierDetailViewModel(
    private val faceId: String,
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val anchorImageDao: com.hereliesaz.doxray.db.AnchorImageDao,
) : ViewModel() {
```

Inside `init { viewModelScope.launch(Dispatchers.IO) { ... } }`, where the identity is loaded, also load the anchor:

```kotlin
        viewModelScope.launch(Dispatchers.IO) {
            val identity = identityDao.getIdentityById(faceId)
            val socialLinks = identity?.socialLinks
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val backgroundData = runCatching { JSONObject(identity?.backgroundData ?: "{}") }
                .getOrElse { JSONObject() }
            val anchor = anchorImageDao.getByFaceId(faceId)?.imageBytes
            _state.value = _state.value.copy(
                identity = identity,
                socialLinks = socialLinks,
                backgroundData = backgroundData,
                anchorBytes = anchor,
            )
        }
```

- [ ] **Step 2: Update factory in NavRail**

Edit `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`. Find the `DossierDetailViewModel(...)` factory inside the `composable(Destinations.DOSSIER_DETAIL)` block and change:

```kotlin
                            initializer {
                                val db = AppDatabase.getDatabase(app)
                                DossierDetailViewModel(
                                    faceId = faceId,
                                    identityDao = db.identityDao(),
                                    encounterDao = db.encounterDao(),
                                )
                            }
```

to:

```kotlin
                            initializer {
                                val db = AppDatabase.getDatabase(app)
                                DossierDetailViewModel(
                                    faceId = faceId,
                                    identityDao = db.identityDao(),
                                    encounterDao = db.encounterDao(),
                                    anchorImageDao = db.anchorImageDao(),
                                )
                            }
```

- [ ] **Step 3: Render the anchor thumbnail in DossierDetailScreen**

Edit `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`. Add imports:

```kotlin
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
```

Find the header `Row { Column { Text(primaryIdentity) ... } IconButton(...) }` block. Wrap the `Column` block in an outer `Row` that places the anchor thumbnail to the left:

```kotlin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    val anchor = state.anchorBytes
                    if (anchor != null) {
                        val bmp = remember(anchor) { BitmapFactory.decodeByteArray(anchor, 0, anchor.size) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = identity.primaryIdentity,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(Modifier.height(0.dp))
                            Spacer(modifier = Modifier.padding(end = 12.dp))
                        }
                    } else {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Color.LightGray))
                        Spacer(modifier = Modifier.padding(end = 12.dp))
                    }
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
                }
                IconButton(onClick = { confirmingDelete = true }) {
                    Text("✕")
                }
            }
```

(The outer `Row` exists already; the inner left-side `Row { anchor + Column }` is new.)

- [ ] **Step 4: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`. All 68 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt \
        app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt \
        app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt
git commit -m "Show anchor thumbnail in DossierDetail header"
```

---

## Task 13: Final verification

- [ ] **Step 1: Full build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. Total tests = 68 (63 prior + 2 PhoneFrameSource + 3 AnchorImageRepository).

- [ ] **Step 2: APK present**

Run: `ls -la app/build/outputs/apk/debug/app-debug.apk`
Expected: non-empty.

- [ ] **Step 3: Test-count check**

Run: `find app/build/test-results -name "*.xml" -exec grep -h "tests=" {} \; | sed -n 's/.*tests="\([0-9]*\)".*failures="\([0-9]*\)".*errors="\([0-9]*\)".*/\1 \2 \3/p' | awk '{t+=$1; f+=$2; e+=$3} END {print "tests:", t, "failures:", f, "errors:", e}'`
Expected: `tests: 68 failures: 0 errors: 0`.

- [ ] **Step 4: verifyMetaSdk still works**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew verifyMetaSdk --no-daemon 2>&1 | grep "gh.packages.url\|DAT SDK"`
Expected: same WARN line as previous phases.

- [ ] **Step 5: No commit needed.** Validation only.

---

## Notes for the executor

- **JDK:** `/usr/lib/jvm/java-21-openjdk-amd64`. Prefix every gradle command with `JAVA_HOME=…`.
- **vfat exec bit:** repo is on vfat; invoke gradle as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / Room 2.8.4 / OkHttp 5.3.2 / CameraX 1.3.4 (added in Task 1).
- **No new BuildConfig keys, no new permissions** — `CAMERA` already declared and pre-requested in `MainActivity.onCreate` from earlier phases.
- **Project naming:** the app is **Doxray** (`com.hereliesaz.doxray`). The repo directory happens to be `doxxr`; ignore that. Use "Doxray" in commit messages and code comments.
- **Robolectric tests** must run with `@Config(manifest = Config.NONE, sdk = [34])` (Robolectric 4.13 caps at SDK 34; matches Phase 3+ patterns).
- **MetaGlassesManager FrameListener shape**: read `app/src/main/java/com/hereliesaz/doxray/meta/MetaGlassesManager.kt` before implementing `MetaFrameSource` — if `FrameListener` isn't a SAM-compatible interface, the lambda in the spec needs adapting to a named anonymous class.
- **CameraX schema:** the v5 schema JSON `app/schemas/com.hereliesaz.doxray.db.AppDatabase/5.json` is generated by Room's KSP processor — commit it alongside the migration in Task 3.
- **`LiveViewModel.appendLog` visibility:** if currently `private`, Task 11 needs it `internal` so MainActivity can call it on permission denial. This is a one-keyword change; do it inline as part of Task 11 if not already done.
- **AzNavRail action callback pattern:** the existing Phase 5 "Export DB" / "Import DB" entries use `azMenuItem(onClick = ...)`. For the input-mode toggle in Task 10, we use `azRailItem` (visible in the collapsed rail, not just the drawer) and route via a sentinel composable since `azRailItem` doesn't expose `onClick`. If a newer AzNavRail version exposes `onClick` directly, prefer that.

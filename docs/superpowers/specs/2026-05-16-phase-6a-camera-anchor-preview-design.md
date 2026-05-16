# Phase 6a — Phone camera mode, anchor images, live preview UI

**Status:** approved 2026-05-16
**Successor of:** Phase 5 (OCR / re-ID surfacing / DB export-import)
**Schema impact:** Room DB v4 → v5

## Goal

Make doxxr usable from the device's own camera (not just Meta Ray-Ban glasses), persist a representative face crop per identity ("anchor image"), and overhaul `LiveScreen` from a text-log surface into a real-time camera preview with anchor-thumbnail picture-in-picture on match.

## Architecture

A new `FrameSource` interface abstracts the byte-stream from any input device. Two implementations: `MetaFrameSource` (default, wraps existing `MetaGlassesManager`) and `PhoneFrameSource` (CameraX `ImageAnalysis`, opt-in via NavRail). `FaceTrackerManager.processFrame(imageBytes)` is unchanged — both sources feed it.

```
NavRail "Camera"/"Glasses" toggle
   ↓
LiveViewModel.inputMode (StateFlow<InputMode>)
   ↓
LiveScreen branches: META → text log surface (current)
                     PHONE → fullscreen CameraPreview + log overlay + anchor PiP

frame source ── framesFlow ──> FaceTrackerManager ─→ embedding/OCR/pipeline (unchanged)
                                                  ↘ on match → AnchorImageRepository.upsert
                                                              ↘ lastMatchFlow → AnchorPipOverlay (8s)
```

**Mode swap UX:** Meta is the default (covert use case — screen off is the point of glasses). Phone mode is opt-in via an explicit NavRail button labeled "Camera". When active, the same button slot shows "Glasses" to swap back.

## Components

### FrameSource abstraction

`app/src/main/java/com/hereliesaz/doxray/camera/FrameSource.kt`:

```kotlin
interface FrameSource {
    val framesFlow: Flow<ByteArray>
    suspend fun start()
    suspend fun stop()
}
```

`MetaFrameSource(metaGlassesManager: MetaGlassesManager)` — thin adapter. `framesFlow` proxies `metaGlassesManager.framesFlow`. `start()/stop()` proxy connect/disconnect.

`PhoneFrameSource(context, lifecycleOwner)` — CameraX-based. Holds:
- `_framesFlow: MutableSharedFlow<ByteArray>` exposed read-only as `framesFlow`
- `useFrontCamera: Boolean` (default false)
- `lastEmittedMs: Long` (frame throttle state)

`start()`:
```kotlin
val provider = ProcessCameraProvider.getInstance(context).get()
val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(Size(1280, 720))
    .build()
    .also { it.setAnalyzer(executor, ::onFrame) }
val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
provider.unbindAll()
provider.bindToLifecycle(lifecycleOwner, selector, analysis, preview)
```

`onFrame(image: ImageProxy)`:
```kotlin
val now = System.currentTimeMillis()
if (now - lastEmittedMs < 200) { image.close(); return }
lastEmittedMs = now
val jpegBytes = encodeToJpeg(image, quality = 85)
image.close()
viewModelScope.launch { _framesFlow.emit(jpegBytes) }
```

YUV → JPEG via `YuvImage.compressToJpeg(...)`. Throttle to ~5fps. JPEG quality 85 matches `FaceTrackerManager.cropFace`.

`flipCamera()` toggles `useFrontCamera`, calls `stop()`, then `start()`. Clean rebind.

### AnchorImage entity + DAO

`app/src/main/java/com/hereliesaz/doxray/db/AnchorImage.kt`:

```kotlin
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
    override fun equals(other: Any?): Boolean { /* by-content over ByteArray */ }
    override fun hashCode(): Int { /* contentHashCode */ }
}
```

`AnchorImageDao`:
```kotlin
@Dao
interface AnchorImageDao {
    @Query("SELECT * FROM anchor_images WHERE faceId = :faceId LIMIT 1")
    suspend fun getByFaceId(faceId: String): AnchorImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(anchor: AnchorImage)
}
```

### AnchorImageRepository

`app/src/main/java/com/hereliesaz/doxray/api/AnchorImageRepository.kt`:

```kotlin
class AnchorImageRepository(private val dao: AnchorImageDao) {
    suspend fun upsert(faceId: String, imageBytes: ByteArray, qualityScore: Float) {
        val existing = dao.getByFaceId(faceId)
        if (existing == null || qualityScore > existing.qualityScore) {
            val bytes = ensureUnderOneMb(imageBytes) ?: return
            dao.upsert(AnchorImage(faceId, bytes, qualityScore, System.currentTimeMillis()))
        }
    }

    suspend fun get(faceId: String): AnchorImage? = dao.getByFaceId(faceId)

    private fun ensureUnderOneMb(bytes: ByteArray): ByteArray? {
        if (bytes.size <= 1_048_576) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray().takeIf { it.size <= 1_048_576 }
    }
}
```

### Migration v4 → v5

`app/src/main/java/com/hereliesaz/doxray/db/Migration_4_5.kt`:

```kotlin
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

`AppDatabase` bumps to `version = 5`, registers all three migrations.

### LiveViewModel additions

```kotlin
enum class InputMode { META, PHONE }

data class MatchEvent(
    val identityName: String,
    val anchorImageBytes: ByteArray?,
    val firedAtMs: Long,
)

// New fields
private val _inputMode = MutableStateFlow(InputMode.META)
val inputMode: StateFlow<InputMode> = _inputMode

private val _lastMatchFlow = MutableStateFlow<MatchEvent?>(null)
val lastMatchFlow: StateFlow<MatchEvent?> = _lastMatchFlow

private val _permissionRequest = MutableSharedFlow<Unit>()
val permissionRequest: SharedFlow<Unit> = _permissionRequest

private var clearMatchJob: Job? = null

// New methods
fun requestPhoneCamera()           // checks permission, emits request or switches directly
fun onCameraPermissionGranted()    // called by MainActivity after permission grant
fun switchToMeta()                  // stops phone source, starts meta source
fun flipPhoneCamera()                // delegates to PhoneFrameSource.flipCamera()
```

Match-event emission lives where the existing `appendLog("Cached Match: ...")` block lives in `processFocusedFace`:

```kotlin
_lastMatchFlow.value = MatchEvent(
    identityName = cachedMatch.primaryIdentity,
    anchorImageBytes = anchorImageRepository.get(cachedMatch.faceId)?.imageBytes,
    firedAtMs = System.currentTimeMillis(),
)
clearMatchJob?.cancel()
clearMatchJob = viewModelScope.launch {
    delay(8_000)
    _lastMatchFlow.value = null
}
```

Anchor upsert calls live alongside `localFaceCache.cacheIdentity` and after a successful `findMatch` returns a non-null hit. The repository compares scores; the VM doesn't need to know.

**Anchor quality score:** `QualityResult.Pass` is a singleton object (no numeric payload from Phase 2). The anchor pipeline derives its own score inline at the call site:

```kotlin
val qualityScore = faceFraction * (1f - (abs(eulerY) / 90f).coerceAtMost(1f))
```

Combined size × frontality heuristic in `[0f, 1f]`. Bigger face × straighter pose = higher score. Computed in `processFocusedFace` where `faceFraction` and `eulerX/Y/Z` are already in scope. On `QualityResult.Fail`, the face never reaches the anchor path.

### LiveScreen + new composables

`LiveScreen` branches on `inputMode.collectAsStateWithLifecycle()`:
- `META` → existing `MetaLiveSurface` (current text-log layout, untouched aside from being extracted into a private composable).
- `PHONE` → `PhoneLiveSurface(...)` as a `Box(fillMaxSize)` with 4 layered children.

`app/src/main/java/com/hereliesaz/doxray/ui/live/CameraPreview.kt` — wraps `androidx.camera.view.PreviewView` via `AndroidView`. Receives the `Preview` use case from the VM (the VM holds the use case via `PhoneFrameSource`).

`app/src/main/java/com/hereliesaz/doxray/ui/live/AnchorPipOverlay.kt`:
```kotlin
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
            Box(Modifier.size(96.dp).background(Color.DarkGray)) // placeholder
        }
        Text(match.identityName, fontSize = 12.sp, color = Color.White)
    }
}
```

### DossierDetailScreen anchor

Header `Column` (next to `primaryIdentity`) gains a 64dp `Image` showing `state.anchorBytes?.let { decode }`, with placeholder fallback when null. `DossierDetailViewModel.init` loads `anchorImageRepository.get(faceId)?.imageBytes`.

### NavRail mode toggle

`DoxrayNavRail` accepts:
- `inputMode: InputMode` (observed by host)
- `onSwapInput: () -> Unit` (calls VM.requestPhoneCamera or VM.switchToMeta based on mode)

DSL change:
```kotlin
azRailItem(
    id = "input-mode",
    text = if (inputMode == InputMode.META) "Camera" else "Glasses",
    route = "swap-input",
)
```

Click handler routes through the AzNavRail `onClick` callback (consistent with Phase 5's azMenuItem usage).

### MainActivity permission launcher

`setContent { ... }` registers:
```kotlin
val cameraPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    if (granted) liveVm.onCameraPermissionGranted()
    else liveVm.appendLog("Camera permission denied; phone mode unavailable.")
}

LaunchedEffect(Unit) {
    liveVm.permissionRequest.collect { cameraPermission.launch(Manifest.permission.CAMERA) }
}
```

Where `liveVm` is hoisted to `setContent`'s scope so both MainActivity (permission flow) and NavRail (mode swap) can reach it. Existing NavRail viewModel acquisition for `LiveScreen` adapts to consume the hoisted instance.

### Dependencies

`app/build.gradle.kts` adds (CameraX 1.3.4 stable):
```kotlin
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
```

No new permissions — `CAMERA` already declared.

## Error handling

| Failure | Behavior |
|---|---|
| `CAMERA` permission denied | `appendLog` warning; stay in META mode; "Camera" rail button still visible (user can retry) |
| `PhoneFrameSource.start()` throws | Catch in `start()`, emit error to log, revert `_inputMode` to META |
| No back camera (front-only device) | Catch CameraX exception in `start()`, retry once with `DEFAULT_FRONT_CAMERA`; on second failure, abort with log |
| Anchor BLOB > 1MB | Re-compress at JPEG quality 75; if still > 1MB, skip the anchor write (`Log.w` only); identity itself is unaffected |
| Anchor row constraint violation | `onConflict = REPLACE` handles it silently |
| Anchor decode failure on render | Render `Color.DarkGray` placeholder; no crash |
| Mode swap during in-flight identification | Existing `viewModelScope` cancellation handles it; in-flight job completes against original `imageBytes` |
| App backgrounded in phone mode | CameraX auto-unbinds (lifecycle observer); rebinds on foreground |
| Meta SDK stub (no `gh.packages.url`) | `MetaFrameSource` emits nothing; "Glasses" still selectable but yields no frames. Documented behavior, not an error. |

## Testing

Target: ~69 total tests (63 existing + ~6 new). All JUnit 4 + Robolectric, no instrumentation.

| Test class | Cases |
|---|---|
| `AnchorImageRepositoryTest` | (1) first call inserts; (2) higher-score call updates; (3) lower-score call keeps existing row unchanged. |
| `PhoneFrameSourceTest` | (1) rapid `onFrame` calls within 200ms emit only one frame (throttle); (2) `flipCamera()` toggles selector field. CameraX itself is stubbed/mocked — assertions are at the throttle and state-toggle layer only. |
| `Migration_4_5Test` | Open v4 DB via test helper → migrate → assert `anchor_images` table exists; insert row; query back. (If Room migration test infra is unavailable, fall back to a round-trip integration test on an in-memory DB built fresh at v5.) |

## Out of scope

- Encounter map view (deferred to Phase 6b).
- Database export/import does NOT round-trip anchors. Anchors stay local until a future "Phase 6c" export expansion.
- No Compose UI tests, no screenshot tests, no CameraX instrumentation tests.
- No camera zoom, tap-to-focus, exposure controls, or other camera-app niceties.
- No video recording.
- No camera-mode persistence across sessions (always starts in META mode).
- No anchor display in DossierList (the listing screen) — DossierDetail only.

## File map

**New:**
- `app/src/main/java/com/hereliesaz/doxray/camera/FrameSource.kt`
- `app/src/main/java/com/hereliesaz/doxray/camera/MetaFrameSource.kt`
- `app/src/main/java/com/hereliesaz/doxray/camera/PhoneFrameSource.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/AnchorImage.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/AnchorImageDao.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/Migration_4_5.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/AnchorImageRepository.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/live/CameraPreview.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/live/AnchorPipOverlay.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/AnchorImageRepositoryTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/camera/PhoneFrameSourceTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/db/Migration_4_5Test.kt`

**Modified:**
- `app/build.gradle.kts` — add CameraX 1.3.4 deps
- `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt` — version 5, register Migration_4_5, expose anchorImageDao()
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` — inputMode, lastMatchFlow, requestPhoneCamera, onCameraPermissionGranted, switchToMeta, flipPhoneCamera; anchor upsert calls
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt` — branch by inputMode; extract MetaLiveSurface; add PhoneLiveSurface
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt` — load anchor in init
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt` — render anchor thumbnail
- `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt` — add input-mode rail item with dynamic Camera/Glasses label
- `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt` — RequestPermission launcher, hoist LiveViewModel for NavRail consumption

## Notes for the executor

- **JDK:** `/usr/lib/jvm/java-21-openjdk-amd64`. Prefix every gradle command with `JAVA_HOME=…`.
- **vfat exec bit:** repo is on vfat; invoke gradle as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / Room 2.8.4 / OkHttp 5.3.2.
- **No new BuildConfig keys, no new permissions.**
- **CameraX 1.3.4** is the most recent stable as of 2026-05; bump if a newer stable exists by execution time.
- **Match emission and anchor upsert** both run on the cached-match branch and the new-identity branch of `processFocusedFace`. Don't miss the second site.
- **LiveViewModel hoisting:** the existing `viewModel()` call inside `composable(Destinations.LIVE)` returns a per-route VM, but NavRail lives outside that route's composition scope. Solution: introduce a `CompositionLocalProvider(LocalLiveViewModel provides liveVm)` wrapper around `DoxrayNavRail(...)` in MainActivity's `setContent`, where `liveVm` is acquired via `viewModel<LiveViewModel>()` at the MainActivity composition root. Both `DoxrayNavRail` (rail toggle) and the `LiveScreen` composable consume via `LocalLiveViewModel.current`. The existing `viewModel()` call inside `composable(Destinations.LIVE)` is dropped — single source of truth at the top level.

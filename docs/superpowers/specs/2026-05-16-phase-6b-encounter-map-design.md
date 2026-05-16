# Phase 6b — Encounter map view in DossierDetail

**Status:** approved 2026-05-16
**Successor of:** Phase 6a (camera mode, anchor images, live preview UI)
**Schema impact:** none — uses existing `Encounter` lat/lon columns

## Goal

Add an osmdroid-backed map widget to `DossierDetailScreen` that pins every encounter with geo data. Map is full-bleed (background of a `BottomSheetScaffold`); the existing dossier content moves into the sheet with a 120dp peek height. Fall back to the current layout unchanged when no encounter has lat/lon.

## Architecture

`DossierDetailScreen` branches at render time:

```
state.encounters.any { it.latitude != null && it.longitude != null }
   ├─ true  → BottomSheetScaffold(
   │            sheetPeekHeight = 120.dp,
   │            sheetContent = existing dossier composables (LazyColumn body),
   │            content = EncounterMap(geoEncounters),
   │          )
   └─ false → existing LazyColumn fallback (unchanged from Phase 6a)
```

The map widget is a single new composable that owns its osmdroid `MapView` via `AndroidView`. Initialization, marker placement, fit-to-bounds, and lifecycle hooks all live inside it.

## Components

### EncounterMap composable

`app/src/main/java/com/hereliesaz/doxray/ui/dossier/EncounterMap.kt`:

```kotlin
@Composable
fun EncounterMap(
    encounters: List<Encounter>,
    modifier: Modifier = Modifier,
)
```

Responsibilities:
1. Run `osmdroidInit(context)` (idempotent — caches internally).
2. Wrap `org.osmdroid.views.MapView` via `AndroidView`. Use `cacheDir` for tile cache (keep tiles out of public storage).
3. Add a `Marker` per encounter with non-null lat/lon. `title` = formatted timestamp (`SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.getDefault())`), `snippet` = `"±${accuracy.toInt()}m"` when `locationAccuracyMeters != null` else empty.
4. `fitMapToEncounters(map, encounters)`:
   - Filter to non-null lat/lon points.
   - Single point: `setZoom(15.0)` + `setCenter(point)`.
   - Multiple points: `BoundingBox.fromGeoPoints(points)` + `zoomToBoundingBox(box, false, 64)` (64-pixel padding).
5. Lifecycle hooks via `DisposableEffect`:
   - On dispose: `mapView.onPause()` then `mapView.onDetach()`.
   - Resume on each composition: `mapView.onResume()` inside `factory` and `update` blocks.

Private helper `osmdroidInit(context: Context)`:

```kotlin
private fun osmdroidInit(context: Context) {
    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    val cfg = org.osmdroid.config.Configuration.getInstance()
    cfg.load(context, prefs)
    cfg.userAgentValue = "com.hereliesaz.doxray"
    cfg.osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles")
}
```

osmdroid 6.x requires a user-agent string to comply with OpenStreetMap tile usage policy. Setting it to the package name is acceptable for low-volume non-commercial use.

### DossierDetailScreen restructure

In `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DossierDetailScreen(viewModel: DossierDetailViewModel, onDeleted: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val identity = state.identity
    if (identity == null) {
        Text(text = "Dossier not found.", modifier = Modifier.padding(16.dp))
        return
    }
    val geoEncounters = state.encounters.filter { it.latitude != null && it.longitude != null }
    if (geoEncounters.isEmpty()) {
        DossierContent(state, onDeleted)  // existing LazyColumn body, extracted
    } else {
        BottomSheetScaffold(
            sheetPeekHeight = 120.dp,
            sheetContent = { DossierContent(state, onDeleted) },
            content = { padding ->
                EncounterMap(
                    encounters = geoEncounters,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            },
        )
    }
}
```

`DossierContent(state, onDeleted)` is extracted from the existing screen body as a private composable taking the state and delete callback — mechanical move, no logic change.

### Dependencies

`app/build.gradle.kts` adds:

```kotlin
    implementation("org.osmdroid:osmdroid-android:6.1.20")
```

osmdroid pulls `androidx.preference:preference:1.2.x` transitively, which the project may not already have — verify after adding.

## Data flow

Identical to Phase 6a — `DossierDetailViewModel` observes `encounterDao.observeByFace(faceId)` and populates `state.encounters`. The new map widget filters this list at render time. No new VM state, no new DAO calls.

## Error handling

| Failure | Behavior |
|---|---|
| Tile fetch fails (offline) | osmdroid handles internally — pins render on gray background. No app-level handling. |
| Corrupted lat/lon in DB | `GeoPoint` clamps silently to valid range. Pin places at clamp boundary. |
| All encounters have null lat/lon | `DossierDetailScreen` falls back to existing `LazyColumn` before constructing the map. |
| Single encounter | `fitMapToEncounters` branches to `setZoom(15.0) + setCenter(point)` (BoundingBox of one point has zero extent). |
| MapView lifecycle (Compose recomposition) | `AndroidView.factory` builds once; `DisposableEffect` calls `onPause()` + `onDetach()` on cleanup. `onResume()` runs in `factory` and `update`. |
| Configuration loaded multiple times | osmdroid is idempotent on repeated `Configuration.getInstance().load(...)`. |
| Tile cache full | osmdroid auto-evicts LRU. |

## Testing

**0 new tests.** Total stays at 68 (carried over from Phase 6a).

Rationale: osmdroid's `MapView` is `AndroidView`-wrapped — not exercisable under Robolectric. The map widget has no testable pure-Kotlin surface beyond a one-line `any { ... }` branch. Established Phase 1/3/5 pattern: UI-rendering changes are validated visually + via `assembleDebug` success, not unit tests.

Validation:
1. `JAVA_HOME=… bash ./gradlew :app:assembleDebug :app:test --no-daemon` → BUILD SUCCESSFUL, 68 tests pass.
2. APK present at `app/build/outputs/apk/debug/app-debug.apk`.
3. Manual smoke (on device): open a dossier with at least one geo-tagged encounter → confirm map renders + pins place + sheet peeks at 120dp.

## Out of scope

- Map tile dark/light theming.
- Custom marker icons (using osmdroid's default red pin).
- Marker clustering at high zoom-out levels.
- "Current location" indicator (dossier is historical, not live).
- DossierList map view (only DossierDetail).
- Anchor image round-trip through export (still deferred from 6a).
- Tap-to-scroll between map and encounter list (decoupled — map InfoWindow is sufficient).

## File map

**New:**
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/EncounterMap.kt`

**Modified:**
- `app/build.gradle.kts` — add `org.osmdroid:osmdroid-android:6.1.20`
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt` — extract `DossierContent` composable; branch on `geoEncounters` non-empty to render `BottomSheetScaffold` or fall back to existing `LazyColumn`

## Notes for the executor

- **JDK:** `/usr/lib/jvm/java-21-openjdk-amd64`. Prefix every gradle command with `JAVA_HOME=…`.
- **vfat exec bit:** repo is on vfat; invoke gradle as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / Room 2.8.4 / OkHttp 5.3.2 / CameraX 1.3.4 / osmdroid 6.1.20 (added in this phase).
- **No new permissions, no new BuildConfig keys.**
- **Compose Material3 BottomSheetScaffold** is in BOM 2026.05.00. Use `@OptIn(ExperimentalMaterial3Api::class)` on the composable function — the API is still marked experimental in this BOM version.
- **osmdroid user agent** must be set before adding the MapView, or tile fetches return 403. Set in `osmdroidInit` per the spec.
- **Project name** is **Doxray** (repo dir `doxxr` is unrelated).

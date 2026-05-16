# Phase 6b — Encounter Map View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an osmdroid-backed encounter map to `DossierDetailScreen` as the full-bleed background of a `BottomSheetScaffold` (120dp peek) when any encounter has lat/lon; fall back to the current `LazyColumn` layout otherwise.

**Architecture:** One new composable `EncounterMap` owns its osmdroid `MapView` via `AndroidView` and handles init, marker placement, fit-to-bounds, and lifecycle. `DossierDetailScreen` branches at render time: with-geo → `BottomSheetScaffold`, without-geo → existing layout. Existing dossier body is extracted into a private `DossierContent` composable shared by both branches.

**Tech Stack:** Kotlin 2.3.21 / Compose Material3 BOM 2026.05.00 / osmdroid 6.1.20 (new) / existing Room DB.

---

## Task 1: Add osmdroid dependency + create EncounterMap composable

**Files:**
- Modify: `app/build.gradle.kts` (add 1 dep)
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/EncounterMap.kt`

- [ ] **Step 1: Add osmdroid dependency**

Edit `app/build.gradle.kts`. In the `dependencies { }` block (next to the existing CameraX lines added in Phase 6a), add:

```kotlin
    implementation("org.osmdroid:osmdroid-android:6.1.20")
```

- [ ] **Step 2: Create the EncounterMap composable**

`app/src/main/java/com/hereliesaz/doxray/ui/dossier/EncounterMap.kt`:

```kotlin
package com.hereliesaz.doxray.ui.dossier

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import com.hereliesaz.doxray.db.Encounter
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-bleed map showing every [Encounter] with non-null lat/lon as a pin.
 * Tapping a pin opens osmdroid's default InfoWindow with the formatted
 * timestamp and accuracy radius.
 */
@Composable
fun EncounterMap(
    encounters: List<Encounter>,
    modifier: Modifier = Modifier,
) {
    var mapView: MapView? = null
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            osmdroidInit(ctx)
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                addEncounterMarkers(this, encounters)
                fitMapToEncounters(this, encounters)
                onResume()
            }.also { mapView = it }
        },
        update = { map ->
            map.overlays.clear()
            addEncounterMarkers(map, encounters)
            fitMapToEncounters(map, encounters)
            map.invalidate()
            map.onResume()
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            mapView?.onPause()
            mapView?.onDetach()
        }
    }
}

private fun osmdroidInit(context: Context) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val cfg = Configuration.getInstance()
    cfg.load(context, prefs)
    cfg.userAgentValue = "com.hereliesaz.doxray"
    cfg.osmdroidTileCache = File(context.cacheDir, "osmdroid_tiles")
}

private fun addEncounterMarkers(map: MapView, encounters: List<Encounter>) {
    val fmt = SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.getDefault())
    for (e in encounters) {
        val lat = e.latitude ?: continue
        val lon = e.longitude ?: continue
        val marker = Marker(map).apply {
            position = GeoPoint(lat, lon)
            title = fmt.format(Date(e.timestamp))
            snippet = e.locationAccuracyMeters?.let { "±${it.toInt()}m" }.orEmpty()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(marker)
    }
}

private fun fitMapToEncounters(map: MapView, encounters: List<Encounter>) {
    val points = encounters.mapNotNull { e ->
        val lat = e.latitude ?: return@mapNotNull null
        val lon = e.longitude ?: return@mapNotNull null
        GeoPoint(lat, lon)
    }
    if (points.isEmpty()) return
    if (points.size == 1) {
        map.controller.setZoom(15.0)
        map.controller.setCenter(points[0])
    } else {
        val box = BoundingBox.fromGeoPoints(points)
        map.zoomToBoundingBox(box, false, 64)
    }
}
```

- [ ] **Step 3: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. osmdroid + transitive `androidx.preference:preference` downloads and compiles.

If the build fails on `androidx.preference.PreferenceManager` unresolved, add to `app/build.gradle.kts`:

```kotlin
    implementation("androidx.preference:preference:1.2.1")
```

(osmdroid normally pulls this transitively but the version may need explicit pinning.)

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/hereliesaz/doxray/ui/dossier/EncounterMap.kt
git commit -m "Add osmdroid dep and EncounterMap composable with fit-to-bounds + InfoWindow"
```

---

## Task 2: Restructure DossierDetailScreen with BottomSheetScaffold

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`

Extract the existing screen body into a private `DossierContent` composable, then branch the top-level `DossierDetailScreen` to wrap it in `BottomSheetScaffold` when any encounter has geo data.

- [ ] **Step 1: Replace the file with the restructured version**

Replace `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt` with:

```kotlin
package com.hereliesaz.doxray.ui.dossier

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.doxray.db.Encounter
import com.hereliesaz.doxray.db.IdentityRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        DossierContent(viewModel = viewModel, state = state, identity = identity, onDeleted = onDeleted)
    } else {
        BottomSheetScaffold(
            sheetPeekHeight = 120.dp,
            sheetContent = {
                DossierContent(viewModel = viewModel, state = state, identity = identity, onDeleted = onDeleted)
            },
            content = { padding ->
                EncounterMap(
                    encounters = geoEncounters,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            },
        )
    }
}

@Composable
private fun DossierContent(
    viewModel: DossierDetailViewModel,
    state: DossierDetailUiState,
    identity: IdentityRecord,
    onDeleted: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
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
                        } else {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Color.LightGray))
                        }
                    } else {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Color.LightGray))
                    }
                    Spacer(modifier = Modifier.padding(end = 12.dp))
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
            Spacer(Modifier.height(16.dp))
            Text(text = "Encounters", fontWeight = FontWeight.Bold)
        }
        items(state.encounters, key = { it.id }) { e ->
            EncounterRow(e)
        }
        if (state.socialLinks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(text = "Links", fontWeight = FontWeight.Bold)
                state.socialLinks.forEach { Text(text = it, fontSize = 12.sp) }
            }
        }
        if (state.backgroundData.length() > 0) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(text = "Background", fontWeight = FontWeight.Bold)
                Text(text = state.backgroundData.toString(2), fontSize = 12.sp)
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete dossier?") },
            text = { Text("This permanently removes ${identity.primaryIdentity} and all encounters.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete(onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EncounterRow(e: Encounter) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = formatAbsolute(e.timestamp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (e.latitude != null && e.longitude != null) {
            val acc = e.locationAccuracyMeters?.let { " ±${it.toInt()}m" } ?: ""
            Text(text = "📍 ${"%.5f".format(e.latitude)}, ${"%.5f".format(e.longitude)}$acc", fontSize = 12.sp)
        } else {
            Text(text = "📍 location unavailable", fontSize = 12.sp)
        }
    }
}

private fun formatAbsolute(ms: Long): String =
    SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.getDefault()).format(Date(ms))
```

Changes vs. the existing file:
- Added imports: `BottomSheetScaffold`, `ExperimentalMaterial3Api`, `IdentityRecord`.
- `DossierDetailScreen` no longer holds `confirmingDelete` state (moved into `DossierContent`).
- New `geoEncounters` filter at the screen level; branches between `DossierContent` directly (no map) and `BottomSheetScaffold(sheetContent = DossierContent, content = EncounterMap)`.
- New private `DossierContent` composable wraps the previous screen body (`LazyColumn` + `AlertDialog`).
- `EncounterRow` and `formatAbsolute` unchanged.

- [ ] **Step 2: Verify build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`. All 68 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt
git commit -m "Wrap DossierDetail in BottomSheetScaffold when encounters have geo data"
```

---

## Task 3: Final verification

- [ ] **Step 1: Full build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. Total tests = 68 (unchanged — no new tests in Phase 6b per the spec).

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
- **Toolchain:** Kotlin 2.3.21 / Compose Material3 BOM 2026.05.00 / osmdroid 6.1.20 (new in this phase).
- **No new permissions, no new BuildConfig keys.** `INTERNET` and `ACCESS_NETWORK_STATE` already declared from earlier phases.
- **`BottomSheetScaffold`** is still marked `@ExperimentalMaterial3Api` in BOM 2026.05.00. The `@OptIn(ExperimentalMaterial3Api::class)` annotation on `DossierDetailScreen` is required.
- **osmdroid user agent** must be set in `osmdroidInit` before `MapView` is constructed — otherwise OSM tile requests return 403.
- **Tile caching** uses `context.cacheDir/osmdroid_tiles`. The OS may purge `cacheDir` under storage pressure; that's fine — tiles re-download on next launch.
- **Project name** is **Doxray** (repo dir `doxxr` is unrelated).

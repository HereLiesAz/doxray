# Phase 1 — Dossier Surface in Compose

**Date:** 2026-05-15
**Status:** Approved
**Predecessor:** [Phase 0 — Baseline Fix-ups](2026-05-14-phase-0-baseline-fixups-design.md)
**Successor:** Phase 2 — Quality & efficiency gate (TBD)

---

## Context

After Phase 0 the doxxr pipeline is no longer theatrical: HTTP is centralised, Lenso parses correctly, scrapers replay real flows. But the user cannot *see* what the app captures. `LocalFaceCache` writes to Room and the only UI surface is a single Live screen with a connect/disconnect button and a flat log. Every cached identity is invisible. Every audit-worthy event is unrecorded. Encounter geography is unknown.

Phase 1 fixes that. After Phase 1:

- A "Dossiers" screen lists everyone the app has identified, with encounter count and last-seen time.
- Tapping a dossier opens a detail view with the full encounter timeline (timestamp + GPS pin), social links, background data, and a delete control.
- Every face capture, outbound API call, dossier read, and app-lifecycle event lands in a persistent audit log, viewable on its own screen.
- Navigation is via a custom rail/drawer hybrid (`AzNavRail`).

Watchlist behaviour is explicitly out of scope and deferred to a later phase. GPS is best-effort, foreground only.

---

## Goals

After Phase 1:

1. `MainActivity` hosts `AzHostActivityLayout` with three rail destinations: Live, Dossiers, Audit. Old Compose code moves into `LiveScreen.kt`.
2. Each identification recorded by `LocalFaceCache` writes a new row to a new `encounters` table; latitude/longitude attached when permission is granted.
3. The user can browse a list of all known dossiers and tap into a detail view.
4. Every IDENTIFY / API_CALL / DOSSIER_READ / LIFECYCLE event lands in a new `audit_events` table.
5. Room schema bumps to v3 with a real `Migration` — existing cached identities survive the upgrade.

Non-goals:

- Watchlist mode (deferred)
- Map view of encounters (Phase 1 shows raw coords; a real map needs Google Maps SDK)
- Encounter thumbnails (deferred — Phase 2 has a quality gate that produces the right input)
- Compose UI tests (deferred — layouts will iterate)
- Background location permission

---

## Architecture

### Package layout

```
com.hereliesaz.doxray
├── DoxrayApp.kt            ← existing; now also bootstraps AuditLogger
├── MainActivity.kt         ← shrinks to ~50 lines, hosts AzHostActivityLayout
├── nav/
│   ├── Destinations.kt     ← string route constants
│   └── DoxrayNavRail.kt    ← AzHostActivityLayout DSL + AzNavHost wiring
├── ui/
│   ├── live/
│   │   ├── LiveScreen.kt           ← moved from MainActivity (Compose body)
│   │   └── LiveViewModel.kt        ← extracts service wiring out of the Activity
│   ├── dossier/
│   │   ├── DossierListScreen.kt
│   │   ├── DossierListViewModel.kt
│   │   ├── DossierDetailScreen.kt
│   │   └── DossierDetailViewModel.kt
│   └── audit/
│       ├── AuditLogScreen.kt
│       └── AuditLogViewModel.kt
├── location/
│   └── LocationService.kt          ← FusedLocationProviderClient wrapper
└── audit/
    └── AuditLogger.kt              ← singleton facade
```

### Build wiring

- `settings.gradle.kts`: add `maven { url = uri("https://jitpack.io") }` to `dependencyResolutionManagement.repositories`.
- `app/build.gradle.kts`: add
  - `implementation("com.github.HereLiesAz:AzNavRail:8.11")`
  - `implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")` (already implied by the Compose BOM but declare explicitly)
  - `implementation("com.google.android.gms:play-services-location:21.3.0")`
  - `androidTestImplementation("androidx.room:room-testing:2.8.4")` (for migration tests)

### Navigation

`AzHostActivityLayout` lives in `MainActivity.setContent { … }`. Three top-level destinations, declared as `azRailItem`s:

```kotlin
azConfig(dockingSide = AzDockingSide.LEFT, packButtons = true)
azTheme(activeColor = MaterialTheme.colorScheme.primary)
azRailItem(id = "live", text = "Live", route = Destinations.LIVE)
azRailItem(id = "dossiers", text = "Dossiers", route = Destinations.DOSSIERS)
azRailItem(id = "audit", text = "Audit", route = Destinations.AUDIT)
onscreen(alignment = Alignment.Center) {
    AzNavHost(startDestination = Destinations.LIVE) {
        composable(Destinations.LIVE) { LiveScreen(viewModel()) }
        composable(Destinations.DOSSIERS) {
            DossierListScreen(viewModel(), onOpen = { faceId ->
                navController.navigate("${Destinations.DOSSIERS}/$faceId")
            })
        }
        composable("${Destinations.DOSSIERS}/{faceId}") { entry ->
            DossierDetailScreen(viewModel(), faceId = entry.arguments?.getString("faceId").orEmpty())
        }
        composable(Destinations.AUDIT) { AuditLogScreen(viewModel()) }
    }
}
```

`Destinations` is a tiny `object` with `const val LIVE = "live"`, etc.

---

## Schema

### `Encounter` (new)

```kotlin
@Entity(
    tableName = "encounters",
    foreignKeys = [ForeignKey(
        entity = IdentityRecord::class,
        parentColumns = ["faceId"],
        childColumns = ["faceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("faceId"), Index("timestamp")],
)
data class Encounter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val faceId: String,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
)
```

### `AuditEvent` (new)

```kotlin
@Entity(tableName = "audit_events", indices = [Index("timestamp")])
data class AuditEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,         // "IDENTIFY" | "API_CALL" | "DOSSIER_READ" | "LIFECYCLE"
    val summary: String,
    val detailsJson: String,  // "{}" by default
)
```

### `IdentityRecord` (unchanged this phase)

The denormalised `lastSeenTimestamp` / `encounterCount` fields stay — they're already maintained by `IdentityDao.recordEncounter` and let `DossierListScreen` render without joining. `DossierDetailScreen` reads the full encounter history via `EncounterDao.observeByFace`. Both writes happen together in `LocalFaceCache`: when a cache match fires, `findMatch` calls `recordEncounter` (existing) *and* inserts a new `Encounter` row; when a new identity is cached, `cacheIdentity` inserts the first `Encounter` row. The two writes are not transactional — if either fails the other still helps — and that's acceptable for a best-effort dossier surface.

### Migration 2 → 3

Drop `fallbackToDestructiveMigration()` from `AppDatabase`. Add:

```kotlin
object Migration_2_3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE encounters (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                faceId TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                locationAccuracyMeters REAL,
                FOREIGN KEY(faceId) REFERENCES identity_records(faceId) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_encounters_faceId ON encounters(faceId)")
        db.execSQL("CREATE INDEX index_encounters_timestamp ON encounters(timestamp)")

        db.execSQL("""
            CREATE TABLE audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                summary TEXT NOT NULL,
                detailsJson TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_audit_events_timestamp ON audit_events(timestamp)")
    }
}
```

Wired via `.addMigrations(Migration_2_3)` in `AppDatabase.getDatabase`.

### DAOs

```kotlin
@Dao
interface EncounterDao {
    @Insert suspend fun insert(encounter: Encounter): Long
    @Query("SELECT * FROM encounters WHERE faceId = :faceId ORDER BY timestamp DESC")
    fun observeByFace(faceId: String): Flow<List<Encounter>>
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(event: AuditEvent): Long
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AuditEvent>>
}
```

`IdentityDao` gains:

```kotlin
@Query("SELECT * FROM identity_records ORDER BY lastSeenTimestamp DESC")
fun observeAll(): Flow<List<IdentityRecord>>

@Query("DELETE FROM identity_records WHERE faceId = :faceId")
suspend fun delete(faceId: String): Int
```

---

## AuditLogger

```kotlin
object AuditLogger {
    enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE }

    private var dao: AuditDao? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(dao: AuditDao) { this.dao = dao }

    fun log(type: Type, summary: String, details: JSONObject = JSONObject()) {
        val current = dao ?: return
        val event = AuditEvent(
            timestamp = System.currentTimeMillis(),
            type = type.name,
            summary = summary,
            detailsJson = details.toString(),
        )
        scope.launch { runCatching { current.insert(event) } }
    }
}
```

Initialised from `DoxrayApp.onCreate` after `AppDatabase.getDatabase(this)` is constructed. Logging is fire-and-forget — never throws into the caller, never blocks.

Call sites:

| Event | Caller |
|-------|--------|
| `IDENTIFY` with face match details | `LocalFaceCache.findMatch` (on cache hit) and `LocalFaceCache.cacheIdentity` (on new identity) |
| `API_CALL` with host + status code | `CaptureInterceptor` — extend it to call `AuditLogger.log(API_CALL, "$method $host → $code", …)` after writing the capture |
| `DOSSIER_READ` with faceId | `DossierDetailViewModel.init` (one log per detail navigation) |
| `LIFECYCLE` | `DoxrayApp.onCreate` ("app start"), `LiveViewModel.connect`/`disconnect` ("glasses connected" / "disconnected") |

---

## Location

`LocationService` is a thin wrapper, **not** a singleton (one per Activity scope is fine):

```kotlin
class LocationService(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Returns a recent location if permission granted and a fix is available,
     * else null. Best-effort: never throws.
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? {
        if (!hasFinePermission() && !hasCoarsePermission()) return null
        return try {
            suspendCancellableCoroutine { cont ->
                fused.lastLocation
                    .addOnSuccessListener { cont.resume(it) {} }
                    .addOnFailureListener { cont.resume(null) {} }
            }
        } catch (e: Exception) { null }
    }

    private fun hasFinePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasCoarsePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
```

Wired into `LocalFaceCache.cacheIdentity` (or wherever the encounter row is written) so each `Encounter` insert gets the freshest available pin.

### Manifest

Add to `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### Permission request

`LiveScreen` registers a `rememberLauncherForActivityResult(RequestMultiplePermissions())` and requests both location permissions alongside the existing camera/bluetooth permission flow on first Connect tap. Denial is non-fatal — the rest of the pipeline proceeds with null GPS coords.

---

## ViewModels

Each VM owns a `StateFlow<UiState>` exposed to its screen. Reads from DAOs via `Flow` collected in `viewModelScope`. Writes are suspend functions on the VM.

### `DossierListViewModel`

```kotlin
data class DossierListUiState(val rows: List<DossierRow> = emptyList())
data class DossierRow(
    val faceId: String,
    val name: String,
    val encounterCount: Int,
    val lastSeenMillis: Long,
)
```

Backed by `IdentityDao.observeAll()` mapped into `DossierRow`. No join needed — the denormalised count/timestamp live on `IdentityRecord` already.

### `DossierDetailViewModel`

```kotlin
data class DossierDetailUiState(
    val identity: IdentityRecord? = null,
    val encounters: List<Encounter> = emptyList(),
    val socialLinks: List<String> = emptyList(),
    val backgroundData: JSONObject = JSONObject(),
)
```

Constructed with a `faceId` arg. Combines `IdentityDao.getIdentityById(faceId)` (one-shot) and `EncounterDao.observeByFace(faceId)` (flow). Logs `DOSSIER_READ` once on init. Exposes `delete()` which calls `IdentityDao.delete(faceId)` and navigates back.

### `AuditLogViewModel`

Backed by `AuditDao.observeRecent(limit = 200)`. Exposes the flow as `StateFlow<List<AuditEvent>>`. No pagination yet — 200 is enough for human review.

### `LiveViewModel` (refactor)

Moves the service wiring (FaceTracker, the 4 search services + 4 scrapers + 2 bg scrapers, MetaGlassesManager, EmbeddingGenerator, LocalFaceCache) out of `MainActivity` and into a VM. Activity becomes pure rendering. The existing `DoxrayUiState` class in `MainActivity.kt` becomes the VM's state holder.

This is a real refactor — it should land in a single task so reviewers can audit the move atomically.

---

## Screens

### `LiveScreen`

Same shape as the current Compose body — status text, Connect/Disconnect buttons, log `LazyColumn`. Buttons call into `LiveViewModel`. No visual change for the user.

### `DossierListScreen`

`LazyColumn` of card rows:

```
┌────────────────────────────────────────────────┐
│ Sarah Chen                              4×     │
│ 2 hours ago                                    │
└────────────────────────────────────────────────┘
```

- Name (large), encounter count (right-aligned)
- Last-seen relative time (`DateUtils.getRelativeTimeSpanString`)
- Whole row tappable → navigates to detail
- Empty state: "No dossiers yet. Connect to glasses and the app will start cataloguing faces."

### `DossierDetailScreen`

`LazyColumn` with sections:

1. **Header** — name, total encounters, first/last seen timestamps. Right-edge delete `IconButton` (with confirm dialog).
2. **Encounter timeline** — each row: timestamp + optional `(lat, lng ± Xm)` chip. Reverse-chronological.
3. **Social links** — chips wrapping; each tappable opens browser intent.
4. **Background data** — parsed from `IdentityRecord.backgroundData` JSON; rendered as `phones / addresses / relatives / emails` sub-sections.

### `AuditLogScreen`

Reverse-chronological `LazyColumn`. Each row:

```
┌────────────────────────────────────────────────┐
│ [API_CALL]  POST api.eyematch.ai/search → 200  │
│ 2 minutes ago                                  │
└────────────────────────────────────────────────┘
```

Tap expands the row to show `detailsJson` pretty-printed.

---

## Testing

### Unit tests (`app/src/test/`)

1. `MigrationTest` — uses `MigrationTestHelper` from `androidx.room:room-testing` to verify `Migration_2_3` succeeds on a v2 DB with at least one row, and that the row survives intact.
2. `EncounterDaoTest` — in-memory Room. Insert two encounters for one identity, observe via `observeByFace`, assert order + content.
3. `AuditDaoTest` — same pattern. Insert events, assert `observeRecent` returns them newest-first respecting `limit`.
4. `DossierListViewModelTest` — fake `IdentityDao` returning a fixed `Flow<List<IdentityRecord>>`; assert VM maps to `DossierRow` correctly and exposes via state flow.

### Instrumented test

`MigrationTest` runs as an Android instrumented test (Room's helper requires `androidx.test`). Add `androidTestImplementation("androidx.room:room-testing:2.8.4")`.

### No Compose UI tests this phase

Layouts will iterate. We'll add Compose UI tests in a later phase when the surface stabilises.

---

## Decisions made during brainstorm

- **Navigation library:** AzNavRail (user-supplied; guide at `docs/AZNAVRAIL_COMPLETE_GUIDE.md`). Settles the bottom-nav / drawer / single-screen question.
- **Watchlist:** explicitly out of scope for Phase 1. Every match is silently cached and listed.
- **GPS source:** Fused Location Provider, foreground-only. Best-effort — denial yields null coords. No background location.
- **Audit scope:** all four event categories (IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE). One unified `audit_events` table.
- **Schema migration:** real `Migration_2_3` — drop destructive fallback. Protects existing cached dossiers.
- **No Compose UI tests in Phase 1.**

---

## Out-of-scope notes for downstream phases

- **Encounter thumbnails**: Phase 2's quality gate is the right home — it'll produce clean face crops that are worth keeping.
- **Map view of encounters**: needs Google Maps SDK (heavy dep) and tile credits. Defer until there's a real demand.
- **AuditLog pagination beyond 200**: Phase 1 caps reads; later phases can add `androidx.paging:paging-compose` if growth becomes a problem.
- **Watchlist mode**: user explicitly deferred. Revisit when usage data tells us whether opt-in vs opt-out semantics fit better.
- **SQLCipher encryption** on the Room DB: planned for Phase 7. Phase 1's schema changes will be migration-compatible with adding cipher later.
- **Background location**: not needed until counter-surveillance or geo-watchlist features land in Phase 8/9.

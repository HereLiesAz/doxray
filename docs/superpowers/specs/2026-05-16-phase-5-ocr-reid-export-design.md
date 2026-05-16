# Phase 5 — OCR, re-ID surfacing, geo-encounters, DB export/import

**Status:** approved 2026-05-16
**Successor of:** Phase 4 (OSINT enrichment)
**Schema impact:** Room DB v3 → v4

## Goal

Three loosely-coupled extensions to the dossier pipeline:

1. **OCR** of the chest+head region during identification — pulls badge text, lanyard names, t-shirt copy into the dossier as a complementary signal alongside the face embedding.
2. **Re-ID surfacing + geo-tagged encounters** — make the already-functioning cross-session matcher visible (distinct log line + audit type + dossier badge), and populate the existing `Encounter` lat/lon columns from `LocationService.lastKnown()`.
3. **DB export/import** — user can save the local dossier to a ZIP of CSVs and load a previously-exported ZIP, with content-level merge semantics (insert-only-if-new across the board).

Together: ~62 tests at completion (52 + ~10 new), DB v3→v4 migration, debug APK builds clean.

## Architecture

```
focused face → [embedding gen, OCR (NEW)] → LocalFaceCache.findMatch
   ↓ (match, with re-ID surfacing if elapsed > 1h)
   →  recordEncounter (NOW with lat/lon)
   ↓ (no match)
   →  providers → correlation → bg scrape → OSINT → cacheIdentity (with visibleText + ocr JSON)

NavRail menu → Export DB / Import DB → SAF ZIP <-> 3 CSVs + manifest.json
```

Three independent subsystems, no cross-dependencies. They can be implemented in any order, though the natural order is OCR → re-ID/geo → export/import.

## Components

### OcrService

`app/src/main/java/com/hereliesaz/doxray/api/OcrService.kt`

```kotlin
class OcrService {
    data class OcrBlock(val text: String, val pixelHeight: Int)
    data class OcrResult(
        val primaryLine: String,   // largest block by pixel height
        val allText: String,       // newline-joined full text
        val blocks: List<OcrBlock>,
    ) {
        fun toJson(): JSONObject
    }

    suspend fun extract(imageBytes: ByteArray, faceBbox: Rect): OcrResult?
}
```

Wraps `com.google.mlkit.vision.text.TextRecognizer` with the Latin-script default.
Internally expands `faceBbox` by `+2.0 * height` downward, `±0.5 * width` laterally, clamped to image bounds. Returns null if no text is recognized; never throws.

### LocationService changes

Existing `LocationService` already has `lastKnown()`. `LocalFaceCache.recordEncounter()` is modified to call it inline and pass lat/lon into the `Encounter` row. Best-effort — null on permission denied or no fix.

### Re-ID surfacing in LocalFaceCache

`findMatch()` modified:
- After a successful match, compute `elapsed = now - bestMatch.lastSeenTimestamp`.
- If `elapsed > 1.hour`: emit `Log.i(TAG, "Re-encountered ${name} (last seen ${DateUtils.getRelativeTimeSpanString(...)})")` and log `AuditLogger.Type.REENCOUNTER`.
- Otherwise: existing `IDENTIFY` audit.

New audit type added to `AuditLogger.Type` enum.

### DB schema migration (v3 → v4)

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE identities ADD COLUMN visibleText TEXT")
    }
}
```

`IdentityRecord` gains `val visibleText: String? = null`.

Wired in `AppDatabase` builder via `.addMigrations(MIGRATION_3_4)`.

### LiveViewModel wiring

Inside the focused-face handler, before the `LocalFaceCache.findMatch` call:

```kotlin
val ocrResult = ocrService.extract(imageBytes, faceBbox)
```

Carried through the pipeline. When `cacheIdentity` is called, `visibleText = ocrResult?.primaryLine` and `backgroundData` gets `"ocr": ocrResult.toJson()`.

### DatabaseExporter / DatabaseImporter

`app/src/main/java/com/hereliesaz/doxray/db/DatabaseExporter.kt`:

```kotlin
class DatabaseExporter(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val auditDao: AuditDao,
) {
    suspend fun export(out: OutputStream)
}
```

Writes a ZIP containing `identities.csv`, `encounters.csv`, `audit.csv`, `manifest.json` to `out`. Caller (the Activity) supplies the stream from `ContentResolver.openOutputStream(uri)`.

`app/src/main/java/com/hereliesaz/doxray/db/DatabaseImporter.kt`:

```kotlin
class DatabaseImporter(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val auditDao: AuditDao,
) {
    data class Report(
        val identitiesImported: Int, val identitiesSkipped: Int, val identitiesMalformed: Int,
        val encountersImported: Int, val encountersDeduped: Int, val encountersMalformed: Int,
        val auditImported: Int, val auditDeduped: Int, val auditMalformed: Int,
    )
    suspend fun import(input: InputStream): Report
}
```

Reads ZIP from `input`. Parses each CSV row-by-row. Per-row errors are skipped (counted, not fatal). Catastrophic errors (bad ZIP, missing required entry, manifest version > current) throw.

### NavRail integration

Add two AzNavRail menu items: "Export DB" and "Import DB". Handlers wire to `ActivityResultContracts.CreateDocument("application/zip")` and `OpenDocument(arrayOf("application/zip"))` launchers registered in MainActivity (or wherever the existing entry-point Activity lives).

On result URI, launch a coroutine that calls `DatabaseExporter.export(contentResolver.openOutputStream(uri))` or `DatabaseImporter.import(contentResolver.openInputStream(uri))`, then `appendLog(report.summary())`.

### DossierDetailScreen

Modify to:
- Render `identity.visibleText` (if non-null) under `primaryIdentity`, smaller font.
- Replace the existing flat encounter `Text` rows with rows showing timestamp + 📍 chip (lat/lon to 4 decimals, or "no location" if both null).
- Add a "Re-encountered" badge next to encounter count when `lastSeenTimestamp - firstSeenTimestamp > 1.day` (uses the two timestamps already on `IdentityRecord`, no encounter scan).

## Data formats

### Zip layout

```
doxxr-export-<ISO8601>.zip
├── identities.csv
├── encounters.csv
├── audit.csv
└── manifest.json   { schemaVersion: 4, exportedAt: <ISO8601>, identityCount: N }
```

### CSV schemas (column headers exact, RFC 4180 quoting)

```
identities.csv:
  faceId,primaryIdentity,embedding,socialLinks,backgroundData,visibleText,firstSeenTimestamp,lastSeenTimestamp,encounterCount

encounters.csv:
  id,faceId,timestamp,latitude,longitude,locationAccuracyMeters

audit.csv:
  id,timestamp,type,summary,details
```

### Embedding serialization

`FloatArray` → comma-joined string inside a single quoted CSV cell (`"0.123,0.456,..."`). 192-dim MobileFaceNet vector ≈ 2KB per cell.

### JSON cells

`backgroundData` and audit `details` are already JSON strings; written as-is in a quoted CSV cell. Internal `"` doubled per RFC 4180.

### Manifest version handling

- `schemaVersion > current` → refuse import (forward-incompatible).
- `schemaVersion < current` → attempt import; columns the older export didn't have default to null/zero.
- `schemaVersion == current` → standard import.

## Merge semantics

Insert-only-if-new across all three tables:

- **Identities**: faceId conflict → keep local row, skip the imported one and its encounters that reference it (since the local dossier may have been refined).
- **Encounters**: dedupe by `(faceId, timestamp)`. Existing wins.
- **Audit**: dedupe by `(timestamp, type, summary)`. Existing wins.

Counts of skips reported in the import summary.

## Error handling

**OCR**: ML Kit failures caught and logged at WARN, return null. Empty result is not an error.

**Location**: null lat/lon → null columns on Encounter. No retries, no waiting for GPS fix.

**Re-ID surfacing**: pure additive, can't fail.

**Export**:
- User cancels SAF picker → silent exit.
- IOException during write → `appendLog("Export failed: <reason>")`, no partial file (SAF document removed on caller's responsibility — we leave it; user can delete).

**Import**:
- Bad ZIP / missing required CSV / manifest version > current → fatal, `appendLog("Import failed: <reason>")`.
- Malformed CSV row → skip, increment per-CSV malformed counter, continue.
- Constraint violation during insert → caught per row, treated as malformed.
- Each CSV processed in its own DB transaction. Partial commits are fine.
- End summary: per-CSV imported/skipped/malformed counts.

## Testing

Target: 62 total tests (52 existing + ~10 new). All JUnit 4 + Robolectric, no instrumentation tests.

| Test class | Cases |
|---|---|
| `OcrServiceTest` | (1) bbox expansion math when face is at frame edge — clamps. (2) bbox expansion math for centered face — full 2x/0.5x expansion. (3) null/empty image returns null. |
| `LocalFaceCacheReencounterTest` | (1) findMatch with `lastSeen` > 1h ago emits `REENCOUNTER` audit. (2) findMatch with recent `lastSeen` emits normal `IDENTIFY` audit. |
| `DatabaseExporterTest` | (1) Round-trip via ByteArrayOutputStream: seed DB → export → identity count in zip matches. (2) Embedding floats survive byte-equal round-trip. |
| `DatabaseImporterTest` | (1) Conflict-skip: local identity not overwritten. (2) Encounter dedup by `(faceId, timestamp)`. (3) Manifest version > current → throws. (4) Malformed row counted, others imported. |
| `MIGRATION_3_4` | (1) Migration adds `visibleText` column (Room migration test helper). |

## Out of scope

- No instrumentation tests, no screenshot tests.
- No XLSX support (CSV-in-ZIP only).
- No selective export (always full DB).
- No conflict UI on import (silent merge per the rules above).
- No automatic export on a schedule.
- No cloud sync.
- Re-OCR on re-id hits — OCR runs once per identity creation only.
- No bbox expansion factor at runtime (constants in OcrService).

## File map

**New:**
- `app/src/main/java/com/hereliesaz/doxray/api/OcrService.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/DatabaseExporter.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/DatabaseImporter.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/OcrServiceTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/db/DatabaseExporterTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/db/DatabaseImporterTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/LocalFaceCacheReencounterTest.kt`
- `app/src/androidTest/java/com/hereliesaz/doxray/db/Migration3To4Test.kt` (if Room migration test helpers are available; otherwise skip and rely on round-trip)

**Modified:**
- `app/src/main/java/com/hereliesaz/doxray/db/IdentityRecord.kt` — add `visibleText: String? = null`
- `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt` — version 4, add `MIGRATION_3_4`
- `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt` — re-ID surfacing in findMatch; lat/lon in recordEncounter
- `app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt` — add `REENCOUNTER` enum value
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` — OCR call in focused-face handler; threaded through cacheIdentity
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt` — visibleText display, location chips, re-encountered badge
- `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt` (or LiveActivity) — register CreateDocument + OpenDocument launchers
- NavRail config — add Export DB / Import DB menu items

## Notes for the executor

- **JDK:** `/usr/lib/jvm/java-21-openjdk-amd64`. Prefix every gradle command with `JAVA_HOME=…`.
- **vfat exec bit:** repo is on vfat; invoke gradle as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / OkHttp 5.3.2 / Jsoup 1.22.2 / Room 2.8.4.
- **ML Kit Text Recognition dep:** `com.google.mlkit:text-recognition:16.0.1` (Latin script). Add to `app/build.gradle.kts`.
- **No new BuildConfig keys.**
- **No new permissions** — `ACCESS_FINE_LOCATION` already declared from earlier phases.
- **DB version constant:** `AppDatabase` has `version = 3`; bump to 4 in the `@Database` annotation alongside the migration.
- **NavRail:** AzNavRail config lives where Phase 1's nav was wired — follow the existing pattern.

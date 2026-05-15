# Phase 1 Dossier Surface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Room-cached identities visible to the user via three new Compose screens (Dossier List, Dossier Detail, Audit Log) wired through AzNavRail navigation, with encounter timeline + GPS tagging + full audit logging.

**Architecture:** `MainActivity` shrinks to an `AzHostActivityLayout` host driving `AzNavHost` between four destinations. Two new Room entities (`Encounter`, `AuditEvent`) extend the existing `IdentityRecord` with proper migration 2→3. A `LocationService` wraps `FusedLocationProviderClient`. An `AuditLogger` singleton records IDENTIFY / API_CALL / DOSSIER_READ / LIFECYCLE events from every layer. Per-screen ViewModels expose `StateFlow<UiState>` collected by Compose.

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.1, Gradle 9.5.1, Compose BOM 2026.05.00, Room 2.8.4 + KSP1, AzNavRail 8.11 (JitPack), Play Services Location 21.3.0.

**Reference:** Spec at `docs/superpowers/specs/2026-05-15-phase-1-dossier-surface-design.md`.

---

## File Structure

**New files**
- `app/src/main/java/com/hereliesaz/doxray/db/Encounter.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/EncounterDao.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/AuditEvent.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/AuditDao.kt`
- `app/src/main/java/com/hereliesaz/doxray/db/Migration_2_3.kt`
- `app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt`
- `app/src/main/java/com/hereliesaz/doxray/location/LocationService.kt`
- `app/src/main/java/com/hereliesaz/doxray/nav/Destinations.kt`
- `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListScreen.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModel.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogScreen.kt`
- `app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogViewModel.kt`
- `app/src/test/java/com/hereliesaz/doxray/db/EncounterDaoTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/db/AuditDaoTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModelTest.kt`
- `app/src/androidTest/java/com/hereliesaz/doxray/db/Migration_2_3_Test.kt`

**Modified files**
- `settings.gradle.kts` — add JitPack repo
- `app/build.gradle.kts` — add AzNavRail, lifecycle-viewmodel-compose, play-services-location, room-testing
- `app/src/main/AndroidManifest.xml` — add `ACCESS_COARSE_LOCATION` + `ACCESS_FINE_LOCATION`
- `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt` — register Encounter + AuditEvent, add migration, drop destructive fallback, expose new DAOs
- `app/src/main/java/com/hereliesaz/doxray/db/IdentityDao.kt` — add `observeAll()` + `delete()`
- `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt` — accept new constructor deps; write Encounter rows; log IDENTIFY events
- `app/src/main/java/com/hereliesaz/doxray/net/CaptureInterceptor.kt` — log API_CALL events
- `app/src/main/java/com/hereliesaz/doxray/DoxrayApp.kt` — initialise AuditLogger; log LIFECYCLE start
- `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt` — shrink to ~80 lines hosting `AzHostActivityLayout`

---

## Task 1: Build wiring — JitPack + new deps

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add JitPack to settings.gradle.kts**

In `settings.gradle.kts`, locate the `dependencyResolutionManagement { repositories { … } }` block. Inside it, after `mavenCentral()` and before the closing brace of `repositories`, add:
```kotlin
        maven { url = uri("https://jitpack.io") }
```

- [ ] **Step 2: Add deps to app/build.gradle.kts**

In `app/build.gradle.kts`, locate the `dependencies { … }` block. Add inside it (group with other implementations):
```kotlin
    // Navigation rail
    implementation("com.github.HereLiesAz:AzNavRail:8.11")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Compose ViewModel binding
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Location (Fused Provider)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Room migration test helper (instrumented tests only)
    androidTestImplementation("androidx.room:room-testing:2.8.4")
```

- [ ] **Step 3: Verify the project syncs**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts
git commit -m "Add JitPack + AzNavRail + location + room-testing dependencies"
```

---

## Task 2: Add location permissions to manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add the two permission lines**

In `app/src/main/AndroidManifest.xml`, find the existing `<uses-permission>` block (already contains INTERNET, CAMERA, RECORD_AUDIO, BLUETOOTH, BLUETOOTH_CONNECT). After the last existing permission line and before `<application`, add:
```xml
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "Add location permissions to manifest"
```

---

## Task 3: Add `Encounter` entity + `EncounterDao` (TDD)

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/db/Encounter.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/db/EncounterDao.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/db/EncounterDaoTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/hereliesaz/doxray/db/EncounterDaoTest.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EncounterDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var encounterDao: EncounterDao
    private lateinit var identityDao: IdentityDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        encounterDao = db.encounterDao()
        identityDao = db.identityDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `inserts and observes encounters newest-first`() = runBlocking {
        identityDao.insertIdentity(IdentityRecord(
            faceId = "face-1", primaryIdentity = "Test User", embedding = FloatArray(192),
            socialLinks = "", backgroundData = "{}", firstSeenTimestamp = 1000L,
            lastSeenTimestamp = 3000L, encounterCount = 2,
        ))
        encounterDao.insert(Encounter(faceId = "face-1", timestamp = 1000L,
            latitude = 37.0, longitude = -122.0, locationAccuracyMeters = 5f))
        encounterDao.insert(Encounter(faceId = "face-1", timestamp = 3000L,
            latitude = null, longitude = null, locationAccuracyMeters = null))

        val list = encounterDao.observeByFace("face-1").first()
        assertEquals(2, list.size)
        assertEquals(3000L, list[0].timestamp)
        assertEquals(1000L, list[1].timestamp)
        assertEquals(37.0, list[1].latitude!!, 0.0001)
    }
}
```

This test requires Robolectric. Add to `app/build.gradle.kts` inside `dependencies`:
```kotlin
    testImplementation("org.robolectric:robolectric:4.13")
```
And inside `android { }`:
```kotlin
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.EncounterDaoTest" --no-daemon`
Expected: compile error `Unresolved reference 'Encounter'` or `Unresolved reference 'encounterDao'`.

- [ ] **Step 3: Create the entity**

`app/src/main/java/com/hereliesaz/doxray/db/Encounter.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per face capture that produced a match (cached or new). Linked back
 * to the [IdentityRecord] via faceId. CASCADE delete keeps the encounter
 * timeline in sync when a dossier is purged.
 */
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

- [ ] **Step 4: Create the DAO**

`app/src/main/java/com/hereliesaz/doxray/db/EncounterDao.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(encounter: Encounter): Long

    @Query("SELECT * FROM encounters WHERE faceId = :faceId ORDER BY timestamp DESC")
    fun observeByFace(faceId: String): Flow<List<Encounter>>
}
```

- [ ] **Step 5: Register the entity + DAO in AppDatabase**

In `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt`, change the `@Database` annotation entities to include both classes:
```kotlin
@Database(entities = [IdentityRecord::class, Encounter::class], version = 3, exportSchema = false)
```
Bump the version to `3` as shown.
Add an abstract DAO accessor inside the class body (after `abstract fun identityDao()`):
```kotlin
    abstract fun encounterDao(): EncounterDao
```
Leave the `.fallbackToDestructiveMigration()` line **in place for now** — Task 5 swaps it for a real migration. The DB will rebuild from scratch on first run after version bump, which is fine for this isolated DAO test task.

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.EncounterDaoTest" --no-daemon`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/hereliesaz/doxray/db/Encounter.kt \
        app/src/main/java/com/hereliesaz/doxray/db/EncounterDao.kt \
        app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt \
        app/src/test/java/com/hereliesaz/doxray/db/EncounterDaoTest.kt
git commit -m "Add Encounter entity + EncounterDao with TDD (Robolectric)"
```

---

## Task 4: Add `AuditEvent` entity + `AuditDao` (TDD)

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/db/AuditEvent.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/db/AuditDao.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/db/AuditDaoTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/hereliesaz/doxray/db/AuditDaoTest.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AuditDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var auditDao: AuditDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        auditDao = db.auditDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `observeRecent returns newest first, respects limit`() = runBlocking {
        auditDao.insert(AuditEvent(timestamp = 100L, type = "LIFECYCLE", summary = "start", detailsJson = "{}"))
        auditDao.insert(AuditEvent(timestamp = 200L, type = "API_CALL", summary = "x", detailsJson = "{}"))
        auditDao.insert(AuditEvent(timestamp = 300L, type = "IDENTIFY", summary = "match", detailsJson = "{}"))

        val all = auditDao.observeRecent(limit = 10).first()
        assertEquals(3, all.size)
        assertEquals(300L, all[0].timestamp)
        assertEquals(100L, all[2].timestamp)

        val capped = auditDao.observeRecent(limit = 2).first()
        assertEquals(2, capped.size)
        assertEquals(300L, capped[0].timestamp)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.AuditDaoTest" --no-daemon`
Expected: compile error `Unresolved reference 'AuditEvent'` or `Unresolved reference 'auditDao'`.

- [ ] **Step 3: Create the entity**

`app/src/main/java/com/hereliesaz/doxray/db/AuditEvent.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable record of one notable runtime event. The free-form [detailsJson]
 * carries type-specific payload; the [type] field discriminates.
 */
@Entity(tableName = "audit_events", indices = [Index("timestamp")])
data class AuditEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val summary: String,
    val detailsJson: String,
)
```

- [ ] **Step 4: Create the DAO**

`app/src/main/java/com/hereliesaz/doxray/db/AuditDao.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(event: AuditEvent): Long

    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AuditEvent>>
}
```

- [ ] **Step 5: Register in AppDatabase**

In `AppDatabase.kt`, update the entities list and add the DAO accessor:
```kotlin
@Database(entities = [IdentityRecord::class, Encounter::class, AuditEvent::class], version = 3, exportSchema = false)
```
And:
```kotlin
    abstract fun auditDao(): AuditDao
```

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.db.AuditDaoTest" --no-daemon`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/db/AuditEvent.kt \
        app/src/main/java/com/hereliesaz/doxray/db/AuditDao.kt \
        app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt \
        app/src/test/java/com/hereliesaz/doxray/db/AuditDaoTest.kt
git commit -m "Add AuditEvent entity + AuditDao with TDD"
```

---

## Task 5: Migration 2 → 3 + drop destructive fallback

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/db/Migration_2_3.kt`
- Create: `app/src/androidTest/java/com/hereliesaz/doxray/db/Migration_2_3_Test.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt`

- [ ] **Step 1: Create the migration**

`app/src/main/java/com/hereliesaz/doxray/db/Migration_2_3.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `encounters` and `audit_events` tables (Phase 1 dossier surface).
 * Existing `identity_records` rows are untouched.
 */
object Migration_2_3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS encounters (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                faceId TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                locationAccuracyMeters REAL,
                FOREIGN KEY(faceId) REFERENCES identity_records(faceId) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_encounters_faceId ON encounters(faceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_encounters_timestamp ON encounters(timestamp)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                summary TEXT NOT NULL,
                detailsJson TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_timestamp ON audit_events(timestamp)")
    }
}
```

- [ ] **Step 2: Wire the migration into AppDatabase + drop destructive**

In `AppDatabase.kt`, replace `.fallbackToDestructiveMigration()` with `.addMigrations(Migration_2_3)`. Add `exportSchema = true` to the `@Database` annotation:
```kotlin
@Database(entities = [IdentityRecord::class, Encounter::class, AuditEvent::class], version = 3, exportSchema = true)
```

The full `getDatabase` builder should now read:
```kotlin
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "doxray_database"
                )
                .addMigrations(Migration_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
```

In `app/build.gradle.kts`, add the Room schema export directory (inside `android { }`):
```kotlin
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
```

- [ ] **Step 3: Create the migration test (instrumented)**

`app/src/androidTest/java/com/hereliesaz/doxray/db/Migration_2_3_Test.kt`:
```kotlin
package com.hereliesaz.doxray.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_2_3_Test {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate2To3_preservesIdentityRecords_addsNewTables() {
        // Create v2 DB with one row
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL("""
                INSERT INTO identity_records
                    (faceId, primaryIdentity, embedding, socialLinks, backgroundData,
                     firstSeenTimestamp, lastSeenTimestamp, encounterCount)
                VALUES ('face-1', 'Test User', X'00', '', '{}', 1000, 2000, 1)
            """.trimIndent())
            close()
        }

        // Apply the migration and re-open with the production schema
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migration_2_3)

        // Existing row survives
        migratedDb.query("SELECT faceId, primaryIdentity FROM identity_records").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("face-1", c.getString(0))
            assertEquals("Test User", c.getString(1))
        }

        // New tables exist and are empty
        migratedDb.query("SELECT COUNT(*) FROM encounters").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM audit_events").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }

        migratedDb.close()
    }
}
```

- [ ] **Step 4: Verify the migration code compiles + assembleDebug succeeds**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. The schema JSON should be exported under `app/schemas/com.hereliesaz.doxray.db.AppDatabase/3.json`.

The instrumented test runs only on a device/emulator. It is checked in but not invoked here.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/hereliesaz/doxray/db/AppDatabase.kt \
        app/src/main/java/com/hereliesaz/doxray/db/Migration_2_3.kt \
        app/src/androidTest/java/com/hereliesaz/doxray/db/Migration_2_3_Test.kt \
        app/schemas
git commit -m "Migrate Room schema 2->3; drop destructive fallback"
```

---

## Task 6: Extend `IdentityDao` with observeAll + delete

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/db/IdentityDao.kt`

- [ ] **Step 1: Add the two new methods**

In `IdentityDao.kt`, add inside the interface (alongside existing methods):
```kotlin
    @Query("SELECT * FROM identity_records ORDER BY lastSeenTimestamp DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<IdentityRecord>>

    @Query("DELETE FROM identity_records WHERE faceId = :faceId")
    suspend fun delete(faceId: String): Int
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/db/IdentityDao.kt
git commit -m "Add observeAll + delete to IdentityDao"
```

---

## Task 7: `AuditLogger` singleton

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt`

- [ ] **Step 1: Create the file**

`app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt`:
```kotlin
package com.hereliesaz.doxray.audit

import com.hereliesaz.doxray.db.AuditDao
import com.hereliesaz.doxray.db.AuditEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fire-and-forget audit log. Initialised once from Application startup with
 * the production [AuditDao]; all subsequent [log] calls are non-blocking and
 * never throw.
 *
 * Logging before [init] is a no-op (events are dropped silently). This
 * matches the rest of the codebase where Application.onCreate is the
 * happens-before boundary.
 */
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

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/audit/AuditLogger.kt
git commit -m "Add AuditLogger singleton (fire-and-forget)"
```

---

## Task 8: `LocationService` wrapper

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/location/LocationService.kt`

- [ ] **Step 1: Create the file**

`app/src/main/java/com/hereliesaz/doxray/location/LocationService.kt`:
```kotlin
package com.hereliesaz.doxray.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Best-effort wrapper around [FusedLocationProviderClient.getLastLocation].
 * Returns null when:
 *   - neither COARSE nor FINE permission is granted
 *   - the device has no cached fix
 *   - Play Services throws or is missing
 *
 * Never throws. Safe to call from any thread.
 */
class LocationService(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? {
        if (!hasAnyLocationPermission()) return null
        return try {
            suspendCancellableCoroutine { cont ->
                fused.lastLocation
                    .addOnSuccessListener { loc -> cont.resume(loc) { _, _, _ -> } }
                    .addOnFailureListener { cont.resume(null) { _, _, _ -> } }
            }
        } catch (e: Exception) { null }
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`. (If `cont.resume` overload mismatch appears, ensure the dependency `kotlinx-coroutines-android` is on the classpath — it already is.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/location/LocationService.kt
git commit -m "Add LocationService wrapper around FusedLocationProviderClient"
```

---

## Task 9: Initialise `AuditLogger` from `DoxrayApp` + LIFECYCLE start event

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/DoxrayApp.kt`

- [ ] **Step 1: Replace the file**

`app/src/main/java/com/hereliesaz/doxray/DoxrayApp.kt`:
```kotlin
package com.hereliesaz.doxray

import android.app.Application
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.net.HttpClients

class DoxrayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HttpClients.init(this)
        val db = AppDatabase.getDatabase(this)
        AuditLogger.init(db.auditDao())
        AuditLogger.log(AuditLogger.Type.LIFECYCLE, "App started")
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/DoxrayApp.kt
git commit -m "Initialise AuditLogger from DoxrayApp + log app start"
```

---

## Task 10: Log API_CALL events from `CaptureInterceptor`

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/net/CaptureInterceptor.kt`

- [ ] **Step 1: Inject audit logging into the interceptor**

The interceptor currently writes capture files unconditionally when its `enabled` predicate is true. Audit logging must happen on **every** request regardless of capture mode (audit is a release-build feature too). Add imports at the top of `CaptureInterceptor.kt`:
```kotlin
import com.hereliesaz.doxray.audit.AuditLogger
import org.json.JSONObject
```

In the `intercept` function, *after* the capture-write block (or the early-return when disabled), add audit logging. The cleanest shape is to restructure the function so the audit always fires:
```kotlin
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = if (enabled()) {
            val ts = System.currentTimeMillis()
            val n = seq.incrementAndGet()
            val host = try { URI(request.url.toString()).host ?: "unknown" } catch (e: Exception) { "unknown" }
            val base = "${ts}_${n}_${host}"

            writer.write("$base.req.bin", encodeRequest(request))
            val resp = chain.proceed(request)
            val peek = resp.peekBody(MAX_BODY_BYTES.toLong())
            writer.write("$base.resp.bin", encodeResponse(resp, peek.bytes()))
            resp
        } else {
            chain.proceed(request)
        }

        AuditLogger.log(
            AuditLogger.Type.API_CALL,
            summary = "${request.method} ${request.url.host}${request.url.encodedPath} → ${response.code}",
            details = JSONObject().apply {
                put("method", request.method)
                put("url", request.url.toString())
                put("code", response.code)
            },
        )
        return response
    }
```

This is a full rewrite of `intercept`. Replace the entire existing method with the block above.

- [ ] **Step 2: Re-run the CaptureInterceptor tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.net.CaptureInterceptorTest" --no-daemon`
Expected: both tests still pass. (The AuditLogger has no DAO injected in unit tests, so its `log` calls are no-ops — exactly what the design says.)

- [ ] **Step 3: Verify full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/net/CaptureInterceptor.kt
git commit -m "Log API_CALL events from CaptureInterceptor on every request"
```

---

## Task 11: Extend `LocalFaceCache` to write encounters + log IDENTIFY

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`

- [ ] **Step 1: Replace the file**

`app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.Encounter
import com.hereliesaz.doxray.db.EncounterDao
import com.hereliesaz.doxray.db.IdentityDao
import com.hereliesaz.doxray.db.IdentityRecord
import com.hereliesaz.doxray.location.LocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Manages the local cache of unique facial embeddings + per-encounter history.
 */
class LocalFaceCache(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val locationService: LocationService,
) {

    private val TAG = "LocalFaceCache"
    private val memoryCache = mutableListOf<IdentityRecord>()
    private val SIMILARITY_THRESHOLD = 0.85f

    suspend fun loadFromDatabase() = withContext(Dispatchers.IO) {
        val records = identityDao.getAllIdentities()
        memoryCache.clear()
        memoryCache.addAll(records)
        Log.d(TAG, "Loaded ${records.size} identities from local database.")
    }

    suspend fun findMatch(embedding: FloatArray): IdentityRecord? = withContext(Dispatchers.IO) {
        if (memoryCache.isEmpty()) return@withContext null

        var bestMatch: IdentityRecord? = null
        var highestSimilarity = 0f
        for (cached in memoryCache) {
            val similarity = calculateCosineSimilarity(embedding, cached.embedding)
            if (similarity > highestSimilarity && similarity >= SIMILARITY_THRESHOLD) {
                highestSimilarity = similarity
                bestMatch = cached
            }
        }

        if (bestMatch != null) {
            val currentTime = System.currentTimeMillis()
            identityDao.recordEncounter(bestMatch.faceId, currentTime)
            recordEncounter(bestMatch.faceId, currentTime)
            AuditLogger.log(
                AuditLogger.Type.IDENTIFY,
                summary = "Cache hit: ${bestMatch.primaryIdentity}",
                details = JSONObject().apply {
                    put("faceId", bestMatch.faceId)
                    put("similarity", highestSimilarity)
                },
            )
            val index = memoryCache.indexOf(bestMatch)
            if (index != -1) {
                memoryCache[index] = bestMatch.copy(
                    lastSeenTimestamp = currentTime,
                    encounterCount = bestMatch.encounterCount + 1,
                )
            }
        }
        bestMatch
    }

    suspend fun cacheIdentity(
        faceId: String,
        embedding: FloatArray,
        primaryIdentity: String,
        socialLinks: List<String>,
        backgroundData: String,
    ) = withContext(Dispatchers.IO) {
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
        recordEncounter(faceId, currentTime)
        AuditLogger.log(
            AuditLogger.Type.IDENTIFY,
            summary = "New identity: $primaryIdentity",
            details = JSONObject().put("faceId", faceId),
        )
    }

    private suspend fun recordEncounter(faceId: String, timestamp: Long) {
        val location = locationService.getLastLocation()
        encounterDao.insert(Encounter(
            faceId = faceId,
            timestamp = timestamp,
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationAccuracyMeters = location?.accuracy,
        ))
    }

    private fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        return if (normA == 0f || normB == 0f) 0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}
```

- [ ] **Step 2: Update the LocalFaceCache constructor call in MainActivity**

`MainActivity.kt` currently constructs `LocalFaceCache(appDatabase.identityDao())` — the test caller stays valid for now because we update MainActivity in a later task. To keep the project compiling **right now**, find the existing line:
```kotlin
        localFaceCache = LocalFaceCache(appDatabase.identityDao())
```
Replace with:
```kotlin
        localFaceCache = LocalFaceCache(
            identityDao = appDatabase.identityDao(),
            encounterDao = appDatabase.encounterDao(),
            locationService = com.hereliesaz.doxray.location.LocationService(this),
        )
```

- [ ] **Step 3: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/LocalFaceCache.kt \
        app/src/main/java/com/hereliesaz/doxray/MainActivity.kt
git commit -m "Wire LocalFaceCache to write encounters + log IDENTIFY events"
```

---

## Task 12: `LiveViewModel` — extract service wiring from MainActivity

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

`app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`:
```kotlin
package com.hereliesaz.doxray.ui.live

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.api.CyberBackgroundChecksScraper
import com.hereliesaz.doxray.api.EmbeddingGenerator
import com.hereliesaz.doxray.api.FaceCheckIdScraperService
import com.hereliesaz.doxray.api.FaceCheckIdService
import com.hereliesaz.doxray.api.FaceSeekScraperService
import com.hereliesaz.doxray.api.FaceSeekService
import com.hereliesaz.doxray.api.FaceTrackerManager
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.LocalFaceCache
import com.hereliesaz.doxray.api.SmartBackgroundChecksScraper
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.location.LocationService
import com.hereliesaz.doxray.meta.MetaGlassesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class LiveUiState(
    val isConnected: Boolean = false,
    val logLines: List<String> = emptyList(),
)

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "LiveViewModel"

    private val metaGlassesManager = MetaGlassesManager(application)
    private val embeddingGenerator = EmbeddingGenerator(application)
    private val appDatabase = AppDatabase.getDatabase(application)
    private val localFaceCache = LocalFaceCache(
        identityDao = appDatabase.identityDao(),
        encounterDao = appDatabase.encounterDao(),
        locationService = LocationService(application),
    )

    private val faceSeekService = FaceSeekService()
    private val yandexSearchService = YandexSearchService()
    private val lensoSearchService = LensoSearchService()
    private val faceCheckIdService = FaceCheckIdService()
    private val faceSeekScraper = FaceSeekScraperService()
    private val yandexScraper = YandexScraperService()
    private val lensoScraper = LensoScraperService()
    private val faceCheckIdScraper = FaceCheckIdScraperService()
    private val smartBgScraper = SmartBackgroundChecksScraper()
    private val cyberBgScraper = CyberBackgroundChecksScraper()
    private val faceTrackerManager = FaceTrackerManager()
    private val activeInvestigations = ConcurrentHashMap<Int, Job>()

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state

    init {
        viewModelScope.launch { localFaceCache.loadFromDatabase() }
    }

    fun connect() {
        appendLog("Attempting connection...")
        AuditLogger.log(AuditLogger.Type.LIFECYCLE, "Connect requested")
        try {
            metaGlassesManager.connect()
            _state.value = _state.value.copy(isConnected = true)
            appendLog("Connected successfully.")
            AuditLogger.log(AuditLogger.Type.LIFECYCLE, "Glasses connected")

            metaGlassesManager.startVideoStream(object : MetaGlassesManager.FrameListener {
                override fun onFrameReceived(imageBytes: ByteArray) {
                    faceTrackerManager.processFrame(imageBytes, object : FaceTrackerManager.FaceFocusListener {
                        override fun onFaceFocused(focusedImageBytes: ByteArray, trackingId: Int, faceCrop: ByteArray) {
                            if (activeInvestigations.containsKey(trackingId)) return
                            appendLog("Target acquired (ID: $trackingId). Processing search...")
                            val job = viewModelScope.launch {
                                processFocusedFace(focusedImageBytes, faceCrop, trackingId)
                            }
                            activeInvestigations[trackingId] = job
                        }

                        override fun onFaceLost(trackingId: Int) {
                            val job = activeInvestigations.remove(trackingId)
                            if (job != null && job.isActive) {
                                appendLog("Target lost (ID: $trackingId). Halting active investigation.")
                                job.cancel()
                            }
                        }

                        override fun onError(e: Exception) {
                            Log.e(TAG, "Face tracking error", e)
                        }
                    })
                }

                override fun onError(error: Throwable) {
                    appendLog("Stream Error: ${error.message}")
                }
            })
        } catch (e: Exception) {
            _state.value = _state.value.copy(isConnected = false)
            appendLog("Connection failed: ${e.message}")
        }
    }

    fun disconnect() {
        metaGlassesManager.stopVideoStream()
        metaGlassesManager.disconnect()
        _state.value = _state.value.copy(isConnected = false)
        appendLog("Disconnected from glasses.")
        AuditLogger.log(AuditLogger.Type.LIFECYCLE, "Glasses disconnected")
    }

    override fun onCleared() {
        super.onCleared()
        metaGlassesManager.stopVideoStream()
        metaGlassesManager.disconnect()
    }

    private suspend fun processFocusedFace(imageBytes: ByteArray, faceCrop: ByteArray, trackingId: Int) {
        try {
            val embedding = embeddingGenerator.generateEmbedding(faceCrop)
            val cachedMatch = localFaceCache.findMatch(embedding)
            if (cachedMatch != null) {
                appendLog("Cached Match: ${cachedMatch.primaryIdentity}. Encounters: ${cachedMatch.encounterCount}.")
                appendLog("Known Links: ${cachedMatch.socialLinks}")
                metaGlassesManager.playAudioMessage("Match found: ${cachedMatch.primaryIdentity}. Previous encounters: ${cachedMatch.encounterCount}")
                if (cachedMatch.backgroundData == "{}" || cachedMatch.backgroundData.isEmpty()) {
                    metaGlassesManager.playAudioMessage("Resuming background investigation.")
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","))
                }
                return
            }

            var primaryIdentity = ""
            var socialLinks = listOf<String>()
            var referenceImageUrl = ""
            var faceId = ""

            var lensoResult = lensoSearchService.identifyFace(imageBytes)
            if (lensoResult == null) {
                appendLog("Lenso API failed, trying scraper fallback...")
                lensoResult = lensoScraper.identifyFace(imageBytes)
            }
            if (lensoResult != null && lensoResult.confidence > 0.6f) {
                appendLog("Lenso face matched from domain: ${lensoResult.sourceDomain}")
                referenceImageUrl = lensoResult.referenceImageUrl
                faceId = lensoResult.faceId
            } else {
                var faceResult = faceSeekService.identifyFace(imageBytes)
                if (faceResult == null) {
                    appendLog("FaceSeek API failed, trying scraper fallback...")
                    faceResult = faceSeekScraper.identifyFace(imageBytes)
                }
                if (faceResult != null && faceResult.confidence > 0.6f) {
                    appendLog("FaceSeek matched! ID: ${faceResult.faceId}")
                    referenceImageUrl = faceResult.referenceImageUrl
                    faceId = faceResult.faceId
                } else {
                    var faceCheckResult = faceCheckIdService.identifyFace(imageBytes)
                    if (faceCheckResult == null) {
                        appendLog("FaceCheck.ID API failed, trying scraper fallback...")
                        faceCheckResult = faceCheckIdScraper.identifyFace(imageBytes)
                    }
                    if (faceCheckResult != null && faceCheckResult.confidence > 0.6f) {
                        appendLog("FaceCheck.ID matched! ID: ${faceCheckResult.faceId}")
                        referenceImageUrl = faceCheckResult.referenceImageUrl
                        faceId = faceCheckResult.faceId
                    }
                }
            }

            if (referenceImageUrl.isNotEmpty()) {
                metaGlassesManager.playAudioMessage("Face matched. Correlating identity...")
                var identityResult = yandexSearchService.searchIdentity(referenceImageUrl)
                if (identityResult == null) {
                    appendLog("Yandex API failed, trying scraper fallback...")
                    identityResult = yandexScraper.searchIdentity(referenceImageUrl)
                }
                if (identityResult != null && identityResult.identities.isNotEmpty()) {
                    primaryIdentity = identityResult.identities.first()
                    socialLinks = identityResult.socialLinks
                }
            } else {
                metaGlassesManager.playAudioMessage("No confident face match found.")
            }

            if (primaryIdentity.isNotEmpty()) {
                appendLog("Identity correlated: $primaryIdentity")
                appendLog("Links: ${socialLinks.joinToString(", ")}")
                metaGlassesManager.playAudioMessage("Identity correlated: $primaryIdentity")
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks)
            } else if (referenceImageUrl.isNotEmpty()) {
                appendLog("No online identity correlation found.")
                metaGlassesManager.playAudioMessage("No online identity found.")
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Investigation for ID $trackingId was cancelled.")
        } catch (e: Exception) {
            appendLog("Pipeline Exception: ${e.message}")
        } finally {
            activeInvestigations.remove(trackingId)
        }
    }

    private suspend fun performDeepBackgroundScrape(
        primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>,
    ) {
        metaGlassesManager.playAudioMessage("Digging for background data.")
        appendLog("Digging for deep background info on: $primaryIdentity...")
        val bgDataJson = JSONObject()
        val smartData = smartBgScraper.searchBackground(primaryIdentity)
        if (smartData != null) {
            bgDataJson.put("smart", smartData)
            val phonesCount = smartData.optJSONArray("phones")?.length() ?: 0
            if (phonesCount > 0) {
                metaGlassesManager.playAudioMessage("Found $phonesCount phone numbers.")
                appendLog("Extracted phone numbers.")
            }
        }
        val cyberData = cyberBgScraper.searchBackground(primaryIdentity)
        if (cyberData != null) {
            bgDataJson.put("cyber", cyberData)
            val emailsCount = cyberData.optJSONArray("emails")?.length() ?: 0
            if (emailsCount > 0) {
                metaGlassesManager.playAudioMessage("Found $emailsCount email addresses.")
                appendLog("Extracted email addresses.")
            }
        }
        localFaceCache.cacheIdentity(
            faceId = faceId, embedding = embedding,
            primaryIdentity = primaryIdentity, socialLinks = socialLinks,
            backgroundData = bgDataJson.toString(),
        )
        if (bgDataJson.length() > 0) {
            metaGlassesManager.playAudioMessage("Investigation complete. Dossier saved.")
        } else {
            metaGlassesManager.playAudioMessage("No additional offline data found.")
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLines = listOf("[$time] $message") + _state.value.logLines
        _state.value = _state.value.copy(logLines = newLines.take(500))
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Add LiveViewModel — service wiring extracted from MainActivity"
```

---

## Task 13: `DossierListViewModel` (TDD)

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModel.kt`
- Create: `app/src/test/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModelTest.kt`:
```kotlin
package com.hereliesaz.doxray.ui.dossier

import com.hereliesaz.doxray.db.IdentityDao
import com.hereliesaz.doxray.db.IdentityRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DossierListViewModelTest {

    private fun record(id: String, name: String, lastSeen: Long, count: Int) = IdentityRecord(
        faceId = id, primaryIdentity = name, embedding = FloatArray(192),
        socialLinks = "", backgroundData = "{}",
        firstSeenTimestamp = 0L, lastSeenTimestamp = lastSeen, encounterCount = count,
    )

    private class FakeIdentityDao(records: List<IdentityRecord>) : IdentityDao {
        private val flow = MutableStateFlow(records)
        override suspend fun getAllIdentities(): List<IdentityRecord> = flow.value
        override suspend fun getIdentityById(faceId: String): IdentityRecord? = flow.value.firstOrNull { it.faceId == faceId }
        override suspend fun insertIdentity(record: IdentityRecord): Long = 0
        override suspend fun updateIdentity(record: IdentityRecord): Int = 0
        override suspend fun recordEncounter(faceId: String, timestamp: Long): Int = 0
        override fun observeAll(): Flow<List<IdentityRecord>> = flow
        override suspend fun delete(faceId: String): Int = 0
    }

    @Test
    fun `maps identity records to dossier rows`() = runTest {
        val dao = FakeIdentityDao(listOf(
            record("a", "Alice", 2000L, 3),
            record("b", "Bob", 1000L, 1),
        ))
        val vm = DossierListViewModel(dao)
        val rows = vm.state.first { it.rows.size == 2 }.rows
        assertEquals(2, rows.size)
        assertEquals("Alice", rows[0].name)
        assertEquals(3, rows[0].encounterCount)
        assertEquals(2000L, rows[0].lastSeenMillis)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.ui.dossier.DossierListViewModelTest" --no-daemon`
Expected: compile error `Unresolved reference 'DossierListViewModel'`.

- [ ] **Step 3: Create the ViewModel**

`app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModel.kt`:
```kotlin
package com.hereliesaz.doxray.ui.dossier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.db.IdentityDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DossierRow(
    val faceId: String,
    val name: String,
    val encounterCount: Int,
    val lastSeenMillis: Long,
)

data class DossierListUiState(val rows: List<DossierRow> = emptyList())

class DossierListViewModel(identityDao: IdentityDao) : ViewModel() {
    val state: StateFlow<DossierListUiState> = identityDao.observeAll()
        .map { records ->
            DossierListUiState(rows = records.map { r ->
                DossierRow(
                    faceId = r.faceId,
                    name = r.primaryIdentity,
                    encounterCount = r.encounterCount,
                    lastSeenMillis = r.lastSeenTimestamp,
                )
            })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DossierListUiState())
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.ui.dossier.DossierListViewModelTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModel.kt \
        app/src/test/java/com/hereliesaz/doxray/ui/dossier/DossierListViewModelTest.kt
git commit -m "Add DossierListViewModel with TDD"
```

---

## Task 14: `DossierDetailViewModel`

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

`app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt`:
```kotlin
package com.hereliesaz.doxray.ui.dossier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.Encounter
import com.hereliesaz.doxray.db.EncounterDao
import com.hereliesaz.doxray.db.IdentityDao
import com.hereliesaz.doxray.db.IdentityRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

data class DossierDetailUiState(
    val identity: IdentityRecord? = null,
    val encounters: List<Encounter> = emptyList(),
    val socialLinks: List<String> = emptyList(),
    val backgroundData: JSONObject = JSONObject(),
)

class DossierDetailViewModel(
    private val faceId: String,
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
) : ViewModel() {

    private val _state = MutableStateFlow(DossierDetailUiState())
    val state: StateFlow<DossierDetailUiState> = _state

    init {
        AuditLogger.log(
            AuditLogger.Type.DOSSIER_READ,
            summary = "Opened dossier $faceId",
            details = JSONObject().put("faceId", faceId),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val identity = identityDao.getIdentityById(faceId)
            val socialLinks = identity?.socialLinks
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val backgroundData = runCatching { JSONObject(identity?.backgroundData ?: "{}") }
                .getOrElse { JSONObject() }
            _state.value = _state.value.copy(
                identity = identity,
                socialLinks = socialLinks,
                backgroundData = backgroundData,
            )
        }
        viewModelScope.launch {
            encounterDao.observeByFace(faceId).collect { encounters ->
                _state.value = _state.value.copy(encounters = encounters)
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            identityDao.delete(faceId)
            onDeleted()
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailViewModel.kt
git commit -m "Add DossierDetailViewModel"
```

---

## Task 15: `AuditLogViewModel`

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

`app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogViewModel.kt`:
```kotlin
package com.hereliesaz.doxray.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.db.AuditDao
import com.hereliesaz.doxray.db.AuditEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AuditLogViewModel(auditDao: AuditDao) : ViewModel() {
    val events: StateFlow<List<AuditEvent>> = auditDao.observeRecent(limit = 200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogViewModel.kt
git commit -m "Add AuditLogViewModel"
```

---

## Task 16: `LiveScreen` Composable

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt`

- [ ] **Step 1: Create the file**

`app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt`:
```kotlin
package com.hereliesaz.doxray.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LiveScreen(viewModel: LiveViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = if (state.isConnected) "Status: Connected to Glasses" else "Status: Disconnected",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { viewModel.connect() }, enabled = !state.isConnected) { Text("Connect") }
            Button(onClick = { viewModel.disconnect() }, enabled = state.isConnected) { Text("Disconnect") }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Recent Activity Log:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.logLines) { line ->
                Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveScreen.kt
git commit -m "Add LiveScreen Composable backed by LiveViewModel"
```

---

## Task 17: `DossierListScreen` Composable

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListScreen.kt`

- [ ] **Step 1: Create the file**

`app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListScreen.kt`:
```kotlin
package com.hereliesaz.doxray.ui.dossier

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DossierListScreen(viewModel: DossierListViewModel, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.rows.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "No dossiers yet. Connect to glasses and the app will start cataloguing faces.",
                fontSize = 14.sp,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.rows, key = { it.faceId }) { row ->
            DossierRowCard(row = row, onClick = { onOpen(row.faceId) })
        }
    }
}

@Composable
private fun DossierRowCard(row: DossierRow, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(text = row.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = DateUtils.getRelativeTimeSpanString(row.lastSeenMillis).toString(),
                    fontSize = 12.sp,
                )
            }
            Text(text = "${row.encounterCount}×", fontWeight = FontWeight.Bold)
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierListScreen.kt
git commit -m "Add DossierListScreen Composable"
```

---

## Task 18: `DossierDetailScreen` Composable

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`

- [ ] **Step 1: Create the file**

`app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt`:
```kotlin
package com.hereliesaz.doxray.ui.dossier

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.doxray.db.Encounter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DossierDetailScreen(viewModel: DossierDetailViewModel, onDeleted: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }
    val identity = state.identity

    if (identity == null) {
        Text(text = "Dossier not found.", modifier = Modifier.padding(16.dp))
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
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
                IconButton(onClick = { confirmingDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete dossier")
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

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`. If `material-icons-extended` is not on the Compose BOM, replace `Icons.Default.Delete` with `Icons.Filled.Delete` (it's part of the standard icons set).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/dossier/DossierDetailScreen.kt
git commit -m "Add DossierDetailScreen Composable"
```

---

## Task 19: `AuditLogScreen` Composable

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogScreen.kt`

- [ ] **Step 1: Create the file**

`app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogScreen.kt`:
```kotlin
package com.hereliesaz.doxray.ui.audit

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.doxray.db.AuditEvent
import org.json.JSONObject

@Composable
fun AuditLogScreen(viewModel: AuditLogViewModel) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    if (events.isEmpty()) {
        Text(text = "No audit events yet.", modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(events, key = { it.id }) { event ->
            AuditEventCard(event)
        }
    }
}

@Composable
private fun AuditEventCard(event: AuditEvent) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(event.type, fontSize = 10.sp) },
                colors = AssistChipDefaults.assistChipColors(),
            )
            Text(text = event.summary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                text = DateUtils.getRelativeTimeSpanString(event.timestamp).toString(),
                fontSize = 11.sp,
            )
            AnimatedVisibility(visible = expanded) {
                val pretty = runCatching { JSONObject(event.detailsJson).toString(2) }
                    .getOrElse { event.detailsJson }
                Text(
                    text = pretty,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/audit/AuditLogScreen.kt
git commit -m "Add AuditLogScreen Composable"
```

---

## Task 20: Navigation package — `Destinations` + `DoxrayNavRail`

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/nav/Destinations.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`

- [ ] **Step 1: Create Destinations**

`app/src/main/java/com/hereliesaz/doxray/nav/Destinations.kt`:
```kotlin
package com.hereliesaz.doxray.nav

object Destinations {
    const val LIVE = "live"
    const val DOSSIERS = "dossiers"
    const val DOSSIER_DETAIL = "dossiers/{faceId}"
    const val AUDIT = "audit"
    fun dossierDetail(faceId: String) = "dossiers/$faceId"
}
```

- [ ] **Step 2: Create DoxrayNavRail wrapper**

`app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt`:
```kotlin
package com.hereliesaz.doxray.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.AzDockingSide
import com.hereliesaz.aznavrail.azConfig
import com.hereliesaz.aznavrail.azRailItem
import com.hereliesaz.aznavrail.azTheme
import com.hereliesaz.aznavrail.onscreen
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.ui.audit.AuditLogScreen
import com.hereliesaz.doxray.ui.audit.AuditLogViewModel
import com.hereliesaz.doxray.ui.dossier.DossierDetailScreen
import com.hereliesaz.doxray.ui.dossier.DossierDetailViewModel
import com.hereliesaz.doxray.ui.dossier.DossierListScreen
import com.hereliesaz.doxray.ui.dossier.DossierListViewModel
import com.hereliesaz.doxray.ui.live.LiveScreen
import com.hereliesaz.doxray.ui.live.LiveViewModel

@Composable
fun DoxrayNavRail() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    AzHostActivityLayout(
        navController = navController,
        modifier = Modifier,
        currentDestination = currentBackStack?.destination?.route,
        initiallyExpanded = false,
    ) {
        azConfig(dockingSide = AzDockingSide.LEFT, packButtons = true)
        azTheme(activeColor = MaterialTheme.colorScheme.primary, translucentBackground = Color.Black.copy(alpha = 0.5f))
        azRailItem(id = "live", text = "Live", route = Destinations.LIVE)
        azRailItem(id = "dossiers", text = "Dossiers", route = Destinations.DOSSIERS)
        azRailItem(id = "audit", text = "Audit", route = Destinations.AUDIT)

        onscreen(alignment = Alignment.Center) {
            AzNavHost(navController = navController, startDestination = Destinations.LIVE) {
                composable(Destinations.LIVE) {
                    val vm: LiveViewModel = viewModel()
                    LiveScreen(viewModel = vm)
                }
                composable(Destinations.DOSSIERS) {
                    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
                    val dao = AppDatabase.getDatabase(app as android.app.Application).identityDao()
                    val vm = remember { DossierListViewModel(dao) }
                    DossierListScreen(viewModel = vm, onOpen = { faceId ->
                        navController.navigate(Destinations.dossierDetail(faceId))
                    })
                }
                composable(
                    route = Destinations.DOSSIER_DETAIL,
                    arguments = listOf(navArgument("faceId") { type = NavType.StringType }),
                ) { entry ->
                    val faceId = entry.arguments?.getString("faceId").orEmpty()
                    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
                    val db = AppDatabase.getDatabase(app)
                    val vm = remember(faceId) {
                        DossierDetailViewModel(
                            faceId = faceId,
                            identityDao = db.identityDao(),
                            encounterDao = db.encounterDao(),
                        )
                    }
                    DossierDetailScreen(viewModel = vm, onDeleted = { navController.popBackStack() })
                }
                composable(Destinations.AUDIT) {
                    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
                    val dao = AppDatabase.getDatabase(app).auditDao()
                    val vm = remember { AuditLogViewModel(dao) }
                    AuditLogScreen(viewModel = vm)
                }
            }
        }
    }
}
```

Required additional imports inside this file (Compose navigation):
```kotlin
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
```

Add them with the others.

- [ ] **Step 3: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`. If the `composable` function isn't found from `AzNavHost`, it means AzNavRail relies on the regular `androidx.navigation:navigation-compose` API surface — add `implementation("androidx.navigation:navigation-compose:2.9.5")` to `app/build.gradle.kts` and rerun.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/hereliesaz/doxray/nav/Destinations.kt \
        app/src/main/java/com/hereliesaz/doxray/nav/DoxrayNavRail.kt
git commit -m "Add DoxrayNavRail (AzHostActivityLayout + AzNavHost wiring)"
```

---

## Task 21: Refactor `MainActivity` to host `DoxrayNavRail`

**Files:**
- Modify (full rewrite): `app/src/main/java/com/hereliesaz/doxray/MainActivity.kt`

- [ ] **Step 1: Replace the file contents**

`app/src/main/java/com/hereliesaz/doxray/MainActivity.kt`:
```kotlin
package com.hereliesaz.doxray

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hereliesaz.doxray.nav.DoxrayNavRail

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(requiredPermissions)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DoxrayNavRail()
                }
            }
        }
    }
}
```

This deletes ~340 lines (the previous service wiring, `DoxrayUiState`, `DoxrayScreen`, and the inline coroutines). All of that logic now lives in `LiveViewModel` and the Compose screens.

- [ ] **Step 2: Verify the build, including the test suite**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL` with all previous tests (ApiParsingTest, LensoParsingTest, CaptureInterceptorTest, EncounterDaoTest, AuditDaoTest, DossierListViewModelTest) green.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/MainActivity.kt
git commit -m "Shrink MainActivity to AzHostActivityLayout host; move service wiring into LiveViewModel"
```

---

## Task 22: Final verification

- [ ] **Step 1: Full build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL` with all 9+ tests green (the previous 7 + EncounterDaoTest + AuditDaoTest + DossierListViewModelTest).

- [ ] **Step 2: APK present**

Run: `ls -la app/build/outputs/apk/debug/app-debug.apk`
Expected: a non-empty APK.

- [ ] **Step 3: verifyMetaSdk still works**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew verifyMetaSdk --no-daemon 2>&1 | grep "gh.packages.url\|DAT SDK"`
Expected: the same WARN line from Phase 0.

- [ ] **Step 4: No commit needed**

Validation only. If anything failed, return to the offending task.

---

## Notes for the executor

- **JDK:** local environment has JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64`. Every gradle command prefixes `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- **vfat exec bit:** the repo lives on a vfat mount so `gradlew` lacks the unix exec bit. Always invoke as `bash ./gradlew`.
- **Toolchain bumps:** Kotlin 2.3.21 / KSP 2.3.7 / Room 2.8.4 / Compose BOM 2026.05.00 / AGP 9.2.1. Don't downgrade. If a dep version conflict appears, prefer the catalog at `gradle/libs.versions.toml`.
- **AzNavRail on JitPack:** JitPack builds artifacts on first request. The first `:app:compileDebugKotlin` after Task 1 may take longer as JitPack compiles the library. Subsequent builds use the cache.
- **The instrumented migration test** (`Migration_2_3_Test`) is checked in but only runs on a device/emulator. Do not block on it during this plan.
- **No Compose UI tests** are written this phase — layouts will iterate.
- **Don't touch `app/src/stub/java/`** — that's the Meta DAT SDK stub from Phase 0. It stays untouched unless the user supplies `gh.packages.url`.

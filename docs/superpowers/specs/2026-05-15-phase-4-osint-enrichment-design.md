# Phase 4 — OSINT enrichment past name

**Date:** 2026-05-15
**Status:** Approved
**Predecessor:** [Phase 3 — More providers](2026-05-15-phase-3-more-providers-design.md)
**Successor:** TBD (likely Phase 5 — public records, glasses UX, AI agent layer, or ship-as-is)

---

## Context

By the time `performDeepBackgroundScrape` runs, the pipeline has a real name. Today that name only feeds Smart/CyberBackgroundChecks scrapers, which look up phone numbers, addresses, and known relatives — useful but US-flavoured public-records data. There's another whole class of enrichment that the name unlocks: bio data from professional/social platforms, alternate identity surfaces (GitHub), and historical versions of profiles (Wayback). Phase 4 adds that layer.

Specifically:
- **SerpAPI site: queries** for LinkedIn / Twitter / Instagram — the SerpAPI key already exists from Phase 0 (`SERPAPI_KEY`, currently only used by `YandexSearchService`). Three more queries per identification reuse the same key without new auth setup.
- **GitHub username probe** — many people who appear in correlation results don't show up as a `github.com/<username>` page directly, but a small percentage do, and the probe is free. It tries plausible username variants and Jsoup-parses the public profile.
- **Wayback Machine snapshot lookup** — when the dossier has social links that no longer resolve (deleted Twitter accounts, abandoned LinkedIn profiles), `archive.org` often has snapshots. The lookup is free, fast, and complements every other social-link Phase already pulled.

HaveIBeenPwned was on the original scope; dropped during brainstorm. Its email-exposure data is high-signal but requires a paid HIBP API key and only fires when an email leaks out of the bg scrape — marginal hit rate for the cost.

The pipeline shape stays simple: new services run in parallel inside `performDeepBackgroundScrape`, their results are folded into the same `backgroundData` JSON column on `IdentityRecord`, and the existing Dossier Detail screen renders them via its existing pretty-printed JSON view. No schema migration, no new audit type, no new UI.

---

## Goals

After Phase 4:

1. Three new `api/` services — `SerpApiSiteSearchService`, `GitHubProbeService`, `WaybackMachineService` — each a single `class` (no scraper counterpart because each is either API-only or scraper-only by nature).
2. `LiveViewModel.performDeepBackgroundScrape` fans them out in parallel via `coroutineScope { async {} ... await }` after the existing Smart/CyberBg scrapers. Results merge into `backgroundData` JSON under three new top-level keys (`osint_serpapi`, `osint_github`, `osint_wayback`).
3. Each service has a small `toJson(): JSONObject` helper so the merge into `backgroundData` is one line per source.
4. Three new parser unit tests (`SerpApiSiteSearchParserTest`, `GitHubProbeParserTest`, `WaybackMachineParserTest`) — same TDD pattern as Phase 3.
5. No new schema, no new audit type. Existing `API_CALL` audit (via `CaptureInterceptor` from Phase 1) records every outbound request automatically.

Non-goals:
- HIBP (dropped).
- Scraper fallback for SerpAPI when key missing (would require a real Google scraper).
- Dossier UI changes to surface enrichment prominently — the existing JSON pretty-print is enough.
- New audit type or persistence column.

---

## Architecture

### Pipeline position

```
processFocusedFace
   ↓ (existing) embed + cache lookup + identify pipeline + correlation merge
   ↓
performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks)
   ↓
   smartBgScraper.searchBackground(name)      ← existing, sequential
   cyberBgScraper.searchBackground(name)      ← existing, sequential
   ↓
   coroutineScope {                            ← NEW: parallel fan-out
     async { serpApiSiteSearch.search(name) }
     async { gitHubProbe.probe(name) }
     async { waybackMachine.snapshotAll(socialLinks) }
   }.awaitAll
   ↓
   bgDataJson.put("smart"|"cyber"|"osint_serpapi"|"osint_github"|"osint_wayback", …)
   ↓
   localFaceCache.cacheIdentity(... , backgroundData = bgDataJson.toString())
```

### Components

**`SerpApiSiteSearchService`** — three SerpAPI Google searches in parallel within a single `search(name)` call.

```kotlin
class SerpApiSiteSearchService {
    data class Result(
        val linkedIn: List<Hit>,
        val twitter: List<Hit>,
        val instagram: List<Hit>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("linkedIn", JSONArray(linkedIn.map { it.toJson() }))
            put("twitter", JSONArray(twitter.map { it.toJson() }))
            put("instagram", JSONArray(instagram.map { it.toJson() }))
        }
    }
    data class Hit(val title: String, val snippet: String, val link: String) {
        fun toJson() = JSONObject().apply {
            put("title", title); put("snippet", snippet); put("link", link)
        }
    }

    suspend fun search(name: String): Result? {
        if (BuildConfig.SERPAPI_KEY.isBlank()) return null
        // Three async SerpAPI calls: q="site:linkedin.com \"$name\"" etc., top 3 results each
        // Parse via SerpApiSiteSearchParser
    }
}
```

Each query uses `https://serpapi.com/search.json?engine=google&q=...&api_key=$SERPAPI_KEY&num=3`. The existing `YandexSearchService` already uses Retrofit against SerpAPI but for the `yandex_images` engine — this new service does the `google` engine. Use `HttpClients.api()` and raw OkHttp (the existing Retrofit is only worth reusing for one endpoint, not two).

**`GitHubProbeService`** — probe up to 4 GitHub username variants per name and Jsoup-parse the profile.

```kotlin
class GitHubProbeService {
    data class Result(val profiles: List<Profile>) {
        fun toJson() = JSONObject().apply {
            put("profiles", JSONArray(profiles.map { it.toJson() }))
        }
    }
    data class Profile(
        val username: String,
        val bio: String,
        val followers: Int,
        val publicRepos: Int,
    ) {
        fun toJson() = JSONObject().apply {
            put("username", username); put("bio", bio)
            put("followers", followers); put("publicRepos", publicRepos)
        }
    }

    suspend fun probe(name: String): Result? {
        val variants = buildVariants(name)   // see below
        // Parallel GET https://github.com/<variant>; skip 404s
        // Jsoup-parse profile bio + counters
    }
}
```

Username variants for `"John Smith"`:
- `johnsmith`
- `john-smith`
- `john_smith`
- `jsmith`  (first-initial + last)

For single-name input (no space) the variants collapse to the single lowercased name. For 3+ word names use the first and last word only. Empty `bio` is OK and stored as `""`.

**`WaybackMachineService`** — given a list of URLs, query `archive.org/wayback/available?url=<encoded>` for each. Parse the `archived_snapshots.closest` block.

```kotlin
class WaybackMachineService {
    data class Result(val snapshots: List<Snapshot>) {
        fun toJson() = JSONObject().apply {
            put("snapshots", JSONArray(snapshots.map { it.toJson() }))
        }
    }
    data class Snapshot(
        val originalUrl: String,
        val archiveUrl: String,
        val timestamp: String,   // raw "20240115123045" form from Wayback
    ) {
        fun toJson() = JSONObject().apply {
            put("originalUrl", originalUrl)
            put("archiveUrl", archiveUrl)
            put("timestamp", timestamp)
        }
    }

    suspend fun snapshotAll(urls: List<String>): Result? {
        if (urls.isEmpty()) return null
        // For each URL, parallel-await GET archive.org/wayback/available?url=...
        // Parse via WaybackMachineParser
    }
}
```

No key required. Uses `HttpClients.api()` (Wayback returns clean JSON; no need for browser headers).

### Parsers

Each service has a dedicated `ResponseParser` object for testability:

- `SerpApiSiteSearchParser.parse(jsonBody): List<Hit>` — takes one SerpAPI response, returns up to 3 hits (the caller invokes it three times with the three platform-specific JSON bodies and stitches into `Result`).
- `GitHubProbeParser.parse(html): Profile?` — Jsoup-parses a profile page, returns null on 404-like content.
- `WaybackMachineParser.parse(jsonBody, originalUrl): Snapshot?` — parses `archived_snapshots.closest` block, returns null when `closest` is absent.

Pure Kotlin where possible (the SerpAPI + Wayback parsers); GitHub probe uses Jsoup which is JVM-pure so it tests under plain JUnit (same as `GoogleLensScrapingTest` pattern from Phase 3).

### LiveViewModel wiring

Three new fields, three new lines inside `performDeepBackgroundScrape`. Full new body of `performDeepBackgroundScrape`:

```kotlin
private val serpApiSiteSearch = SerpApiSiteSearchService()
private val gitHubProbe = GitHubProbeService()
private val waybackMachine = WaybackMachineService()

private suspend fun performDeepBackgroundScrape(
    primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>,
) {
    metaGlassesManager.playAudioMessage("Digging for background data.")
    appendLog("Digging for deep background info on: $primaryIdentity...")
    val bgDataJson = JSONObject()

    // Existing — sequential bg scrapers
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

    // NEW — OSINT enrichment fan-out
    coroutineScope {
        val serpJob = async { serpApiSiteSearch.search(primaryIdentity) }
        val ghJob = async { gitHubProbe.probe(primaryIdentity) }
        val wbJob = async { waybackMachine.snapshotAll(socialLinks) }

        serpJob.await()?.let {
            bgDataJson.put("osint_serpapi", it.toJson())
            appendLog("SerpAPI: LinkedIn=${it.linkedIn.size} Twitter=${it.twitter.size} IG=${it.instagram.size}")
        }
        ghJob.await()?.let {
            bgDataJson.put("osint_github", it.toJson())
            if (it.profiles.isNotEmpty()) appendLog("GitHub: ${it.profiles.size} profile(s) found")
        }
        wbJob.await()?.let {
            bgDataJson.put("osint_wayback", it.toJson())
            if (it.snapshots.isNotEmpty()) appendLog("Wayback: ${it.snapshots.size} snapshot(s)")
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
```

### Schema

Unchanged. The existing `IdentityRecord.backgroundData: String` column absorbs three new top-level JSON keys. `DossierDetailScreen` already shows `state.backgroundData.toString(2)` (pretty-printed) under the "Background" section — new keys appear automatically.

### Audit

Unchanged. Every outbound HTTP call goes through `HttpClients.api()` (or `HttpClients.browser()` for the GitHub probe) which is wired through `CaptureInterceptor` from Phase 1. Each call emits one `API_CALL` audit row with `host` + `path` + `code` in the summary. Operators can filter by `serpapi.com` / `github.com` / `archive.org` in the existing Audit Log screen.

---

## Testing

### Unit tests

1. **`SerpApiSiteSearchParserTest`** (`app/src/test/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParserTest.kt`) — fixture-driven, three cases:
   - Standard response with `organic_results: [{ title, snippet, link }, …]` → top 3 hits parsed.
   - Empty `organic_results` → empty list.
   - Malformed JSON → empty list (not null — caller handles null at the service layer).

2. **`GitHubProbeParserTest`** (`app/src/test/java/com/hereliesaz/doxray/api/GitHubProbeParserTest.kt`) — plain JUnit, fixture HTML:
   - Profile HTML with `.p-name`, `.user-profile-bio`, `[data-tab-item="followers"]`, `[itemprop="owns"]` → Profile parsed.
   - HTML lacking any of those selectors → null.
   - GitHub 404 page (HTML) → null.

3. **`WaybackMachineParserTest`** (`app/src/test/java/com/hereliesaz/doxray/api/WaybackMachineParserTest.kt`) — fixture-driven:
   - Standard response with `archived_snapshots.closest.url + timestamp` → Snapshot parsed.
   - No `closest` block → null.
   - Malformed JSON → null.

### Manual / smoke

After build, install debug APK + run with a real face that produces an identified name:
- Expect ~5 new HTTP calls (3 SerpAPI + 1-4 GitHub probes + N Wayback per existing social link).
- Expect a new audit row per call.
- Expect 3 new keys in the dossier's `backgroundData` JSON pretty-print.

No pipeline integration test — same rationale as Phase 3's bg-scrape glue: the orchestration logic is one straight-line function in `LiveViewModel`, and the value of an integration test there is low compared to per-service parser tests.

---

## File layout

```
com.hereliesaz.doxray.api/
├── SerpApiSiteSearchService.kt           (new)
├── SerpApiSiteSearchParser.kt            (new)
├── GitHubProbeService.kt                 (new)
├── GitHubProbeParser.kt                  (new)
├── WaybackMachineService.kt              (new)
└── WaybackMachineParser.kt               (new)

app/src/test/java/com/hereliesaz/doxray/api/
├── SerpApiSiteSearchParserTest.kt        (new)
├── GitHubProbeParserTest.kt              (new)
└── WaybackMachineParserTest.kt           (new)
```

**Modified:**
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` — three new fields, new fan-out block inside `performDeepBackgroundScrape`, three new imports.

No `build.gradle.kts` changes (SERPAPI_KEY already exists). No README changes (no new API keys needed).

---

## Decisions made during brainstorm

- **Scope:** SerpAPI site: queries + GitHub probe + Wayback. HIBP dropped (paid + email-dependent + low hit rate).
- **Pipeline position:** inside `performDeepBackgroundScrape`, after the existing Smart/CyberBg scrapers, in a single `coroutineScope { … }` parallel fan-out. Single `cacheIdentity` call at the end with merged JSON.
- **No scraper symmetry:** each new service is single-purpose (API-only or scraper-only by nature of the source). No second-tier fallback.
- **Audit:** existing `API_CALL` via `CaptureInterceptor` covers visibility. No new enum value.
- **Schema:** unchanged. New keys merge into the existing `backgroundData` JSON column.
- **No UI changes:** Dossier Detail's existing pretty-printed JSON view renders new keys automatically.

---

## Out of scope for downstream phases

- **HIBP and other paid lookups** (BeenVerified, Spokeo, PeopleFinder). Could land in a future "deep premium enrichment" phase if scope demands grow.
- **Per-source dossier UI sections.** Currently all enrichment lands in one JSON blob. A future UI pass could split into per-source sections with collapsible headers.
- **Email-driven enrichment.** If a future phase adds HIBP, `IdentityRecord` would benefit from a denormalized `emails: String` (CSV) field so HIBP doesn't need to re-parse the bg JSON every time.
- **Result freshness / re-enrichment.** Today enrichment runs once at first identification and is frozen. A future enhancement could re-run enrichment when a cached dossier is revisited and N days have passed.
- **Username variants beyond 4.** GitHub probe could expand to more permutations (`john.smith`, `johnnysmith`, etc.) if hit rate justifies the additional 404s.

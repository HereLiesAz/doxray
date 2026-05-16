# Phase 3 — More face-search providers

**Date:** 2026-05-15
**Status:** Approved
**Predecessor:** [Phase 2 — Quality gate](2026-05-15-phase-2-quality-gate-design.md)
**Successor:** Phase 4 — OSINT enrichment past name (TBD)

---

## Context

After Phase 0 the HTTP plumbing was real; Phase 1 made the data visible; Phase 2 stopped wasting credits on garbage frames. The identify pipeline still hits only four endpoints though: Lenso → FaceSeek → FaceCheck.ID (face tier, sequential) and Yandex (correlation tier, single provider). When all four miss, the dossier is empty even when the person is plainly catalogued elsewhere on the web.

Phase 3 adds three more providers:
- **PimEyes** in the face tier — major commercial face-search index, biggest match-rate uplift available.
- **TinEye** in the correlation tier — reverse-image lookup that catches matches Yandex misses (e.g. small US image hosts).
- **Google Lens** in the correlation tier — unofficial scraper of `lens.google.com/uploadbyurl`. No paid API but covers Google's index.

Bing Visual Search was on the original scope; dropped during brainstorm. Its API is the most stable of the four candidates but the marginal coverage gain over Yandex + TinEye + Google Lens didn't justify the engineering cost. Available as Phase 3.5 if the others prove too fragile.

The pipeline shape changes too. Today both tiers are sequential first-match-wins. Phase 3 fans out both tiers in parallel and merges results — every provider that returns a confident match contributes a reference URL into the face tier output (deduped), and every correlation provider that returns identities/links contributes to the merged set. This maximises recall at the cost of running every provider on every identification.

---

## Goals

After Phase 3:

1. Three new services + three new scrapers, all hooked through the existing `HttpClients` singletons and audited via `CaptureInterceptor`'s `API_CALL` events.
2. A new `IdentifyPipeline` class owns the fan-out + merge orchestration. `LiveViewModel.processFocusedFace` becomes a ~20-line caller.
3. The face tier runs all four providers (Lenso/FaceSeek/FaceCheck.ID/PimEyes) in parallel via `async {}`. Output is the deduped list of `referenceUrl`s from providers returning confidence > 0.6.
4. The correlation tier runs all three providers (Yandex/TinEye/Google Lens) in parallel **per unique reference URL**. Output is a single `CorrelationHit` whose `identities` and `socialLinks` are the deduped unions across all responses.
5. `PIMEYES_KEY` and `TINEYE_KEY` are wired through `local.properties → BuildConfig` like the existing `SERPAPI_KEY` / `FACESEEK_KEY` / `LENSO_KEY` / `FACECHECK_KEY`. Google Lens has no key (scraper-only).
6. Test coverage: three new parser/scraper tests + two new pipeline tests.

Non-goals:
- Bing Visual Search (deferred to potential Phase 3.5).
- Per-provider confidence calibration (uniform 0.6 threshold for all face providers continues).
- Cross-URL correlation deduplication (when two face providers return the same `referenceUrl`, the dedup already handles it; but if two return *different* URLs that resolve to the same web page, we don't detect that).
- New schema. No Room migration.
- New UI. Dossier screen already renders any identities/links list.

---

## Architecture

### New package + class

```
com.hereliesaz.doxray.identify/
└── IdentifyPipeline.kt
```

`IdentifyPipeline` is constructed once per `LiveViewModel`. It owns references to all face + correlation services (existing + new) and exposes two suspend functions:

```kotlin
class IdentifyPipeline(
    private val lensoService: LensoSearchService,
    private val lensoScraper: LensoScraperService,
    private val faceSeekService: FaceSeekService,
    private val faceSeekScraper: FaceSeekScraperService,
    private val faceCheckService: FaceCheckIdService,
    private val faceCheckScraper: FaceCheckIdScraperService,
    private val pimEyesService: PimEyesService,
    private val pimEyesScraper: PimEyesScraperService,
    private val yandexService: YandexSearchService,
    private val yandexScraper: YandexScraperService,
    private val tinEyeService: TinEyeSearchService,
    private val tinEyeScraper: TinEyeScraperService,
    private val googleLensService: GoogleLensSearchService,   // returns null — kept for symmetry
    private val googleLensScraper: GoogleLensScraperService,
) {
    data class FaceHit(
        val provider: String,
        val faceId: String,
        val confidence: Float,
        val referenceUrl: String,
    )
    data class CorrelationHit(
        val identities: List<String>,
        val socialLinks: List<String>,
    )

    suspend fun runFaceTier(imageBytes: ByteArray): List<FaceHit> = coroutineScope {
        listOf(
            async { lensoFaceHit(imageBytes) },
            async { faceSeekFaceHit(imageBytes) },
            async { faceCheckFaceHit(imageBytes) },
            async { pimEyesFaceHit(imageBytes) },
        ).awaitAll()
            .filterNotNull()
            .filter { it.confidence > FACE_CONFIDENCE_THRESHOLD }
            .distinctBy { it.referenceUrl }
    }

    suspend fun runCorrelationTier(refUrl: String): CorrelationHit = coroutineScope {
        val results = listOf(
            async { yandexService.searchIdentity(refUrl) ?: yandexScraper.searchIdentity(refUrl) },
            async { tinEyeService.searchIdentity(refUrl) ?: tinEyeScraper.searchIdentity(refUrl) },
            async { googleLensService.searchIdentity(refUrl) ?: googleLensScraper.searchIdentity(refUrl) },
        ).awaitAll().filterNotNull()
        CorrelationHit(
            identities = results.flatMap { it.identities }.distinct(),
            socialLinks = results.flatMap { it.socialLinks }.distinct(),
        )
    }

    private suspend fun lensoFaceHit(bytes: ByteArray): FaceHit? {
        val r = lensoService.identifyFace(bytes) ?: lensoScraper.identifyFace(bytes) ?: return null
        return FaceHit("lenso", r.faceId, r.confidence, r.referenceImageUrl)
    }
    // … same shape for faceSeek / faceCheck / pimEyes

    companion object {
        private const val FACE_CONFIDENCE_THRESHOLD = 0.6f
    }
}
```

The correlation tier's result types (`YandexSearchService.Result`, `TinEyeSearchService.Result`, `GoogleLensSearchService.Result`) must share a structural shape: an `identities: List<String>` and `socialLinks: List<String>`. Yandex already has this. The two new types match it. The pipeline only reads those two fields off each.

### Pipeline shape

```
After embedding + cache lookup miss, LiveViewModel.processFocusedFace calls:

  faceHits = identifyPipeline.runFaceTier(imageBytes)   // 4 async, dedup URLs

  if (faceHits.isEmpty()) → audio "no match" + return

  // Fan correlation across each unique URL, merge results
  merged = faceHits
      .map { hit -> async { identifyPipeline.runCorrelationTier(hit.referenceUrl) } }
      .awaitAll()
      .fold(CorrelationHit(empty, empty)) { acc, r ->
          CorrelationHit(
              identities = (acc.identities + r.identities).distinct(),
              socialLinks = (acc.socialLinks + r.socialLinks).distinct(),
          )
      }

  primaryIdentity = merged.identities.firstOrNull().orEmpty()
  faceId          = faceHits.maxByOrNull { it.confidence }!!.faceId

  // Existing flow: deep-bg scrape + cacheIdentity continue
```

The result is that on a face captured against 4 face-providers and 2 unique reference URLs, the pipeline runs **4 face calls (parallel) + 2 × 3 correlation calls (parallel)** = 10 outbound HTTP calls per identification. Phase 2's quality gate is the gatekeeper that prevents this from firing on garbage frames.

---

## Provider details

### PimEyes (face tier)

**API path** (`PimEyesService`):
- POST `https://pimeyes.com/api/search/upload` with `Authorization: Bearer $PIMEYES_KEY`, body multipart `image=<jpeg>`.
- Response: `{ "search_hash": "abc123…" }`.
- POST `https://pimeyes.com/api/search/results` with same Authorization, body JSON `{ "searchHash": "<hash>" }`.
- Response: `{ "results": [{ "url": "<source page>", "score": 0..1, "thumbnail": "…" }, …] }`.

`PimEyesResponseParser.parse(json)` returns the top match as a `PimEyesService.Result(faceId, confidence, referenceImageUrl)`. `faceId` is derived from the result thumbnail URL hash; `confidence` is the raw `score`; `referenceImageUrl` is `result.url`.

Mark `// PHASE-3: real endpoint may need capture refinement` — the documented PimEyes API surface drifts and this matches the FaceSeek/Lenso treatment from Phase 0.

**Scraper path** (`PimEyesScraperService`):
- Same two-step upload + poll flow without `Authorization`. PimEyes' public demo limits to thumbnails-only but the `searchHash` + first-page-results pattern works anonymously.
- Reuses `PimEyesResponseParser` via shared parsing.

**HttpClients**: `api()` for the authenticated service; `browser()` for the scraper (cookie jar + browser headers + capture interceptor).

### TinEye (correlation tier)

**API path** (`TinEyeSearchService`):
- POST `https://api.tineye.com/rest/search/` with HMAC-SHA256 request signing. Body params: `image_url=<refUrl>`, `nonce=<random>`, `date=<unix>`, `api_key=$TINEYE_KEY`, `api_sig=<hmac>`.
- The signature is `hmac-sha256($TINEYE_KEY_SECRET, "POST/.../<sorted-query>")`. Phase 3 will need both a public key (`TINEYE_KEY`) and private key (`TINEYE_SECRET`) in `local.properties` — TinEye gives you both. Both empty → service no-ops, scraper runs.
- Response: `{ "results": { "matches": [{ "image_url": "...", "domain": "..." }, …] } }`.

`TinEyeResponseParser.parse(json)` returns `TinEyeSearchService.Result(identities = match.domain.distinct(), socialLinks = match.image_url.distinct())`. We treat domains as identities for the same reason Yandex does — the domain often hosts the canonical profile.

**Scraper path** (`TinEyeScraperService`):
- POST `https://tineye.com/search` form with `url=<refUrl>`. Returns HTML with match cards.
- Jsoup parses `.match a` for the destination URL and `.match-row .domain` for the domain.

### Google Lens (correlation tier)

**API path** (`GoogleLensSearchService`):
- No API key exists. `searchIdentity(referenceUrl)` returns `null` unconditionally.
- The class exists for structural symmetry — every other provider has `Service + Scraper`, and the `IdentifyPipeline` is cleaner if it can call `Service ?: Scraper` uniformly.

**Scraper path** (`GoogleLensScraperService`):
- GET `https://lens.google.com/uploadbyurl?url=<encoded>` with browser client (cookie jar + browser headers + referer).
- Follow redirects to the result page.
- Parse `a[href]` inside `.result` containers for outbound URLs. Page titles inside `.result-title` are the identities.

Google Lens redesigns its frontend regularly — this is the most fragile of the three new scrapers. Mark `// PHASE-3: HTML selectors guessed, refine with capture mode`.

### BuildConfig keys

Add to `app/build.gradle.kts`'s `defaultConfig` block:
```kotlin
buildConfigField("String", "PIMEYES_KEY", "\"${secret("PIMEYES_KEY")}\"")
buildConfigField("String", "TINEYE_KEY", "\"${secret("TINEYE_KEY")}\"")
buildConfigField("String", "TINEYE_SECRET", "\"${secret("TINEYE_SECRET")}\"")
```

The existing `secret()` helper reads from `local.properties` first, then env vars. Empty fallback means the service uses the scraper path automatically.

`README.md`'s API-keys block gains three lines documenting the new keys.

---

## Audit logging

No new `AuditLogger.Type` value. Every new HTTP call automatically lands as `API_CALL` audit row via `CaptureInterceptor`. The summary already reads `METHOD host/path → status`, which is informative enough — operators can filter on PimEyes / TinEye / Google Lens in the existing Audit Log screen.

For the face tier outcome we don't need an audit row per provider — the existing `IDENTIFY` events from `LocalFaceCache.cacheIdentity` cover the merged result. If individual provider visibility becomes important later, the granularity is one line of new code.

---

## Testing

### New unit tests

1. **`PimEyesParsingTest`** (`app/src/test/java/com/hereliesaz/doxray/api/PimEyesParsingTest.kt`) — plain JUnit, fixture-driven:
   - Top result parsed (score 0.87 → confidence 0.87, url → referenceImageUrl, derived faceId).
   - Empty results returns null.
   - Malformed JSON returns null.

2. **`TinEyeParsingTest`** (`.../TinEyeParsingTest.kt`) — plain JUnit, fixture-driven:
   - Match results parsed into `Result(identities=distinct domains, socialLinks=distinct image_urls)`.
   - Empty matches returns null.
   - Malformed JSON returns null.

3. **`GoogleLensScrapingTest`** (`.../GoogleLensScrapingTest.kt`) — Robolectric (`Jsoup` works in plain JUnit too, but consistency with the rest of the scraper tests is worth the small overhead):
   - Given a fixture HTML body with two result anchors, assert two extracted identities + two socialLinks.

### New pipeline tests

4. **`IdentifyPipelineFaceTierTest`** (`app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineFaceTierTest.kt`) — plain JUnit with fakes:
   - One provider returns confidence > 0.6 → result has one FaceHit.
   - Multiple providers return overlapping URLs → result deduped by `referenceUrl`.
   - All return null/low-confidence → empty list.

5. **`IdentifyPipelineCorrelationMergeTest`** — plain JUnit with fakes:
   - Two correlation providers return overlapping identities + links → merged + deduped.
   - All correlation providers return null → empty CorrelationHit.

### Manual / smoke

After build, install the debug APK + run with capture mode on, then:
- Point camera at a face for 6+ seconds.
- Expect: ~10 capture files appear under `Android/data/com.hereliesaz.doxray/files/captures/` (4 face + 3×N correlation per unique URL).
- Audit log should show one `API_CALL` row per outbound request.

---

## Decisions made during brainstorm

- **Scope**: PimEyes + TinEye + Google Lens. Bing Visual Search dropped (largest engineering cost for marginal coverage gain over Yandex/TinEye/Google Lens together).
- **Pipeline shape**: parallel fan-out, merge correlation results. Face tier picks deduped URLs; correlation tier fans out per unique URL and merges identities/socialLinks across all providers.
- **Extraction**: `IdentifyPipeline` is a new class; the orchestration moves out of `LiveViewModel.processFocusedFace`.
- **Google Lens asymmetry**: `Service` returns null, `Scraper` does all the work. Structural symmetry preserved.
- **No new audit type**: Existing `API_CALL` from `CaptureInterceptor` covers visibility.
- **Confidence threshold**: uniform 0.6 across face providers.
- **No new schema, no new UI**: Dossier surface from Phase 1 renders any merged identities/socialLinks unchanged.

---

## Out of scope for downstream phases

- **Bing Visual Search**: if the three new providers prove too fragile in production, Phase 3.5 adds Bing (most stable API of the four).
- **Per-provider calibration**: a 0.6 threshold may be too aggressive for some providers. A `BuildConfig.*_CONFIDENCE` per-provider override would let us tune without re-compiling.
- **Correlation result caching**: When face providers A and B return the same reference URL, the dedup catches it. But when they return *different* URLs that resolve to the same page, the correlation tier runs twice for effectively the same input. A redirect-following resolver + cache could close that gap.
- **Per-source attribution in the UI**: the dossier currently shows a flat list of identities/links with no indication of which provider returned them. A future enhancement could tag each item with its source.

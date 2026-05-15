# Phase 0 — Baseline Fix-ups

**Date:** 2026-05-14
**Status:** Approved
**Predecessor:** none (first phase)
**Successor:** Phase 1 — Dossier surface in Compose

---

## Context

`doxxr` has its scaffolding in place: Compose UI, four face-search services (Lenso, FaceSeek, FaceCheck.ID, Yandex correlation), two background scrapers (SmartBg, CyberBg), Room persistent cache, ML Kit tracking, TFLite embedding, Meta DAT SDK stub fallback. The build is green.

The problem: most of the data layer is *theatrical*. Web reconnaissance during brainstorm surfaced four concrete defects:

1. **`LensoSearchService.kt` parses the wrong response schema.** Lenso publishes their API at `github.com/lenso-ai/reverse-image-search-api`; the real response is `results[].urlList[].{imageUrl, sourceUrl, title}` + top-level `confidenceScore` (0–100). Our code expects `results[].url/domain/similarity`.
2. **TFLite filename mismatch.** `EmbeddingGenerator.kt` opens `mobile_face_net.tflite`; the bundled asset is `mobilefacenet.tflite`. The face cache never works until this is fixed.
3. **SmartBg + CyberBg scrapers will 403 in production.** Both sites return `403 Forbidden` to bot-shaped HTTP. The scrapers currently send a UA string only; they need a full browser-shaped header set and a cookie jar with a warmup GET.
4. **FaceSeek scraper is honestly a guess.** No public API docs, SPA upload, no static-fetchable form. We cannot fix this from the outside; we need real captured traffic from a running device.

Phase 0 fixes 1–3 directly and installs the instrumentation (#4) that unblocks every future scraper iteration.

---

## Goals

After Phase 0:

- A real face captured by the app produces a real cached `IdentityRecord` (TFLite works).
- Lenso (API + anonymous scraper) returns correctly-parsed `Result` objects.
- SmartBg/CyberBg scrapers send browser-class requests and use a cookie jar; they may still be blocked by JS challenges, but logs surface that case clearly.
- Any HTTP traffic the app makes can be captured to a file by flipping a debug toggle.
- The Meta DAT SDK swap-in is a one-line edit to `local.properties` and there's a Gradle task that verifies it resolves.

Non-goals (deferred to later phases):

- Real working FaceSeek selectors (requires user-supplied captures — Phase 0.1)
- A UI to view captures (Phase 1 will host it)
- A JS-execution / WebView scraper for sites behind Cloudflare JS challenges (deferred, possibly Phase 4)
- Any new face-search providers (Phase 3)

---

## Components

### 1. `net.HttpClients` (new, shared)

Single source of `OkHttpClient` instances used across every service and scraper. Two flavours:

- `HttpClients.api()` — minimal client, JSON content type, no cookie jar. For authenticated API calls.
- `HttpClients.browser()` — full browser-shaped client:
  - `User-Agent`: Chrome 120 on Windows desktop
  - `Accept`, `Accept-Language: en-US,en;q=0.9`, `Accept-Encoding: gzip, deflate, br`
  - `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `Sec-Fetch-User`, `Upgrade-Insecure-Requests`
  - In-memory `CookieJar` that survives across calls
  - 30s connect/read timeouts
- Both go through `CaptureInterceptor` (below).

Wired by replacing every ad-hoc `OkHttpClient.Builder()` in the codebase. New code in `app/src/main/java/com/hereliesaz/doxray/net/HttpClients.kt`.

### 2. `net.CaptureInterceptor` (new)

OkHttp `Interceptor`. When `BuildConfig.DEBUG_CAPTURE_HTTP` is true, dumps each request/response (headers + body bytes) to `context.getExternalFilesDir("captures")/{epochMs}_{seq}_{host}.{req|resp}.bin`, where `seq` is a monotonic `AtomicLong` to disambiguate calls inside the same millisecond. When false, passes through with zero overhead.

File format per capture: the request line (`POST /path HTTP/1.1`), each header on its own line, blank line, then raw body bytes. Binary-safe — handles non-text response bodies like images.

Wired into both clients in `HttpClients`. Receives an `Application` reference via a static init called from `Application.onCreate` — we'll add a minimal `DoxrayApp : Application` class in this phase and register it in the manifest.

### 3. `LensoSearchService.kt` rewrite

Replace the result parser with the documented schema:

```kotlin
val results = json.optJSONArray("results") ?: return null
if (results.length() == 0) return null
val top = results.getJSONObject(0)
val urlList = top.optJSONArray("urlList") ?: return null
val firstUrl = urlList.optJSONObject(0)
val confidence = top.optDouble("confidenceScore", 0.0).toFloat() / 100f
Result(
    faceId = "lenso_${top.optString("date", "")}_${top.optString("base64Image", "").hashCode()}",
    confidence = confidence,
    referenceImageUrl = firstUrl?.optString("sourceUrl", "") ?: "",
    sourceDomain = firstUrl?.optString("title", "") ?: "",
)
```

Move from ad-hoc `OkHttpClient.Builder()` to `HttpClients.api()`.

### 4. `LensoScraperService.kt` rewrite

Same parser as the API service. Anonymous mode: best-effort attempt at the keyless preview the public Lenso site offers. Two candidate paths to try in order:

1. POST to `https://api.eyematch.ai/search` without `Authorization` header (some keyless tiers exist on that host).
2. POST to `https://api.lenso.ai/search` (the documented *non-face* category endpoint) without `Authorization` header as a degraded fallback.

If both fail, return `null` and rely on the capture interceptor to record the actual web-flow response for a future iteration. Browser-shaped client.

### 5. `SmartBackgroundChecksScraper.kt` + `CyberBackgroundChecksScraper.kt` hardening

Two-step flow on `HttpClients.browser()`:

1. GET `https://www.{site}.com/` (warmup; populates cookies)
2. GET `/people/{first}-{last}` (uses cookies from step 1)

If either returns 403 / 503, log the response code + first 200 bytes of body and return `null`. Existing selectors stay as best-effort; updating them is gated on real captures (Phase 0.1 or via the capture interceptor + a real device).

### 6. `EmbeddingGenerator.kt`

Single change: `"mobile_face_net.tflite"` → `"mobilefacenet.tflite"`. The existing try/catch already handles missing-file gracefully.

### 7. `FaceSeekService.kt` + `FaceSeekScraperService.kt`

No code changes — explicitly mark with a `// PHASE-0: real flow unknown, instrumented capture mode active`. Move clients to `HttpClients.api()` / `HttpClients.browser()` so captures fire when used.

### 8. `app/build.gradle.kts`

- Add `buildConfigField("boolean", "DEBUG_CAPTURE_HTTP", "true")` to `debug` build type, `"false"` to `release`. (Currently only `release` is defined; add an explicit `debug` block.)
- Add `verifyMetaSdk` task: `tasks.register("verifyMetaSdk") { doLast { … } }` that checks whether `gh.packages.url` is configured and (if so) reports whether `com.facebook.wearables:dat-android:1.0.0-beta` is on the runtime classpath. Prints `OK: real DAT SDK resolved` or `WARN: stub fallback active because <reason>`.

### 9. `DoxrayApp : Application`

New file `app/src/main/java/com/hereliesaz/doxray/DoxrayApp.kt`. Wires `HttpClients`/`CaptureInterceptor` with the application context on startup. Registered in `AndroidManifest.xml` via `android:name=".DoxrayApp"`.

### 10. `README.md` Meta DAT section

New section showing the exact `local.properties` keys needed:

```
gh.user=<your-github-username>
gh.token=<personal-access-token-with-read:packages>
gh.packages.url=https://maven.pkg.github.com/facebook/meta-wearables-dat-android
```

Plus a note: "Run `./gradlew verifyMetaSdk` to confirm credentials work."

---

## Testing

### Unit tests

- `LensoParsingTest`: feed a fixture JSON matching the documented schema, assert the `Result` has the right confidence (normalised), sourceUrl, and sourceDomain. Add to `app/src/test/java/com/hereliesaz/doxray/api/LensoParsingTest.kt`.
- Reuse the pattern in the existing `ApiParsingTest.kt`.

### Manual verification

- `./gradlew :app:assembleDebug :app:test` — green.
- Install debug APK on a real device, hit Connect, wait for a frame, verify files appear in `Android/data/com.hereliesaz.doxray/files/captures/` (visible via `adb shell ls`).
- Run `./gradlew verifyMetaSdk` once with `gh.packages.url` empty, once configured — confirm both paths print clear messages.

---

## Decisions made during brainstorm

- **Scraper recon strategy:** combine live WebFetch (already done — Lenso docs found, 403s confirmed) with instrumented capture mode (this phase ships it).
- **TFLite source:** user already has the file in assets; only the code-side filename mismatch needs fixing.
- **Cloudflare JS challenges:** acknowledged unfixable in Phase 0; ship the headers+cookie hardening and clearly log when it fails. No WebView fallback yet.
- **Lenso fixture test:** include in Phase 0 (small, high value).
- **FaceSeek:** known-broken until user supplies captures from a real run with the new interceptor active.

---

## Out-of-scope notes for downstream phases

- Phase 1 will need a "Captures" screen in the dossier surface to browse/share captured HTTP files.
- Phase 0.1 (informal): once you run the debug APK with capture mode on and share the resulting FaceSeek files, rewrite `FaceSeekService.kt` + scraper against real data.
- A Cloudflare-bypass scraper (WebView with JS execution) is a fallback worth Phase 4-ish, only if SmartBg/CyberBg start consistently failing in production.

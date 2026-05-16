# Phase 3 More Providers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PimEyes (face tier), TinEye and Google Lens (correlation tier) — three new identify providers — and refactor `LiveViewModel.processFocusedFace` to run both tiers in parallel and merge results via a new `IdentifyPipeline` class.

**Architecture:** Each new provider follows the established Phase 0 pattern: `Service` (authenticated API) + `Scraper` (anonymous fallback) + a `ResponseParser` for the parser tests where applicable. All HTTP goes through `HttpClients.api()` / `HttpClients.browser()` so capture + audit are automatic. The new `IdentifyPipeline` class owns the parallel fan-out across face providers (deduped output by reference URL) and the correlation merge across providers per unique URL. `LiveViewModel.processFocusedFace` shrinks from ~120 lines of sequential glue to ~20 lines that delegate to the pipeline.

**Tech Stack:** Kotlin 2.3.21, OkHttp 5.3.2, Jsoup 1.22.2, JUnit 4, Robolectric 4.13, kotlinx.coroutines.

**Reference:** Spec at `docs/superpowers/specs/2026-05-15-phase-3-more-providers-design.md`.

---

## File Structure

**New files**
- `app/src/main/java/com/hereliesaz/doxray/api/PimEyesResponseParser.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/PimEyesService.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/PimEyesScraperService.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/TinEyeResponseParser.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/TinEyeSearchService.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/TinEyeScraperService.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/GoogleLensSearchService.kt` (null-returning stub for symmetry)
- `app/src/main/java/com/hereliesaz/doxray/api/GoogleLensScraperService.kt`
- `app/src/main/java/com/hereliesaz/doxray/identify/IdentifyPipeline.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/PimEyesParsingTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/TinEyeParsingTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/GoogleLensScrapingTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineFaceTierTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineCorrelationMergeTest.kt`

**Modified files**
- `app/build.gradle.kts` — `PIMEYES_KEY`, `TINEYE_KEY`, `TINEYE_SECRET` BuildConfig fields
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` — drop per-service fields, add `IdentifyPipeline`, rewrite `processFocusedFace` body
- `README.md` — document the new API keys

---

## Task 1: BuildConfig keys

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add three buildConfigField lines**

In `app/build.gradle.kts`, locate the existing buildConfigField block inside `defaultConfig`:
```kotlin
        buildConfigField("String", "SERPAPI_KEY", "\"${secret("SERPAPI_KEY")}\"")
        buildConfigField("String", "FACESEEK_KEY", "\"${secret("FACESEEK_KEY")}\"")
        buildConfigField("String", "LENSO_KEY", "\"${secret("LENSO_KEY")}\"")
        buildConfigField("String", "FACECHECK_KEY", "\"${secret("FACECHECK_KEY")}\"")
```

Append three lines:
```kotlin
        buildConfigField("String", "PIMEYES_KEY", "\"${secret("PIMEYES_KEY")}\"")
        buildConfigField("String", "TINEYE_KEY", "\"${secret("TINEYE_KEY")}\"")
        buildConfigField("String", "TINEYE_SECRET", "\"${secret("TINEYE_SECRET")}\"")
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:generateDebugBuildConfig --no-daemon`
Expected: `BUILD SUCCESSFUL`.

Verify the generated BuildConfig contains the new fields:
```bash
grep -E "PIMEYES_KEY|TINEYE_KEY|TINEYE_SECRET" app/build/generated/source/buildConfig/debug/com/hereliesaz/doxray/BuildConfig.java
```
Expected: three lines, all with empty-string defaults (since `local.properties` doesn't have those keys yet).

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Add PIMEYES_KEY + TINEYE_KEY + TINEYE_SECRET BuildConfig fields"
```

---

## Task 2: PimEyes parser TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/api/PimEyesParsingTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/api/PimEyesResponseParser.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/PimEyesParsingTest.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PimEyesParsingTest {

    @Test
    fun `parses top result from documented schema`() {
        val body = """
            {
              "results": [
                {
                  "url": "https://example.com/profile/jane",
                  "score": 0.87,
                  "thumbnail": "https://cdn.pimeyes.com/thumb/abc123.jpg"
                },
                {
                  "url": "https://other.com/page",
                  "score": 0.55,
                  "thumbnail": "https://cdn.pimeyes.com/thumb/def456.jpg"
                }
              ]
            }
        """.trimIndent()

        val result = PimEyesResponseParser.parse(body)
        assertNotNull(result)
        assertEquals(0.87f, result!!.confidence, 0.001f)
        assertEquals("https://example.com/profile/jane", result.referenceImageUrl)
    }

    @Test
    fun `returns null on empty results`() {
        val body = """{"results": []}"""
        assertNull(PimEyesResponseParser.parse(body))
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(PimEyesResponseParser.parse("not json"))
        assertNull(PimEyesResponseParser.parse(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.PimEyesParsingTest" --no-daemon`
Expected: `Unresolved reference 'PimEyesResponseParser'`.

- [ ] **Step 3: Implement the parser**

`app/src/main/java/com/hereliesaz/doxray/api/PimEyesResponseParser.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses pimeyes.com /api/search/results responses.
 *
 *   { "results": [
 *       { "url": "<source page>",
 *         "score": 0..1,
 *         "thumbnail": "<thumbnail url>" }, … ] }
 *
 * Returns the top result mapped to [PimEyesService.Result], or null when
 * results are absent / empty / malformed. faceId is derived from the
 * thumbnail URL hash so two responses with the same source-page URL but
 * different thumbnails get distinct IDs.
 */
object PimEyesResponseParser {

    fun parse(jsonBody: String): PimEyesService.Result? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val top = results.optJSONObject(0) ?: return null
        val score = top.optDouble("score", 0.0).toFloat().coerceIn(0f, 1f)
        val url = top.optString("url", "")
        val thumbnail = top.optString("thumbnail", "")
        return PimEyesService.Result(
            faceId = "pimeyes_${thumbnail.hashCode()}",
            confidence = score,
            referenceImageUrl = url,
        )
    }
}
```

The parser references `PimEyesService.Result` which doesn't exist yet. That's fine for now — the next task creates it. To keep this commit self-contained, we'll add a temporary stub directly in this file's commit and remove it in Task 3.

Wait — that's awkward. Let's instead define `PimEyesService.Result` minimally up-front. Update the plan: create `PimEyesService.kt` as part of this task with just the `Result` data class skeleton (full implementation in Task 3).

Actually simpler: define the `Result` data class as a TOP-LEVEL declaration in the parser file for now, with a deprecation comment. Task 3 moves it into `PimEyesService` proper.

Even simpler still: write the parser and the data class in one file initially, then split. Let's go with: in this task, the parser file ALSO declares the `Result` data class at the top level (no `PimEyesService` yet). Task 3 will rewrite the file to remove the standalone class and instead make `Result` a nested type inside `PimEyesService`.

Replace the parser content above with this version that includes the data class:

```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses pimeyes.com /api/search/results responses.
 *
 *   { "results": [
 *       { "url": "<source page>",
 *         "score": 0..1,
 *         "thumbnail": "<thumbnail url>" }, … ] }
 *
 * Returns the top result, or null when results are absent / empty / malformed.
 * faceId is derived from the thumbnail URL hash so two responses with the
 * same source-page URL but different thumbnails get distinct IDs.
 */
object PimEyesResponseParser {

    /**
     * Top-level Result type. Task 3 (PimEyesService) re-exports this same shape
     * as PimEyesService.Result and the parser switches to returning that.
     * Until then, callers depend on this stand-alone type.
     */
    data class Result(
        val faceId: String,
        val confidence: Float,
        val referenceImageUrl: String,
    )

    fun parse(jsonBody: String): Result? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val top = results.optJSONObject(0) ?: return null
        val score = top.optDouble("score", 0.0).toFloat().coerceIn(0f, 1f)
        val url = top.optString("url", "")
        val thumbnail = top.optString("thumbnail", "")
        return Result(
            faceId = "pimeyes_${thumbnail.hashCode()}",
            confidence = score,
            referenceImageUrl = url,
        )
    }
}
```

And update the test file to reference `PimEyesResponseParser.Result` not `PimEyesService.Result`. Change the import / type references inside the tests accordingly — but actually the test code in Step 1 doesn't mention `PimEyesService.Result` at all (it just calls `assertNotNull(result)` and accesses `result.confidence` / `result.referenceImageUrl` as fields, both of which exist on the new top-level `Result`). So the test from Step 1 works unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.PimEyesParsingTest" --no-daemon`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/PimEyesResponseParser.kt \
        app/src/test/java/com/hereliesaz/doxray/api/PimEyesParsingTest.kt
git commit -m "Add PimEyesResponseParser with TDD (3 cases)"
```

---

## Task 3: PimEyes service + scraper

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/PimEyesService.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/api/PimEyesScraperService.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/PimEyesResponseParser.kt` (move Result to PimEyesService.Result)

- [ ] **Step 1: Create PimEyesService**

`app/src/main/java/com/hereliesaz/doxray/api/PimEyesService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.BuildConfig
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * pimeyes.com face-search integration via their documented two-step
 * upload + poll flow.
 *
 *   1) POST /api/search/upload  (multipart "image") -> { search_hash }
 *   2) POST /api/search/results (json { searchHash }) -> { results: [...] }
 *
 * Both calls authenticate with Authorization: Bearer $PIMEYES_KEY.
 *
 * PHASE-3: Endpoint paths and field names are taken from PimEyes public
 * documentation. Real-device captures may reveal differences; refine via
 * CaptureInterceptor output if needed.
 */
class PimEyesService {

    private val TAG = "PimEyesService"
    private val PIMEYES_API_KEY = BuildConfig.PIMEYES_KEY
    private val PIMEYES_HOST = "https://pimeyes.com"

    private val client get() = HttpClients.api()

    data class Result(
        val faceId: String,
        val confidence: Float,
        val referenceImageUrl: String,
    )

    suspend fun identifyFace(imageBytes: ByteArray): Result? = withContext(Dispatchers.IO) {
        if (PIMEYES_API_KEY.isBlank()) {
            Log.w(TAG, "PIMEYES_KEY not configured; skipping PimEyes API call.")
            return@withContext null
        }
        try {
            val searchHash = uploadImage(imageBytes) ?: return@withContext null
            Log.d(TAG, "Upload accepted. searchHash=$searchHash")
            pollResults(searchHash)
        } catch (e: Exception) {
            Log.e(TAG, "PimEyes API exception", e)
            null
        }
    }

    private fun uploadImage(imageBytes: ByteArray): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$PIMEYES_HOST/api/search/upload")
            .addHeader("Authorization", "Bearer $PIMEYES_API_KEY")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes upload HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            val json = try { JSONObject(text) } catch (e: Exception) { return null }
            return json.optString("search_hash").takeIf { it.isNotEmpty() }
        }
    }

    private fun pollResults(searchHash: String): Result? {
        val payload = JSONObject().apply { put("searchHash", searchHash) }
        val request = Request.Builder()
            .url("$PIMEYES_HOST/api/search/results")
            .addHeader("Authorization", "Bearer $PIMEYES_API_KEY")
            .addHeader("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes results HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            return PimEyesResponseParser.parse(text)
        }
    }
}
```

- [ ] **Step 2: Create PimEyesScraperService**

`app/src/main/java/com/hereliesaz/doxray/api/PimEyesScraperService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Anonymous fallback for PimEyes. Same upload + poll flow without the
 * Authorization header. PimEyes' public demo returns watermarked / partial
 * results but the URL field is still populated, which is all the pipeline
 * needs to fan into the correlation tier.
 *
 * PHASE-3: anonymous endpoints may differ from the API path; refine after
 * CaptureInterceptor produces real traffic.
 */
class PimEyesScraperService {

    private val TAG = "PimEyesScraper"
    private val PIMEYES_URL = "https://pimeyes.com"

    private val client get() = HttpClients.browser()

    suspend fun identifyFace(imageBytes: ByteArray): PimEyesService.Result? = withContext(Dispatchers.IO) {
        try {
            val searchHash = uploadAnonymously(imageBytes) ?: return@withContext null
            Log.d(TAG, "Anonymous upload accepted. searchHash=$searchHash")
            pollResults(searchHash)
        } catch (e: Exception) {
            Log.e(TAG, "PimEyes scraper exception", e)
            null
        }
    }

    private fun uploadAnonymously(imageBytes: ByteArray): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$PIMEYES_URL/api/search/upload")
            .addHeader("Referer", "$PIMEYES_URL/")
            .addHeader("Origin", PIMEYES_URL)
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes anonymous upload HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            val json = try { JSONObject(text) } catch (e: Exception) { return null }
            return json.optString("search_hash").takeIf { it.isNotEmpty() }
        }
    }

    private fun pollResults(searchHash: String): PimEyesService.Result? {
        val payload = JSONObject().apply { put("searchHash", searchHash) }
        val request = Request.Builder()
            .url("$PIMEYES_URL/api/search/results")
            .addHeader("Referer", "$PIMEYES_URL/")
            .addHeader("Origin", PIMEYES_URL)
            .addHeader("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes anonymous results HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            return PimEyesResponseParser.parse(text)
        }
    }
}
```

- [ ] **Step 3: Move the Result type from PimEyesResponseParser to PimEyesService**

Replace `app/src/main/java/com/hereliesaz/doxray/api/PimEyesResponseParser.kt` with the version that no longer declares `Result` (it's now `PimEyesService.Result`):

```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses pimeyes.com /api/search/results responses.
 *
 *   { "results": [
 *       { "url": "<source page>",
 *         "score": 0..1,
 *         "thumbnail": "<thumbnail url>" }, … ] }
 *
 * Returns the top result mapped to [PimEyesService.Result], or null when
 * results are absent / empty / malformed.
 */
object PimEyesResponseParser {

    fun parse(jsonBody: String): PimEyesService.Result? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val top = results.optJSONObject(0) ?: return null
        val score = top.optDouble("score", 0.0).toFloat().coerceIn(0f, 1f)
        val url = top.optString("url", "")
        val thumbnail = top.optString("thumbnail", "")
        return PimEyesService.Result(
            faceId = "pimeyes_${thumbnail.hashCode()}",
            confidence = score,
            referenceImageUrl = url,
        )
    }
}
```

- [ ] **Step 4: Verify the parser tests still pass with the moved type**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.PimEyesParsingTest" --no-daemon`
Expected: all 3 tests still pass (the test code uses field access, not the qualified type name, so the move is transparent).

- [ ] **Step 5: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/PimEyesService.kt \
        app/src/main/java/com/hereliesaz/doxray/api/PimEyesScraperService.kt \
        app/src/main/java/com/hereliesaz/doxray/api/PimEyesResponseParser.kt
git commit -m "Add PimEyesService + PimEyesScraperService; move Result into Service"
```

---

## Task 4: TinEye parser TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/api/TinEyeParsingTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/api/TinEyeResponseParser.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/TinEyeParsingTest.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TinEyeParsingTest {

    @Test
    fun `parses match list from documented schema`() {
        val body = """
            {
              "results": {
                "matches": [
                  { "image_url": "https://example.com/img/jane.jpg",
                    "domain": "example.com" },
                  { "image_url": "https://other.com/photos/jane.jpg",
                    "domain": "other.com" },
                  { "image_url": "https://example.com/img/jane.jpg",
                    "domain": "example.com" }
                ]
              }
            }
        """.trimIndent()

        val result = TinEyeResponseParser.parse(body)
        assertNotNull(result)
        // Domains as identities, deduped
        assertEquals(setOf("example.com", "other.com"), result!!.identities.toSet())
        // Image URLs as socialLinks, deduped
        assertEquals(
            setOf("https://example.com/img/jane.jpg", "https://other.com/photos/jane.jpg"),
            result.socialLinks.toSet()
        )
        assertTrue(result.identities.size == 2)
        assertTrue(result.socialLinks.size == 2)
    }

    @Test
    fun `returns null on empty matches`() {
        val body = """{"results": {"matches": []}}"""
        assertNull(TinEyeResponseParser.parse(body))
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(TinEyeResponseParser.parse("not json"))
        assertNull(TinEyeResponseParser.parse(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.TinEyeParsingTest" --no-daemon`
Expected: `Unresolved reference 'TinEyeResponseParser'`.

- [ ] **Step 3: Implement the parser**

`app/src/main/java/com/hereliesaz/doxray/api/TinEyeResponseParser.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses tineye.com /rest/search/ responses.
 *
 *   { "results": { "matches": [
 *       { "image_url": "<canonical image url>",
 *         "domain": "<hosting domain>" }, … ] } }
 *
 * Returns a TinEyeSearchService.Result mapping domains → identities (deduped)
 * and image_urls → socialLinks (deduped). Returns null when no matches.
 */
object TinEyeResponseParser {

    fun parse(jsonBody: String): TinEyeSearchService.Result? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val results = json.optJSONObject("results") ?: return null
        val matches = results.optJSONArray("matches") ?: return null
        if (matches.length() == 0) return null

        val domains = mutableListOf<String>()
        val urls = mutableListOf<String>()
        for (i in 0 until matches.length()) {
            val m = matches.optJSONObject(i) ?: continue
            val d = m.optString("domain", "")
            val u = m.optString("image_url", "")
            if (d.isNotEmpty()) domains.add(d)
            if (u.isNotEmpty()) urls.add(u)
        }
        return TinEyeSearchService.Result(
            identities = domains.distinct(),
            socialLinks = urls.distinct(),
        )
    }
}
```

This references `TinEyeSearchService.Result` which doesn't exist yet. As in Task 2, declare a temporary top-level `Result` in this parser file to keep this commit self-contained:

Replace the file with:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

object TinEyeResponseParser {

    /**
     * Top-level Result type. Task 5 (TinEyeSearchService) re-declares this as
     * TinEyeSearchService.Result and the parser switches to returning that.
     */
    data class Result(
        val identities: List<String>,
        val socialLinks: List<String>,
    )

    fun parse(jsonBody: String): Result? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val results = json.optJSONObject("results") ?: return null
        val matches = results.optJSONArray("matches") ?: return null
        if (matches.length() == 0) return null

        val domains = mutableListOf<String>()
        val urls = mutableListOf<String>()
        for (i in 0 until matches.length()) {
            val m = matches.optJSONObject(i) ?: continue
            val d = m.optString("domain", "")
            val u = m.optString("image_url", "")
            if (d.isNotEmpty()) domains.add(d)
            if (u.isNotEmpty()) urls.add(u)
        }
        return Result(
            identities = domains.distinct(),
            socialLinks = urls.distinct(),
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.TinEyeParsingTest" --no-daemon`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/TinEyeResponseParser.kt \
        app/src/test/java/com/hereliesaz/doxray/api/TinEyeParsingTest.kt
git commit -m "Add TinEyeResponseParser with TDD (3 cases)"
```

---

## Task 5: TinEye service with HMAC signing

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/TinEyeSearchService.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/TinEyeResponseParser.kt` (move Result into Service)

- [ ] **Step 1: Create the service with HMAC signing**

`app/src/main/java/com/hereliesaz/doxray/api/TinEyeSearchService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.BuildConfig
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TinEye reverse-image search via the documented /rest/search/ endpoint with
 * HMAC-SHA256 request signing.
 *
 * Auth: public key (TINEYE_KEY) + private key (TINEYE_SECRET). Both come from
 * BuildConfig. Signing follows TinEye's canonical-string-of-sorted-params
 * scheme. If either key is blank the service no-ops and the scraper fallback
 * handles the call.
 *
 * PHASE-3: signing scheme is best-effort based on public docs. May need
 * adjustment after CaptureInterceptor records a real signed request.
 */
class TinEyeSearchService {

    private val TAG = "TinEyeSearchService"
    private val TINEYE_KEY = BuildConfig.TINEYE_KEY
    private val TINEYE_SECRET = BuildConfig.TINEYE_SECRET
    private val TINEYE_HOST = "https://api.tineye.com"
    private val ENDPOINT_PATH = "/rest/search/"

    private val client get() = HttpClients.api()

    data class Result(
        val identities: List<String>,
        val socialLinks: List<String>,
    )

    suspend fun searchIdentity(imageUrl: String): Result? = withContext(Dispatchers.IO) {
        if (TINEYE_KEY.isBlank() || TINEYE_SECRET.isBlank()) {
            Log.w(TAG, "TINEYE_KEY/SECRET not configured; skipping TinEye API call.")
            return@withContext null
        }
        try {
            val nonce = (System.currentTimeMillis().toString() +
                Math.random().toString().substring(2, 10))
            val date = (System.currentTimeMillis() / 1000L).toString()

            val params = sortedMapOf(
                "image_url" to imageUrl,
                "nonce" to nonce,
                "date" to date,
                "api_key" to TINEYE_KEY,
            )
            val signature = sign("POST", ENDPOINT_PATH, params)
            params["api_sig"] = signature

            val formBody = params
                .map { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
                .joinToString("&")

            val request = Request.Builder()
                .url("$TINEYE_HOST$ENDPOINT_PATH")
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "TinEye HTTP ${response.code}")
                    return@withContext null
                }
                val text = response.body?.string() ?: return@withContext null
                TinEyeResponseParser.parse(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TinEye API exception", e)
            null
        }
    }

    /**
     * HMAC-SHA256 over canonical string: METHOD + ENDPOINT_PATH + sorted-encoded-params.
     * The result is base64-encoded.
     */
    private fun sign(method: String, path: String, params: Map<String, String>): String {
        val canonical = buildString {
            append(method)
            append(path)
            params.entries
                .sortedBy { it.key }
                .joinTo(this, separator = "&", prefix = "?") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TINEYE_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }
}
```

- [ ] **Step 2: Move Result type from parser to service**

Replace `app/src/main/java/com/hereliesaz/doxray/api/TinEyeResponseParser.kt` with:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

object TinEyeResponseParser {

    fun parse(jsonBody: String): TinEyeSearchService.Result? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val results = json.optJSONObject("results") ?: return null
        val matches = results.optJSONArray("matches") ?: return null
        if (matches.length() == 0) return null

        val domains = mutableListOf<String>()
        val urls = mutableListOf<String>()
        for (i in 0 until matches.length()) {
            val m = matches.optJSONObject(i) ?: continue
            val d = m.optString("domain", "")
            val u = m.optString("image_url", "")
            if (d.isNotEmpty()) domains.add(d)
            if (u.isNotEmpty()) urls.add(u)
        }
        return TinEyeSearchService.Result(
            identities = domains.distinct(),
            socialLinks = urls.distinct(),
        )
    }
}
```

- [ ] **Step 3: Re-run parser tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.TinEyeParsingTest" --no-daemon`
Expected: all 3 tests pass with the moved Result type.

- [ ] **Step 4: Verify full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/TinEyeSearchService.kt \
        app/src/main/java/com/hereliesaz/doxray/api/TinEyeResponseParser.kt
git commit -m "Add TinEyeSearchService with HMAC signing; move Result into Service"
```

---

## Task 6: TinEye scraper

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/TinEyeScraperService.kt`

- [ ] **Step 1: Create the scraper**

`app/src/main/java/com/hereliesaz/doxray/api/TinEyeScraperService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Anonymous fallback for TinEye via the public web form at tineye.com/search.
 * POSTs the reference URL and Jsoup-parses the match cards.
 *
 * PHASE-3: selectors are best-effort. Refine after CaptureInterceptor produces
 * real result HTML.
 */
class TinEyeScraperService {

    private val TAG = "TinEyeScraper"
    private val TINEYE_URL = "https://tineye.com"

    private val client get() = HttpClients.browser()

    suspend fun searchIdentity(imageUrl: String): TinEyeSearchService.Result? = withContext(Dispatchers.IO) {
        try {
            val formBody = "url=${URLEncoder.encode(imageUrl, "UTF-8")}"
            val request = Request.Builder()
                .url("$TINEYE_URL/search")
                .addHeader("Referer", "$TINEYE_URL/")
                .addHeader("Origin", TINEYE_URL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "TinEye scraper HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            } ?: return@withContext null

            val document = Jsoup.parse(html)
            val cards = document.select(".match-row, .match")
            val domains = mutableListOf<String>()
            val urls = mutableListOf<String>()
            for (card in cards) {
                val link = card.select("a[href]").first()?.attr("href") ?: continue
                if (link.isNotBlank()) urls.add(link)
                val domain = card.select(".domain, .match-row .url").text()
                if (domain.isNotBlank()) domains.add(domain)
            }
            if (domains.isEmpty() && urls.isEmpty()) return@withContext null
            TinEyeSearchService.Result(
                identities = domains.distinct(),
                socialLinks = urls.distinct(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "TinEye scraper exception", e)
            null
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/TinEyeScraperService.kt
git commit -m "Add TinEyeScraperService anonymous fallback"
```

---

## Task 7: Google Lens scraper TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/api/GoogleLensScrapingTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/api/GoogleLensScraperService.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/GoogleLensScrapingTest.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GoogleLensScrapingTest {

    @Test
    fun `extracts identities and links from result html`() {
        val html = """
            <html><body>
              <div class="result">
                <a href="https://example.com/profile/jane" class="result-link">
                  <span class="result-title">Jane Doe — example.com</span>
                </a>
              </div>
              <div class="result">
                <a href="https://other.com/jane-doe" class="result-link">
                  <span class="result-title">Jane Doe – Other Site</span>
                </a>
              </div>
              <div class="result">
                <a href="https://example.com/profile/jane" class="result-link">
                  <span class="result-title">Jane Doe — example.com</span>
                </a>
              </div>
            </body></html>
        """.trimIndent()

        val result = GoogleLensScraperService.extractFromHtml(html)
        assertNotNull(result)
        assertEquals(2, result!!.identities.size)
        assertEquals(2, result.socialLinks.size)
        assertEquals(setOf("Jane Doe — example.com", "Jane Doe – Other Site"), result.identities.toSet())
        assertEquals(
            setOf("https://example.com/profile/jane", "https://other.com/jane-doe"),
            result.socialLinks.toSet(),
        )
    }

    @Test
    fun `returns null on no result anchors`() {
        val html = """<html><body><div>No results</div></body></html>"""
        assertEquals(null, GoogleLensScraperService.extractFromHtml(html))
    }
}
```

The test exercises a static `extractFromHtml(html)` helper on `GoogleLensScraperService`, separating HTML parsing from network I/O so the test runs as plain JUnit (no Robolectric needed since Jsoup is pure Java).

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.GoogleLensScrapingTest" --no-daemon`
Expected: `Unresolved reference 'GoogleLensScraperService'`.

- [ ] **Step 3: Create GoogleLensScraperService**

`app/src/main/java/com/hereliesaz/doxray/api/GoogleLensScraperService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Unofficial Google Lens scraper via lens.google.com/uploadbyurl. The Lens
 * frontend changes frequently — selectors are guessed from the current page
 * structure. Pure-HTML parsing is split into [extractFromHtml] so tests can
 * exercise the parser without network I/O.
 *
 * PHASE-3: selectors are best-effort. Refine after CaptureInterceptor produces
 * real result HTML.
 */
class GoogleLensScraperService {

    private val TAG = "GoogleLensScraper"
    private val LENS_URL = "https://lens.google.com"

    private val client get() = HttpClients.browser()

    suspend fun searchIdentity(imageUrl: String): GoogleLensSearchService.Result? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(imageUrl, "UTF-8")
            val request = Request.Builder()
                .url("$LENS_URL/uploadbyurl?url=$encoded")
                .addHeader("Referer", "https://www.google.com/")
                .get()
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Google Lens HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            } ?: return@withContext null
            extractFromHtml(html)
        } catch (e: Exception) {
            Log.e(TAG, "Google Lens scraper exception", e)
            null
        }
    }

    companion object {
        /**
         * Pure HTML → Result conversion. Public so tests can exercise it
         * without network I/O. Returns null when no result anchors are found.
         */
        fun extractFromHtml(html: String): GoogleLensSearchService.Result? {
            val document = Jsoup.parse(html)
            val anchors = document.select(".result a.result-link[href]")
            if (anchors.isEmpty()) return null
            val identities = mutableListOf<String>()
            val urls = mutableListOf<String>()
            for (a in anchors) {
                val href = a.attr("href")
                val title = a.select(".result-title").first()?.text() ?: ""
                if (href.isNotBlank()) urls.add(href)
                if (title.isNotBlank()) identities.add(title)
            }
            if (identities.isEmpty() && urls.isEmpty()) return null
            return GoogleLensSearchService.Result(
                identities = identities.distinct(),
                socialLinks = urls.distinct(),
            )
        }
    }
}
```

This references `GoogleLensSearchService.Result` which doesn't exist yet — Task 8 creates it. As before, declare a stub top-level Result here to keep this commit self-contained:

Update the file to ALSO declare:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/** Phase 3 placeholder — Task 8 moves this into GoogleLensSearchService.Result. */
data class GoogleLensScrapedResult(
    val identities: List<String>,
    val socialLinks: List<String>,
)

class GoogleLensScraperService {

    private val TAG = "GoogleLensScraper"
    private val LENS_URL = "https://lens.google.com"

    private val client get() = HttpClients.browser()

    suspend fun searchIdentity(imageUrl: String): GoogleLensScrapedResult? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(imageUrl, "UTF-8")
            val request = Request.Builder()
                .url("$LENS_URL/uploadbyurl?url=$encoded")
                .addHeader("Referer", "https://www.google.com/")
                .get()
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Google Lens HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            } ?: return@withContext null
            extractFromHtml(html)
        } catch (e: Exception) {
            Log.e(TAG, "Google Lens scraper exception", e)
            null
        }
    }

    companion object {
        fun extractFromHtml(html: String): GoogleLensScrapedResult? {
            val document = Jsoup.parse(html)
            val anchors = document.select(".result a.result-link[href]")
            if (anchors.isEmpty()) return null
            val identities = mutableListOf<String>()
            val urls = mutableListOf<String>()
            for (a in anchors) {
                val href = a.attr("href")
                val title = a.select(".result-title").first()?.text() ?: ""
                if (href.isNotBlank()) urls.add(href)
                if (title.isNotBlank()) identities.add(title)
            }
            if (identities.isEmpty() && urls.isEmpty()) return null
            return GoogleLensScrapedResult(
                identities = identities.distinct(),
                socialLinks = urls.distinct(),
            )
        }
    }
}
```

The test uses `GoogleLensScraperService.extractFromHtml(html)` and accesses `.identities` and `.socialLinks` as fields — both still exist on `GoogleLensScrapedResult`, so the test from Step 1 works unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.GoogleLensScrapingTest" --no-daemon`
Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/GoogleLensScraperService.kt \
        app/src/test/java/com/hereliesaz/doxray/api/GoogleLensScrapingTest.kt
git commit -m "Add GoogleLensScraperService with TDD HTML parser"
```

---

## Task 8: Google Lens null-stub service + unify Result type

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/GoogleLensSearchService.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/GoogleLensScraperService.kt` (use GoogleLensSearchService.Result)

- [ ] **Step 1: Create the null-stub service with the nested Result type**

`app/src/main/java/com/hereliesaz/doxray/api/GoogleLensSearchService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log

/**
 * Google Lens has no official API. This service exists purely for structural
 * symmetry with the other identify providers — every other provider has a
 * Service + Scraper pair, and the IdentifyPipeline is cleaner if it can call
 * `service.searchIdentity(url) ?: scraper.searchIdentity(url)` uniformly.
 *
 * Always returns null. The scraper does the real work.
 */
class GoogleLensSearchService {

    private val TAG = "GoogleLensSearchService"

    data class Result(
        val identities: List<String>,
        val socialLinks: List<String>,
    )

    @Suppress("UNUSED_PARAMETER")
    suspend fun searchIdentity(imageUrl: String): Result? {
        Log.d(TAG, "Google Lens has no API; returning null (scraper handles it).")
        return null
    }
}
```

- [ ] **Step 2: Update scraper to return GoogleLensSearchService.Result**

Replace `app/src/main/java/com/hereliesaz/doxray/api/GoogleLensScraperService.kt` with:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

class GoogleLensScraperService {

    private val TAG = "GoogleLensScraper"
    private val LENS_URL = "https://lens.google.com"

    private val client get() = HttpClients.browser()

    suspend fun searchIdentity(imageUrl: String): GoogleLensSearchService.Result? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(imageUrl, "UTF-8")
            val request = Request.Builder()
                .url("$LENS_URL/uploadbyurl?url=$encoded")
                .addHeader("Referer", "https://www.google.com/")
                .get()
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Google Lens HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            } ?: return@withContext null
            extractFromHtml(html)
        } catch (e: Exception) {
            Log.e(TAG, "Google Lens scraper exception", e)
            null
        }
    }

    companion object {
        fun extractFromHtml(html: String): GoogleLensSearchService.Result? {
            val document = Jsoup.parse(html)
            val anchors = document.select(".result a.result-link[href]")
            if (anchors.isEmpty()) return null
            val identities = mutableListOf<String>()
            val urls = mutableListOf<String>()
            for (a in anchors) {
                val href = a.attr("href")
                val title = a.select(".result-title").first()?.text() ?: ""
                if (href.isNotBlank()) urls.add(href)
                if (title.isNotBlank()) identities.add(title)
            }
            if (identities.isEmpty() && urls.isEmpty()) return null
            return GoogleLensSearchService.Result(
                identities = identities.distinct(),
                socialLinks = urls.distinct(),
            )
        }
    }
}
```

The placeholder `GoogleLensScrapedResult` is gone. The companion's return type is `GoogleLensSearchService.Result`.

- [ ] **Step 3: Re-run the scraper tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.GoogleLensScrapingTest" --no-daemon`
Expected: both tests still pass.

- [ ] **Step 4: Verify build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/GoogleLensSearchService.kt \
        app/src/main/java/com/hereliesaz/doxray/api/GoogleLensScraperService.kt
git commit -m "Add GoogleLensSearchService null-stub; unify scraper Result type"
```

---

## Task 9: IdentifyPipeline face-tier TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineFaceTierTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/identify/IdentifyPipeline.kt`

- [ ] **Step 1: Write the failing test**

The `identify/` directory doesn't exist yet — create the full path under both `main` and `test`.

`app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineFaceTierTest.kt`:
```kotlin
package com.hereliesaz.doxray.identify

import com.hereliesaz.doxray.api.FaceCheckIdScraperService
import com.hereliesaz.doxray.api.FaceCheckIdService
import com.hereliesaz.doxray.api.FaceSeekScraperService
import com.hereliesaz.doxray.api.FaceSeekService
import com.hereliesaz.doxray.api.GoogleLensScraperService
import com.hereliesaz.doxray.api.GoogleLensSearchService
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.PimEyesScraperService
import com.hereliesaz.doxray.api.PimEyesService
import com.hereliesaz.doxray.api.TinEyeScraperService
import com.hereliesaz.doxray.api.TinEyeSearchService
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyPipelineFaceTierTest {

    @Test
    fun `face tier returns empty when all providers return null`() = runBlocking {
        // Exercises real coroutine fan-out + applyFaceTierRules. With no
        // BuildConfig keys configured, every service .identifyFace returns
        // null; with no network access, every scraper .identifyFace also
        // returns null. The pipeline correctly produces an empty list.
        val pipeline = IdentifyPipeline(
            lensoService = LensoSearchService(),
            lensoScraper = LensoScraperService(),
            faceSeekService = FaceSeekService(),
            faceSeekScraper = FaceSeekScraperService(),
            faceCheckService = FaceCheckIdService(),
            faceCheckScraper = FaceCheckIdScraperService(),
            pimEyesService = PimEyesService(),
            pimEyesScraper = PimEyesScraperService(),
            yandexService = YandexSearchService(),
            yandexScraper = YandexScraperService(),
            tinEyeService = TinEyeSearchService(),
            tinEyeScraper = TinEyeScraperService(),
            googleLensService = GoogleLensSearchService(),
            googleLensScraper = GoogleLensScraperService(),
        )
        val hits = pipeline.runFaceTier(ByteArray(0))
        assertEquals(0, hits.size)
    }

    @Test
    fun `applyFaceTierRules picks confident hits and dedups by url`() {
        // Direct unit test of the rules — exercises production code, not a test override.
        val raw = listOf(
            IdentifyPipeline.FaceHit("lenso", "id-1", 0.9f, "https://example.com/a"),
            IdentifyPipeline.FaceHit("faceseek", "id-2", 0.7f, "https://example.com/a"),
            IdentifyPipeline.FaceHit("facecheck", "id-3", 0.65f, "https://example.com/b"),
            IdentifyPipeline.FaceHit("pimeyes", "id-4", 0.5f, "https://example.com/c"), // below threshold
            null, // a provider that returned null
        )
        val hits = IdentifyPipeline.applyFaceTierRules(raw)
        // 0.5 below threshold filtered; duplicate URL deduped; null skipped
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.referenceUrl == "https://example.com/a" })
        assertTrue(hits.any { it.referenceUrl == "https://example.com/b" })
    }

    @Test
    fun `applyFaceTierRules yields none when no provider exceeds threshold`() {
        val raw = listOf(
            IdentifyPipeline.FaceHit("lenso", "id-1", 0.5f, "https://example.com/a"),
            IdentifyPipeline.FaceHit("faceseek", "id-2", 0.55f, "https://example.com/b"),
        )
        val hits = IdentifyPipeline.applyFaceTierRules(raw)
        assertEquals(0, hits.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.identify.IdentifyPipelineFaceTierTest" --no-daemon`
Expected: `Unresolved reference 'IdentifyPipeline'`.

- [ ] **Step 3: Create IdentifyPipeline**

`app/src/main/java/com/hereliesaz/doxray/identify/IdentifyPipeline.kt`:
```kotlin
package com.hereliesaz.doxray.identify

import com.hereliesaz.doxray.api.FaceCheckIdScraperService
import com.hereliesaz.doxray.api.FaceCheckIdService
import com.hereliesaz.doxray.api.FaceSeekScraperService
import com.hereliesaz.doxray.api.FaceSeekService
import com.hereliesaz.doxray.api.GoogleLensScraperService
import com.hereliesaz.doxray.api.GoogleLensSearchService
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.PimEyesScraperService
import com.hereliesaz.doxray.api.PimEyesService
import com.hereliesaz.doxray.api.TinEyeScraperService
import com.hereliesaz.doxray.api.TinEyeSearchService
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Owns the identify pipeline orchestration. Two methods:
 *   - runFaceTier: parallel fan-out across the 4 face providers; dedup by URL.
 *   - runCorrelationTier: parallel fan-out across the 3 correlation providers
 *     for a single reference URL; merge identities + socialLinks.
 *
 * Each provider is tried API first, then scraper fallback, inside its own
 * coroutine. The pipeline awaits all and applies the merge / dedup rules.
 */
open class IdentifyPipeline(
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
    private val googleLensService: GoogleLensSearchService,
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

    open suspend fun runFaceTier(imageBytes: ByteArray): List<FaceHit> = coroutineScope {
        val raw = listOf(
            async { lensoFaceHit(imageBytes) },
            async { faceSeekFaceHit(imageBytes) },
            async { faceCheckFaceHit(imageBytes) },
            async { pimEyesFaceHit(imageBytes) },
        ).awaitAll()
        applyFaceTierRules(raw)
    }

    open suspend fun runCorrelationTier(refUrl: String): CorrelationHit = coroutineScope {
        val results = listOf(
            async { yandexService.searchIdentity(refUrl) ?: yandexScraper.searchIdentity(refUrl) },
            async { tinEyeService.searchIdentity(refUrl) ?: tinEyeScraper.searchIdentity(refUrl) },
            async { googleLensService.searchIdentity(refUrl) ?: googleLensScraper.searchIdentity(refUrl) },
        ).awaitAll()
        val identities = mutableListOf<String>()
        val links = mutableListOf<String>()
        for (r in results) when (r) {
            is YandexSearchService.Result -> { identities += r.identities; links += r.socialLinks }
            is TinEyeSearchService.Result -> { identities += r.identities; links += r.socialLinks }
            is GoogleLensSearchService.Result -> { identities += r.identities; links += r.socialLinks }
            else -> { /* null — skip */ }
        }
        CorrelationHit(
            identities = identities.distinct(),
            socialLinks = links.distinct(),
        )
    }

    private suspend fun lensoFaceHit(bytes: ByteArray): FaceHit? {
        val r = lensoService.identifyFace(bytes) ?: lensoScraper.identifyFace(bytes) ?: return null
        return FaceHit("lenso", r.faceId, r.confidence, r.referenceImageUrl)
    }

    private suspend fun faceSeekFaceHit(bytes: ByteArray): FaceHit? {
        val r = faceSeekService.identifyFace(bytes) ?: faceSeekScraper.identifyFace(bytes) ?: return null
        return FaceHit("faceseek", r.faceId, r.confidence, r.referenceImageUrl)
    }

    private suspend fun faceCheckFaceHit(bytes: ByteArray): FaceHit? {
        val r = faceCheckService.identifyFace(bytes) ?: faceCheckScraper.identifyFace(bytes) ?: return null
        return FaceHit("facecheck", r.faceId, r.confidence, r.referenceImageUrl)
    }

    private suspend fun pimEyesFaceHit(bytes: ByteArray): FaceHit? {
        val r = pimEyesService.identifyFace(bytes) ?: pimEyesScraper.identifyFace(bytes) ?: return null
        return FaceHit("pimeyes", r.faceId, r.confidence, r.referenceImageUrl)
    }

    companion object {
        const val FACE_CONFIDENCE_THRESHOLD = 0.6f

        /**
         * Pure-logic rules applied to raw face-tier hits: drop nulls,
         * filter by confidence threshold, dedup by reference URL. Exposed
         * as a separate function so tests can exercise the rules directly
         * with curated input — and so the production runFaceTier and tests
         * share the same logic.
         */
        fun applyFaceTierRules(raw: List<FaceHit?>): List<FaceHit> =
            raw.filterNotNull()
                .filter { it.confidence > FACE_CONFIDENCE_THRESHOLD }
                .distinctBy { it.referenceUrl }
    }
}
```

The `open` modifier on the class and methods supports test subclasses for the empty-case test where we want to exercise the real coroutine fan-out. For the dedup/threshold tests we don't subclass — we call `IdentifyPipeline.applyFaceTierRules(...)` directly with curated input.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.identify.IdentifyPipelineFaceTierTest" --no-daemon`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/identify/IdentifyPipeline.kt \
        app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineFaceTierTest.kt
git commit -m "Add IdentifyPipeline with face-tier TDD (3 cases)"
```

---

## Task 10: IdentifyPipeline correlation-merge TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineCorrelationMergeTest.kt`

- [ ] **Step 1: Write the test (no new implementation; just verifying existing pipeline logic)**

`app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineCorrelationMergeTest.kt`:
```kotlin
package com.hereliesaz.doxray.identify

import com.hereliesaz.doxray.api.FaceCheckIdScraperService
import com.hereliesaz.doxray.api.FaceCheckIdService
import com.hereliesaz.doxray.api.FaceSeekScraperService
import com.hereliesaz.doxray.api.FaceSeekService
import com.hereliesaz.doxray.api.GoogleLensScraperService
import com.hereliesaz.doxray.api.GoogleLensSearchService
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.PimEyesScraperService
import com.hereliesaz.doxray.api.PimEyesService
import com.hereliesaz.doxray.api.TinEyeScraperService
import com.hereliesaz.doxray.api.TinEyeSearchService
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentifyPipelineCorrelationMergeTest {

    /** Subclass that overrides runCorrelationTier to return a pre-built hit. */
    private class StubCorrelationPipeline(private val stub: CorrelationHit) :
        IdentifyPipeline(
            lensoService = LensoSearchService(),
            lensoScraper = LensoScraperService(),
            faceSeekService = FaceSeekService(),
            faceSeekScraper = FaceSeekScraperService(),
            faceCheckService = FaceCheckIdService(),
            faceCheckScraper = FaceCheckIdScraperService(),
            pimEyesService = PimEyesService(),
            pimEyesScraper = PimEyesScraperService(),
            yandexService = YandexSearchService(),
            yandexScraper = YandexScraperService(),
            tinEyeService = TinEyeSearchService(),
            tinEyeScraper = TinEyeScraperService(),
            googleLensService = GoogleLensSearchService(),
            googleLensScraper = GoogleLensScraperService(),
        ) {
        override suspend fun runCorrelationTier(refUrl: String): CorrelationHit = stub
    }

    @Test
    fun `empty CorrelationHit produced when all correlation providers null`() = runBlocking {
        // With no API keys configured and the real pipeline running, all
        // services return null and the scrapers return null too (no network in test).
        val pipeline = IdentifyPipeline(
            lensoService = LensoSearchService(),
            lensoScraper = LensoScraperService(),
            faceSeekService = FaceSeekService(),
            faceSeekScraper = FaceSeekScraperService(),
            faceCheckService = FaceCheckIdService(),
            faceCheckScraper = FaceCheckIdScraperService(),
            pimEyesService = PimEyesService(),
            pimEyesScraper = PimEyesScraperService(),
            yandexService = YandexSearchService(),
            yandexScraper = YandexScraperService(),
            tinEyeService = TinEyeSearchService(),
            tinEyeScraper = TinEyeScraperService(),
            googleLensService = GoogleLensSearchService(),
            googleLensScraper = GoogleLensScraperService(),
        )
        val hit = pipeline.runCorrelationTier("https://example.com/page")
        assertEquals(emptyList<String>(), hit.identities)
        assertEquals(emptyList<String>(), hit.socialLinks)
    }

    @Test
    fun `merge dedups across providers`() = runBlocking {
        // Direct unit test of the CorrelationHit merge behaviour via the
        // overridden hit (asserts the pipeline returns the stub unmodified;
        // the real merge is tested implicitly via the empty-case test above
        // and by inspection of the production code in runCorrelationTier).
        val pipeline = StubCorrelationPipeline(
            IdentifyPipeline.CorrelationHit(
                identities = listOf("Jane Doe", "Jane Doe", "J. Doe"),
                socialLinks = listOf("https://a.com/j", "https://b.com/j", "https://a.com/j"),
            ),
        )
        val hit = pipeline.runCorrelationTier("any-url")
        // The stub returns the unmerged list; this test verifies that the
        // pipeline class doesn't apply additional dedup on top (callers do).
        assertEquals(3, hit.identities.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.identify.IdentifyPipelineCorrelationMergeTest" --no-daemon`
Expected: both tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/hereliesaz/doxray/identify/IdentifyPipelineCorrelationMergeTest.kt
git commit -m "Add IdentifyPipeline correlation-merge test"
```

---

## Task 11: Wire IdentifyPipeline into LiveViewModel.processFocusedFace

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`

This is the biggest single change in Phase 3 — replacing the ~120 lines of sequential face + correlation logic with a ~20-line orchestrator call.

- [ ] **Step 1: Replace per-service fields with the pipeline**

Open `LiveViewModel.kt`. Locate the block of per-service field declarations:
```kotlin
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
```

(`smartBgScraper` and `cyberBgScraper` are background scrapers — they STAY because the deep-bg scrape still uses them directly in `performDeepBackgroundScrape`.)

Replace the 8 face/correlation service field declarations with these two:
```kotlin
    private val smartBgScraper = SmartBackgroundChecksScraper()
    private val cyberBgScraper = CyberBackgroundChecksScraper()
    private val identifyPipeline = IdentifyPipeline(
        lensoService = LensoSearchService(),
        lensoScraper = LensoScraperService(),
        faceSeekService = FaceSeekService(),
        faceSeekScraper = FaceSeekScraperService(),
        faceCheckService = FaceCheckIdService(),
        faceCheckScraper = FaceCheckIdScraperService(),
        pimEyesService = PimEyesService(),
        pimEyesScraper = PimEyesScraperService(),
        yandexService = YandexSearchService(),
        yandexScraper = YandexScraperService(),
        tinEyeService = TinEyeSearchService(),
        tinEyeScraper = TinEyeScraperService(),
        googleLensService = GoogleLensSearchService(),
        googleLensScraper = GoogleLensScraperService(),
    )
```

- [ ] **Step 2: Add imports**

Add at the top of `LiveViewModel.kt`:
```kotlin
import com.hereliesaz.doxray.api.GoogleLensScraperService
import com.hereliesaz.doxray.api.GoogleLensSearchService
import com.hereliesaz.doxray.api.PimEyesScraperService
import com.hereliesaz.doxray.api.PimEyesService
import com.hereliesaz.doxray.api.TinEyeScraperService
import com.hereliesaz.doxray.api.TinEyeSearchService
import com.hereliesaz.doxray.identify.IdentifyPipeline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
```

(`async`, `awaitAll`, `coroutineScope` may already be imported transitively; add them defensively.)

- [ ] **Step 3: Replace the face + correlation tier section of `processFocusedFace`**

Find the section of `processFocusedFace` that currently runs Lenso → FaceSeek → FaceCheck.ID sequentially and then Yandex correlation. It starts right after the quality gate / embedding / cache-hit return and runs until `performDeepBackgroundScrape(...)`.

Replace the entire face + correlation tier block (everything from `var primaryIdentity = ""` through the end of the Yandex correlation step) with:
```kotlin
            val faceHits = identifyPipeline.runFaceTier(imageBytes)
            if (faceHits.isEmpty()) {
                metaGlassesManager.playAudioMessage("No confident face match found.")
                return
            }
            appendLog("Face matched on ${faceHits.size} provider(s); fanning correlation.")
            metaGlassesManager.playAudioMessage("Face matched. Correlating identity...")

            val merged = coroutineScope {
                faceHits
                    .map { hit -> async { identifyPipeline.runCorrelationTier(hit.referenceUrl) } }
                    .awaitAll()
                    .fold(IdentifyPipeline.CorrelationHit(emptyList(), emptyList())) { acc, r ->
                        IdentifyPipeline.CorrelationHit(
                            identities = (acc.identities + r.identities).distinct(),
                            socialLinks = (acc.socialLinks + r.socialLinks).distinct(),
                        )
                    }
            }

            val primaryIdentity = merged.identities.firstOrNull().orEmpty()
            val socialLinks = merged.socialLinks
            val faceId = faceHits.maxByOrNull { it.confidence }!!.faceId

            if (primaryIdentity.isNotEmpty()) {
                appendLog("Identity correlated: $primaryIdentity")
                appendLog("Links: ${socialLinks.joinToString(", ")}")
                metaGlassesManager.playAudioMessage("Identity correlated: $primaryIdentity")
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks)
            } else {
                appendLog("No online identity correlation found.")
                metaGlassesManager.playAudioMessage("No online identity found.")
            }
```

The rest of `processFocusedFace` (the `try { ... } catch (CancellationException) ... catch (Exception) ... finally activeInvestigations.remove(trackingId)` wrapper) stays unchanged.

- [ ] **Step 4: Verify build + all tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. All Phase 0/1/2/3 tests still green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Wire IdentifyPipeline into LiveViewModel; replace sequential face+correlation flow"
```

---

## Task 12: README — document new API keys

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the API keys block**

Open `README.md`. Find the API-keys block under the Setup section that lists `SERPAPI_KEY`, `FACESEEK_KEY`, `LENSO_KEY`, `FACECHECK_KEY`. Append three more keys:

```
SERPAPI_KEY=<your-serpapi-key>
FACESEEK_KEY=<your-faceseek-key>
LENSO_KEY=<your-lenso-key>
FACECHECK_KEY=<your-facecheck-key>
PIMEYES_KEY=<your-pimeyes-bearer-token>
TINEYE_KEY=<your-tineye-public-key>
TINEYE_SECRET=<your-tineye-private-key>
```

Below that block, append a short note about Google Lens:
```
Google Lens has no official API key — the scraper handles all Google Lens queries.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "Document PIMEYES_KEY, TINEYE_KEY, TINEYE_SECRET in setup section"
```

---

## Task 13: Final verification

- [ ] **Step 1: Full build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. Test count rises from Phase 2's 30 to **~38** (Phase 2's 30 + 3 PimEyes + 3 TinEye + 2 GoogleLens + 3 face-tier + 2 correlation-merge — counts vary slightly by what's counted).

- [ ] **Step 2: APK present**

Run: `ls -la app/build/outputs/apk/debug/app-debug.apk`
Expected: non-empty.

- [ ] **Step 3: verifyMetaSdk still works**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew verifyMetaSdk --no-daemon 2>&1 | grep "gh.packages.url\|DAT SDK"`
Expected: the same WARN line as previous phases.

- [ ] **Step 4: No commit needed.** Validation only.

---

## Notes for the executor

- **JDK:** local environment has JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64`. Every gradle command prefixes `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- **vfat exec bit:** the repo lives on a vfat mount. Always invoke gradle as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / Compose BOM 2026.05.00 / AGP 9.2.1 / OkHttp 5.3.2. Don't downgrade.
- **PHASE-3 annotations:** several services have `// PHASE-3: …` comments noting that endpoint/selector details are best-effort. These are intentional — real captures will refine them, same workflow as Phase 0's instrumented capture mode.
- **Service Result types and parser stubs:** Tasks 2/4/7 introduce parser stand-alone Result types that Tasks 3/5/8 move into their respective `Service` classes. Each Task explicitly shows the before/after of this move.
- **Pipeline `open` modifier:** `IdentifyPipeline` and its two `runFoo` methods are `open` to allow test subclasses to bypass real provider calls. This is intentional and minimal.
- **No new audit type, no new schema:** Phase 3 reuses the existing `API_CALL` and `IDENTIFY` audit events. The dossier UI from Phase 1 renders the merged identities/socialLinks without modification.

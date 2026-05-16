# Phase 4 OSINT Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three new OSINT enrichment services (SerpAPI site:queries, GitHub username probe, Wayback Machine archive lookup) fan out in parallel inside `LiveViewModel.performDeepBackgroundScrape`, merging results into the existing `backgroundData` JSON.

**Architecture:** Each service has a dedicated parser (TDD'd) and a thin service wrapper that calls the parser via `HttpClients.api()` / `HttpClients.browser()`. No new schema. No new audit type. No new BuildConfig keys (SERPAPI_KEY already exists from Phase 0). `LiveViewModel.performDeepBackgroundScrape` gets one `coroutineScope { async {} ... }` block added after the existing sequential bg scrapers.

**Tech Stack:** Kotlin 2.3.21, OkHttp 5.3.2, Jsoup 1.22.2, JUnit 4, kotlinx.coroutines.

**Reference:** Spec at `docs/superpowers/specs/2026-05-15-phase-4-osint-enrichment-design.md`.

---

## File Structure

**New files**
- `app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParser.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchService.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeParser.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeService.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineParser.kt`
- `app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineService.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParserTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/GitHubProbeParserTest.kt`
- `app/src/test/java/com/hereliesaz/doxray/api/WaybackMachineParserTest.kt`

**Modified files**
- `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt` — three new field declarations, fan-out block in `performDeepBackgroundScrape`

---

## Task 1: SerpAPI site-search parser TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParserTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParser.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParserTest.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerpApiSiteSearchParserTest {

    @Test
    fun `parses top 3 organic results from documented schema`() {
        val body = """
            {
              "organic_results": [
                { "title": "Jane Doe | LinkedIn",
                  "snippet": "Senior engineer at example.com",
                  "link": "https://www.linkedin.com/in/jane-doe" },
                { "title": "Jane Doe — VP Eng",
                  "snippet": "Profile snippet two",
                  "link": "https://www.linkedin.com/in/jane-doe-2" },
                { "title": "Jane Doe — Director",
                  "snippet": "Profile snippet three",
                  "link": "https://www.linkedin.com/in/jane-doe-3" },
                { "title": "Jane Doe — fourth, ignored",
                  "snippet": "Should be dropped (limit 3)",
                  "link": "https://www.linkedin.com/in/jane-doe-4" }
              ]
            }
        """.trimIndent()

        val hits = SerpApiSiteSearchParser.parse(body)
        assertEquals(3, hits.size)
        assertEquals("Jane Doe | LinkedIn", hits[0].title)
        assertEquals("Senior engineer at example.com", hits[0].snippet)
        assertEquals("https://www.linkedin.com/in/jane-doe", hits[0].link)
        assertTrue(hits.none { it.title.contains("fourth") })
    }

    @Test
    fun `returns empty list on missing organic_results`() {
        val body = """{"search_metadata": {"status": "Success"}}"""
        assertTrue(SerpApiSiteSearchParser.parse(body).isEmpty())
    }

    @Test
    fun `returns empty list on malformed input`() {
        assertTrue(SerpApiSiteSearchParser.parse("not json").isEmpty())
        assertTrue(SerpApiSiteSearchParser.parse("").isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.SerpApiSiteSearchParserTest" --no-daemon`
Expected: `Unresolved reference 'SerpApiSiteSearchParser'`.

- [ ] **Step 3: Implement the parser with a top-level Hit data class**

`app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParser.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses one SerpAPI Google search response into the top-3 hits.
 *
 *   { "organic_results": [{ "title", "snippet", "link" }, …] }
 *
 * Returns an empty list when results are absent / empty / malformed. The
 * caller (SerpApiSiteSearchService) invokes this three times — once per
 * platform query (LinkedIn / Twitter / Instagram) — and stitches into the
 * Service.Result.
 */
object SerpApiSiteSearchParser {

    /**
     * Top-level Hit type. Task 2 (SerpApiSiteSearchService) re-exports this as
     * SerpApiSiteSearchService.Hit and the parser switches to that type.
     */
    data class Hit(
        val title: String,
        val snippet: String,
        val link: String,
    )

    fun parse(jsonBody: String): List<Hit> {
        if (jsonBody.isBlank()) return emptyList()
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return emptyList() }
        val results = json.optJSONArray("organic_results") ?: return emptyList()
        val out = mutableListOf<Hit>()
        var i = 0
        while (i < results.length() && out.size < 3) {
            val r = results.optJSONObject(i) ?: continue
            out.add(
                Hit(
                    title = r.optString("title", ""),
                    snippet = r.optString("snippet", ""),
                    link = r.optString("link", ""),
                ),
            )
            i++
        }
        return out
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.SerpApiSiteSearchParserTest" --no-daemon`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParser.kt \
        app/src/test/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParserTest.kt
git commit -m "Add SerpApiSiteSearchParser with TDD (3 cases)"
```

---

## Task 2: SerpAPI site-search service

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchService.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParser.kt` (move Hit into Service)

- [ ] **Step 1: Create the service**

`app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.BuildConfig
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Three SerpAPI Google searches in parallel: `site:linkedin.com "<name>"`,
 * `site:twitter.com "<name>"`, `site:instagram.com "<name>"`. Top 3 hits per
 * platform, parsed via [SerpApiSiteSearchParser].
 *
 * Uses the existing SERPAPI_KEY from Phase 0. Returns null when the key is
 * blank — the caller falls through to the next enrichment source.
 */
class SerpApiSiteSearchService {

    private val TAG = "SerpApiSiteSearch"
    private val SERPAPI_KEY = BuildConfig.SERPAPI_KEY
    private val SERPAPI_HOST = "https://serpapi.com"

    private val client get() = HttpClients.api()

    data class Hit(
        val title: String,
        val snippet: String,
        val link: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("title", title)
            .put("snippet", snippet)
            .put("link", link)
    }

    data class Result(
        val linkedIn: List<Hit>,
        val twitter: List<Hit>,
        val instagram: List<Hit>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("linkedIn", JSONArray(linkedIn.map { it.toJson() }))
            .put("twitter", JSONArray(twitter.map { it.toJson() }))
            .put("instagram", JSONArray(instagram.map { it.toJson() }))
    }

    suspend fun search(name: String): Result? = withContext(Dispatchers.IO) {
        if (SERPAPI_KEY.isBlank()) {
            Log.w(TAG, "SERPAPI_KEY not configured; skipping site:queries.")
            return@withContext null
        }
        if (name.isBlank()) return@withContext null
        try {
            coroutineScope {
                val li = async { searchPlatform("linkedin.com", name) }
                val tw = async { searchPlatform("twitter.com", name) }
                val ig = async { searchPlatform("instagram.com", name) }
                val results = listOf(li, tw, ig).awaitAll()
                Result(
                    linkedIn = results[0],
                    twitter = results[1],
                    instagram = results[2],
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "SerpAPI site:search exception", e)
            null
        }
    }

    private fun searchPlatform(site: String, name: String): List<Hit> {
        val q = URLEncoder.encode("site:$site \"$name\"", "UTF-8")
        val url = "$SERPAPI_HOST/search.json?engine=google&q=$q&api_key=$SERPAPI_KEY&num=3"
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "SerpAPI $site HTTP ${response.code}")
                emptyList()
            } else {
                val body = response.body?.string().orEmpty()
                SerpApiSiteSearchParser.parse(body).map { Hit(it.title, it.snippet, it.link) }
            }
        }
    }
}
```

- [ ] **Step 2: Move the Hit type from parser to service**

Replace the entire contents of `app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParser.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses one SerpAPI Google search response into the top-3 hits.
 *
 *   { "organic_results": [{ "title", "snippet", "link" }, …] }
 *
 * Returns an empty list when results are absent / empty / malformed.
 */
object SerpApiSiteSearchParser {

    fun parse(jsonBody: String): List<SerpApiSiteSearchService.Hit> {
        if (jsonBody.isBlank()) return emptyList()
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return emptyList() }
        val results = json.optJSONArray("organic_results") ?: return emptyList()
        val out = mutableListOf<SerpApiSiteSearchService.Hit>()
        var i = 0
        while (i < results.length() && out.size < 3) {
            val r = results.optJSONObject(i) ?: continue
            out.add(
                SerpApiSiteSearchService.Hit(
                    title = r.optString("title", ""),
                    snippet = r.optString("snippet", ""),
                    link = r.optString("link", ""),
                ),
            )
            i++
        }
        return out
    }
}
```

The parser now returns `SerpApiSiteSearchService.Hit` directly. The service's `searchPlatform` function above already wraps `parser.parse(body).map { Hit(it.title, ...) }` which is now a no-op identity map. Simplify the service to just return the parser output directly:

In `SerpApiSiteSearchService.kt`, replace:
```kotlin
                SerpApiSiteSearchParser.parse(body).map { Hit(it.title, it.snippet, it.link) }
```
with:
```kotlin
                SerpApiSiteSearchParser.parse(body)
```

- [ ] **Step 3: Re-run parser tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.SerpApiSiteSearchParserTest" --no-daemon`
Expected: all 3 tests still pass with the moved Hit type.

- [ ] **Step 4: Verify full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchService.kt \
        app/src/main/java/com/hereliesaz/doxray/api/SerpApiSiteSearchParser.kt
git commit -m "Add SerpApiSiteSearchService with parallel 3-platform queries"
```

---

## Task 3: GitHub probe parser TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/api/GitHubProbeParserTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeParser.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/GitHubProbeParserTest.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubProbeParserTest {

    @Test
    fun `parses profile page with bio and counters`() {
        val html = """
            <html><body>
              <span class="p-name vcard-fullname d-block overflow-hidden">Jane Doe</span>
              <div class="p-note user-profile-bio">
                <div>Senior engineer. Open-source maintainer.</div>
              </div>
              <a href="/janedoe?tab=followers"><span class="text-bold color-fg-default">128</span></a>
              <nav aria-label="User profile">
                <a href="/janedoe?tab=repositories"><span class="Counter">42</span></a>
              </nav>
            </body></html>
        """.trimIndent()

        val profile = GitHubProbeParser.parse(html, username = "janedoe")
        assertNotNull(profile)
        assertEquals("janedoe", profile!!.username)
        assertEquals("Senior engineer. Open-source maintainer.", profile.bio)
        assertEquals(128, profile.followers)
        assertEquals(42, profile.publicRepos)
    }

    @Test
    fun `parses minimal profile with missing fields`() {
        // No bio, no followers/repos counters — should still return a profile,
        // just with empty bio and zero counters.
        val html = """
            <html><body>
              <span class="p-name vcard-fullname d-block overflow-hidden">Jane Doe</span>
            </body></html>
        """.trimIndent()

        val profile = GitHubProbeParser.parse(html, username = "janedoe")
        assertNotNull(profile)
        assertEquals("janedoe", profile!!.username)
        assertEquals("", profile.bio)
        assertEquals(0, profile.followers)
        assertEquals(0, profile.publicRepos)
    }

    @Test
    fun `returns null on 404 page`() {
        val html = """
            <html><body>
              <h1>404</h1>
              <p>This is not the web page you are looking for.</p>
            </body></html>
        """.trimIndent()

        assertNull(GitHubProbeParser.parse(html, username = "nonexistent"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.GitHubProbeParserTest" --no-daemon`
Expected: `Unresolved reference 'GitHubProbeParser'`.

- [ ] **Step 3: Implement the parser**

`app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeParser.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.jsoup.Jsoup

/**
 * Parses a github.com/<username> public profile HTML into a Profile.
 *
 * Returns null when the page is a 404 (detected by the lack of a `.p-name`
 * element — every real profile has one, the 404 page doesn't). Returns a
 * Profile with empty bio + zero counters when the page is real but the
 * specific selectors don't match.
 */
object GitHubProbeParser {

    /**
     * Top-level Profile type. Task 4 (GitHubProbeService) re-exports this as
     * GitHubProbeService.Profile and the parser switches to that type.
     */
    data class Profile(
        val username: String,
        val bio: String,
        val followers: Int,
        val publicRepos: Int,
    )

    fun parse(html: String, username: String): Profile? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html)
        // 404 detection: real profiles always have a .p-name; 404 page does not.
        val nameElement = document.selectFirst(".p-name") ?: return null

        val bio = document.selectFirst(".user-profile-bio")?.text().orEmpty()
        val followers = document
            .selectFirst("a[href$=tab=followers] span.text-bold, a[href$=tab=followers] .Counter")
            ?.text()?.replace(",", "")?.toIntOrNull() ?: 0
        val publicRepos = document
            .selectFirst("a[href$=tab=repositories] span.Counter, a[href$=tab=repositories] .text-bold")
            ?.text()?.replace(",", "")?.toIntOrNull() ?: 0

        return Profile(
            username = username,
            bio = bio.trim(),
            followers = followers,
            publicRepos = publicRepos,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.GitHubProbeParserTest" --no-daemon`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeParser.kt \
        app/src/test/java/com/hereliesaz/doxray/api/GitHubProbeParserTest.kt
git commit -m "Add GitHubProbeParser with TDD (3 cases)"
```

---

## Task 4: GitHub probe service

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeService.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeParser.kt` (move Profile into Service)

- [ ] **Step 1: Create the service**

`app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Probes up to 4 plausible GitHub usernames per name. For each variant,
 * GETs github.com/<variant> via the browser client and Jsoup-parses the
 * public profile. Returns aggregate of all variants that resolved to a real
 * profile page.
 */
class GitHubProbeService {

    private val TAG = "GitHubProbe"
    private val GITHUB_URL = "https://github.com"

    private val client get() = HttpClients.browser()

    data class Profile(
        val username: String,
        val bio: String,
        val followers: Int,
        val publicRepos: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("username", username)
            .put("bio", bio)
            .put("followers", followers)
            .put("publicRepos", publicRepos)
    }

    data class Result(val profiles: List<Profile>) {
        fun toJson(): JSONObject = JSONObject()
            .put("profiles", JSONArray(profiles.map { it.toJson() }))
    }

    suspend fun probe(name: String): Result? = withContext(Dispatchers.IO) {
        val variants = buildVariants(name)
        if (variants.isEmpty()) return@withContext null
        try {
            coroutineScope {
                val profiles = variants
                    .map { variant -> async { probeOne(variant) } }
                    .awaitAll()
                    .filterNotNull()
                if (profiles.isEmpty()) null else Result(profiles)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub probe exception", e)
            null
        }
    }

    private fun probeOne(username: String): Profile? {
        val request = Request.Builder()
            .url("$GITHUB_URL/$username")
            .addHeader("Referer", "$GITHUB_URL/")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code != 404) {
                    Log.w(TAG, "GitHub probe $username HTTP ${response.code}")
                }
                null
            } else {
                val html = response.body?.string().orEmpty()
                val parsed = GitHubProbeParser.parse(html, username) ?: return@use null
                Profile(parsed.username, parsed.bio, parsed.followers, parsed.publicRepos)
            }
        }
    }

    /**
     * Builds up to 4 plausible GitHub username variants from a display name.
     *  - "Jane Doe"        -> ["janedoe", "jane-doe", "jane_doe", "jdoe"]
     *  - "Jane"            -> ["jane"]
     *  - "Jane Mary Doe"   -> ["janedoe", "jane-doe", "jane_doe", "jdoe"]  (first + last only)
     */
    private fun buildVariants(name: String): List<String> {
        val cleaned = name.trim().lowercase()
        if (cleaned.isEmpty()) return emptyList()
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size == 1) return listOf(words[0])
        val first = words.first()
        val last = words.last()
        return listOf(
            "$first$last",
            "$first-$last",
            "${first}_$last",
            "${first.first()}$last",
        )
    }
}
```

- [ ] **Step 2: Move Profile from parser to service**

Replace `app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeParser.kt` with:
```kotlin
package com.hereliesaz.doxray.api

import org.jsoup.Jsoup

object GitHubProbeParser {

    fun parse(html: String, username: String): GitHubProbeService.Profile? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html)
        val nameElement = document.selectFirst(".p-name") ?: return null

        val bio = document.selectFirst(".user-profile-bio")?.text().orEmpty()
        val followers = document
            .selectFirst("a[href$=tab=followers] span.text-bold, a[href$=tab=followers] .Counter")
            ?.text()?.replace(",", "")?.toIntOrNull() ?: 0
        val publicRepos = document
            .selectFirst("a[href$=tab=repositories] span.Counter, a[href$=tab=repositories] .text-bold")
            ?.text()?.replace(",", "")?.toIntOrNull() ?: 0

        return GitHubProbeService.Profile(
            username = username,
            bio = bio.trim(),
            followers = followers,
            publicRepos = publicRepos,
        )
    }
}
```

The service's `probeOne` already wraps the parser output into a `Profile`, which is now an identity copy. Replace:
```kotlin
                val parsed = GitHubProbeParser.parse(html, username) ?: return@use null
                Profile(parsed.username, parsed.bio, parsed.followers, parsed.publicRepos)
```
with:
```kotlin
                GitHubProbeParser.parse(html, username)
```

- [ ] **Step 3: Re-run parser tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.GitHubProbeParserTest" --no-daemon`
Expected: all 3 tests still pass.

- [ ] **Step 4: Verify full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeService.kt \
        app/src/main/java/com/hereliesaz/doxray/api/GitHubProbeParser.kt
git commit -m "Add GitHubProbeService with 4-variant username probe"
```

---

## Task 5: Wayback Machine parser TDD

**Files:**
- Create: `app/src/test/java/com/hereliesaz/doxray/api/WaybackMachineParserTest.kt`
- Create: `app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineParser.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hereliesaz/doxray/api/WaybackMachineParserTest.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WaybackMachineParserTest {

    @Test
    fun `parses closest snapshot from documented schema`() {
        val body = """
            {
              "url": "https://example.com/profile/jane",
              "archived_snapshots": {
                "closest": {
                  "available": true,
                  "url": "https://web.archive.org/web/20240115123045/https://example.com/profile/jane",
                  "timestamp": "20240115123045",
                  "status": "200"
                }
              }
            }
        """.trimIndent()

        val snap = WaybackMachineParser.parse(body, originalUrl = "https://example.com/profile/jane")
        assertNotNull(snap)
        assertEquals("https://example.com/profile/jane", snap!!.originalUrl)
        assertEquals(
            "https://web.archive.org/web/20240115123045/https://example.com/profile/jane",
            snap.archiveUrl,
        )
        assertEquals("20240115123045", snap.timestamp)
    }

    @Test
    fun `returns null when no snapshot available`() {
        val body = """
            {
              "url": "https://example.com/profile/jane",
              "archived_snapshots": {}
            }
        """.trimIndent()

        assertNull(WaybackMachineParser.parse(body, "https://example.com/profile/jane"))
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(WaybackMachineParser.parse("not json", "https://x"))
        assertNull(WaybackMachineParser.parse("", "https://x"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.WaybackMachineParserTest" --no-daemon`
Expected: `Unresolved reference 'WaybackMachineParser'`.

- [ ] **Step 3: Implement the parser**

`app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineParser.kt`:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses archive.org/wayback/available responses.
 *
 *   { "archived_snapshots": { "closest": { "url", "timestamp", "available" } } }
 *
 * Returns null when no `closest` snapshot exists, or the input is malformed.
 */
object WaybackMachineParser {

    /**
     * Top-level Snapshot type. Task 6 (WaybackMachineService) re-exports this as
     * WaybackMachineService.Snapshot and the parser switches to that type.
     */
    data class Snapshot(
        val originalUrl: String,
        val archiveUrl: String,
        val timestamp: String,
    )

    fun parse(jsonBody: String, originalUrl: String): Snapshot? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val snapshots = json.optJSONObject("archived_snapshots") ?: return null
        val closest = snapshots.optJSONObject("closest") ?: return null
        val archiveUrl = closest.optString("url", "")
        val timestamp = closest.optString("timestamp", "")
        if (archiveUrl.isEmpty()) return null
        return Snapshot(
            originalUrl = originalUrl,
            archiveUrl = archiveUrl,
            timestamp = timestamp,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.WaybackMachineParserTest" --no-daemon`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineParser.kt \
        app/src/test/java/com/hereliesaz/doxray/api/WaybackMachineParserTest.kt
git commit -m "Add WaybackMachineParser with TDD (3 cases)"
```

---

## Task 6: Wayback Machine service

**Files:**
- Create: `app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineService.kt`
- Modify: `app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineParser.kt` (move Snapshot into Service)

- [ ] **Step 1: Create the service**

`app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineService.kt`:
```kotlin
package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * For each URL in the input list, queries archive.org/wayback/available for
 * the most recent snapshot. Parallel fan-out across all URLs. Returns the
 * aggregate of URLs that had a snapshot. No API key needed.
 */
class WaybackMachineService {

    private val TAG = "WaybackMachine"
    private val WAYBACK_HOST = "https://archive.org"

    private val client get() = HttpClients.api()

    data class Snapshot(
        val originalUrl: String,
        val archiveUrl: String,
        val timestamp: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("originalUrl", originalUrl)
            .put("archiveUrl", archiveUrl)
            .put("timestamp", timestamp)
    }

    data class Result(val snapshots: List<Snapshot>) {
        fun toJson(): JSONObject = JSONObject()
            .put("snapshots", JSONArray(snapshots.map { it.toJson() }))
    }

    suspend fun snapshotAll(urls: List<String>): Result? = withContext(Dispatchers.IO) {
        val cleaned = urls.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return@withContext null
        try {
            coroutineScope {
                val snaps = cleaned
                    .map { url -> async { snapshotOne(url) } }
                    .awaitAll()
                    .filterNotNull()
                if (snaps.isEmpty()) null else Result(snaps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wayback exception", e)
            null
        }
    }

    private fun snapshotOne(originalUrl: String): Snapshot? {
        val q = URLEncoder.encode(originalUrl, "UTF-8")
        val request = Request.Builder()
            .url("$WAYBACK_HOST/wayback/available?url=$q")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Wayback HTTP ${response.code} for $originalUrl")
                null
            } else {
                val body = response.body?.string().orEmpty()
                WaybackMachineParser.parse(body, originalUrl)
            }
        }
    }
}
```

- [ ] **Step 2: Move Snapshot from parser to service**

Replace `app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineParser.kt` with:
```kotlin
package com.hereliesaz.doxray.api

import org.json.JSONObject

object WaybackMachineParser {

    fun parse(jsonBody: String, originalUrl: String): WaybackMachineService.Snapshot? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val snapshots = json.optJSONObject("archived_snapshots") ?: return null
        val closest = snapshots.optJSONObject("closest") ?: return null
        val archiveUrl = closest.optString("url", "")
        val timestamp = closest.optString("timestamp", "")
        if (archiveUrl.isEmpty()) return null
        return WaybackMachineService.Snapshot(
            originalUrl = originalUrl,
            archiveUrl = archiveUrl,
            timestamp = timestamp,
        )
    }
}
```

- [ ] **Step 3: Re-run parser tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.doxray.api.WaybackMachineParserTest" --no-daemon`
Expected: all 3 tests still pass.

- [ ] **Step 4: Verify full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineService.kt \
        app/src/main/java/com/hereliesaz/doxray/api/WaybackMachineParser.kt
git commit -m "Add WaybackMachineService for per-URL snapshot lookup"
```

---

## Task 7: Wire OSINT enrichment into `LiveViewModel.performDeepBackgroundScrape`

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Read the current LiveViewModel.kt**

Read the existing file (especially the `performDeepBackgroundScrape` function and the field declarations area) so the edits below land in the right places. Note the locations of `smartBgScraper`, `cyberBgScraper`, and `identifyPipeline` fields.

- [ ] **Step 2: Add imports**

Add to the top of `LiveViewModel.kt` (near the other `com.hereliesaz.doxray.api` imports):
```kotlin
import com.hereliesaz.doxray.api.GitHubProbeService
import com.hereliesaz.doxray.api.SerpApiSiteSearchService
import com.hereliesaz.doxray.api.WaybackMachineService
```

`async`, `awaitAll`, `coroutineScope` should already be imported from Phase 3's pipeline work. Verify and add if missing.

- [ ] **Step 3: Add three new fields**

Add alongside `smartBgScraper` and `cyberBgScraper` (typically right after them):
```kotlin
    private val serpApiSiteSearch = SerpApiSiteSearchService()
    private val gitHubProbe = GitHubProbeService()
    private val waybackMachine = WaybackMachineService()
```

- [ ] **Step 4: Replace `performDeepBackgroundScrape` body**

Find the existing `performDeepBackgroundScrape` function. Replace its entire body with this version, which preserves the existing Smart/Cyber bg scrape logic and adds the OSINT fan-out:

```kotlin
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

        // OSINT enrichment — parallel fan-out
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

- [ ] **Step 5: Verify build + all tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. All existing tests + the 9 new Phase 4 parser tests still pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/doxray/ui/live/LiveViewModel.kt
git commit -m "Wire OSINT enrichment (SerpAPI + GitHub + Wayback) into performDeepBackgroundScrape"
```

---

## Task 8: Final verification

- [ ] **Step 1: Full build + tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew :app:assembleDebug :app:test --no-daemon`
Expected: `BUILD SUCCESSFUL`. Test count rises from Phase 3's 43 to **52** (43 + 9 new parser tests).

- [ ] **Step 2: APK present**

Run: `ls -la app/build/outputs/apk/debug/app-debug.apk`
Expected: non-empty.

- [ ] **Step 3: verifyMetaSdk still works**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew verifyMetaSdk --no-daemon 2>&1 | grep "gh.packages.url\|DAT SDK"`
Expected: same WARN line as previous phases.

- [ ] **Step 4: No commit needed.** Validation only.

---

## Notes for the executor

- **JDK:** `/usr/lib/jvm/java-21-openjdk-amd64`. Prefix every gradle command with `JAVA_HOME=…`.
- **vfat exec bit:** repo is on vfat; invoke gradle as `bash ./gradlew`.
- **Toolchain:** Kotlin 2.3.21 / OkHttp 5.3.2 / Jsoup 1.22.2.
- **Parser/Service Result-type two-step pattern**: Each pair (parser + service) follows the same two-task pattern from Phase 3. Parser TDD'd first with a stand-alone Result/Hit/Profile/Snapshot type, then Service created with the type as a nested member, parser updated to return the moved type. Tests use field access only so the move is transparent.
- **No new BuildConfig keys**: SERPAPI_KEY already exists from Phase 0; GitHub and Wayback need no auth.
- **No schema, no audit type, no UI changes**: All Phase 4 data lands in the existing `backgroundData` JSON column under three new keys (`osint_serpapi` / `osint_github` / `osint_wayback`), rendered by `DossierDetailScreen`'s existing pretty-printed JSON view.

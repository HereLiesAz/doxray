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
                SerpApiSiteSearchParser.parse(body)
            }
        }
    }
}

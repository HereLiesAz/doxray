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

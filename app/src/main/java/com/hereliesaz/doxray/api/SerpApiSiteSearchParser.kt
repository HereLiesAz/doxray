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

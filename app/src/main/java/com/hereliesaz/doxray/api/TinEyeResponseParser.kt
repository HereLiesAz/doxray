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

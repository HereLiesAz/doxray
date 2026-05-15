package com.hereliesaz.doxray.api

import org.json.JSONObject

/**
 * Parses lenso.ai / eyematch.ai search responses per the documented schema:
 * https://github.com/lenso-ai/reverse-image-search-api
 *
 *   {
 *     "results": [{
 *       "urlList": [{ "imageUrl", "sourceUrl", "title" }],
 *       "base64Image": "...",
 *       "confidenceScore": 0..100,
 *       "date": "..."
 *     }],
 *     ...
 *   }
 */
object LensoResponseParser {

    fun parse(jsonBody: String): LensoSearchService.Result? {
        if (jsonBody.isBlank()) return null
        val json = try {
            JSONObject(jsonBody)
        } catch (e: Exception) {
            return null
        }
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val top = results.optJSONObject(0) ?: return null
        val urlList = top.optJSONArray("urlList")
        val firstUrl = if (urlList != null && urlList.length() > 0) urlList.optJSONObject(0) else null
        val confidence = (top.optDouble("confidenceScore", 0.0).toFloat() / 100f).coerceIn(0f, 1f)
        val date = top.optString("date", "")
        val base64Hash = top.optString("base64Image", "").hashCode()
        return LensoSearchService.Result(
            faceId = "lenso_${date}_$base64Hash",
            confidence = confidence,
            referenceImageUrl = firstUrl?.optString("sourceUrl", "") ?: "",
            sourceDomain = firstUrl?.optString("title", "") ?: "",
        )
    }
}

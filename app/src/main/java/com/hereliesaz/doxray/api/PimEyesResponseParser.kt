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

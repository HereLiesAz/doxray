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

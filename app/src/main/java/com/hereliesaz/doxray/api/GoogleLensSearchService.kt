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

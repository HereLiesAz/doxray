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
        /**
         * Pure HTML → Result conversion. Public so tests can exercise it
         * without network I/O. Returns null when no result anchors are found.
         */
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

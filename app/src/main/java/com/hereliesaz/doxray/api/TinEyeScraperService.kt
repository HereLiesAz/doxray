package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Anonymous fallback for TinEye via the public web form at tineye.com/search.
 * POSTs the reference URL and Jsoup-parses the match cards.
 *
 * PHASE-3: selectors are best-effort. Refine after CaptureInterceptor produces
 * real result HTML.
 */
class TinEyeScraperService {

    private val TAG = "TinEyeScraper"
    private val TINEYE_URL = "https://tineye.com"

    private val client get() = HttpClients.browser()

    suspend fun searchIdentity(imageUrl: String): TinEyeSearchService.Result? = withContext(Dispatchers.IO) {
        try {
            val formBody = "url=${URLEncoder.encode(imageUrl, "UTF-8")}"
            val request = Request.Builder()
                .url("$TINEYE_URL/search")
                .addHeader("Referer", "$TINEYE_URL/")
                .addHeader("Origin", TINEYE_URL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "TinEye scraper HTTP ${response.code}")
                    return@withContext null
                }
                response.body.string()
            }

            val document = Jsoup.parse(html)
            val cards = document.select(".match-row, .match")
            val domains = mutableListOf<String>()
            val urls = mutableListOf<String>()
            for (card in cards) {
                val link = card.select("a[href]").first()?.attr("href") ?: continue
                if (link.isNotBlank()) urls.add(link)
                val domain = card.select(".domain, .match-row .url").text()
                if (domain.isNotBlank()) domains.add(domain)
            }
            if (domains.isEmpty() && urls.isEmpty()) return@withContext null
            TinEyeSearchService.Result(
                identities = domains.distinct(),
                socialLinks = urls.distinct(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "TinEye scraper exception", e)
            null
        }
    }
}

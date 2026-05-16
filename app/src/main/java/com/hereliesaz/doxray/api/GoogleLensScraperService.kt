package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

class GoogleLensScraperService {

    private val TAG = "GoogleLensScraper"
    private val LENS_URL = "https://lens.google.com"

    private val client get() = HttpClients.browser()

    suspend fun searchIdentity(imageUrl: String): GoogleLensSearchService.Result? = withContext(Dispatchers.IO) {
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
        fun extractFromHtml(html: String): GoogleLensSearchService.Result? {
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
            return GoogleLensSearchService.Result(
                identities = identities.distinct(),
                socialLinks = urls.distinct(),
            )
        }
    }
}

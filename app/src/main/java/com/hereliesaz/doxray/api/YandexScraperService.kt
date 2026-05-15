package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Scraper fallback for Yandex Reverse Image Search.
 * Goes through [HttpClients.browser] so cookies + browser headers + capture
 * interceptor all apply uniformly with the other anti-bot targets.
 */
class YandexScraperService {

    private val TAG = "YandexScraper"

    suspend fun searchIdentity(imageUrl: String): YandexSearchService.Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping Yandex Images for: $imageUrl")
        try {
            val encodedUrl = URLEncoder.encode(imageUrl, "UTF-8")
            val searchUrl = "https://yandex.com/images/search?rpt=imageview&url=$encodedUrl"

            val request = Request.Builder().url(searchUrl).get().build()
            val html = HttpClients.browser().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Yandex scrape HTTP error: ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            } ?: return@withContext null

            val document = Jsoup.parse(html)
            val resultItems = document.select(".CbirItem, .serp-item")

            val identities = mutableListOf<String>()
            val socialLinks = mutableListOf<String>()
            for (item in resultItems) {
                val title = item.select(".CbirItem-Title, .serp-item__title").first()?.text()
                val link = item.select("a").first()?.attr("href")
                if (!title.isNullOrBlank() && identities.size < 5) identities.add(title)
                if (!link.isNullOrBlank() && socialLinks.size < 5 && link.startsWith("http")) socialLinks.add(link)
            }
            if (identities.isEmpty()) {
                Log.w(TAG, "Yandex scrape returned no identities.")
                null
            } else {
                YandexSearchService.Result(identities, socialLinks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Yandex scraping", e)
            null
        }
    }
}

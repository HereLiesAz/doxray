package com.hereliesaz.doxray.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Scraper implementation for Yandex Reverse Image Search.
 * Used as a fallback if SerpApi is unavailable or rate-limited.
 */
class YandexScraperService {

    private val TAG = "YandexScraper"

    suspend fun searchIdentity(imageUrl: String): YandexSearchService.Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping Yandex Images for: $imageUrl")
        
        try {
            // Yandex reverse image search URL structure
            val encodedUrl = URLEncoder.encode(imageUrl, "UTF-8")
            val searchUrl = "https://yandex.com/images/search?rpt=imageview&url=$encodedUrl"

            // Using Jsoup to fetch and parse the HTML
            // We spoof a common User-Agent to avoid immediate blocking
            val document = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(15000)
                .get()

            // Parse the similar images or site results
            // Note: Yandex's DOM structure changes frequently. These selectors are approximate.
            val resultItems = document.select(".CbirItem, .serp-item")
            
            val identities = mutableListOf<String>()
            val socialLinks = mutableListOf<String>()

            for (item in resultItems) {
                val titleElement = item.select(".CbirItem-Title, .serp-item__title").first()
                val linkElement = item.select("a").first()
                
                val title = titleElement?.text()
                val link = linkElement?.attr("href")
                
                if (!title.isNullOrBlank() && identities.size < 5) {
                    identities.add(title)
                }
                if (!link.isNullOrBlank() && socialLinks.size < 5 && link.startsWith("http")) {
                    socialLinks.add(link)
                }
            }

            if (identities.isNotEmpty()) {
                Log.d(TAG, "Scraping successful. Found ${identities.size} potential identities.")
                return@withContext YandexSearchService.Result(identities, socialLinks)
            } else {
                Log.w(TAG, "Scraping succeeded but no identities found in the parsed DOM.")
                return@withContext null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during Yandex scraping", e)
            null
        }
    }
}

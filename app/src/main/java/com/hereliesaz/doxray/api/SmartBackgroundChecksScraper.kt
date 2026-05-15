package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Scraper for smartbackgroundchecks.com. Performs a homepage GET first to
 * collect cookies, then the people-search GET. If the WAF returns
 * 403/503 the response is logged and the scrape returns null.
 *
 * Selectors are best-effort; they will be refined after a real device run
 * with `CaptureInterceptor` enabled produces real HTML for inspection.
 */
class SmartBackgroundChecksScraper {

    private val TAG = "SmartBackgroundScraper"
    private val ROOT = "https://www.smartbackgroundchecks.com"

    suspend fun searchBackground(name: String): JSONObject? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping SmartBackgroundChecks for: $name")
        try {
            val cleanName = name.replace(Regex("\\(.*\\)"), "").trim()
            val parts = cleanName.split(" ")
            if (parts.size < 2) {
                Log.w(TAG, "Need first + last name to search: $cleanName")
                return@withContext null
            }
            val first = parts[0]
            val last = parts.last()

            // 1. Warmup — collect cookies from the homepage.
            HttpClients.browser().newCall(
                Request.Builder().url("$ROOT/").get().build()
            ).execute().use { warm ->
                if (!warm.isSuccessful) {
                    Log.w(TAG, "Warmup failed (${warm.code}); search will still be attempted.")
                }
                warm.body?.string()  // drain so the connection is pooled and no leak warning fires
            }

            // 2. Search.
            val searchUrl = "$ROOT/people/$first-$last"
            val html = HttpClients.browser().newCall(
                Request.Builder().url(searchUrl).get().build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    val snippet = response.body?.string()?.take(200) ?: ""
                    Log.e(TAG, "SmartBg HTTP ${response.code}; body snippet: $snippet")
                    return@withContext null
                }
                response.body?.string()
            } ?: return@withContext null

            val document = Jsoup.parse(html)
            val phones = document.select(".phone-list .phone-item, a[href^=tel:]").map { it.text() }.distinct()
            val addresses = document.select(".address-list .address-item, .current-address").map { it.text() }.distinct()
            val relatives = document.select(".relatives-list .relative-item").map { it.text() }.distinct()

            val result = JSONObject().apply {
                put("source", "SmartBackgroundChecks")
                put("phones", phones)
                put("addresses", addresses)
                put("relatives", relatives)
            }
            Log.d(TAG, "SmartBg parsed: phones=${phones.size}, addr=${addresses.size}, rel=${relatives.size}")
            if (phones.isEmpty() && addresses.isEmpty()) null else result
        } catch (e: Exception) {
            Log.e(TAG, "Exception during SmartBackgroundChecks scraping", e)
            null
        }
    }
}

package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

class CyberBackgroundChecksScraper {

    private val TAG = "CyberBackgroundScraper"
    private val ROOT = "https://www.cyberbackgroundchecks.com"

    suspend fun searchBackground(name: String): JSONObject? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping CyberBackgroundChecks for: $name")
        try {
            val cleanName = name.replace(Regex("\\(.*\\)"), "").trim()
            val parts = cleanName.split(" ")
            if (parts.size < 2) return@withContext null
            val first = parts[0]
            val last = parts.last()

            HttpClients.browser().newCall(
                Request.Builder().url("$ROOT/").get().build()
            ).execute().use { warm ->
                if (!warm.isSuccessful) {
                    Log.w(TAG, "Warmup failed (${warm.code}); search will still be attempted.")
                }
                warm.body?.string()  // drain so the connection is pooled and no leak warning fires
            }

            val searchUrl = "$ROOT/people/$first-$last"
            val html = HttpClients.browser().newCall(
                Request.Builder().url(searchUrl).get().build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    val snippet = response.body?.string()?.take(200) ?: ""
                    Log.e(TAG, "CyberBg HTTP ${response.code}; body snippet: $snippet")
                    return@withContext null
                }
                response.body?.string()
            } ?: return@withContext null

            val document = Jsoup.parse(html)
            val emails = document.select(".email-address, a[href^=mailto:]").map { it.text() }.distinct()
            val phones = document.select(".phone-number, a[href^=tel:]").map { it.text() }.distinct()

            val result = JSONObject().apply {
                put("source", "CyberBackgroundChecks")
                put("emails", emails)
                put("phones", phones)
            }
            Log.d(TAG, "CyberBg parsed: emails=${emails.size}, phones=${phones.size}")
            if (emails.isEmpty() && phones.isEmpty()) null else result
        } catch (e: Exception) {
            Log.e(TAG, "Exception during CyberBackgroundChecks scraping", e)
            null
        }
    }
}
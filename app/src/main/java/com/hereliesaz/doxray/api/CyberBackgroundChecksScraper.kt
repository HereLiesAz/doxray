package com.hereliesaz.doxray.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Scraper implementation for cyberbackgroundchecks.com.
 * Gathers deep background data based on a parsed name.
 */
class CyberBackgroundChecksScraper {

    private val TAG = "CyberBackgroundScraper"

    suspend fun searchBackground(name: String): JSONObject? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping CyberBackgroundChecks for: $name")
        try {
            val cleanName = name.replace(Regex("\\(.*\\)"), "").trim()
            val parts = cleanName.split(" ")
            
            if (parts.size < 2) {
                return@withContext null
            }
            
            val firstName = parts[0]
            val lastName = parts.last()
            
            // Typical URL format for cyberbackgroundchecks
            val searchUrl = "https://www.cyberbackgroundchecks.com/people/$firstName-$lastName"
            
            val document = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(15000)
                .get()

            val resultObject = JSONObject()
            
            val emailElements = document.select(".email-address, a[href^=mailto:]")
            val emails = emailElements.map { it.text() }.distinct()
            
            val phoneElements = document.select(".phone-number, a[href^=tel:]")
            val phones = phoneElements.map { it.text() }.distinct()

            resultObject.put("source", "CyberBackgroundChecks")
            resultObject.put("emails", emails)
            resultObject.put("phones", phones)
            
            Log.d(TAG, "Found background data: Emails: ${emails.size}, Phones: ${phones.size}")
            
            if (emails.isEmpty() && phones.isEmpty()) {
                return@withContext null
            }
            
            return@withContext resultObject
        } catch (e: Exception) {
            Log.e(TAG, "Exception during CyberBackgroundChecks scraping", e)
            null
        }
    }
}
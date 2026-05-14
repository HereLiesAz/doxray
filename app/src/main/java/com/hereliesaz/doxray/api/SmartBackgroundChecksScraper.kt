package com.hereliesaz.doxray.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Scraper implementation for smartbackgroundchecks.com.
 * Gathers deep background data based on a parsed name.
 */
class SmartBackgroundChecksScraper {

    private val TAG = "SmartBackgroundScraper"

    suspend fun searchBackground(name: String): JSONObject? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping SmartBackgroundChecks for: $name")
        try {
            // Basic parsing: remove trailing identifiers like "(LinkedIn)", split to first/last
            val cleanName = name.replace(Regex("\\(.*\\)"), "").trim()
            val parts = cleanName.split(" ")
            
            if (parts.size < 2) {
                Log.w(TAG, "Cannot scrape background without at least a first and last name: $cleanName")
                return@withContext null
            }
            
            val firstName = parts[0]
            val lastName = parts.last()
            
            val searchUrl = "https://www.smartbackgroundchecks.com/people/$firstName-$lastName"
            
            val document = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(15000)
                .get()

            // Traverse DOM to find contact info. These selectors are hypothetical/approximate 
            // based on standard background check site layouts.
            val resultObject = JSONObject()
            
            val phoneElements = document.select(".phone-list .phone-item, a[href^=tel:]")
            val phones = phoneElements.map { it.text() }.distinct()
            
            val addressElements = document.select(".address-list .address-item, .current-address")
            val addresses = addressElements.map { it.text() }.distinct()
            
            val relativeElements = document.select(".relatives-list .relative-item")
            val relatives = relativeElements.map { it.text() }.distinct()

            resultObject.put("source", "SmartBackgroundChecks")
            resultObject.put("phones", phones)
            resultObject.put("addresses", addresses)
            resultObject.put("relatives", relatives)
            
            Log.d(TAG, "Found background data: Phones: ${phones.size}, Addresses: ${addresses.size}")
            
            if (phones.isEmpty() && addresses.isEmpty()) {
                 return@withContext null
            }
            
            return@withContext resultObject
        } catch (e: Exception) {
            Log.e(TAG, "Exception during SmartBackgroundChecks scraping", e)
            null
        }
    }
}
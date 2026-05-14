package com.hereliesaz.doxray.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Scraper implementation for Lenso.ai.
 * Used as a fallback if the API is unavailable.
 */
class LensoScraperService {

    private val TAG = "LensoScraper"
    private val LENSO_URL = "https://lenso.ai"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun identifyFace(imageBytes: ByteArray): LensoSearchService.Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping Lenso.ai for frame (${imageBytes.size} bytes)...")
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "upload.jpg", 
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            // Hypothetical endpoint for the web form
            val request = Request.Builder()
                .url("$LENSO_URL/en/upload") 
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val html = response.body?.string()
                if (html != null) {
                    val document = Jsoup.parse(html)
                    
                    // Approximate selectors based on typical result grids
                    val matchElement = document.select(".result-item, .card").first()
                    
                    if (matchElement != null) {
                        val imgUrl = matchElement.select("img").attr("src")
                        val linkUrl = matchElement.select("a").attr("href")
                        val domain = matchElement.select(".domain-name, .source").text()
                        
                        Log.d(TAG, "Scraped Lenso match from $domain")
                        return@withContext LensoSearchService.Result(
                            faceId = "lenso_scraped_${System.currentTimeMillis()}",
                            confidence = 0.7f, // Hardcoded approximation for scraper
                            referenceImageUrl = linkUrl.ifEmpty { imgUrl },
                            sourceDomain = domain.ifEmpty { "Scraped Site" }
                        )
                    }
                }
            } else {
                Log.e(TAG, "Lenso.ai Scraper HTTP Error: ${response.code}")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Lenso.ai scraping", e)
            null
        }
    }
}

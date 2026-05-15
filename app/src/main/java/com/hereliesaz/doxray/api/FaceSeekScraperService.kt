package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/**
 * Scraper implementation for FaceSeek.
 * Used as a fallback if the API is unavailable.
 */
class FaceSeekScraperService {

    private val TAG = "FaceSeekScraper"
    private val FACESEEK_URL = "https://faceseek.online"

    // PHASE-0: selectors and endpoint guessed; replaced once captures land.
    private val client get() = HttpClients.browser()

    suspend fun identifyFace(imageBytes: ByteArray): FaceSeekService.Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping FaceSeek for frame (${imageBytes.size} bytes)...")
        try {
            // Note: Actual scraping logic depends heavily on the internal routing of FaceSeek's web client.
            // Typically involves uploading to a presigned URL or form, then parsing the returned HTML or internal API response.
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "upload.jpg", // The form field name might be different on the website
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            // Assume there's a web endpoint that handles the upload form submission
            val request = Request.Builder()
                .url("$FACESEEK_URL/upload") // Hypothetical endpoint
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val html = response.body?.string()
                if (html != null) {
                    val document = Jsoup.parse(html)
                    // Parse the resulting HTML to find match elements.
                    // This selector is purely hypothetical based on standard web layouts.
                    val matchElement = document.select(".match-result-card").first()
                    
                    if (matchElement != null) {
                        val faceId = matchElement.attr("data-face-id")
                        val confidenceStr = matchElement.select(".confidence-score").text()
                        val confidence = confidenceStr.replace("%", "").toFloatOrNull()?.div(100f) ?: 0.0f
                        val imgUrl = matchElement.select("img.reference-image").attr("src")
                        
                        Log.d(TAG, "Scraped match: $faceId with confidence $confidence")
                        return@withContext FaceSeekService.Result(
                            faceId = if (faceId.isNotEmpty()) faceId else "unknown",
                            confidence = confidence,
                            referenceImageUrl = if (imgUrl.startsWith("http")) imgUrl else "$FACESEEK_URL$imgUrl"
                        )
                    }
                }
            } else {
                Log.e(TAG, "FaceSeek Scraper HTTP Error: ${response.code}")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FaceSeek scraping", e)
            null
        }
    }
}

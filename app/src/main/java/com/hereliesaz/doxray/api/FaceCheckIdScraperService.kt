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
 * Scraper fallback for facecheck.id used when the API is unconfigured or fails.
 */
class FaceCheckIdScraperService {

    private val TAG = "FaceCheckIdScraper"
    private val FACECHECK_URL = "https://facecheck.id"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun identifyFace(imageBytes: ByteArray): FaceCheckIdService.Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping FaceCheck.ID for frame (${imageBytes.size} bytes)...")
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "images", "upload.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$FACECHECK_URL/upload")
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "FaceCheck.ID Scraper HTTP Error: ${response.code}")
                return@withContext null
            }

            val html = response.body?.string() ?: return@withContext null
            val document = Jsoup.parse(html)

            // Public results page renders match cards in a grid. Selectors are approximate
            // and will need adjustment if FaceCheck redesigns its frontend.
            val matchElement = document.select(".result-card, .face-result").first()
                ?: return@withContext null

            val imgUrl = matchElement.select("img").attr("src")
            val refLink = matchElement.select("a[href]").attr("href")
            val scoreText = matchElement.select(".score, .match-score").text()
            val confidence = scoreText.replace("%", "").trim().toFloatOrNull()?.div(100f) ?: 0.7f

            Log.d(TAG, "Scraped FaceCheck.ID match with confidence $confidence")
            FaceCheckIdService.Result(
                faceId = "facecheck_scraped_${System.currentTimeMillis()}",
                confidence = confidence,
                referenceImageUrl = refLink.ifEmpty { imgUrl }.let {
                    if (it.startsWith("http")) it else "$FACECHECK_URL$it"
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FaceCheck.ID scraping", e)
            null
        }
    }
}

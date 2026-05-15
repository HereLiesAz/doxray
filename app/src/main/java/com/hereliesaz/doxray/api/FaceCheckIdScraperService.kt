package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Non-API (anonymous) fallback for facecheck.id. Replays the same upload + poll
 * flow the website performs when a visitor uses the public demo: no Authorization
 * header, `demo=true` in the search payload. Results from this path are typically
 * watermarked / partially obfuscated by the service, but the reference URL and
 * match score are usable as weak signal for the rest of the pipeline.
 */
class FaceCheckIdScraperService {

    private val TAG = "FaceCheckIdScraper"
    private val FACECHECK_URL = "https://facecheck.id"
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val MAX_POLL_ATTEMPTS = 12
    private val POLL_INTERVAL_MS = 2500L

    private val client get() = HttpClients.browser()

    suspend fun identifyFace(imageBytes: ByteArray): FaceCheckIdService.Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scraping FaceCheck.ID demo flow for frame (${imageBytes.size} bytes)...")
        try {
            val idSearch = uploadAnonymously(imageBytes) ?: return@withContext null
            Log.d(TAG, "Anonymous upload accepted. id_search=$idSearch — polling demo results.")

            repeat(MAX_POLL_ATTEMPTS) {
                val result = pollDemo(idSearch)
                if (result != null) return@withContext result
                delay(POLL_INTERVAL_MS)
            }
            Log.w(TAG, "FaceCheck.ID demo polling timed out after $MAX_POLL_ATTEMPTS attempts.")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FaceCheck.ID scraping", e)
            null
        }
    }

    private fun uploadAnonymously(imageBytes: ByteArray): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "images", "frame.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("$FACECHECK_URL/api/upload_pic")
            .header("User-Agent", UA)
            .header("Referer", "$FACECHECK_URL/")
            .header("Origin", FACECHECK_URL)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "FaceCheck.ID anonymous upload HTTP error: ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optBoolean("error", false)) {
                Log.e(TAG, "FaceCheck.ID anonymous upload error: ${json.optString("message")}")
                return null
            }
            return json.optString("id_search").takeIf { it.isNotEmpty() }
        }
    }

    private fun pollDemo(idSearch: String): FaceCheckIdService.Result? {
        val payload = JSONObject().apply {
            put("id_search", idSearch)
            put("with_progress", true)
            put("status_only", false)
            put("demo", true)
        }
        val request = Request.Builder()
            .url("$FACECHECK_URL/api/search")
            .header("User-Agent", UA)
            .header("Referer", "$FACECHECK_URL/")
            .header("Origin", FACECHECK_URL)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "FaceCheck.ID demo search HTTP error: ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            if (json.optBoolean("error", false)) {
                Log.e(TAG, "FaceCheck.ID demo search error: ${json.optString("message")}")
                return null
            }

            val output = json.optJSONObject("output") ?: return null
            val items = output.optJSONArray("items") ?: return null
            if (items.length() == 0) return null

            val top = items.getJSONObject(0)
            val score = top.optInt("score", 0).coerceIn(0, 100) / 100f
            val refUrl = top.optString("url", "")
            val guid = top.optString("guid", "facecheck_scraped_${System.currentTimeMillis()}")

            Log.d(TAG, "Scraped FaceCheck.ID demo match with confidence $score from $refUrl")
            return FaceCheckIdService.Result(
                faceId = "scraped_$guid",
                confidence = score,
                referenceImageUrl = refUrl
            )
        }
    }
}

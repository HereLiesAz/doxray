package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Anonymous fallback for PimEyes. Same upload + poll flow without the
 * Authorization header. PimEyes' public demo returns watermarked / partial
 * results but the URL field is still populated, which is all the pipeline
 * needs to fan into the correlation tier.
 *
 * PHASE-3: anonymous endpoints may differ from the API path; refine after
 * CaptureInterceptor produces real traffic.
 */
class PimEyesScraperService {

    private val TAG = "PimEyesScraper"
    private val PIMEYES_URL = "https://pimeyes.com"

    private val client get() = HttpClients.browser()

    suspend fun identifyFace(imageBytes: ByteArray): PimEyesService.Result? = withContext(Dispatchers.IO) {
        try {
            val searchHash = uploadAnonymously(imageBytes) ?: return@withContext null
            Log.d(TAG, "Anonymous upload accepted. searchHash=$searchHash")
            pollResults(searchHash)
        } catch (e: Exception) {
            Log.e(TAG, "PimEyes scraper exception", e)
            null
        }
    }

    private fun uploadAnonymously(imageBytes: ByteArray): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$PIMEYES_URL/api/search/upload")
            .addHeader("Referer", "$PIMEYES_URL/")
            .addHeader("Origin", PIMEYES_URL)
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes anonymous upload HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            val json = try { JSONObject(text) } catch (e: Exception) { return null }
            return json.optString("search_hash").takeIf { it.isNotEmpty() }
        }
    }

    private fun pollResults(searchHash: String): PimEyesService.Result? {
        val payload = JSONObject().apply { put("searchHash", searchHash) }
        val request = Request.Builder()
            .url("$PIMEYES_URL/api/search/results")
            .addHeader("Referer", "$PIMEYES_URL/")
            .addHeader("Origin", PIMEYES_URL)
            .addHeader("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes anonymous results HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            return PimEyesResponseParser.parse(text)
        }
    }
}

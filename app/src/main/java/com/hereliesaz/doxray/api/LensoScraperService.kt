package com.hereliesaz.doxray.api

import android.util.Base64
import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Anonymous (no-API-key) fallback for Lenso. Tries two host candidates in
 * order — first the dedicated face endpoint, then the category endpoint.
 * Same JSON schema as the API path; results parsed by [LensoResponseParser].
 *
 * Lenso's public site uses a keyless preview tier; if neither host accepts
 * an anonymous request the call returns null and the recorded
 * `CaptureInterceptor` traffic should be used to iterate.
 */
class LensoScraperService {

    private val TAG = "LensoScraper"

    private val candidates = listOf(
        "https://api.eyematch.ai/search",
        "https://api.lenso.ai/search",
    )

    suspend fun identifyFace(imageBytes: ByteArray): LensoSearchService.Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Anonymous Lenso scrape of ${imageBytes.size} bytes...")
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply { put("image", base64Image) }
        val bodyJson = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())

        for (url in candidates) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Origin", "https://lenso.ai")
                    .addHeader("Referer", "https://lenso.ai/")
                    .post(bodyJson)
                    .build()
                HttpClients.browser().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Anonymous Lenso call to $url failed: ${response.code}")
                        return@use
                    }
                    val text = response.body?.string() ?: return@use
                    Log.d(TAG, "Anonymous Lenso response from $url: ${text.take(200)}")
                    val parsed = LensoResponseParser.parse(text)
                    if (parsed != null) return@withContext parsed
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception scraping $url", e)
            }
        }
        null
    }
}

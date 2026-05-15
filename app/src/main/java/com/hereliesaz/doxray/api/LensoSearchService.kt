package com.hereliesaz.doxray.api

import android.util.Base64
import android.util.Log
import com.hereliesaz.doxray.BuildConfig
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Lenso.ai face search via the documented `api.eyematch.ai/search` endpoint.
 * Response shape and error handling live in [LensoResponseParser].
 */
class LensoSearchService {

    private val TAG = "LensoSearchService"
    private val LENSO_API_KEY = BuildConfig.LENSO_KEY
    private val LENSO_FACE_HOST = "https://api.eyematch.ai"

    suspend fun identifyFace(imageBytes: ByteArray): Result? = withContext(Dispatchers.IO) {
        if (LENSO_API_KEY.isBlank()) {
            Log.w(TAG, "LENSO_KEY not configured; skipping Lenso API call (scraper fallback will run).")
            return@withContext null
        }
        Log.d(TAG, "Uploading frame to Lenso.ai (${imageBytes.size} bytes)...")

        try {
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val payload = JSONObject().apply { put("image", base64Image) }

            val request = Request.Builder()
                .url("$LENSO_FACE_HOST/search")
                .addHeader("Authorization", "Bearer $LENSO_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            HttpClients.api().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Lenso.ai HTTP Error: ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                Log.d(TAG, "Lenso.ai response: ${body.take(200)}")
                LensoResponseParser.parse(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network exception during Lenso.ai API call", e)
            null
        }
    }

    data class Result(
        val faceId: String,
        val confidence: Float,
        val referenceImageUrl: String,
        val sourceDomain: String,
    )
}

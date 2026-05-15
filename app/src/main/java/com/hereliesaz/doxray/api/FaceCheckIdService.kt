package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.BuildConfig
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
 * facecheck.id integration. Two-phase: upload the frame, then poll the search
 * endpoint until results or progress completes (or the budget expires).
 */
class FaceCheckIdService {

    private val TAG = "FaceCheckIdService"
    private val FACECHECK_API_KEY = BuildConfig.FACECHECK_KEY
    private val FACECHECK_HOST = "https://facecheck.id"

    // Polling budget for the asynchronous search
    private val MAX_POLL_ATTEMPTS = 10
    private val POLL_INTERVAL_MS = 2000L

    private val client get() = HttpClients.api()

    suspend fun identifyFace(imageBytes: ByteArray): Result? = withContext(Dispatchers.IO) {
        if (FACECHECK_API_KEY.isBlank()) {
            Log.w(TAG, "FACECHECK_KEY not configured; skipping FaceCheck.ID API call (scraper fallback will run).")
            return@withContext null
        }
        Log.d(TAG, "Uploading frame to FaceCheck.ID (${imageBytes.size} bytes)...")

        try {
            val idSearch = uploadImage(imageBytes) ?: return@withContext null
            Log.d(TAG, "Upload accepted. id_search=$idSearch — polling for results.")

            repeat(MAX_POLL_ATTEMPTS) { attempt ->
                val result = pollSearch(idSearch)
                if (result != null) return@withContext result
                delay(POLL_INTERVAL_MS)
            }
            Log.w(TAG, "FaceCheck.ID polling timed out after $MAX_POLL_ATTEMPTS attempts.")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Network exception during FaceCheck.ID API call", e)
            null
        }
    }

    private fun uploadImage(imageBytes: ByteArray): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "images", "frame.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("$FACECHECK_HOST/api/upload_pic")
            .addHeader("Authorization", FACECHECK_API_KEY)
            .addHeader("accept", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "FaceCheck.ID upload HTTP error: ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optBoolean("error", false)) {
                Log.e(TAG, "FaceCheck.ID upload error: ${json.optString("message")}")
                return null
            }
            return json.optString("id_search").takeIf { it.isNotEmpty() }
        }
    }

    private fun pollSearch(idSearch: String): Result? {
        val payload = JSONObject().apply {
            put("id_search", idSearch)
            put("with_progress", true)
            put("status_only", false)
            put("demo", false)
        }
        val request = Request.Builder()
            .url("$FACECHECK_HOST/api/search")
            .addHeader("Authorization", FACECHECK_API_KEY)
            .addHeader("accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "FaceCheck.ID search HTTP error: ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            if (json.optBoolean("error", false)) {
                Log.e(TAG, "FaceCheck.ID search error: ${json.optString("message")}")
                return null
            }

            val output = json.optJSONObject("output") ?: return null
            val items = output.optJSONArray("items") ?: return null
            if (items.length() == 0) return null

            val top = items.getJSONObject(0)
            // FaceCheck returns score 0..100; normalise to 0..1
            val score = top.optInt("score", 0).coerceIn(0, 100) / 100f
            val refUrl = top.optString("url", "")
            val guid = top.optString("guid", "facecheck_${System.currentTimeMillis()}")
            return Result(
                faceId = guid,
                confidence = score,
                referenceImageUrl = refUrl
            )
        }
    }

    data class Result(
        val faceId: String,
        val confidence: Float,
        val referenceImageUrl: String,
    )
}

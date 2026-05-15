package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.BuildConfig
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Actual implementation of FaceSeek API integration.
 */
class FaceSeekService {

    private val TAG = "FaceSeekService"
    private val FACESEEK_API_KEY = BuildConfig.FACESEEK_KEY
    private val FACESEEK_HOST = "https://api.faceseek.online"

    // PHASE-0: real FaceSeek flow unknown; CaptureInterceptor will record
    // production traffic so we can rewrite this against captures.
    private val client get() = HttpClients.api()

    /**
     * Uploads a frame to FaceSeek using multipart/form-data.
     */
    suspend fun identifyFace(imageBytes: ByteArray): Result? = withContext(Dispatchers.IO) {
        if (FACESEEK_API_KEY.isBlank()) {
            Log.w(TAG, "FACESEEK_KEY not configured; skipping FaceSeek API call (scraper fallback will run).")
            return@withContext null
        }
        Log.d(TAG, "Uploading frame to FaceSeek (${imageBytes.size} bytes)...")

        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("token", FACESEEK_API_KEY)
                .addFormDataPart(
                    "image", "frame.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$FACESEEK_HOST/search_face")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseData = response.body?.string()
                Log.d(TAG, "FaceSeek Response: $responseData")
                
                if (responseData != null) {
                    val json = JSONObject(responseData)
                    // Assuming FaceSeek returns an array of matches or a single top match
                    // This parsing logic needs to match exact FaceSeek response schema
                    if (json.has("matches")) {
                        val matches = json.getJSONArray("matches")
                        if (matches.length() > 0) {
                            val topMatch = matches.getJSONObject(0)
                            return@withContext Result(
                                faceId = topMatch.optString("face_id", "unknown"),
                                confidence = topMatch.optDouble("confidence", 0.0).toFloat(),
                                referenceImageUrl = topMatch.optString("url", "")
                            )
                        }
                    }
                }
            } else {
                Log.e(TAG, "FaceSeek HTTP Error: ${response.code} - ${response.body?.string()}")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Network exception during FaceSeek API call", e)
            null
        }
    }

    data class Result(
        val faceId: String, 
        val confidence: Float,
        val referenceImageUrl: String
    )
}

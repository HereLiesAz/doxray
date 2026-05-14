package com.hereliesaz.doxray.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Actual implementation of FaceSeek API integration.
 */
class FaceSeekService {

    private val TAG = "FaceSeekService"
    // TODO: Securely inject this via BuildConfig in a production environment
    private val FACESEEK_API_KEY = "YOUR_FACESEEK_API_KEY"
    private val FACESEEK_HOST = "https://api.faceseek.online"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads a frame to FaceSeek using multipart/form-data.
     */
    suspend fun identifyFace(imageBytes: ByteArray): Result? = withContext(Dispatchers.IO) {
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

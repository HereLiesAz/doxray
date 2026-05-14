package com.hereliesaz.doxray.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Actual implementation of Lenso.ai (eyematch.ai) API integration for person searching.
 */
class LensoSearchService {

    private val TAG = "LensoSearchService"
    // TODO: Securely inject this via BuildConfig in a production environment
    private val LENSO_API_KEY = "YOUR_LENSO_API_KEY"
    private val LENSO_FACE_HOST = "https://api.eyematch.ai" // Eyematch is Lenso's facial search endpoint

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads a frame to Lenso/Eyematch using base64 JSON payload as per their documentation.
     */
    suspend fun identifyFace(imageBytes: ByteArray): Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Uploading frame to Lenso.ai (${imageBytes.size} bytes)...")
        
        try {
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            
            val jsonPayload = JSONObject().apply {
                put("image", base64Image)
                // Other parameters like limit, domains, etc. can be added here
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$LENSO_FACE_HOST/search")
                .addHeader("Authorization", "Bearer $LENSO_API_KEY")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseData = response.body?.string()
                Log.d(TAG, "Lenso.ai Response: $responseData")
                
                if (responseData != null) {
                    val json = JSONObject(responseData)
                    // Lenso typically returns a 'results' array
                    if (json.has("results")) {
                        val results = json.getJSONArray("results")
                        if (results.length() > 0) {
                            val topMatch = results.getJSONObject(0)
                            val identityUrl = topMatch.optString("url", "")
                            val siteName = topMatch.optString("domain", "Unknown Site")
                            
                            // Return the result
                            return@withContext Result(
                                faceId = topMatch.optString("id", "lenso_unknown"),
                                confidence = topMatch.optDouble("similarity", 0.0).toFloat(), // Assuming similarity is returned
                                referenceImageUrl = identityUrl,
                                sourceDomain = siteName
                            )
                        }
                    }
                }
            } else {
                Log.e(TAG, "Lenso.ai HTTP Error: ${response.code} - ${response.body?.string()}")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Network exception during Lenso.ai API call", e)
            null
        }
    }

    data class Result(
        val faceId: String, 
        val confidence: Float,
        val referenceImageUrl: String,
        val sourceDomain: String
    )
}

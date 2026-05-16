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
 * pimeyes.com face-search integration via their documented two-step
 * upload + poll flow.
 *
 *   1) POST /api/search/upload  (multipart "image") -> { search_hash }
 *   2) POST /api/search/results (json { searchHash }) -> { results: [...] }
 *
 * Both calls authenticate with Authorization: Bearer $PIMEYES_KEY.
 *
 * PHASE-3: Endpoint paths and field names are taken from PimEyes public
 * documentation. Real-device captures may reveal differences; refine via
 * CaptureInterceptor output if needed.
 */
class PimEyesService {

    private val TAG = "PimEyesService"
    private val PIMEYES_API_KEY = BuildConfig.PIMEYES_KEY
    private val PIMEYES_HOST = "https://pimeyes.com"

    private val client get() = HttpClients.api()

    data class Result(
        val faceId: String,
        val confidence: Float,
        val referenceImageUrl: String,
    )

    suspend fun identifyFace(imageBytes: ByteArray): Result? = withContext(Dispatchers.IO) {
        if (PIMEYES_API_KEY.isBlank()) {
            Log.w(TAG, "PIMEYES_KEY not configured; skipping PimEyes API call.")
            return@withContext null
        }
        try {
            val searchHash = uploadImage(imageBytes) ?: return@withContext null
            Log.d(TAG, "Upload accepted. searchHash=$searchHash")
            pollResults(searchHash)
        } catch (e: Exception) {
            Log.e(TAG, "PimEyes API exception", e)
            null
        }
    }

    private fun uploadImage(imageBytes: ByteArray): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$PIMEYES_HOST/api/search/upload")
            .addHeader("Authorization", "Bearer $PIMEYES_API_KEY")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes upload HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            val json = try { JSONObject(text) } catch (e: Exception) { return null }
            return json.optString("search_hash").takeIf { it.isNotEmpty() }
        }
    }

    private fun pollResults(searchHash: String): Result? {
        val payload = JSONObject().apply { put("searchHash", searchHash) }
        val request = Request.Builder()
            .url("$PIMEYES_HOST/api/search/results")
            .addHeader("Authorization", "Bearer $PIMEYES_API_KEY")
            .addHeader("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "PimEyes results HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            return PimEyesResponseParser.parse(text)
        }
    }
}

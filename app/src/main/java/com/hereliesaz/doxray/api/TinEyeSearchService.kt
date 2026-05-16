package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.BuildConfig
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TinEye reverse-image search via the documented /rest/search/ endpoint with
 * HMAC-SHA256 request signing.
 *
 * Auth: public key (TINEYE_KEY) + private key (TINEYE_SECRET). Both come from
 * BuildConfig. Signing follows TinEye's canonical-string-of-sorted-params
 * scheme. If either key is blank the service no-ops and the scraper fallback
 * handles the call.
 *
 * PHASE-3: signing scheme is best-effort based on public docs. May need
 * adjustment after CaptureInterceptor records a real signed request.
 */
class TinEyeSearchService {

    private val TAG = "TinEyeSearchService"
    private val TINEYE_KEY = BuildConfig.TINEYE_KEY
    private val TINEYE_SECRET = BuildConfig.TINEYE_SECRET
    private val TINEYE_HOST = "https://api.tineye.com"
    private val ENDPOINT_PATH = "/rest/search/"

    private val client get() = HttpClients.api()

    data class Result(
        val identities: List<String>,
        val socialLinks: List<String>,
    )

    suspend fun searchIdentity(imageUrl: String): Result? = withContext(Dispatchers.IO) {
        if (TINEYE_KEY.isBlank() || TINEYE_SECRET.isBlank()) {
            Log.w(TAG, "TINEYE_KEY/SECRET not configured; skipping TinEye API call.")
            return@withContext null
        }
        try {
            val nonce = (System.currentTimeMillis().toString() +
                Math.random().toString().substring(2, 10))
            val date = (System.currentTimeMillis() / 1000L).toString()

            val params = sortedMapOf(
                "image_url" to imageUrl,
                "nonce" to nonce,
                "date" to date,
                "api_key" to TINEYE_KEY,
            )
            val signature = sign("POST", ENDPOINT_PATH, params)
            params["api_sig"] = signature

            val formBody = params
                .map { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
                .joinToString("&")

            val request = Request.Builder()
                .url("$TINEYE_HOST$ENDPOINT_PATH")
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "TinEye HTTP ${response.code}")
                    return@withContext null
                }
                val text = response.body?.string() ?: return@withContext null
                TinEyeResponseParser.parse(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TinEye API exception", e)
            null
        }
    }

    /**
     * HMAC-SHA256 over canonical string: METHOD + ENDPOINT_PATH + sorted-encoded-params.
     * The result is base64-encoded.
     */
    private fun sign(method: String, path: String, params: Map<String, String>): String {
        val canonical = buildString {
            append(method)
            append(path)
            params.entries
                .sortedBy { it.key }
                .joinTo(this, separator = "&", prefix = "?") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TINEYE_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }
}

package com.hereliesaz.doxray.api

import android.util.Log
import com.hereliesaz.doxray.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class GitHubProbeService {

    private val TAG = "GitHubProbe"
    private val GITHUB_URL = "https://github.com"

    private val client get() = HttpClients.browser()

    data class Profile(
        val username: String,
        val bio: String,
        val followers: Int,
        val publicRepos: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("username", username)
            .put("bio", bio)
            .put("followers", followers)
            .put("publicRepos", publicRepos)
    }

    data class Result(val profiles: List<Profile>) {
        fun toJson(): JSONObject = JSONObject()
            .put("profiles", JSONArray(profiles.map { it.toJson() }))
    }

    suspend fun probe(name: String): Result? = withContext(Dispatchers.IO) {
        val variants = buildVariants(name)
        if (variants.isEmpty()) return@withContext null
        try {
            coroutineScope {
                val profiles = variants
                    .map { variant -> async { probeOne(variant) } }
                    .awaitAll()
                    .filterNotNull()
                if (profiles.isEmpty()) null else Result(profiles)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub probe exception", e)
            null
        }
    }

    private fun probeOne(username: String): Profile? {
        val request = Request.Builder()
            .url("$GITHUB_URL/$username")
            .addHeader("Referer", "$GITHUB_URL/")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code != 404) {
                    Log.w(TAG, "GitHub probe $username HTTP ${response.code}")
                }
                null
            } else {
                val html = response.body?.string().orEmpty()
                GitHubProbeParser.parse(html, username)
            }
        }
    }

    private fun buildVariants(name: String): List<String> {
        val cleaned = name.trim().lowercase()
        if (cleaned.isEmpty()) return emptyList()
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size == 1) return listOf(words[0])
        val first = words.first()
        val last = words.last()
        return listOf(
            "$first$last",
            "$first-$last",
            "${first}_$last",
            "${first.first()}$last",
        )
    }
}

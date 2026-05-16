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
import java.net.URLEncoder

class WaybackMachineService {

    private val TAG = "WaybackMachine"
    private val WAYBACK_HOST = "https://archive.org"

    private val client get() = HttpClients.api()

    data class Snapshot(
        val originalUrl: String,
        val archiveUrl: String,
        val timestamp: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("originalUrl", originalUrl)
            .put("archiveUrl", archiveUrl)
            .put("timestamp", timestamp)
    }

    data class Result(val snapshots: List<Snapshot>) {
        fun toJson(): JSONObject = JSONObject()
            .put("snapshots", JSONArray(snapshots.map { it.toJson() }))
    }

    suspend fun snapshotAll(urls: List<String>): Result? = withContext(Dispatchers.IO) {
        val cleaned = urls.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return@withContext null
        try {
            coroutineScope {
                val snaps = cleaned
                    .map { url -> async { snapshotOne(url) } }
                    .awaitAll()
                    .filterNotNull()
                if (snaps.isEmpty()) null else Result(snaps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wayback exception", e)
            null
        }
    }

    private fun snapshotOne(originalUrl: String): Snapshot? {
        val q = URLEncoder.encode(originalUrl, "UTF-8")
        val request = Request.Builder()
            .url("$WAYBACK_HOST/wayback/available?url=$q")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Wayback HTTP ${response.code} for $originalUrl")
                null
            } else {
                val body = response.body?.string().orEmpty()
                WaybackMachineParser.parse(body, originalUrl)
            }
        }
    }
}

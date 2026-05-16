package com.hereliesaz.doxray.api

import org.json.JSONObject

object WaybackMachineParser {

    data class Snapshot(
        val originalUrl: String,
        val archiveUrl: String,
        val timestamp: String,
    )

    fun parse(jsonBody: String, originalUrl: String): Snapshot? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val snapshots = json.optJSONObject("archived_snapshots") ?: return null
        val closest = snapshots.optJSONObject("closest") ?: return null
        val archiveUrl = closest.optString("url", "")
        val timestamp = closest.optString("timestamp", "")
        if (archiveUrl.isEmpty()) return null
        return Snapshot(
            originalUrl = originalUrl,
            archiveUrl = archiveUrl,
            timestamp = timestamp,
        )
    }
}

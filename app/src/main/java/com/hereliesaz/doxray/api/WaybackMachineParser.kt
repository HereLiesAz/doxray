package com.hereliesaz.doxray.api

import org.json.JSONObject

object WaybackMachineParser {

    fun parse(jsonBody: String, originalUrl: String): WaybackMachineService.Snapshot? {
        if (jsonBody.isBlank()) return null
        val json = try { JSONObject(jsonBody) } catch (e: Exception) { return null }
        val snapshots = json.optJSONObject("archived_snapshots") ?: return null
        val closest = snapshots.optJSONObject("closest") ?: return null
        val archiveUrl = closest.optString("url", "")
        val timestamp = closest.optString("timestamp", "")
        if (archiveUrl.isEmpty()) return null
        return WaybackMachineService.Snapshot(
            originalUrl = originalUrl,
            archiveUrl = archiveUrl,
            timestamp = timestamp,
        )
    }
}

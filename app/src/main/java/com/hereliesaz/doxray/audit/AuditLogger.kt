package com.hereliesaz.doxray.audit

import com.hereliesaz.doxray.db.AuditDao
import com.hereliesaz.doxray.db.AuditEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fire-and-forget audit log. Initialised once from Application startup with
 * the production [AuditDao]; all subsequent [log] calls are non-blocking and
 * never throw.
 *
 * Logging before [init] is a no-op (events are dropped silently). This
 * matches the rest of the codebase where Application.onCreate is the
 * happens-before boundary.
 */
object AuditLogger {
    enum class Type { IDENTIFY, API_CALL, DOSSIER_READ, LIFECYCLE }

    private var dao: AuditDao? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(dao: AuditDao) { this.dao = dao }

    fun log(type: Type, summary: String, details: JSONObject = JSONObject()) {
        val current = dao ?: return
        val event = AuditEvent(
            timestamp = System.currentTimeMillis(),
            type = type.name,
            summary = summary,
            detailsJson = details.toString(),
        )
        scope.launch { runCatching { current.insert(event) } }
    }
}

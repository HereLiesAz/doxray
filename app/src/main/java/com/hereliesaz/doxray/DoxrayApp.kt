package com.hereliesaz.doxray

import android.app.Application
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.net.HttpClients

class DoxrayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HttpClients.init(this)
        val db = AppDatabase.getDatabase(this)
        AuditLogger.init(db.auditDao())
        AuditLogger.log(AuditLogger.Type.LIFECYCLE, "App started")
    }
}

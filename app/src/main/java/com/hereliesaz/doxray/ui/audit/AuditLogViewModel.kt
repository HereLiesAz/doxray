package com.hereliesaz.doxray.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.db.AuditDao
import com.hereliesaz.doxray.db.AuditEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AuditLogViewModel(auditDao: AuditDao) : ViewModel() {
    val events: StateFlow<List<AuditEvent>> = auditDao.observeRecent(limit = 200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

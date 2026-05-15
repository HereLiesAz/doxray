package com.hereliesaz.doxray.ui.dossier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.db.IdentityDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DossierRow(
    val faceId: String,
    val name: String,
    val encounterCount: Int,
    val lastSeenMillis: Long,
)

data class DossierListUiState(val rows: List<DossierRow> = emptyList())

class DossierListViewModel(identityDao: IdentityDao) : ViewModel() {
    val state: StateFlow<DossierListUiState> = identityDao.observeAll()
        .map { records ->
            DossierListUiState(rows = records.map { r ->
                DossierRow(
                    faceId = r.faceId,
                    name = r.primaryIdentity,
                    encounterCount = r.encounterCount,
                    lastSeenMillis = r.lastSeenTimestamp,
                )
            })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DossierListUiState())
}

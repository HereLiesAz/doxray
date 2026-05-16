package com.hereliesaz.doxray.ui.dossier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.Encounter
import com.hereliesaz.doxray.db.EncounterDao
import com.hereliesaz.doxray.db.IdentityDao
import com.hereliesaz.doxray.db.IdentityRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class DossierDetailUiState(
    val identity: IdentityRecord? = null,
    val encounters: List<Encounter> = emptyList(),
    val socialLinks: List<String> = emptyList(),
    val backgroundData: JSONObject = JSONObject(),
    val anchorBytes: ByteArray? = null,
)

class DossierDetailViewModel(
    private val faceId: String,
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val anchorImageDao: com.hereliesaz.doxray.db.AnchorImageDao,
) : ViewModel() {

    private val _state = MutableStateFlow(DossierDetailUiState())
    val state: StateFlow<DossierDetailUiState> = _state

    init {
        AuditLogger.log(
            AuditLogger.Type.DOSSIER_READ,
            summary = "Opened dossier $faceId",
            details = JSONObject().put("faceId", faceId),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val identity = identityDao.getIdentityById(faceId)
            val socialLinks = identity?.socialLinks
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val backgroundData = runCatching { JSONObject(identity?.backgroundData ?: "{}") }
                .getOrElse { JSONObject() }
            val anchor = anchorImageDao.getByFaceId(faceId)?.imageBytes
            _state.value = _state.value.copy(
                identity = identity,
                socialLinks = socialLinks,
                backgroundData = backgroundData,
                anchorBytes = anchor,
            )
        }
        viewModelScope.launch {
            encounterDao.observeByFace(faceId).collect { encounters ->
                _state.value = _state.value.copy(encounters = encounters)
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            identityDao.delete(faceId)
            onDeleted()
        }
    }
}

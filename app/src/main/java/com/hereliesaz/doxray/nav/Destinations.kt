package com.hereliesaz.doxray.nav

object Destinations {
    const val LIVE = "live"
    const val DOSSIERS = "dossiers"
    const val DOSSIER_DETAIL = "dossiers/{faceId}"
    const val AUDIT = "audit"
    fun dossierDetail(faceId: String) = "dossiers/$faceId"
}

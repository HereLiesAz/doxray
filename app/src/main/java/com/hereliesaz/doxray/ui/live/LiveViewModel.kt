package com.hereliesaz.doxray.ui.live

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.doxray.api.CyberBackgroundChecksScraper
import com.hereliesaz.doxray.api.EmbeddingGenerator
import com.hereliesaz.doxray.api.FaceCheckIdScraperService
import com.hereliesaz.doxray.api.FaceCheckIdService
import com.hereliesaz.doxray.api.FaceSeekScraperService
import com.hereliesaz.doxray.api.FaceSeekService
import com.hereliesaz.doxray.api.FaceTrackerManager
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.LocalFaceCache
import com.hereliesaz.doxray.api.SmartBackgroundChecksScraper
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.location.LocationService
import com.hereliesaz.doxray.meta.MetaGlassesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class LiveUiState(
    val isConnected: Boolean = false,
    val logLines: List<String> = emptyList(),
)

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "LiveViewModel"

    private val metaGlassesManager = MetaGlassesManager(application)
    private val embeddingGenerator = EmbeddingGenerator(application)
    private val appDatabase = AppDatabase.getDatabase(application)
    private val localFaceCache = LocalFaceCache(
        identityDao = appDatabase.identityDao(),
        encounterDao = appDatabase.encounterDao(),
        locationService = LocationService(application),
    )

    private val faceSeekService = FaceSeekService()
    private val yandexSearchService = YandexSearchService()
    private val lensoSearchService = LensoSearchService()
    private val faceCheckIdService = FaceCheckIdService()
    private val faceSeekScraper = FaceSeekScraperService()
    private val yandexScraper = YandexScraperService()
    private val lensoScraper = LensoScraperService()
    private val faceCheckIdScraper = FaceCheckIdScraperService()
    private val smartBgScraper = SmartBackgroundChecksScraper()
    private val cyberBgScraper = CyberBackgroundChecksScraper()
    private val faceTrackerManager = FaceTrackerManager()
    private val activeInvestigations = ConcurrentHashMap<Int, Job>()

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state

    init {
        viewModelScope.launch { localFaceCache.loadFromDatabase() }
    }

    fun connect() {
        appendLog("Attempting connection...")
        AuditLogger.log(AuditLogger.Type.LIFECYCLE, "Connect requested")
        try {
            metaGlassesManager.connect()
            _state.value = _state.value.copy(isConnected = true)
            appendLog("Connected successfully.")
            AuditLogger.log(AuditLogger.Type.LIFECYCLE, "Glasses connected")

            metaGlassesManager.startVideoStream(object : MetaGlassesManager.FrameListener {
                override fun onFrameReceived(imageBytes: ByteArray) {
                    faceTrackerManager.processFrame(imageBytes, object : FaceTrackerManager.FaceFocusListener {
                        override fun onFaceFocused(focusedImageBytes: ByteArray, trackingId: Int, faceCrop: ByteArray) {
                            if (activeInvestigations.containsKey(trackingId)) return
                            appendLog("Target acquired (ID: $trackingId). Processing search...")
                            val job = viewModelScope.launch {
                                processFocusedFace(focusedImageBytes, faceCrop, trackingId)
                            }
                            activeInvestigations[trackingId] = job
                        }

                        override fun onFaceLost(trackingId: Int) {
                            val job = activeInvestigations.remove(trackingId)
                            if (job != null && job.isActive) {
                                appendLog("Target lost (ID: $trackingId). Halting active investigation.")
                                job.cancel()
                            }
                        }

                        override fun onError(e: Exception) {
                            Log.e(TAG, "Face tracking error", e)
                        }
                    })
                }

                override fun onError(error: Throwable) {
                    appendLog("Stream Error: ${error.message}")
                }
            })
        } catch (e: Exception) {
            _state.value = _state.value.copy(isConnected = false)
            appendLog("Connection failed: ${e.message}")
        }
    }

    fun disconnect() {
        metaGlassesManager.stopVideoStream()
        metaGlassesManager.disconnect()
        _state.value = _state.value.copy(isConnected = false)
        appendLog("Disconnected from glasses.")
        AuditLogger.log(AuditLogger.Type.LIFECYCLE, "Glasses disconnected")
    }

    override fun onCleared() {
        super.onCleared()
        metaGlassesManager.stopVideoStream()
        metaGlassesManager.disconnect()
    }

    private suspend fun processFocusedFace(imageBytes: ByteArray, faceCrop: ByteArray, trackingId: Int) {
        try {
            val embedding = embeddingGenerator.generateEmbedding(faceCrop)
            val cachedMatch = localFaceCache.findMatch(embedding)
            if (cachedMatch != null) {
                appendLog("Cached Match: ${cachedMatch.primaryIdentity}. Encounters: ${cachedMatch.encounterCount}.")
                appendLog("Known Links: ${cachedMatch.socialLinks}")
                metaGlassesManager.playAudioMessage("Match found: ${cachedMatch.primaryIdentity}. Previous encounters: ${cachedMatch.encounterCount}")
                if (cachedMatch.backgroundData == "{}" || cachedMatch.backgroundData.isEmpty()) {
                    metaGlassesManager.playAudioMessage("Resuming background investigation.")
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","))
                }
                return
            }

            var primaryIdentity = ""
            var socialLinks = listOf<String>()
            var referenceImageUrl = ""
            var faceId = ""

            var lensoResult = lensoSearchService.identifyFace(imageBytes)
            if (lensoResult == null) {
                appendLog("Lenso API failed, trying scraper fallback...")
                lensoResult = lensoScraper.identifyFace(imageBytes)
            }
            if (lensoResult != null && lensoResult.confidence > 0.6f) {
                appendLog("Lenso face matched from domain: ${lensoResult.sourceDomain}")
                referenceImageUrl = lensoResult.referenceImageUrl
                faceId = lensoResult.faceId
            } else {
                var faceResult = faceSeekService.identifyFace(imageBytes)
                if (faceResult == null) {
                    appendLog("FaceSeek API failed, trying scraper fallback...")
                    faceResult = faceSeekScraper.identifyFace(imageBytes)
                }
                if (faceResult != null && faceResult.confidence > 0.6f) {
                    appendLog("FaceSeek matched! ID: ${faceResult.faceId}")
                    referenceImageUrl = faceResult.referenceImageUrl
                    faceId = faceResult.faceId
                } else {
                    var faceCheckResult = faceCheckIdService.identifyFace(imageBytes)
                    if (faceCheckResult == null) {
                        appendLog("FaceCheck.ID API failed, trying scraper fallback...")
                        faceCheckResult = faceCheckIdScraper.identifyFace(imageBytes)
                    }
                    if (faceCheckResult != null && faceCheckResult.confidence > 0.6f) {
                        appendLog("FaceCheck.ID matched! ID: ${faceCheckResult.faceId}")
                        referenceImageUrl = faceCheckResult.referenceImageUrl
                        faceId = faceCheckResult.faceId
                    }
                }
            }

            if (referenceImageUrl.isNotEmpty()) {
                metaGlassesManager.playAudioMessage("Face matched. Correlating identity...")
                var identityResult = yandexSearchService.searchIdentity(referenceImageUrl)
                if (identityResult == null) {
                    appendLog("Yandex API failed, trying scraper fallback...")
                    identityResult = yandexScraper.searchIdentity(referenceImageUrl)
                }
                if (identityResult != null && identityResult.identities.isNotEmpty()) {
                    primaryIdentity = identityResult.identities.first()
                    socialLinks = identityResult.socialLinks
                }
            } else {
                metaGlassesManager.playAudioMessage("No confident face match found.")
            }

            if (primaryIdentity.isNotEmpty()) {
                appendLog("Identity correlated: $primaryIdentity")
                appendLog("Links: ${socialLinks.joinToString(", ")}")
                metaGlassesManager.playAudioMessage("Identity correlated: $primaryIdentity")
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks)
            } else if (referenceImageUrl.isNotEmpty()) {
                appendLog("No online identity correlation found.")
                metaGlassesManager.playAudioMessage("No online identity found.")
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Investigation for ID $trackingId was cancelled.")
        } catch (e: Exception) {
            appendLog("Pipeline Exception: ${e.message}")
        } finally {
            activeInvestigations.remove(trackingId)
        }
    }

    private suspend fun performDeepBackgroundScrape(
        primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>,
    ) {
        metaGlassesManager.playAudioMessage("Digging for background data.")
        appendLog("Digging for deep background info on: $primaryIdentity...")
        val bgDataJson = JSONObject()
        val smartData = smartBgScraper.searchBackground(primaryIdentity)
        if (smartData != null) {
            bgDataJson.put("smart", smartData)
            val phonesCount = smartData.optJSONArray("phones")?.length() ?: 0
            if (phonesCount > 0) {
                metaGlassesManager.playAudioMessage("Found $phonesCount phone numbers.")
                appendLog("Extracted phone numbers.")
            }
        }
        val cyberData = cyberBgScraper.searchBackground(primaryIdentity)
        if (cyberData != null) {
            bgDataJson.put("cyber", cyberData)
            val emailsCount = cyberData.optJSONArray("emails")?.length() ?: 0
            if (emailsCount > 0) {
                metaGlassesManager.playAudioMessage("Found $emailsCount email addresses.")
                appendLog("Extracted email addresses.")
            }
        }
        localFaceCache.cacheIdentity(
            faceId = faceId, embedding = embedding,
            primaryIdentity = primaryIdentity, socialLinks = socialLinks,
            backgroundData = bgDataJson.toString(),
        )
        if (bgDataJson.length() > 0) {
            metaGlassesManager.playAudioMessage("Investigation complete. Dossier saved.")
        } else {
            metaGlassesManager.playAudioMessage("No additional offline data found.")
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLines = listOf("[$time] $message") + _state.value.logLines
        _state.value = _state.value.copy(logLines = newLines.take(500))
    }
}

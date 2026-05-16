package com.hereliesaz.doxray.ui.live

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Rect
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
import com.hereliesaz.doxray.api.GitHubProbeService
import com.hereliesaz.doxray.api.GoogleLensScraperService
import com.hereliesaz.doxray.api.GoogleLensSearchService
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.LocalFaceCache
import com.hereliesaz.doxray.api.OcrService
import com.hereliesaz.doxray.api.PimEyesScraperService
import com.hereliesaz.doxray.api.PimEyesService
import com.hereliesaz.doxray.api.SerpApiSiteSearchService
import com.hereliesaz.doxray.api.SmartBackgroundChecksScraper
import com.hereliesaz.doxray.api.TinEyeScraperService
import com.hereliesaz.doxray.api.TinEyeSearchService
import com.hereliesaz.doxray.api.WaybackMachineService
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.identify.IdentifyPipeline
import com.hereliesaz.doxray.location.LocationService
import com.hereliesaz.doxray.meta.MetaGlassesManager
import com.hereliesaz.doxray.quality.FaceQualityScorer
import com.hereliesaz.doxray.quality.QualityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
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
    private val ocrService = OcrService()
    private val appDatabase = AppDatabase.getDatabase(application)
    private val localFaceCache = LocalFaceCache(
        identityDao = appDatabase.identityDao(),
        encounterDao = appDatabase.encounterDao(),
        locationService = LocationService(application),
    )

    private val smartBgScraper = SmartBackgroundChecksScraper()
    private val cyberBgScraper = CyberBackgroundChecksScraper()
    private val serpApiSiteSearch = SerpApiSiteSearchService()
    private val gitHubProbe = GitHubProbeService()
    private val waybackMachine = WaybackMachineService()
    private val identifyPipeline = IdentifyPipeline(
        lensoService = LensoSearchService(),
        lensoScraper = LensoScraperService(),
        faceSeekService = FaceSeekService(),
        faceSeekScraper = FaceSeekScraperService(),
        faceCheckService = FaceCheckIdService(),
        faceCheckScraper = FaceCheckIdScraperService(),
        pimEyesService = PimEyesService(),
        pimEyesScraper = PimEyesScraperService(),
        yandexService = YandexSearchService(),
        yandexScraper = YandexScraperService(),
        tinEyeService = TinEyeSearchService(),
        tinEyeScraper = TinEyeScraperService(),
        googleLensService = GoogleLensSearchService(),
        googleLensScraper = GoogleLensScraperService(),
    )
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
                        override fun onFaceFocused(
                            imageBytes: ByteArray,
                            trackingId: Int,
                            faceCrop: ByteArray,
                            faceBbox: Rect,
                            eulerX: Float,
                            eulerY: Float,
                            eulerZ: Float,
                        ) {
                            if (activeInvestigations.containsKey(trackingId)) return
                            appendLog("Target acquired (ID: $trackingId). Processing search...")
                            val job = viewModelScope.launch {
                                processFocusedFace(imageBytes, faceCrop, faceBbox, trackingId, eulerX, eulerY, eulerZ)
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

    private suspend fun processFocusedFace(
        imageBytes: ByteArray,
        faceCrop: ByteArray,
        faceBbox: Rect,
        trackingId: Int,
        eulerX: Float,
        eulerY: Float,
        eulerZ: Float,
    ) {
        try {
            // [GATE 2] Face quality
            val cropBitmap = BitmapFactory.decodeByteArray(faceCrop, 0, faceCrop.size)
            val frameBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (cropBitmap == null || frameBitmap == null) {
                appendLog("Quality gate: bitmap decode failed for ID $trackingId; skipping.")
                return
            }
            val faceFrac = (cropBitmap.width.toFloat() * cropBitmap.height) /
                (frameBitmap.width.toFloat() * frameBitmap.height)
            val quality = FaceQualityScorer.scoreFromBitmap(
                bitmap = cropBitmap,
                faceFraction = faceFrac,
                eulerX = eulerX,
                eulerY = eulerY,
                eulerZ = eulerZ,
            )
            if (quality is QualityResult.Fail) {
                appendLog("Low-quality crop (ID: $trackingId): ${quality.reasons.joinToString(", ")}")
                AuditLogger.log(
                    AuditLogger.Type.REJECTED,
                    summary = "Low-quality crop skipped",
                    details = JSONObject().apply {
                        put("reason", "quality")
                        put("trackingId", trackingId)
                        put("failures", JSONArray(quality.reasons))
                    },
                )
                return
            }
            val embedding = embeddingGenerator.generateEmbedding(faceCrop)
            val ocrResult = ocrService.extract(imageBytes, faceBbox)
            if (ocrResult != null) {
                appendLog("Visible text: \"${ocrResult.primaryLine}\"")
            }
            val cachedMatch = localFaceCache.findMatch(embedding)
            if (cachedMatch != null) {
                appendLog("Cached Match: ${cachedMatch.primaryIdentity}. Encounters: ${cachedMatch.encounterCount}.")
                appendLog("Known Links: ${cachedMatch.socialLinks}")
                metaGlassesManager.playAudioMessage("Match found: ${cachedMatch.primaryIdentity}. Previous encounters: ${cachedMatch.encounterCount}")
                if (cachedMatch.backgroundData == "{}" || cachedMatch.backgroundData.isEmpty()) {
                    metaGlassesManager.playAudioMessage("Resuming background investigation.")
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","), ocrResult)
                }
                return
            }

            val faceHits = identifyPipeline.runFaceTier(imageBytes)
            if (faceHits.isEmpty()) {
                metaGlassesManager.playAudioMessage("No confident face match found.")
                return
            }
            appendLog("Face matched on ${faceHits.size} provider(s); fanning correlation.")
            metaGlassesManager.playAudioMessage("Face matched. Correlating identity...")

            val merged = coroutineScope {
                faceHits
                    .map { hit -> async { identifyPipeline.runCorrelationTier(hit.referenceUrl) } }
                    .awaitAll()
                    .fold(IdentifyPipeline.CorrelationHit(emptyList(), emptyList())) { acc, r ->
                        IdentifyPipeline.CorrelationHit(
                            identities = (acc.identities + r.identities).distinct(),
                            socialLinks = (acc.socialLinks + r.socialLinks).distinct(),
                        )
                    }
            }

            val primaryIdentity = merged.identities.firstOrNull().orEmpty()
            val socialLinks = merged.socialLinks
            val faceId = faceHits.maxByOrNull { it.confidence }!!.faceId

            if (primaryIdentity.isNotEmpty()) {
                appendLog("Identity correlated: $primaryIdentity")
                appendLog("Links: ${socialLinks.joinToString(", ")}")
                metaGlassesManager.playAudioMessage("Identity correlated: $primaryIdentity")
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks, ocrResult)
            } else {
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
        ocrResult: OcrService.OcrResult?,
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

        // OSINT enrichment — parallel fan-out
        coroutineScope {
            val serpJob = async { serpApiSiteSearch.search(primaryIdentity) }
            val ghJob = async { gitHubProbe.probe(primaryIdentity) }
            val wbJob = async { waybackMachine.snapshotAll(socialLinks) }

            serpJob.await()?.let {
                bgDataJson.put("osint_serpapi", it.toJson())
                appendLog("SerpAPI: LinkedIn=${it.linkedIn.size} Twitter=${it.twitter.size} IG=${it.instagram.size}")
            }
            ghJob.await()?.let {
                bgDataJson.put("osint_github", it.toJson())
                if (it.profiles.isNotEmpty()) appendLog("GitHub: ${it.profiles.size} profile(s) found")
            }
            wbJob.await()?.let {
                bgDataJson.put("osint_wayback", it.toJson())
                if (it.snapshots.isNotEmpty()) appendLog("Wayback: ${it.snapshots.size} snapshot(s)")
            }
        }

        if (ocrResult != null) {
            bgDataJson.put("ocr", ocrResult.toJson())
        }
        localFaceCache.cacheIdentity(
            faceId = faceId, embedding = embedding,
            primaryIdentity = primaryIdentity, socialLinks = socialLinks,
            backgroundData = bgDataJson.toString(),
            visibleText = ocrResult?.primaryLine,
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

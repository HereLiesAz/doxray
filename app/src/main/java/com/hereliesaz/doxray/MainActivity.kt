package com.hereliesaz.doxray

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.meta.MetaGlassesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MainActivity"
private const val PERMISSION_REQUEST_CODE = 1001

class MainActivity : ComponentActivity() {

    private lateinit var metaGlassesManager: MetaGlassesManager

    // Primary API Services
    private val faceSeekService = FaceSeekService()
    private val yandexSearchService = YandexSearchService()
    private val lensoSearchService = LensoSearchService()
    private val faceCheckIdService = FaceCheckIdService()

    // Fallback Scraper Services
    private val faceSeekScraper = FaceSeekScraperService()
    private val yandexScraper = YandexScraperService()
    private val lensoScraper = LensoScraperService()
    private val faceCheckIdScraper = FaceCheckIdScraperService()

    // Deep Background Scraper Services
    private val smartBgScraper = SmartBackgroundChecksScraper()
    private val cyberBgScraper = CyberBackgroundChecksScraper()

    // Tracking and Caching
    private val faceTrackerManager = FaceTrackerManager()
    private lateinit var localFaceCache: LocalFaceCache
    private lateinit var embeddingGenerator: EmbeddingGenerator
    private lateinit var appDatabase: AppDatabase

    // Active investigation jobs
    private val activeInvestigations = ConcurrentHashMap<Int, Job>()

    // Compose-observable UI state
    private val uiState = DoxrayUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        metaGlassesManager = MetaGlassesManager(this)
        embeddingGenerator = EmbeddingGenerator(this)

        appDatabase = AppDatabase.getDatabase(this)
        localFaceCache = LocalFaceCache(
            identityDao = appDatabase.identityDao(),
            encounterDao = appDatabase.encounterDao(),
            locationService = com.hereliesaz.doxray.location.LocationService(this),
        )

        lifecycleScope.launch { localFaceCache.loadFromDatabase() }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DoxrayScreen(
                        state = uiState,
                        onConnect = {
                            if (checkPermissions()) connectToGlasses()
                        },
                        onDisconnect = {
                            metaGlassesManager.stopVideoStream()
                            metaGlassesManager.disconnect()
                            uiState.setConnected(false)
                            appendLog("Disconnected from glasses.")
                        }
                    )
                }
            }
        }

        checkPermissions()
    }

    private fun connectToGlasses() {
        appendLog("Attempting connection...")
        try {
            metaGlassesManager.connect()
            uiState.setConnected(true)
            appendLog("Connected successfully.")

            metaGlassesManager.startVideoStream(object : MetaGlassesManager.FrameListener {
                override fun onFrameReceived(imageBytes: ByteArray) {
                    faceTrackerManager.processFrame(imageBytes, object : FaceTrackerManager.FaceFocusListener {
                        override fun onFaceFocused(focusedImageBytes: ByteArray, trackingId: Int, faceCrop: ByteArray) {
                            if (activeInvestigations.containsKey(trackingId)) return

                            runOnUiThread { appendLog("Target acquired (ID: $trackingId). Processing search...") }

                            val job = lifecycleScope.launch {
                                processFocusedFace(focusedImageBytes, faceCrop, trackingId)
                            }
                            activeInvestigations[trackingId] = job
                        }

                        override fun onFaceLost(trackingId: Int) {
                            val job = activeInvestigations.remove(trackingId)
                            if (job != null && job.isActive) {
                                runOnUiThread { appendLog("Target lost (ID: $trackingId). Halting active investigation.") }
                                job.cancel()
                            }
                        }

                        override fun onError(e: Exception) {
                            Log.e(TAG, "Face tracking error", e)
                        }
                    })
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        appendLog("Stream Error: ${error.message}")
                        Toast.makeText(this@MainActivity, "Stream Error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } catch (e: Exception) {
            uiState.setConnected(false)
            appendLog("Connection failed: ${e.message}")
            Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun processFocusedFace(imageBytes: ByteArray, faceCrop: ByteArray, trackingId: Int) {
        try {
            val embedding = embeddingGenerator.generateEmbedding(faceCrop)
            val cachedMatch = localFaceCache.findMatch(embedding)

            if (cachedMatch != null) {
                val msg = "Cached Match: ${cachedMatch.primaryIdentity}. Encounters: ${cachedMatch.encounterCount}."
                runOnUiThread {
                    appendLog(msg)
                    appendLog("Known Links: ${cachedMatch.socialLinks}")
                }
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
                runOnUiThread { appendLog("Lenso API failed, trying scraper fallback...") }
                lensoResult = lensoScraper.identifyFace(imageBytes)
            }

            if (lensoResult != null && lensoResult.confidence > 0.6f) {
                runOnUiThread { appendLog("Lenso face matched from domain: ${lensoResult.sourceDomain}") }
                referenceImageUrl = lensoResult.referenceImageUrl
                faceId = lensoResult.faceId
            } else {
                var faceResult = faceSeekService.identifyFace(imageBytes)
                if (faceResult == null) {
                    runOnUiThread { appendLog("FaceSeek API failed, trying scraper fallback...") }
                    faceResult = faceSeekScraper.identifyFace(imageBytes)
                }

                if (faceResult != null && faceResult.confidence > 0.6f) {
                    runOnUiThread { appendLog("FaceSeek matched! ID: ${faceResult.faceId}") }
                    referenceImageUrl = faceResult.referenceImageUrl
                    faceId = faceResult.faceId
                } else {
                    var faceCheckResult = faceCheckIdService.identifyFace(imageBytes)
                    if (faceCheckResult == null) {
                        runOnUiThread { appendLog("FaceCheck.ID API failed, trying scraper fallback...") }
                        faceCheckResult = faceCheckIdScraper.identifyFace(imageBytes)
                    }

                    if (faceCheckResult != null && faceCheckResult.confidence > 0.6f) {
                        runOnUiThread { appendLog("FaceCheck.ID matched! ID: ${faceCheckResult.faceId}") }
                        referenceImageUrl = faceCheckResult.referenceImageUrl
                        faceId = faceCheckResult.faceId
                    }
                }
            }

            if (referenceImageUrl.isNotEmpty()) {
                metaGlassesManager.playAudioMessage("Face matched. Correlating identity...")

                var identityResult = yandexSearchService.searchIdentity(referenceImageUrl)
                if (identityResult == null) {
                    runOnUiThread { appendLog("Yandex API failed, trying scraper fallback...") }
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
                val msg = "Identity correlated: $primaryIdentity"
                runOnUiThread {
                    appendLog(msg)
                    appendLog("Links: ${socialLinks.joinToString(", ")}")
                }
                metaGlassesManager.playAudioMessage(msg)

                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks)
            } else if (referenceImageUrl.isNotEmpty()) {
                runOnUiThread { appendLog("No online identity correlation found.") }
                metaGlassesManager.playAudioMessage("No online identity found.")
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Investigation for ID $trackingId was cancelled because the subject was lost.")
        } catch (e: Exception) {
            runOnUiThread { appendLog("Pipeline Exception: ${e.message}") }
        } finally {
            activeInvestigations.remove(trackingId)
        }
    }

    private suspend fun performDeepBackgroundScrape(primaryIdentity: String, faceId: String, embedding: FloatArray, socialLinks: List<String>) {
        metaGlassesManager.playAudioMessage("Digging for background data.")
        runOnUiThread { appendLog("Digging for deep background info on: $primaryIdentity...") }

        val bgDataJson = JSONObject()

        val smartData = smartBgScraper.searchBackground(primaryIdentity)
        if (smartData != null) {
            bgDataJson.put("smart", smartData)
            val phonesCount = smartData.optJSONArray("phones")?.length() ?: 0
            if (phonesCount > 0) {
                metaGlassesManager.playAudioMessage("Found $phonesCount phone numbers.")
                runOnUiThread { appendLog("Extracted phone numbers.") }
            }
        }

        val cyberData = cyberBgScraper.searchBackground(primaryIdentity)
        if (cyberData != null) {
            bgDataJson.put("cyber", cyberData)
            val emailsCount = cyberData.optJSONArray("emails")?.length() ?: 0
            if (emailsCount > 0) {
                metaGlassesManager.playAudioMessage("Found $emailsCount email addresses.")
                runOnUiThread { appendLog("Extracted email addresses.") }
            }
        }

        localFaceCache.cacheIdentity(
            faceId = faceId,
            embedding = embedding,
            primaryIdentity = primaryIdentity,
            socialLinks = socialLinks,
            backgroundData = bgDataJson.toString()
        )

        if (bgDataJson.length() > 0) {
            metaGlassesManager.playAudioMessage("Investigation complete. Dossier saved.")
        } else {
            metaGlassesManager.playAudioMessage("No additional offline data found.")
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        uiState.addLog("[$time] $message")
    }

    private fun checkPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.INTERNET
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions required for glasses connection.", Toast.LENGTH_LONG).show()
                appendLog("Error: Missing permissions.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        metaGlassesManager.stopVideoStream()
        metaGlassesManager.disconnect()
    }
}

class DoxrayUiState {
    val logLines: SnapshotStateList<String> = mutableStateListOf()
    private val _isConnected = mutableStateOf(false)
    val isConnected get() = _isConnected.value
    fun setConnected(connected: Boolean) { _isConnected.value = connected }
    fun addLog(line: String) { logLines.add(0, line) }
}

@Composable
fun DoxrayScreen(
    state: DoxrayUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        val statusText = if (state.isConnected) "Status: Connected to Glasses" else "Status: Disconnected"
        Text(
            text = statusText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onConnect, enabled = !state.isConnected) { Text("Connect") }
            Button(onClick = onDisconnect, enabled = state.isConnected) { Text("Disconnect") }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Recent Activity Log:",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        val lines = remember { state.logLines }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        }
    }
}

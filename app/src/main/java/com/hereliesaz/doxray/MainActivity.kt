package com.hereliesaz.doxray

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hereliesaz.doxray.api.FaceSeekService
import com.hereliesaz.doxray.api.FaceSeekScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.databinding.ActivityMainBinding
import com.hereliesaz.doxray.meta.MetaGlassesManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.hereliesaz.doxray.api.FaceTrackerManager
import com.hereliesaz.doxray.api.LocalFaceCache
import com.hereliesaz.doxray.api.EmbeddingGenerator
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.SmartBackgroundChecksScraper
import com.hereliesaz.doxray.api.CyberBackgroundChecksScraper
import com.hereliesaz.doxray.db.AppDatabase
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var metaGlassesManager: MetaGlassesManager
    
    // Primary API Services
    private val faceSeekService = FaceSeekService()
    private val yandexSearchService = YandexSearchService()
    private val lensoSearchService = LensoSearchService()
    
    // Fallback Scraper Services
    private val faceSeekScraper = FaceSeekScraperService()
    private val yandexScraper = YandexScraperService()
    private val lensoScraper = LensoScraperService()
    
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
    
    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        metaGlassesManager = MetaGlassesManager(this)
        embeddingGenerator = EmbeddingGenerator(this)

        // Initialize DB and Cache
        appDatabase = AppDatabase.getDatabase(this)
        localFaceCache = LocalFaceCache(appDatabase.identityDao())
        
        lifecycleScope.launch {
            localFaceCache.loadFromDatabase()
        }

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (checkPermissions()) {
                connectToGlasses()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            metaGlassesManager.stopVideoStream()
            metaGlassesManager.disconnect()
            updateUIState(false)
            appendLog("Disconnected from glasses.")
        }
    }

    private fun connectToGlasses() {
        appendLog("Attempting connection...")
        try {
            metaGlassesManager.connect()
            updateUIState(true)
            appendLog("Connected successfully.")
            
            metaGlassesManager.startVideoStream(object : MetaGlassesManager.FrameListener {
                override fun onFrameReceived(imageBytes: ByteArray) {
                    // Feed every frame into the ML Kit Face Tracker
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
                            Log.e("MainActivity", "Face tracking error", e)
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
            updateUIState(false)
            appendLog("Connection failed: ${e.message}")
            Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun processFocusedFace(imageBytes: ByteArray, faceCrop: ByteArray, trackingId: Int) {
        try {
            // Check Local Cache First
            val embedding = embeddingGenerator.generateEmbedding(faceCrop)
            val cachedMatch = localFaceCache.findMatch(embedding)

            if (cachedMatch != null) {
                // Investigative Flow: User has seen this person before
                val lastSeenDate = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(cachedMatch.lastSeenTimestamp))
                val msg = "Cached Match: ${cachedMatch.primaryIdentity}. Encounters: ${cachedMatch.encounterCount}."
                runOnUiThread { 
                    appendLog(msg)
                    appendLog("Known Links: ${cachedMatch.socialLinks}")
                }
                metaGlassesManager.playAudioMessage("Match found: ${cachedMatch.primaryIdentity}. Previous encounters: ${cachedMatch.encounterCount}")
                
                // If background data is empty, we can continue investigating
                if (cachedMatch.backgroundData == "{}" || cachedMatch.backgroundData.isEmpty()) {
                    metaGlassesManager.playAudioMessage("Resuming background investigation.")
                    performDeepBackgroundScrape(cachedMatch.primaryIdentity, cachedMatch.faceId, embedding, cachedMatch.socialLinks.split(","))
                }
                return
            }

            // Not in cache, proceed with remote search
            var primaryIdentity = ""
            var socialLinks = listOf<String>()
            var referenceImageUrl = ""
            var faceId = ""

            // 1. Identify Face via Lenso (Primary)
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
                // Fallback to FaceSeek if Lenso doesn't find a confident match
                var faceResult = faceSeekService.identifyFace(imageBytes)
                if (faceResult == null) {
                    runOnUiThread { appendLog("FaceSeek API failed, trying scraper fallback...") }
                    faceResult = faceSeekScraper.identifyFace(imageBytes)
                }
                
                if (faceResult != null && faceResult.confidence > 0.6f) {
                    runOnUiThread { appendLog("FaceSeek matched! ID: ${faceResult.faceId}") }
                    referenceImageUrl = faceResult.referenceImageUrl
                    faceId = faceResult.faceId
                }
            }

            // Provide early audio feedback if a face was matched visually
            if (referenceImageUrl.isNotEmpty()) {
                metaGlassesManager.playAudioMessage("Face matched. Correlating identity...")
                
                // 2. Correlate Identity via Yandex
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
                
                // Continue following leads for information
                performDeepBackgroundScrape(primaryIdentity, faceId, embedding, socialLinks)
            } else if (referenceImageUrl.isNotEmpty()) {
                runOnUiThread { appendLog("No online identity correlation found.") }
                metaGlassesManager.playAudioMessage("No online identity found.")
            }

        } catch (e: CancellationException) {
            Log.d("MainActivity", "Investigation for ID $trackingId was cancelled because the subject was lost.")
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
        
        // Deep Background Scrapes - SmartBackgroundChecks
        val smartData = smartBgScraper.searchBackground(primaryIdentity)
        if (smartData != null) {
            bgDataJson.put("smart", smartData)
            val phonesCount = smartData.optJSONArray("phones")?.length() ?: 0
            if (phonesCount > 0) {
                 metaGlassesManager.playAudioMessage("Found $phonesCount phone numbers.")
                 runOnUiThread { appendLog("Extracted phone numbers.") }
            }
        }
        
        // Deep Background Scrapes - CyberBackgroundChecks
        val cyberData = cyberBgScraper.searchBackground(primaryIdentity)
        if (cyberData != null) {
            bgDataJson.put("cyber", cyberData)
            val emailsCount = cyberData.optJSONArray("emails")?.length() ?: 0
            if (emailsCount > 0) {
                 metaGlassesManager.playAudioMessage("Found $emailsCount email addresses.")
                 runOnUiThread { appendLog("Extracted email addresses.") }
            }
        }

        // Add to Local Cache & Database
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

    private fun updateUIState(isConnected: Boolean) {
        binding.tvStatus.text = if (isConnected) getString(R.string.status_connected) else getString(R.string.status_disconnected)
        binding.btnConnect.isEnabled = !isConnected
        binding.btnDisconnect.isEnabled = isConnected
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLog = binding.tvLog.text.toString()
        val newLog = "[$time] $message\n$currentLog"
        binding.tvLog.text = newLog
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
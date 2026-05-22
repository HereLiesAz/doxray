package com.hereliesaz.doxray.meta

import android.content.Context
import android.util.Log
import com.facebook.wearables.dat.DeviceManager
import com.facebook.wearables.dat.DeviceSession
import com.facebook.wearables.dat.camera.CameraManager
import com.facebook.wearables.dat.camera.CameraStreamSession
import com.facebook.wearables.dat.camera.FrameFormat
import com.facebook.wearables.dat.camera.Resolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Actual implementation of Meta Wearables Device Access Toolkit (DAT) integration.
 */
class MetaGlassesManager(private val context: Context) {

    private val TAG = "MetaGlassesManager"

    sealed class ConnectResult {
        object Success : ConnectResult()
        object NoPairedDevice : ConnectResult()
        data class Failure(val cause: Throwable) : ConnectResult()
    }

    private val _isConnectedFlow = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()
    val isConnected: Boolean get() = _isConnectedFlow.value

    private var deviceSession: DeviceSession? = null
    private var cameraStreamSession: CameraStreamSession? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    interface FrameListener {
        fun onFrameReceived(imageBytes: ByteArray)
        fun onError(error: Throwable)
    }

    private var frameListener: FrameListener? = null

    /**
     * Awaits the connection attempt and reports the actual outcome instead of
     * fire-and-forgetting on a background coroutine. Callers should observe
     * [isConnectedFlow] if they want to react to disconnect events later.
     */
    suspend fun connect(): ConnectResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting to connect to Meta Ray Bans via DAT SDK...")
        try {
            val deviceManager = DeviceManager.getInstance(context)
            val pairedDevice = deviceManager.getPairedDevices().firstOrNull()
            if (pairedDevice == null) {
                Log.w(TAG, "No paired Meta wearables found.")
                _isConnectedFlow.value = false
                return@withContext ConnectResult.NoPairedDevice
            }
            deviceSession = deviceManager.createSession(pairedDevice).also { it.connect() }
            _isConnectedFlow.value = true
            Log.d(TAG, "Successfully connected to Meta Ray Bans: ${pairedDevice.name}")
            ConnectResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Meta Ray Bans", e)
            _isConnectedFlow.value = false
            ConnectResult.Failure(e)
        }
    }

    fun startVideoStream(listener: FrameListener) {
        if (!isConnected || deviceSession == null) {
            listener.onError(IllegalStateException("Glasses are not connected."))
            return
        }

        this.frameListener = listener
        Log.d(TAG, "Starting video stream...")

        scope.launch {
            try {
                val cameraManager = CameraManager.getInstance(deviceSession!!)

                // Configure stream for 30fps at medium quality to balance bandwidth and facial recognition accuracy
                cameraStreamSession = cameraManager.createCameraStreamSession(
                    resolution = Resolution.MEDIUM, // 504x896
                    fps = 30,
                    format = FrameFormat.JPEG
                )

                cameraStreamSession?.start()

                // Collect frames from the SDK flow
                cameraStreamSession?.framesFlow?.onEach { frame ->
                    val imageBytes = frame.imageBytes
                    if (imageBytes != null) {
                        frameListener?.onFrameReceived(imageBytes)
                    }
                }?.launchIn(scope)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting video stream", e)
                listener.onError(e)
            }
        }
    }

    fun stopVideoStream() {
        Log.d(TAG, "Stopping video stream...")
        cameraStreamSession?.stop()
        cameraStreamSession = null
        this.frameListener = null
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from Meta Ray Bans...")
        deviceSession?.disconnect()
        deviceSession = null
        _isConnectedFlow.value = false
    }

    /**
     * Sends an audio cue or Text-to-Speech message back to the glasses.
     */
    fun playAudioMessage(message: String) {
        if (!isConnected || deviceSession == null) {
            Log.w(TAG, "Cannot play audio, glasses not connected. Message: $message")
            return
        }
        Log.d(TAG, "Playing audio on Meta Ray Bans: \"$message\"")

        scope.launch {
            try {
                deviceSession?.audioManager?.playTts(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play audio message on glasses", e)
            }
        }
    }
}

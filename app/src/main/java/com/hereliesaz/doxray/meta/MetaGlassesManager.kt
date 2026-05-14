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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Actual implementation of Meta Wearables Device Access Toolkit (DAT) integration.
 */
class MetaGlassesManager(private val context: Context) {

    private val TAG = "MetaGlassesManager"
    var isConnected: Boolean = false
        private set

    private var deviceSession: DeviceSession? = null
    private var cameraStreamSession: CameraStreamSession? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Interface for receiving frames from the glasses.
     */
    interface FrameListener {
        fun onFrameReceived(imageBytes: ByteArray)
        fun onError(error: Throwable)
    }

    private var frameListener: FrameListener? = null

    fun connect() {
        Log.d(TAG, "Attempting to connect to Meta Ray Bans via DAT SDK...")
        scope.launch {
            try {
                // Initialize the DAT Device Manager
                val deviceManager = DeviceManager.getInstance(context)
                
                // Get the first paired Meta glasses device
                val pairedDevice = deviceManager.getPairedDevices().firstOrNull()
                
                if (pairedDevice != null) {
                    // Create a session with the device
                    deviceSession = deviceManager.createSession(pairedDevice)
                    
                    deviceSession?.connect()
                    isConnected = true
                    Log.d(TAG, "Successfully connected to Meta Ray Bans: ${pairedDevice.name}")
                } else {
                    Log.w(TAG, "No paired Meta wearables found.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Meta Ray Bans", e)
                isConnected = false
            }
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
                    // Convert the SDK frame to a ByteArray
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
        isConnected = false
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
                // Using the audio/TTS routing features of the Meta Wearables DAT SDK
                // NOTE: The exact Audio API might vary based on the DAT version.
                // Assuming a typical TTS request to the connected wearable.
                deviceSession?.audioManager?.playTts(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play audio message on glasses", e)
            }
        }
    }
}
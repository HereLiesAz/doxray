package com.hereliesaz.doxray.api

import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Uses Google ML Kit to detect and track faces over time.
 * Initiates the search pipeline only if focus on a person is maintained for 5 seconds.
 */
class FaceTrackerManager {

    private val TAG = "FaceTrackerManager"
    
    // High-accuracy face tracking options
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setTrackingEnabled(true)
        .build()

    private val detector = FaceDetection.getClient(options)
    
    // Maps ML Kit tracking IDs to the timestamp they were first seen
    private val trackedFaces = mutableMapOf<Int, Long>()
    // Tracks IDs that have already triggered a search to avoid redundant calls
    private val searchedFaces = mutableSetOf<Int>()

    // 5000 milliseconds = 5 seconds
    private val FOCUS_THRESHOLD_MS = 5000L

    interface FaceFocusListener {
        fun onFaceFocused(imageBytes: ByteArray, trackingId: Int, faceCrop: ByteArray)
        fun onFaceLost(trackingId: Int)
        fun onError(e: Exception)
    }

    /**
     * Processes an incoming frame from the glasses.
     */
    fun processFrame(imageBytes: ByteArray, listener: FaceFocusListener) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return
            val image = InputImage.fromBitmap(bitmap, 0)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    val currentIds = mutableSetOf<Int>()
                    
                    for (face in faces) {
                        val trackingId = face.trackingId ?: continue
                        currentIds.add(trackingId)

                        if (searchedFaces.contains(trackingId)) {
                            // We already processed this person in this session
                            continue
                        }

                        val firstSeen = trackedFaces[trackingId]
                        val currentTime = System.currentTimeMillis()

                        if (firstSeen == null) {
                            // Newly detected face
                            trackedFaces[trackingId] = currentTime
                            Log.d(TAG, "New face detected (ID: $trackingId). Starting 5-second focus timer.")
                        } else {
                            val duration = currentTime - firstSeen
                            if (duration >= FOCUS_THRESHOLD_MS) {
                                Log.d(TAG, "Focus maintained on face (ID: $trackingId) for 5 seconds. Initiating search.")
                                searchedFaces.add(trackingId)
                                
                                // TODO: Extract the face bounding box to generate embeddings
                                // For now, passing the full frame to the listener
                                listener.onFaceFocused(imageBytes, trackingId, imageBytes)
                            }
                        }
                    }

                    // Clean up tracking IDs that are no longer in frame
                    val removedIds = trackedFaces.keys - currentIds
                    for (id in removedIds) {
                        trackedFaces.remove(id)
                        searchedFaces.remove(id) // Reset search so they can be searched again if they return
                        listener.onFaceLost(id)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)
                    listener.onError(e)
                }
        } catch (e: Exception) {
            listener.onError(e)
        }
    }
}

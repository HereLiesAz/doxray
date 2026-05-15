package com.hereliesaz.doxray.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream

/**
 * Uses Google ML Kit to detect and track faces over time.
 * Initiates the search pipeline only if focus on a person is maintained for 5 seconds.
 */
class FaceTrackerManager {

    private val TAG = "FaceTrackerManager"

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .enableTracking()
        .build()

    private val detector = FaceDetection.getClient(options)

    // Maps ML Kit tracking IDs to the timestamp they were first seen
    private val trackedFaces = mutableMapOf<Int, Long>()
    // Tracks IDs that have already triggered a search to avoid redundant calls
    private val searchedFaces = mutableSetOf<Int>()

    private val FOCUS_THRESHOLD_MS = 5000L

    interface FaceFocusListener {
        fun onFaceFocused(imageBytes: ByteArray, trackingId: Int, faceCrop: ByteArray)
        fun onFaceLost(trackingId: Int)
        fun onError(e: Exception)
    }

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

                        if (searchedFaces.contains(trackingId)) continue

                        val firstSeen = trackedFaces[trackingId]
                        val currentTime = System.currentTimeMillis()

                        if (firstSeen == null) {
                            trackedFaces[trackingId] = currentTime
                            Log.d(TAG, "New face detected (ID: $trackingId). Starting 5-second focus timer.")
                        } else {
                            val duration = currentTime - firstSeen
                            if (duration >= FOCUS_THRESHOLD_MS) {
                                Log.d(TAG, "Focus maintained on face (ID: $trackingId) for 5 seconds. Initiating search.")
                                searchedFaces.add(trackingId)

                                val faceCropBytes = cropFace(bitmap, face) ?: imageBytes
                                listener.onFaceFocused(imageBytes, trackingId, faceCropBytes)
                            }
                        }
                    }

                    val removedIds = trackedFaces.keys - currentIds
                    for (id in removedIds) {
                        trackedFaces.remove(id)
                        searchedFaces.remove(id)
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

    private fun cropFace(source: Bitmap, face: Face): ByteArray? {
        return try {
            val bbox = face.boundingBox
            val clamped = Rect(
                bbox.left.coerceIn(0, source.width),
                bbox.top.coerceIn(0, source.height),
                bbox.right.coerceIn(0, source.width),
                bbox.bottom.coerceIn(0, source.height)
            )
            if (clamped.width() <= 0 || clamped.height() <= 0) return null

            val cropped = Bitmap.createBitmap(source, clamped.left, clamped.top, clamped.width(), clamped.height())
            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Face crop failed; falling back to full frame", e)
            null
        }
    }
}

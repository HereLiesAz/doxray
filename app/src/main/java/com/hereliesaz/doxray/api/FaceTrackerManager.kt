package com.hereliesaz.doxray.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.hereliesaz.doxray.audit.AuditLogger
import com.hereliesaz.doxray.quality.FaceSample
import com.hereliesaz.doxray.quality.LivenessHeuristic
import com.hereliesaz.doxray.quality.LivenessResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class FaceTrackerManager {

    private val TAG = "FaceTrackerManager"

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .enableTracking()
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()

    private val detector = FaceDetection.getClient(options)

    private val trackedFaces = mutableMapOf<Int, Long>()
    private val searchedFaces = mutableSetOf<Int>()
    private val samples = mutableMapOf<Int, MutableList<FaceSample>>()

    private val FOCUS_THRESHOLD_MS = 5000L
    private val MAX_SAMPLES_PER_TRACK = 30  // 5s at ~6fps; bound memory

    interface FaceFocusListener {
        fun onFaceFocused(
            imageBytes: ByteArray,
            trackingId: Int,
            faceCrop: ByteArray,
            eulerX: Float,
            eulerY: Float,
            eulerZ: Float,
        )
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
                    val now = System.currentTimeMillis()

                    for (face in faces) {
                        val trackingId = face.trackingId ?: continue
                        currentIds.add(trackingId)

                        // Once a face is in searchedFaces (passed or failed liveness),
                        // it's done — no need to keep building samples for it.
                        if (searchedFaces.contains(trackingId)) continue

                        // Record this frame as a sample for liveness evaluation
                        val sample = FaceSample(
                            leftEyeOpen = face.leftEyeOpenProbability ?: -1f,
                            rightEyeOpen = face.rightEyeOpenProbability ?: -1f,
                            smiling = face.smilingProbability ?: -1f,
                            eulerX = face.headEulerAngleX,
                            eulerY = face.headEulerAngleY,
                            eulerZ = face.headEulerAngleZ,
                            timestampMs = now,
                        )
                        val list = samples.getOrPut(trackingId) { mutableListOf() }
                        list.add(sample)
                        if (list.size > MAX_SAMPLES_PER_TRACK) list.removeAt(0)

                        val firstSeen = trackedFaces[trackingId]
                        if (firstSeen == null) {
                            trackedFaces[trackingId] = now
                            Log.d(TAG, "New face detected (ID: $trackingId). Starting 5-second focus timer.")
                        } else {
                            val duration = now - firstSeen
                            if (duration >= FOCUS_THRESHOLD_MS) {
                                searchedFaces.add(trackingId)

                                // [GATE 1] Liveness
                                val liveness = LivenessHeuristic.evaluate(samples[trackingId].orEmpty())
                                if (liveness is LivenessResult.Fail) {
                                    Log.d(TAG, "Liveness FAIL for ID $trackingId: ${liveness.reasonDetails}")
                                    AuditLogger.log(
                                        AuditLogger.Type.REJECTED,
                                        summary = "Liveness failed for tracked face $trackingId",
                                        details = JSONObject().apply {
                                            put("reason", "liveness")
                                            put("trackingId", trackingId)
                                            put("sampleCount", samples[trackingId]?.size ?: 0)
                                            put("breakdown", liveness.reasonDetails)
                                        },
                                    )
                                    continue
                                }
                                Log.d(TAG, "Liveness PASS for ID $trackingId. Cropping + dispatching.")

                                val faceCropBytes = cropFace(bitmap, face) ?: imageBytes
                                listener.onFaceFocused(
                                    imageBytes = imageBytes,
                                    trackingId = trackingId,
                                    faceCrop = faceCropBytes,
                                    eulerX = face.headEulerAngleX,
                                    eulerY = face.headEulerAngleY,
                                    eulerZ = face.headEulerAngleZ,
                                )
                            }
                        }
                    }

                    val removedIds = trackedFaces.keys - currentIds
                    for (id in removedIds) {
                        trackedFaces.remove(id)
                        searchedFaces.remove(id)
                        samples.remove(id)
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
                bbox.bottom.coerceIn(0, source.height),
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

package com.hereliesaz.doxray.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hereliesaz.doxray.db.AnchorImage
import com.hereliesaz.doxray.db.AnchorImageDao
import java.io.ByteArrayOutputStream

/**
 * Persists a single representative face crop per identity. Best-quality wins:
 * a new upsert only overwrites when [qualityScore] exceeds the stored row's
 * score. Skips writes that would exceed 1 MB after re-compression.
 */
class AnchorImageRepository(private val dao: AnchorImageDao) {

    private val TAG = "AnchorImageRepository"
    private val MAX_BYTES = 1_048_576

    suspend fun upsert(faceId: String, imageBytes: ByteArray, qualityScore: Float) {
        val existing = dao.getByFaceId(faceId)
        if (existing != null && qualityScore <= existing.qualityScore) return
        val bytes = ensureUnderMax(imageBytes) ?: run {
            Log.w(TAG, "Anchor for $faceId exceeds $MAX_BYTES bytes; skipping")
            return
        }
        dao.upsert(AnchorImage(
            faceId = faceId,
            imageBytes = bytes,
            qualityScore = qualityScore,
            capturedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun get(faceId: String): AnchorImage? = dao.getByFaceId(faceId)

    private fun ensureUnderMax(bytes: ByteArray): ByteArray? {
        if (bytes.size <= MAX_BYTES) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray().takeIf { it.size <= MAX_BYTES }
    }
}

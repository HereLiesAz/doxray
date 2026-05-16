package com.hereliesaz.doxray.api

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

class OcrService {

    private val TAG = "OcrService"
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    data class OcrBlock(val text: String, val pixelHeight: Int) {
        fun toJson(): JSONObject = JSONObject()
            .put("text", text)
            .put("pixelHeight", pixelHeight)
    }

    data class OcrResult(
        val primaryLine: String,
        val allText: String,
        val blocks: List<OcrBlock>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("primaryLine", primaryLine)
            .put("allText", allText)
            .put("blocks", JSONArray(blocks.map { it.toJson() }))
    }

    suspend fun extract(imageBytes: ByteArray, faceBbox: Rect): OcrResult? {
        if (imageBytes.isEmpty()) return null
        val frame = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        val region = expandBbox(faceBbox, frame.width, frame.height)
        if (region.width() <= 0 || region.height() <= 0) return null
        val crop = try {
            Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height())
        } catch (e: Exception) {
            Log.w(TAG, "OCR crop failed", e)
            return null
        }
        return try {
            val image = InputImage.fromBitmap(crop, 0)
            val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            } ?: return null
            val blocks = text.textBlocks.map { b ->
                OcrBlock(
                    text = b.text,
                    pixelHeight = b.boundingBox?.height() ?: 0,
                )
            }
            if (blocks.isEmpty()) return null
            val primary = blocks.maxByOrNull { it.pixelHeight }!!.text
            OcrResult(
                primaryLine = primary,
                allText = blocks.joinToString("\n") { it.text },
                blocks = blocks,
            )
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed", e)
            null
        }
    }

    companion object {
        fun expandBbox(face: Rect, imageWidth: Int, imageHeight: Int): Rect {
            val w = face.width()
            val h = face.height()
            val lateral = (w * 0.5f).toInt()
            val downward = (h * 2.0f).toInt()
            return Rect(
                (face.left - lateral).coerceAtLeast(0),
                face.top.coerceAtLeast(0),
                (face.right + lateral).coerceAtMost(imageWidth),
                (face.bottom + downward).coerceAtMost(imageHeight),
            )
        }
    }
}

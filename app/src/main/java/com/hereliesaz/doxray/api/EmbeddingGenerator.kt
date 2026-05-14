package com.hereliesaz.doxray.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Generates facial embeddings from a face crop using a TensorFlow Lite model.
 * Specifically configured for MobileFaceNet.
 */
class EmbeddingGenerator(context: Context) {

    private val TAG = "EmbeddingGenerator"
    private var interpreter: Interpreter? = null
    
    // Standard MobileFaceNet input dimensions
    private val INPUT_IMAGE_SIZE = 112
    // Output embedding dimension for MobileFaceNet
    private val OUTPUT_EMBEDDING_SIZE = 192

    init {
        try {
            val modelBuffer = loadModelFile(context, "mobile_face_net.tflite")
            val options = Interpreter.Options().apply {
                // Use NNAPI or GPU delegates here if needed for performance
                numThreads = 4
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "MobileFaceNet TFLite model loaded successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading MobileFaceNet model. Ensure 'mobile_face_net.tflite' is in the assets folder.", e)
            // Model not present; error handled, interpreter stays null
        }
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Generates the mathematical embedding for the given facial crop.
     */
    fun generateEmbedding(faceCropBytes: ByteArray): FloatArray {
        if (interpreter == null) {
            Log.w(TAG, "Interpreter is null. Cannot generate actual embedding. Returning empty array.")
            return FloatArray(OUTPUT_EMBEDDING_SIZE)
        }

        Log.d(TAG, "Processing face crop (${faceCropBytes.size} bytes) for TFLite inference...")
        
        // 1. Decode to Bitmap
        val bitmap = BitmapFactory.decodeByteArray(faceCropBytes, 0, faceCropBytes.size) 
            ?: return FloatArray(OUTPUT_EMBEDDING_SIZE)
            
        // 2. Resize to 112x112 as expected by MobileFaceNet
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, true)

        // 3. Convert to ByteBuffer and normalize
        // 112 * 112 * 3 channels * 4 bytes per float
        val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE)
        resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)
        
        var pixel = 0
        for (i in 0 until INPUT_IMAGE_SIZE) {
            for (j in 0 until INPUT_IMAGE_SIZE) {
                val value = intValues[pixel++]
                // Extract RGB and apply MobileFaceNet normalization: (val - 127.5) / 128.0
                inputBuffer.putFloat(((value shr 16 and 0xFF) - 127.5f) / 128.0f) // R
                inputBuffer.putFloat(((value shr 8 and 0xFF) - 127.5f) / 128.0f)  // G
                inputBuffer.putFloat(((value and 0xFF) - 127.5f) / 128.0f)        // B
            }
        }

        // 4. Run Inference
        val outputArray = Array(1) { FloatArray(OUTPUT_EMBEDDING_SIZE) }
        interpreter?.run(inputBuffer, outputArray)

        // 5. Apply L2 Normalization to the output vector
        val embedding = outputArray[0]
        var norm = 0f
        for (v in embedding) {
            norm += v * v
        }
        val length = Math.sqrt(norm.toDouble()).toFloat()
        if (length > 0) {
            for (i in embedding.indices) {
                embedding[i] /= length
            }
        }

        return embedding
    }
}
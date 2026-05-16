package com.hereliesaz.doxray.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.annotation.VisibleForTesting
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * CameraX-backed [FrameSource]. Binds an [ImageAnalysis] use case to the
 * given [lifecycleOwner], JPEG-encodes incoming YUV frames, and emits them
 * through [framesFlow] throttled to ~5fps.
 */
class PhoneFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : FrameSource {

    private val TAG = "PhoneFrameSource"
    private val THROTTLE_MS = 200L

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _framesFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 1)
    override val framesFlow: Flow<ByteArray> = _framesFlow.asSharedFlow()

    private var lastEmittedMs: Long = 0L
    private var useFrontCamera: Boolean = false

    @Volatile private var provider: ProcessCameraProvider? = null
    val previewUseCase: Preview = Preview.Builder().build()

    override suspend fun start() {
        try {
            val p = ProcessCameraProvider.getInstance(context).get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
                .also { it.setAnalyzer(executor, ::onFrame) }
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            try {
                p.bindToLifecycle(lifecycleOwner, selector, analysis, previewUseCase)
            } catch (e: Exception) {
                val fallback = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                p.bindToLifecycle(lifecycleOwner, fallback, analysis, previewUseCase)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CameraX bind failed", e)
            throw e
        }
    }

    override suspend fun stop() {
        provider?.unbindAll()
        provider = null
    }

    fun flipCamera() {
        useFrontCamera = !useFrontCamera
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastEmittedMs < THROTTLE_MS) return
            lastEmittedMs = now
            val jpeg = imageProxyToJpeg(image, quality = 85) ?: return
            scope.launch { _framesFlow.emit(jpeg) }
        } finally {
            image.close()
        }
    }

    private fun imageProxyToJpeg(image: ImageProxy, quality: Int): ByteArray? {
        return try {
            val nv21 = yuv420ToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "JPEG encode failed", e)
            null
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        return nv21
    }

    @VisibleForTesting
    internal fun tryEmitForTest(bytes: ByteArray, fakeNow: Long): Boolean {
        if (fakeNow - lastEmittedMs < THROTTLE_MS) return false
        lastEmittedMs = fakeNow
        scope.launch { _framesFlow.emit(bytes) }
        return true
    }

    @VisibleForTesting
    internal fun useFrontCameraForTest(): Boolean = useFrontCamera
}

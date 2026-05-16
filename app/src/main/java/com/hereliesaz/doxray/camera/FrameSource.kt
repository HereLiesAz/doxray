package com.hereliesaz.doxray.camera

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a byte-stream input device. Implementations supply
 * JPEG-encoded frames via [framesFlow]. The downstream pipeline
 * ([com.hereliesaz.doxray.api.FaceTrackerManager.processFrame]) is agnostic
 * to the underlying source.
 */
interface FrameSource {
    val framesFlow: Flow<ByteArray>
    suspend fun start()
    suspend fun stop()
}

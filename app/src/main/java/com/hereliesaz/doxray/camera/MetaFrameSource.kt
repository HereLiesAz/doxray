package com.hereliesaz.doxray.camera

import com.hereliesaz.doxray.meta.MetaGlassesManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Adapter that exposes the existing [MetaGlassesManager.startVideoStream]
 * listener-based API as a [FrameSource]-compatible [Flow].
 *
 * Calling [start] connects + begins streaming; [stop] reverses both steps.
 */
class MetaFrameSource(private val manager: MetaGlassesManager) : FrameSource {

    override val framesFlow: Flow<ByteArray> = callbackFlow {
        val listener = object : MetaGlassesManager.FrameListener {
            override fun onFrameReceived(imageBytes: ByteArray) {
                trySend(imageBytes)
            }
            override fun onError(error: Throwable) {
                // Swallow — errors logged inside MetaGlassesManager already.
                // Closing the channel here would tear down the upstream collector;
                // upstream lifecycle is managed by stop().
            }
        }
        manager.startVideoStream(listener)
        awaitClose { manager.stopVideoStream() }
    }

    override suspend fun start() {
        manager.connect()
    }

    override suspend fun stop() {
        manager.stopVideoStream()
        manager.disconnect()
    }
}

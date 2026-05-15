@file:Suppress("unused", "UNUSED_PARAMETER")
package com.facebook.wearables.dat.camera

import com.facebook.wearables.dat.DeviceSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class Resolution { LOW, MEDIUM, HIGH }
enum class FrameFormat { JPEG, YUV, RAW }

class Frame(val imageBytes: ByteArray?)

class CameraStreamSession internal constructor() {
    val framesFlow: Flow<Frame> = emptyFlow()
    fun start() {}
    fun stop() {}
}

class CameraManager private constructor() {
    fun createCameraStreamSession(
        resolution: Resolution,
        fps: Int,
        format: FrameFormat,
    ): CameraStreamSession = CameraStreamSession()

    companion object {
        fun getInstance(session: DeviceSession): CameraManager = CameraManager()
    }
}

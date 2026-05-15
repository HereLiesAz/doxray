@file:Suppress("unused", "UNUSED_PARAMETER")
// Local stub classes that mirror the closed-beta Meta Wearables DAT SDK signatures
// used by com.hereliesaz.doxray.meta.MetaGlassesManager. These are compiled into
// the APK only when the real `com.facebook.wearables:dat-android` dependency
// cannot be resolved (i.e. `gh.packages.url` is not configured in local.properties).
// They allow the app to compile and exercise its non-Meta code paths.
// At runtime the stub returns no paired devices, so glasses-dependent flows
// simply log and no-op.

package com.facebook.wearables.dat

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class PairedDevice(val name: String)

class AudioManager {
    fun playTts(message: String) {
        // No-op in stub mode
    }
}

class DeviceSession internal constructor() {
    val audioManager: AudioManager = AudioManager()
    fun connect() {}
    fun disconnect() {}
}

class DeviceManager private constructor() {
    fun getPairedDevices(): List<PairedDevice> = emptyList()
    fun createSession(device: PairedDevice): DeviceSession = DeviceSession()

    companion object {
        @Volatile private var instance: DeviceManager? = null
        fun getInstance(context: Context): DeviceManager =
            instance ?: synchronized(this) { instance ?: DeviceManager().also { instance = it } }
    }
}

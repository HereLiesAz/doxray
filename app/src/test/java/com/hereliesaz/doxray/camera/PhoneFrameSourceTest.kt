package com.hereliesaz.doxray.camera

import android.app.Application
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PhoneFrameSourceTest {

    @Test
    fun `throttles rapid onFrame calls to one per 200ms`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val src = PhoneFrameSource(ctx, FakeLifecycleOwner())

        val now = 1_000L
        val accepted1 = src.tryEmitForTest(makeJpeg(), now)
        val accepted2 = src.tryEmitForTest(makeJpeg(), now + 100)
        val accepted3 = src.tryEmitForTest(makeJpeg(), now + 250)

        assertTrue("First call should pass throttle", accepted1)
        assertFalse("Second call within 200ms should be dropped", accepted2)
        assertTrue("Call after 200ms should pass throttle", accepted3)
    }

    @Test
    fun `flipCamera toggles useFrontCamera flag`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val src = PhoneFrameSource(ctx, FakeLifecycleOwner())
        assertFalse("Default is rear-facing", src.useFrontCameraForTest())
        src.flipCamera()
        assertTrue("After flip is front-facing", src.useFrontCameraForTest())
        src.flipCamera()
        assertFalse("After second flip back to rear", src.useFrontCameraForTest())
    }

    private fun makeJpeg(): ByteArray = ByteArray(8) { 0xFF.toByte() }

    private class FakeLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle = registry
    }
}

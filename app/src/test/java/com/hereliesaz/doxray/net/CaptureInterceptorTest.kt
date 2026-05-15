package com.hereliesaz.doxray.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureInterceptorTest {

    private val captures = mutableListOf<Pair<String, ByteArray>>()
    private val writer = object : CaptureWriter {
        override fun write(filename: String, bytes: ByteArray) {
            captures += filename to bytes
        }
    }
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `does not capture when disabled`() {
        server.enqueue(MockResponse().setBody("hello"))
        val client = OkHttpClient.Builder()
            .addInterceptor(CaptureInterceptor(writer) { false })
            .build()
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertTrue("Should capture nothing when disabled", captures.isEmpty())
    }

    @Test
    fun `captures request and response when enabled`() {
        server.enqueue(MockResponse().setBody("world"))
        val client = OkHttpClient.Builder()
            .addInterceptor(CaptureInterceptor(writer) { true })
            .build()
        client.newCall(
            Request.Builder()
                .url(server.url("/y"))
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), "payload"))
                .build()
        ).execute().close()

        assertEquals(2, captures.size)
        val req = captures[0]
        val resp = captures[1]
        assertTrue("req filename ends with .req.bin: ${req.first}", req.first.endsWith(".req.bin"))
        assertTrue("resp filename ends with .resp.bin: ${resp.first}", resp.first.endsWith(".resp.bin"))
        assertTrue("req body included 'payload'", String(req.second).contains("payload"))
        assertTrue("resp body included 'world'", String(resp.second).contains("world"))
    }
}

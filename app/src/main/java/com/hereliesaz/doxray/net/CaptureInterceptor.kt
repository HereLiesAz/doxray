package com.hereliesaz.doxray.net

import com.hereliesaz.doxray.audit.AuditLogger
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp interceptor that, when [enabled] returns true, hands every request
 * and response to [writer]. When disabled it's a pass-through with zero
 * additional work.
 *
 * Filenames are `{epochMs}_{seq}_{host}.req.bin` and `.resp.bin`. The body
 * format is `METHOD path HTTP/1.1\n<header>: <value>\n...\n\n<bytes>`.
 */
class CaptureInterceptor(
    private val writer: CaptureWriter,
    private val enabled: () -> Boolean,
) : Interceptor {

    private val seq = AtomicLong(0L)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = if (enabled()) {
            val ts = System.currentTimeMillis()
            val n = seq.incrementAndGet()
            val host = try { URI(request.url.toString()).host ?: "unknown" } catch (e: Exception) { "unknown" }
            val base = "${ts}_${n}_${host}"

            writer.write("$base.req.bin", encodeRequest(request))
            val resp = chain.proceed(request)
            val peek = resp.peekBody(MAX_BODY_BYTES.toLong())
            writer.write("$base.resp.bin", encodeResponse(resp, peek.bytes()))
            resp
        } else {
            chain.proceed(request)
        }

        AuditLogger.log(
            AuditLogger.Type.API_CALL,
            summary = "${request.method} ${request.url.host}${request.url.encodedPath} → ${response.code}",
            details = JSONObject().apply {
                put("method", request.method)
                put("url", request.url.toString())
                put("code", response.code)
            },
        )
        return response
    }

    private fun encodeRequest(request: okhttp3.Request): ByteArray {
        val buf = Buffer()
        buf.writeUtf8("${request.method} ${request.url} HTTP/1.1\n")
        for ((name, value) in request.headers) buf.writeUtf8("$name: $value\n")
        buf.writeUtf8("\n")
        request.body?.let { body ->
            val copy = Buffer()
            body.writeTo(copy)
            buf.write(copy.readByteArray())
        }
        return buf.readByteArray()
    }

    private fun encodeResponse(response: Response, bodyBytes: ByteArray): ByteArray {
        val buf = Buffer()
        buf.writeUtf8("HTTP/1.1 ${response.code} ${response.message}\n")
        for ((name, value) in response.headers) buf.writeUtf8("$name: $value\n")
        buf.writeUtf8("\n")
        buf.write(bodyBytes)
        return buf.readByteArray()
    }

    companion object {
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024 // 5 MiB
    }
}

package com.hereliesaz.doxray.net

import android.app.Application
import com.hereliesaz.doxray.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Singleton container for the two `OkHttpClient` instances used across
 * doxxr. Must be initialised once from `Application.onCreate` so the
 * `CaptureInterceptor` has a real directory to write to.
 *
 * Two flavours:
 *  - [api]: minimal client for authenticated JSON APIs.
 *  - [browser]: full browser-shaped client (UA + Accept headers +
 *    cookie jar) for anti-bot scraping targets.
 *
 * Both pass through [CaptureInterceptor] gated by
 * `BuildConfig.DEBUG_CAPTURE_HTTP`.
 */
object HttpClients {

    private var apiClient: OkHttpClient? = null
    private var browserClient: OkHttpClient? = null

    fun init(app: Application) {
        val baseDir = app.getExternalFilesDir(null) ?: app.filesDir
        val capturesDir = File(baseDir, "captures")
        val writer = FileCaptureWriter(capturesDir)
        val capture = CaptureInterceptor(writer) { BuildConfig.DEBUG_CAPTURE_HTTP }

        apiClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(capture)
            .build()

        browserClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(InMemoryCookieJar())
            .addInterceptor(BrowserHeadersInterceptor())
            .addInterceptor(capture)
            .build()
    }

    fun api(): OkHttpClient =
        apiClient ?: error("HttpClients.init() not called — register DoxrayApp in AndroidManifest.xml")

    fun browser(): OkHttpClient =
        browserClient ?: error("HttpClients.init() not called — register DoxrayApp in AndroidManifest.xml")

    /**
     * Adds a stable set of headers that make requests look like a desktop
     * Chrome session. Existing headers on the caller's request take
     * precedence (e.g. JSON Content-Type stays JSON).
     */
    private class BrowserHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val original = chain.request()
            val builder = original.newBuilder()
            fun maybe(name: String, value: String) {
                if (original.header(name) == null) builder.header(name, value)
            }
            maybe("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            maybe("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            maybe("Accept-Language", "en-US,en;q=0.9")
            // Do NOT set Accept-Encoding — let OkHttp manage transparent gzip decompression.
            maybe("Upgrade-Insecure-Requests", "1")
            maybe("Sec-Fetch-Dest", "document")
            maybe("Sec-Fetch-Mode", "navigate")
            maybe("Sec-Fetch-Site", "none")
            maybe("Sec-Fetch-User", "?1")
            return chain.proceed(builder.build())
        }
    }
}

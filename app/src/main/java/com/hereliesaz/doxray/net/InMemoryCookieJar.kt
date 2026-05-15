package com.hereliesaz.doxray.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime cookie jar partitioned by host. Cookies are replaced
 * per-host on each response; expiry is not honoured (we treat them as
 * session-scoped because that's all the scrapers need).
 */
class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        store[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host] ?: emptyList()
}

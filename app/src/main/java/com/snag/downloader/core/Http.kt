package com.snag.downloader.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Page(val body: String, val finalUrl: String, val code: Int)

/**
 * Single OkHttp entry point with browser-like defaults.
 *
 * Cookies are kept in memory per host so a page request and the JSON API call that
 * follows it share the same session — TikTok's edge ties the two together.
 */
object Http {

    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/138.0.0.0 Safari/537.36"

    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    const val UA_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    private val cookies = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val list = store.getOrPut(url.host) { mutableListOf() }
            synchronized(list) {
                cookies.forEach { fresh ->
                    list.removeAll { it.name == fresh.name }
                    list.add(fresh)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            synchronized(list) {
                list.removeAll { it.expiresAt < now }
                return list.toList()
            }
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // TikTok's edge serves its WAF page to HTTP/2 clients but the full document
            // to HTTP/1.1, so pin the protocol for both pages and media.
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookies)
            .build()
    }

    fun get(
        url: String,
        userAgent: String = UA_DESKTOP,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = this.client,
    ): Page {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${response.request.url}")
            }
            return Page(
                body = response.body?.string().orEmpty(),
                finalUrl = response.request.url.toString(),
                code = response.code,
            )
        }
    }
}

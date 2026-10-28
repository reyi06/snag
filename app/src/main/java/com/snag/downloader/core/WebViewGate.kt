package com.snag.downloader.core

import android.annotation.SuppressLint
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** One post link scraped from a rendered profile grid. */
data class WebPost(
    val url: String,
    val cover: String?,
)

/** A signed same-origin request the page made, captured for an exact replay. */
data class CapturedRequest(
    val url: String,
    val headers: Map<String, String>,
    val method: String = "GET",
)

/** What the in-app browser saw once a page (and its bot challenge) settled. */
data class WebSnapshot(
    val url: String,
    val title: String?,
    val universalData: String?,
    val apiData: String?,
    val videoUrl: String?,
    val posterUrl: String?,
    val meta: Map<String, String>,
    val posts: List<WebPost> = emptyList(),
) {
    fun og(key: String): String? = meta[key]?.takeIf { it.startsWith("http") }

    /**
     * The generic TikTok shell ships a promotional player asset before hydration.
     * A settled page is only useful once it has data or a non-chrome video.
     */
    val hasPayload: Boolean
        get() = !universalData.isNullOrBlank() ||
            !apiData.isNullOrBlank() ||
            (videoUrl != null && !isChromeAsset(videoUrl)) ||
            og("og:video") != null ||
            og("og:video:url") != null ||
            og("og:image") != null

    private fun isChromeAsset(url: String): Boolean =
        url.contains("webapp-desktop/playback", ignoreCase = true) ||
            url.contains("/tiktok_web_login_static/", ignoreCase = true)
}

/**
 * Last-resort page loader.
 *
 * Some platform edges answer plain HTTP clients with a bot challenge or a client-rendered
 * shell. A WebView runs the challenge with the same engine a browser uses and can read the
 * settled DOM. It is attached once by [MainActivity] and only used while the app is in front;
 * background work still has the HTTP and OpenGraph strategies.
 *
 * Only reads the DOM. No JavaScript interface is exposed to the page, file and content
 * access are disabled, and navigation is handled by the default WebViewClient.
 */
object WebViewGate {

    private val mutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var capturedItemList: CapturedRequest? = null

    fun lastItemListRequest(): CapturedRequest? = capturedItemList

    fun clearCapturedItemList() {
        capturedItemList = null
    }

    fun attach(view: WebView) {
        webView = view
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = Http.UA_DESKTOP
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    /** Loads [url] and returns the settled page, or null when no in-app browser is available. */
    suspend fun snapshot(
        url: String,
        timeoutMs: Long = 22_000,
        mobileUserAgent: Boolean = false,
        accept: (WebSnapshot) -> Boolean = { it.hasPayload },
    ): WebSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            val view = webView ?: return@withContext null
            mutex.withLock {
                suspendCancellableCoroutine { continuation ->
                    val deadline = SystemClock.uptimeMillis() + timeoutMs
                    val previousUserAgent = view.settings.userAgentString
                    if (mobileUserAgent) view.settings.userAgentString = Http.UA_IPHONE
                    capturedItemList = null
                    var settled = false
                    var polling = false
                    var last: WebSnapshot? = null

                    fun complete(value: WebSnapshot?) {
                        if (settled) return
                        settled = true
                        view.stopLoading()
                        view.webViewClient = WebViewClient()
                        if (mobileUserAgent) view.settings.userAgentString = previousUserAgent
                        if (continuation.isActive) continuation.resume(value)
                    }

                    fun evaluate() {
                        if (settled) return
                        if (SystemClock.uptimeMillis() >= deadline) {
                            complete(last)
                            return
                        }
                        view.evaluateJavascript(SCRIPT) { raw ->
                            val snapshot = parseSnapshot(raw)
                            if (snapshot != null) {
                                last = snapshot
                                if (accept(snapshot)) {
                                    complete(snapshot)
                                    return@evaluateJavascript
                                }
                            }
                            view.postDelayed({ evaluate() }, 1_200)
                        }
                    }

                    fun startPolling() {
                        if (polling || settled) return
                        polling = true
                        view.postDelayed({ evaluate() }, 1_500)
                    }

                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            startPolling()
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val requestUrl = request.url?.toString().orEmpty()
                            if (requestUrl.contains("post/item_list") &&
                                request.method != "OPTIONS"
                            ) {
                                capturedItemList = CapturedRequest(
                                    url = requestUrl,
                                    headers = request.requestHeaders.orEmpty(),
                                    method = request.method,
                                )
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    view.loadUrl(url)
                    view.postDelayed({ complete(last) }, timeoutMs + 500)

                    continuation.invokeOnCancellation {
                        settled = true
                        view.stopLoading()
                    }
                }
            }
        }

    /**
     * Runs [script] in the currently loaded page and keeps polling `window.__snagItems`
     * until the page's own fetch resolves it. Used for same-origin JSON calls where the
     * page already has the session and browser context.
     */
    suspend fun evaluate(script: String, timeoutMs: Long = 12_000): String? =
        withContext(Dispatchers.Main.immediate) {
            val view = webView ?: return@withContext null
            mutex.withLock {
                suspendCancellableCoroutine { continuation ->
                    val deadline = SystemClock.uptimeMillis() + timeoutMs
                    var settled = false

                    fun complete(value: String?) {
                        if (settled) return
                        settled = true
                        if (continuation.isActive) continuation.resume(value)
                    }

                    fun decode(raw: String?): String? {
                        if (raw.isNullOrBlank() || raw == "null") return null
                        return runCatching {
                            JSONTokener(raw).nextValue() as? String
                        }.getOrNull()
                    }

                    fun poll() {
                        if (settled) return
                        if (SystemClock.uptimeMillis() >= deadline) {
                            complete(null)
                            return
                        }
                        view.evaluateJavascript("window.__snagItems") { raw ->
                            val value = decode(raw)
                            if (value != null) complete(value)
                            else view.postDelayed({ poll() }, 400)
                        }
                    }

                    view.evaluateJavascript(script) { poll() }
                    view.postDelayed({ complete(null) }, timeoutMs + 500)

                    continuation.invokeOnCancellation {
                        settled = true
                    }
                }
            }
        }

    private fun parseSnapshot(raw: String?): WebSnapshot? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val value = runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? JSONObject
            ?: return null
        val meta = mutableMapOf<String, String>()
        value.optJSONObject("meta")?.let { objectValue ->
            val keys = objectValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val content = objectValue.optString(key)
                if (content.isNotBlank()) meta[key] = content
            }
        }
        return WebSnapshot(
            url = value.optString("url"),
            title = value.optString("title").takeIf { it.isNotBlank() },
            universalData = value.optString("uni").takeIf { it.isNotBlank() },
            apiData = value.optString("api").takeIf { it.isNotBlank() },
            videoUrl = value.optString("video").takeIf { it.startsWith("http") },
            posterUrl = value.optString("poster").takeIf { it.startsWith("http") },
            meta = meta,
            posts = buildList {
                val array = value.optJSONArray("posts") ?: return@buildList
                for (index in 0 until array.length()) {
                    val post = array.optJSONObject(index) ?: continue
                    val url = post.optString("url")
                    if (!url.startsWith("http")) continue
                    add(
                        WebPost(
                            url = url,
                            cover = post.optString("cover").takeIf { it.startsWith("http") },
                        )
                    )
                }
            },
        )
    }

    private val SCRIPT = """
        (function () {
          var out = { url: location.href, title: document.title || "", uni: "", api: "", video: "", poster: "", meta: {}, posts: [] };
          var el = document.getElementById("__UNIVERSAL_DATA_FOR_REHYDRATION__");
          if (el) out.uni = el.textContent || "";
          var api = document.getElementById("api-data");
          if (api) out.api = api.textContent || "";
          var video = document.querySelector("video");
          if (video) {
            out.video = video.currentSrc || video.src || "";
            out.poster = video.poster || "";
          }
          var meta = document.querySelectorAll('meta[property^="og:"],meta[name^="twitter:"]');
          for (var i = 0; i < meta.length; i++) {
            var key = meta[i].getAttribute("property") || meta[i].getAttribute("name");
            if (key) out.meta[key] = meta[i].getAttribute("content") || "";
          }
          var seen = {};
          var anchors = document.querySelectorAll('a[href*="/video/"]');
          for (var j = 0; j < anchors.length; j++) {
            var href = anchors[j].href || "";
            if (!href || seen[href]) continue;
            seen[href] = 1;
            var img = anchors[j].querySelector("img");
            out.posts.push({
              url: href,
              cover: img ? (img.currentSrc || img.src || "") : ""
            });
          }
          out.posts = out.posts.slice(0, 90);
          return out;
        })()
    """.trimIndent()
}

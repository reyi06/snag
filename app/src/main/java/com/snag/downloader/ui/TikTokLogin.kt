package com.snag.downloader.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.snag.downloader.TikTokLoginActivity
import com.snag.downloader.core.CapturedRequest
import com.snag.downloader.core.Http
import com.snag.downloader.data.TikTokProfileHandoff
import com.snag.downloader.data.TikTokSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

private const val LTR_SCRIPT = """
    (function () {
      document.documentElement.dir = 'ltr';
      var style = document.createElement('style');
      style.innerHTML = 'html,body{direction:ltr !important}' +
        'input,textarea{direction:ltr !important;text-align:left !important;unicode-bidi:normal !important}';
      document.head.appendChild(style);
    })();
"""

/**
 * Some Android WebViews resolve TikTok's `height:100%` against a zero-height layout
 * viewport. Setting a pixel height is enough to make the profile page paint without
 * touching TikTok's modal or navigation state.
 */
private fun layoutFixScript(): String = """
    (function () {
      if (window.__snagLayoutFix) return;
      window.__snagLayoutFix = true;

      function fixHeight() {
        var pixels = window.innerHeight;
        if (!pixels) return;
        var height = pixels + 'px';
        var nodes = [
          document.documentElement,
          document.body,
          document.getElementById('app')
        ];
        for (var i = 0; i < nodes.length; i++) {
          if (nodes[i]) {
            nodes[i].style.setProperty('height', height, 'important');
            nodes[i].style.setProperty('min-height', height, 'important');
          }
        }
      }

      fixHeight();
      var tries = 0;
      var timer = setInterval(function () {
        fixHeight();
        if (++tries >= 40) clearInterval(timer);
      }, 500);
      window.addEventListener('resize', fixHeight);
      if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', fixHeight);
      }
    })();
"""

/**
 * TikTok's mobile page sets html/body height to 100%, but some Android WebViews
 * resolve that against a zero-height layout viewport. Fixing the height in pixels
 * makes the login form paint. Also dismiss the "Get the full app experience" sheet
 * that otherwise leaves a gray mask over the form.
 */
private fun loginHelperScript(): String = """
    (function () {
      if (window.__snagLoginHelper) return;
      window.__snagLoginHelper = true;

      function fixHeight() {
        var pixels = window.innerHeight;
        if (!pixels) return;
        var height = pixels + 'px';
        var nodes = [
          document.documentElement,
          document.body,
          document.getElementById('app')
        ];
        for (var i = 0; i < nodes.length; i++) {
          if (nodes[i]) {
            nodes[i].style.setProperty('height', height, 'important');
            nodes[i].style.setProperty('min-height', height, 'important');
          }
        }
      }

      function hideAppPrompt() {
        var cancel = document.querySelector('[data-e2e="bottom-cta-cancel-btn"]');
        if (cancel) {
          cancel.click();
          return;
        }
        var nodes = document.querySelectorAll('button,[role="button"]');
        for (var i = 0; i < nodes.length; i++) {
          if ((nodes[i].innerText || '').trim().toLowerCase() === 'not now') {
            nodes[i].click();
            return;
          }
        }
      }

      function tidy() {
        fixHeight();
        hideAppPrompt();
      }

      tidy();
      var tries = 0;
      var timer = setInterval(function () {
        tidy();
        if (++tries >= 120) clearInterval(timer);
      }, 500);
      window.addEventListener('resize', fixHeight);
      if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', fixHeight);
      }
    })();
"""

/**
 * Returns an action that opens TikTok's own login page. When the cookie store gains a
 * TikTok session, the activity returns RESULT_OK and the caller can retry the profile.
 */
@Composable
fun rememberTikTokSignIn(onSignedIn: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentCallback by rememberUpdatedState(onSignedIn)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) currentCallback()
    }
    return remember(launcher, context) {
        {
            launcher.launch(Intent(context, TikTokLoginActivity::class.java))
        }
    }
}

/**
 * Opens the authenticated profile page in a visible WebView. TikTok can ask for a slider
 * verification there; the user can complete it, and the page's item list is returned.
 */
@Composable
fun rememberTikTokProfileVerify(
    onItems: (String) -> Unit,
    onVerified: () -> Unit = {},
): (String) -> Unit {
    val context = LocalContext.current
    val currentCallback by rememberUpdatedState(onItems)
    val currentVerified by rememberUpdatedState(onVerified)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = TikTokProfileHandoff.raw
            TikTokProfileHandoff.raw = null
            if (raw != null) currentCallback(raw) else currentVerified()
        }
    }
    return remember(launcher, context) {
        { profileUrl: String ->
            launcher.launch(
                Intent(context, TikTokLoginActivity::class.java)
                    .putExtra(TikTokLoginActivity.EXTRA_PROFILE_URL, profileUrl)
            )
        }
    }
}

@Composable
fun TikTokLoginScreen(
    profileUrl: String? = null,
    onClose: () -> Unit,
    onSignedIn: () -> Unit,
    onItems: (String) -> Unit = {},
    onVerified: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf<CapturedRequest?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loginDesktopMode by remember { mutableStateOf(false) }
    var verifyMobileHarvest by remember { mutableStateOf(false) }
    var harvestStartedAt by remember { mutableStateOf(0L) }
    val currentOnItems by rememberUpdatedState(onItems)
    val currentOnVerified by rememberUpdatedState(onVerified)

    fun applyBrowserMode(desktop: Boolean) {
        loginDesktopMode = desktop
        loadError = null
        webView?.settings?.userAgentString = if (desktop) Http.UA_DESKTOP else Http.UA_MOBILE
        webView?.reload()
    }

    fun useMobileHarvester() {
        verifyMobileHarvest = true
        harvestStartedAt = System.currentTimeMillis()
        loadError = null
        captured = null
        webView?.settings?.userAgentString = Http.UA_MOBILE
        webView?.reload()
    }

    LaunchedEffect(profileUrl) {
        if (profileUrl != null) return@LaunchedEffect
        var checking = false
        while (isActive) {
            if (!checking && TikTokSession.hasSessionCookie()) {
                checking = true
                when (TikTokSession.validate()) {
                    TikTokSession.Status.Valid -> {
                        onSignedIn()
                        break
                    }

                    TikTokSession.Status.Expired,
                    TikTokSession.Status.Unknown,
                    -> Unit
                }
                checking = false
            }
            delay(1_500)
        }
    }

    LaunchedEffect(profileUrl) {
        if (profileUrl == null) return@LaunchedEffect
        while (isActive) {
            val view = webView
            if (view != null) {
                if (!verifyMobileHarvest) {
                    val videoLinks = evalJs(
                        view,
                        "String(document.querySelectorAll('a[href*=\"/video/\"]').length)",
                    )?.toIntOrNull() ?: 0
                    if (videoLinks > 0) useMobileHarvester()
                }

                val requests = LinkedHashMap<String, CapturedRequest>()
                val perfJson = evalJs(
                    view,
                    "JSON.stringify(Array.from(new Set(performance.getEntriesByType('resource')" +
                        ".map(function(e){return e.name})" +
                        ".filter(function(n){var u=n.toLowerCase();return " +
                        "u.indexOf('post/item_list')>=0 && u.indexOf('repost')<0})" +
                        ".slice(-3))))",
                )
                val perf = runCatching { JSONArray(perfJson ?: "[]") }.getOrDefault(JSONArray())
                captured?.takeIf { isOwnPostListRequest(it.url) }?.let {
                    requests[it.url] = it
                }
                for (index in perf.length() - 1 downTo 0) {
                    val url = perf.optString(index)
                    if (url.isNotBlank() && isOwnPostListRequest(url)) {
                        requests.putIfAbsent(url, CapturedRequest(url, emptyMap()))
                    }
                }
                for (request in requests.values) {
                    val raw = fetchCaptured(view, request)
                    if (raw != null && hasOwnItems(raw, profileUrl)) {
                        CookieManager.getInstance().flush()
                        currentOnItems(raw)
                        return@LaunchedEffect
                    }
                    if (verifyMobileHarvest && harvestStartedAt > 0 &&
                        System.currentTimeMillis() - harvestStartedAt > HARVEST_TIMEOUT_MS
                    ) break
                }

                if (verifyMobileHarvest && harvestStartedAt > 0 &&
                    System.currentTimeMillis() - harvestStartedAt > HARVEST_TIMEOUT_MS
                ) {
                    CookieManager.getInstance().flush()
                    currentOnVerified()
                    return@LaunchedEffect
                }
            }
            delay(2_500)
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(scheme.surface)
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (profileUrl == null) "Sign in to TikTok" else "Verify on TikTok",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
            if (profileUrl == null) {
                TextButton(onClick = { applyBrowserMode(!loginDesktopMode) }) {
                    Text(if (loginDesktopMode) "Mobile" else "Desktop")
                }
            } else if (!verifyMobileHarvest) {
                TextButton(onClick = { useMobileHarvester() }) {
                    Text("Load videos")
                }
            } else {
                TextButton(onClick = { useMobileHarvester() }) {
                    Text("Retry load")
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
        }
        HorizontalDivider(thickness = 1.dp, color = scheme.outline)
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = snagColors().accent,
                trackColor = scheme.surfaceVariant,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = if (
                            (profileUrl != null && !verifyMobileHarvest) || loginDesktopMode
                        ) {
                            Http.UA_DESKTOP
                        } else {
                            Http.UA_MOBILE
                        }
                        mediaPlaybackRequiresUserGesture = true
                        allowFileAccess = false
                        allowContentAccess = false
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = false
                        displayZoomControls = false
                    }
                    setBackgroundColor(android.graphics.Color.WHITE)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    isVerticalScrollBarEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            return blockNonWebScheme(view, request?.url?.toString())
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            url: String?,
                        ): Boolean {
                            return blockNonWebScheme(view, url)
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            loadError = null
                            loading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            val page = view ?: return
                            page.evaluateJavascript(LTR_SCRIPT, null)
                            if (profileUrl == null) {
                                page.evaluateJavascript(loginHelperScript(), null)
                            } else {
                                page.evaluateJavascript(layoutFixScript(), null)
                            }
                            if (profileUrl != null) CookieManager.getInstance().flush()
                            page.postDelayed({
                                page.evaluateJavascript(
                                    "(function(){var b=document.body;if(!b)return 0;" +
                                        "return b.innerText.trim().length+" +
                                        "b.querySelectorAll('input,button,form').length;})()",
                                ) { raw ->
                                    val score = raw?.trim('"')?.toIntOrNull() ?: 0
                                    if (score == 0 && loadError == null) {
                                        if (!loginDesktopMode && profileUrl == null) {
                                            applyBrowserMode(true)
                                        } else {
                                            loadError = "TikTok returned an empty page."
                                        }
                                    }
                                }
                            }, 1_800)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            val description = error?.description?.toString().orEmpty()
                            if (error?.errorCode == ERROR_UNSUPPORTED_SCHEME ||
                                description.contains("ERR_UNKNOWN_SCHEME", ignoreCase = true)
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    loadError = "TikTok tried to open another app. Use phone / email to sign in."
                                }
                                return
                            }
                            if (request?.isForMainFrame != false) {
                                loading = false
                                loadError = description
                                    .takeIf { it.isNotBlank() }
                                    ?: "TikTok didn't finish loading."
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                loading = false
                                val code = errorResponse?.statusCode
                                loadError = if (code != null && code > 0) {
                                    "TikTok returned HTTP $code."
                                } else {
                                    "TikTok didn't finish loading."
                                }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val requestUrl = request?.url?.toString().orEmpty()
                            if (request != null &&
                                isOwnPostListRequest(requestUrl) &&
                                request.method != "OPTIONS"
                            ) {
                                val copy = CapturedRequest(
                                    url = requestUrl,
                                    headers = request.requestHeaders.orEmpty(),
                                    method = request.method,
                                )
                                view?.post { captured = copy }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loading = newProgress < 100
                        }
                    }
                    loadUrl(profileUrl ?: "https://www.tiktok.com/login?lang=en")
                    webView = this
                }
            },
            update = { webView = it },
        )
        if (loadError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.background.copy(alpha = 0.96f))
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "TikTok didn't load",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    loadError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SnagSecondaryButton(
                    text = "Try again",
                    onClick = {
                        loadError = null
                        webView?.reload()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        }
    }
}

private fun blockNonWebScheme(view: WebView?, url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull() ?: return false
    if (scheme == "http" || scheme == "https") return false

    // TikTok's "Open TikTok" links use tiktok://, snssdk1128://, market:// or intent://.
    // Never let the WebView hand these to the system; that is what opened the app store.
    val context = view?.context
    if (context != null) {
        Toast.makeText(
            context,
            "Use phone / email to sign in to TikTok.",
            Toast.LENGTH_SHORT,
        ).show()
    }
    return true
}

private suspend fun fetchCaptured(webView: WebView, capture: CapturedRequest): String? {
    val script = """
        (function () {
          window.__snagItems = null;
          fetch(${JSONObject.quote(capture.url)}, {
            credentials: 'include',
            headers: { 'Accept': 'application/json, text/plain, */*' }
          }).then(function (r) { return r.text(); })
            .then(function (t) { window.__snagItems = t; })
            .catch(function (e) { window.__snagItems = 'ERR:' + e; });
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
    return withTimeoutOrNull(6_000) {
        var result: String? = null
        while (result == null) {
            val value = evalJs(webView, "window.__snagItems")
            if (value != null && !value.startsWith("ERR:")) {
                result = value
            } else {
                delay(500)
            }
        }
        result
    }
}

private suspend fun evalJs(webView: WebView, script: String): String? =
    suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { raw ->
            continuation.resume(decodeJs(raw))
        }
    }

private fun decodeJs(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}

private const val HARVEST_TIMEOUT_MS = 18_000L

private fun isOwnPostListRequest(url: String): Boolean {
    val normalized = url.lowercase()
    return normalized.contains("post/item_list") && !normalized.contains("repost")
}

private fun hasOwnItems(raw: String, profileUrl: String): Boolean = runCatching {
    val expectedHandle = Uri.parse(profileUrl).path.orEmpty()
        .substringAfter("/@", "")
        .substringBefore('/')
        .lowercase()
    val items = JSONObject(raw).optJSONArray("itemList") ?: return@runCatching false
    (0 until items.length()).any { index ->
        val item = items.optJSONObject(index) ?: return@any false
        if (item.optBoolean("isRepost") || item.optBoolean("is_repost") ||
            item.optJSONObject("repostInfo") != null || item.optJSONObject("repost_info") != null
        ) return@any false
        val author = item.optJSONObject("author")
        val authorHandle = author?.optString("uniqueId")
            ?.removePrefix("@")
            ?.lowercase()
        if (expectedHandle.isNotBlank() && !authorHandle.isNullOrBlank() &&
            authorHandle != expectedHandle
        ) return@any false
        val video = item.optJSONObject("video")
        val hasVideo = video?.optString("playAddr").orEmpty().isNotBlank() ||
            video?.optString("downloadAddr").orEmpty().isNotBlank() ||
            video?.optJSONArray("bitrateInfo")?.length()?.let { it > 0 } == true
        val hasPhotos = item.optJSONObject("imagePost")?.optJSONArray("images")?.length()?.let { it > 0 } == true
        hasVideo || hasPhotos
    }
}.getOrDefault(false)

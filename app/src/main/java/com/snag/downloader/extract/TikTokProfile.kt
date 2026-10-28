package com.snag.downloader.extract

import android.webkit.CookieManager
import com.snag.downloader.core.ExtractionException
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.ProfileBundle
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.WebPost
import com.snag.downloader.core.WebSnapshot
import com.snag.downloader.core.WebViewGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Protocol
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Reads a public profile: identity from the server-rendered page, then the video list
 * from TikTok's own post API using the `secUid` from that payload.
 *
 * TikTok's edge often blocks the direct page request. When that happens, the same profile
 * is loaded in the in-app browser once; its settled rehydration payload supplies the
 * identity and `secUid`, and the WebView cookies are forwarded to the post API.
 */
class TikTokProfileExtractor(
    private val identityCache: TikTokIdentityCache? = null,
) {

    private val apiClient by lazy {
        Http.client.newBuilder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    private val embedClient by lazy {
        Http.client.newBuilder()
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Public profiles are read from TikTok's creator embed first. The embed exposes
     * the profile metadata and recent posts without a login; its first post embed
     * carries the stable `secUid`, which unlocks the creator item-list API.
     *
     * A cached identity lets later opens call the creator API immediately, even when
     * one of the embeds is temporarily returning 503.
     */
    suspend fun fetch(url: String): ProfileBundle {
        val direct = runCatching { fetchDirect(url) }.getOrNull()
        if (direct != null) return direct
        return fetchViaWebView(url)
    }

    suspend fun fetchDirect(
        url: String,
        forceFullProfile: Boolean = false,
    ): ProfileBundle = withContext(Dispatchers.IO) {
        val handle = TikTokProfileUrl.handle(url)
            ?: throw ExtractionException.Unsupported(url)
        val profileUrl = "https://www.tiktok.com/@$handle"
        val cached = identityCache?.get(handle)
        var identity = cached
        var apiTried = false

        // A cached secUid skips the fragile embeds. This is the path that makes a
        // retry fast when the creator embed or post embed is throttled.
        if (cached?.secUid?.isNotBlank() == true) {
            apiTried = true
            val items = fetchCreatorItemsWithRetry(
                cached.secUid,
                profileUrl,
                forceFullProfile,
            )
            if (items.isNotEmpty()) {
                return@withContext fullProfile(handle, cached, items, null)
            }
        }

        var embed: JSONObject? = null
        var user: JSONObject? = null
        var recent = emptyList<MediaBundle>()
        val embedResult = runCatching { loadEmbed("/embed/@$handle") }
        embed = embedResult.getOrNull()
        if (embed != null) {
            user = embed.optJSONObject("userInfo")
            if (user != null && user.optInt("code", 200) !in listOf(0, 200)) {
                throw ExtractionException.Blocked(
                    "profile",
                    user.optString("message").ifBlank { "TikTok rejected the profile" },
                )
            }
            recent = embed.optJSONArray("videoList")
                ?.let { parseRecent(it, handle, profileUrl) }
                .orEmpty()
            if (identity == null) {
                identity = resolveIdentity(embed, handle)
                identity?.let { identityCache?.put(it) }
            } else if (apiTried) {
                // A successful embed may have refreshed TikTok's anonymous session;
                // allow one more API scan before accepting the recent-only list.
                apiTried = false
            }
        } else if (identity == null) {
            throw embedResult.exceptionOrNull()
                ?: ExtractionException.NoMedia("profile", "creator embed was unavailable")
        }

        val secUid = identity?.secUid
        val items = if (!apiTried && !secUid.isNullOrBlank()) {
            fetchCreatorItemsWithRetry(secUid, profileUrl, forceFullProfile)
        } else {
            emptyList()
        }

        if (items.isNotEmpty()) {
            return@withContext fullProfile(handle, identity, items, user)
        }
        if (recent.isNotEmpty()) {
            return@withContext partialProfile(handle, identity, user, recent)
        }
        throw ExtractionException.NoMedia("profile", "profile has no public videos")
    }

    private fun fullProfile(
        fallbackHandle: String,
        identity: TikTokIdentity?,
        items: List<MediaBundle>,
        user: JSONObject?,
    ): ProfileBundle {
        val actualHandle = user?.optString("uniqueId")
            ?.takeIf { it.isNotBlank() }
            ?: identity?.handle
            ?: fallbackHandle
        val avatar = identity?.avatarUrl
            ?: user?.optString("avatarThumbUrl")?.takeIf { it.isNotBlank() }
            ?: user?.optString("avatarThumb")?.takeIf { it.isNotBlank() }
        val videoCount = identity?.videoCount?.takeIf { it > 0 }
            ?: items.size.toLong()
        val merged = TikTokIdentity(
            handle = actualHandle,
            secUid = identity?.secUid.orEmpty(),
            userId = identity?.userId ?: user?.optString("id")?.takeIf { it.isNotBlank() },
            nickname = identity?.nickname
                ?: user?.optString("nickname")?.takeIf { it.isNotBlank() },
            avatarUrl = avatar,
            bio = identity?.bio
                ?: user?.optString("signature")?.takeIf { it.isNotBlank() },
            followerCount = identity?.followerCount?.takeIf { it > 0 }
                ?: user?.optLong("followerCount", 0L) ?: 0L,
            videoCount = videoCount,
        )
        if (merged.secUid.isNotBlank()) identityCache?.put(merged)
        return ProfileBundle(
            platform = SourcePlatform.TIKTOK,
            url = "https://www.tiktok.com/@$actualHandle",
            handle = actualHandle,
            nickname = merged.nickname,
            avatarUrl = merged.avatarUrl,
            bio = merged.bio,
            followerCount = merged.followerCount,
            videoCount = videoCount,
            items = items,
            provenance = "creator-embed",
        )
    }

    private fun partialProfile(
        fallbackHandle: String,
        identity: TikTokIdentity?,
        user: JSONObject?,
        recent: List<MediaBundle>,
    ): ProfileBundle {
        val actualHandle = user?.optString("uniqueId")
            ?.takeIf { it.isNotBlank() }
            ?: identity?.handle
            ?: fallbackHandle
        val avatar = identity?.avatarUrl
            ?: user?.optString("avatarThumbUrl")?.takeIf { it.isNotBlank() }
            ?: user?.optString("avatarThumb")?.takeIf { it.isNotBlank() }
        val knownCount = identity?.videoCount ?: 0L
        if (identity != null) identityCache?.put(identity)
        return ProfileBundle(
            platform = SourcePlatform.TIKTOK,
            url = "https://www.tiktok.com/@$actualHandle",
            handle = actualHandle,
            nickname = identity?.nickname
                ?: user?.optString("nickname")?.takeIf { it.isNotBlank() },
            avatarUrl = avatar,
            bio = identity?.bio
                ?: user?.optString("signature")?.takeIf { it.isNotBlank() },
            followerCount = identity?.followerCount?.takeIf { it > 0 }
                ?: user?.optLong("followerCount", 0L) ?: 0L,
            videoCount = knownCount,
            items = recent,
            provenance = "creator-embed-recent",
            isPartial = true,
            note = "TikTok did not return the full profile. Showing recent posts only.",
        )
    }

    private suspend fun loadEmbed(
        path: String,
        attempts: Int = 5,
        initialDelayMs: Long = 350L,
    ): JSONObject {
        var last: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val page = Http.get(
                    url = "https://www.tiktok.com$path",
                    userAgent = Http.UA_DESKTOP,
                    headers = mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
                        "Referer" to "https://www.tiktok.com/",
                        "Sec-Fetch-Site" to "same-origin",
                    ),
                    client = embedClient,
                )
                if (TikTokParser.isWaf(page.body)) {
                    throw ExtractionException.Blocked("profile", "TikTok's bot check appeared")
                }
                val state = frontityState(page.body)
                    ?: throw ExtractionException.NoMedia("profile", "creator embed state was missing")
                val data = state.optJSONObject("source")?.optJSONObject("data")
                    ?: throw ExtractionException.NoMedia("profile", "creator embed data was missing")
                return data.optJSONObject(path)
                    ?: data.optJSONObject("$path/")
                    ?: throw ExtractionException.NoMedia("profile", "creator embed payload was missing")
            } catch (t: Throwable) {
                last = t
                if (attempt < attempts - 1) {
                    val delayMs = (initialDelayMs * (1L shl attempt)).coerceAtMost(3_000L)
                    delay(delayMs)
                }
            }
        }
        throw last ?: ExtractionException.NoMedia("profile", "creator embed was unavailable")
    }

    private fun frontityState(html: String): JSONObject? {
        val marker = "__FRONTITY_CONNECT_STATE__"
        val markerAt = html.indexOf(marker)
        if (markerAt < 0) return null
        val start = html.indexOf('>', markerAt)
        if (start < 0) return null
        val end = html.indexOf("</script>", start)
        if (end < 0) return null
        return runCatching { JSONObject(html.substring(start + 1, end)) }.getOrNull()
    }

    private suspend fun resolveIdentity(
        embed: JSONObject,
        handle: String,
    ): TikTokIdentity? {
        val ids = embed.optJSONArray("videoList")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                list.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        if (ids.isEmpty()) return null

        val deadline = System.currentTimeMillis() + 12_000
        // A single post embed is occasionally gated even when the creator embed is
        // available. Try several recent posts before giving up on the full feed.
        for (id in ids.take(5)) {
            if (System.currentTimeMillis() >= deadline) break
            val media = runCatching {
                loadEmbed("/embed/v2/$id", attempts = 2, initialDelayMs = 300L)
            }.getOrNull() ?: continue
            val video = media.optJSONObject("videoData") ?: continue
            val author = video.optJSONObject("authorInfos") ?: continue
            val authorHandle = author.optString("uniqueId").removePrefix("@")
            if (authorHandle.isNotBlank() && !authorHandle.equals(handle, ignoreCase = true)) {
                identityCache?.remove(handle)
                continue
            }
            val secUid = author.optString("secUid")
            if (secUid.isBlank()) continue
            val stats = video.optJSONObject("authorStats")
            val covers = author.optJSONArray("coversLarger")
                ?: author.optJSONArray("coversMedium")
                ?: author.optJSONArray("covers")
            return TikTokIdentity(
                handle = authorHandle.ifBlank { handle },
                secUid = secUid,
                userId = author.optString("userId").takeIf { it.isNotBlank() },
                nickname = author.optString("nickName").takeIf { it.isNotBlank() },
                avatarUrl = covers?.optString(0)?.takeIf { it.startsWith("http") },
                bio = author.optString("signature").takeIf { it.isNotBlank() },
                followerCount = stats?.optLong("followerCount", 0L) ?: 0L,
                videoCount = stats?.optLong("videoCount", 0L) ?: 0L,
            )
        }
        return null
    }

    private fun parseRecent(
        list: org.json.JSONArray,
        handle: String,
        profileUrl: String,
    ): List<MediaBundle> {
        val out = LinkedHashMap<String, MediaBundle>()
        for (index in 0 until list.length()) {
            val post = list.optJSONObject(index) ?: continue
            val id = post.optString("id")
            if (id.isBlank()) continue
            val playUrl = post.optString("playAddr")
            if (!playUrl.startsWith("http")) continue
            val width = post.optInt("width", 0)
            val height = post.optInt("height", 0)
            out.putIfAbsent(
                id,
                MediaBundle(
                    id = id,
                    platform = SourcePlatform.TIKTOK,
                    sourceUrl = "https://www.tiktok.com/@$handle/video/$id",
                    title = post.optString("desc").takeIf { it.isNotBlank() },
                    author = com.snag.downloader.core.Author(handle = handle),
                    coverUrl = sequenceOf("coverUrl", "originCoverUrl", "dynamicCoverUrl")
                        .map { post.optString(it) }
                        .firstOrNull { it.startsWith("http") },
                    variants = listOf(
                        com.snag.downloader.core.MediaVariant(
                            kind = com.snag.downloader.core.MediaKind.VIDEO,
                            url = playUrl,
                            width = width,
                            height = height,
                            label = "source",
                        )
                    ),
                    provenance = "creator-embed",
                )
            )
        }
        return out.values.toList()
    }

    private suspend fun fetchCreatorItemsWithRetry(
        secUid: String,
        referer: String,
        forceFullProfile: Boolean,
    ): List<MediaBundle> {
        val rounds = if (forceFullProfile) 3 else 2
        repeat(rounds) { round ->
            val items = runCatching { fetchCreatorItems(secUid, referer) }
                .getOrDefault(emptyList())
            if (items.isNotEmpty()) return items
            if (round < rounds - 1) {
                // The first empty response is often a transient edge/rate-limit result.
                // Give TikTok a moment before deciding the profile is recent-only.
                delay(if (round == 0) 1_200L else 2_500L)
            }
        }
        return emptyList()
    }

    private suspend fun fetchCreatorItems(
        secUid: String,
        referer: String,
    ): List<MediaBundle> {
        val out = LinkedHashMap<String, MediaBundle>()
        var cursor = System.currentTimeMillis()
        var pages = 0
        var emptyPages = 0

        while (pages < MAX_CREATOR_PAGES && out.size < MAX_CREATOR_ITEMS) {
            val json = runCatching {
                requestCreatorPage(secUid, cursor.toString(), referer)
            }.getOrNull() ?: break
            val list = json.optJSONArray("itemList")
            val before = out.size
            parseItemList(json, referer, "creator").forEach {
                out.putIfAbsent(it.id, it)
            }
            val added = out.size - before
            pages++
            emptyPages = if (added == 0) emptyPages + 1 else 0
            if (!json.optBoolean("hasMorePrevious", false)) break
            if (emptyPages >= 3) break

            var next = Long.MAX_VALUE
            for (index in 0 until (list?.length() ?: 0)) {
                val timestamp = list?.optJSONObject(index)?.optLong("createTime", 0L) ?: 0L
                if (timestamp in 1 until next) next = timestamp
            }
            cursor = if (next == Long.MAX_VALUE || next * 1000 >= cursor) {
                cursor - 7L * 86_400_000L
            } else {
                next * 1000
            }
            delay(250)
        }
        return out.values.toList()
    }

    private suspend fun requestCreatorPage(
        secUid: String,
        cursor: String,
        referer: String,
    ): JSONObject {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val page = Http.get(
                    url = creatorItemListUrl(secUid, cursor),
                    userAgent = Http.UA_DESKTOP,
                    headers = mapOf(
                        "Referer" to referer,
                        "Accept" to "application/json, text/plain, */*",
                        "Sec-Fetch-Site" to "same-origin",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Dest" to "empty",
                    ),
                )
                val json = JSONObject(page.body)
                val status = json.optInt("statusCode", json.optInt("status_code", 0))
                if (status != 0) {
                    throw IllegalStateException(
                        json.optString("statusMsg").ifBlank { "profile API error $status" }
                    )
                }
                val empty = json.optJSONArray("itemList")?.length() == 0
                if (empty && json.optBoolean("hasMorePrevious") && attempt < 2) {
                    throw IllegalStateException("TikTok returned a transient empty page")
                }
                return json
            } catch (t: Throwable) {
                last = t
                if (attempt < 2) delay(500L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("profile API request failed")
    }

    private fun creatorItemListUrl(secUid: String, cursor: String): String {
        val params = linkedMapOf(
            "aid" to "1988",
            "app_language" to "en",
            "app_name" to "tiktok_web",
            "browser_language" to "en-US",
            "browser_name" to "Mozilla",
            "browser_online" to "true",
            "browser_platform" to "Win32",
            "browser_version" to "5.0 (Windows)",
            "channel" to "tiktok_web",
            "cookie_enabled" to "true",
            "cursor" to cursor,
            "device_id" to deviceId,
            "device_platform" to "web_pc",
            "focus_state" to "true",
            "from_page" to "user",
            "history_len" to "2",
            "is_fullscreen" to "false",
            "is_page_visible" to "true",
            "language" to "en",
            "os" to "windows",
            "priority_region" to "",
            "referer" to "",
            "region" to "US",
            "screen_height" to "1080",
            "screen_width" to "1920",
            "tz_name" to "UTC",
            "verifyFp" to "verify_" + (1..7)
                .map { "0123456789abcdef".random() }
                .joinToString(""),
            "webcast_language" to "en",
            "count" to "15",
            "secUid" to secUid,
            "type" to "1",
        )
        return "https://www.tiktok.com/api/creator/item_list/?" +
            params.entries.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}"
            }
    }

    private suspend fun fetchViaWebView(url: String): ProfileBundle = withContext(Dispatchers.IO) {
        val handle = TikTokProfileUrl.handle(url)
            ?: throw ExtractionException.Unsupported(url)

        val profileUrl = "https://www.tiktok.com/@$handle"
        val webSnapshot = loadWebViewSnapshot(profileUrl)
        val captured = WebViewGate.lastItemListRequest()

        var info = webSnapshot?.let(::userInfoFromSnapshot)
        var viaHttp = false
        if (info == null) {
            viaHttp = true
            info = runCatching { loadHttpUserInfo(profileUrl) }.getOrNull()
        }
        info ?: throw ExtractionException.Blocked(
            "profile",
            "TikTok's bot check appeared instead of the profile",
        )

        val user = info.optJSONObject("user")
            ?: throw ExtractionException.NoMedia("profile", "profile payload has no user")

        val stats = info.optJSONObject("stats")
        val secUid = user.optString("secUid")

        var items = if (secUid.isBlank()) emptyList() else {
            if (captured != null) {
                fetchItemsFromCapture(captured, profileUrl).ifEmpty {
                    fetchItems(secUid, profileUrl, useWebView = false)
                }
            } else {
                fetchItems(secUid, profileUrl, useWebView = false)
            }
        }

        if (items.isEmpty()) {
            if (items.isEmpty() && secUid.isNotBlank() && captured == null) {
                items = fetchItems(secUid, profileUrl, useWebView = false)
            }
        }

        // DOM-only profile rows have no playable variants. Treating them as batch items
        // produces a grid of empty cards; use the verification path instead.
        items = items.filter { it.variants.isNotEmpty() }

        ProfileBundle(
            platform = SourcePlatform.TIKTOK,
            url = profileUrl,
            handle = user.optString("uniqueId").ifBlank { handle },
            nickname = user.optString("nickname").takeIf { it.isNotBlank() },
            avatarUrl = user.optString("avatarLarger").takeIf { it.isNotBlank() },
            bio = user.optString("signature").takeIf { it.isNotBlank() },
            followerCount = stats?.optLong("followerCount", 0L) ?: 0L,
            videoCount = stats?.optLong("videoCount", 0L) ?: 0L,
            items = items,
            provenance = if (viaHttp) "profile-api" else "profile-webview",
            note = if (items.isEmpty()) {
                "Videos unavailable."
            } else if (items.firstOrNull()?.variants.isNullOrEmpty()) {
                "Videos open individually."
            } else {
                null
            },
        )
    }

    private fun loadHttpUserInfo(url: String): JSONObject? {
        val page = Http.get(
            url = url,
            userAgent = Http.UA_DESKTOP,
            headers = mapOf("Referer" to "https://www.tiktok.com/"),
        )
        if (TikTokParser.isWaf(page.body)) return null
        return userInfoFromScope(TikTokParser.scope(page.body))
    }

    private suspend fun loadWebViewSnapshot(url: String): WebSnapshot? =
        WebViewGate.snapshot(url, timeoutMs = 12_000, mobileUserAgent = false) { candidate ->
            candidate.universalData?.contains("webapp.user-detail") == true &&
                (candidate.posts.isNotEmpty() || WebViewGate.lastItemListRequest() != null)
        }

    private fun userInfoFromSnapshot(snapshot: WebSnapshot): JSONObject? {
        snapshot.universalData?.let { raw ->
            val scope = runCatching {
                JSONObject(raw).optJSONObject("__DEFAULT_SCOPE__")
            }.getOrNull()
            userInfoFromScope(scope)?.let { return it }
        }
        val api = snapshot.apiData ?: return null
        val root = runCatching { JSONObject(api) }.getOrNull() ?: return null
        return root.optJSONObject("userInfo")
            ?: root.optJSONObject("userDetail")?.optJSONObject("userInfo")
            ?: root.optJSONObject("user_detail")?.optJSONObject("userInfo")
    }

    private fun bundleFromPost(post: WebPost, profileUrl: String, handle: String): MediaBundle {
        val id = Regex("""/video/(\d+)""").find(post.url)?.groupValues?.get(1) ?: post.url
        return MediaBundle(
            id = id,
            platform = SourcePlatform.TIKTOK,
            sourceUrl = post.url,
            author = com.snag.downloader.core.Author(handle = handle),
            coverUrl = post.cover,
            variants = emptyList(),
            provenance = "profile-dom",
        )
    }

    private fun userInfoFromScope(scope: JSONObject?): JSONObject? =
        scope?.optJSONObject("webapp.user-detail")
            ?.optJSONObject("userInfo")

    private suspend fun fetchItemsFromCapture(
        capture: com.snag.downloader.core.CapturedRequest,
        referer: String,
    ): List<MediaBundle> {
        val headers = capture.headers
            .filterKeys { it.lowercase() !in DROP_HEADERS }
            .toMutableMap()
        if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            tiktokCookieHeader()?.let { headers["Cookie"] = it }
        }
        headers.putIfAbsent("Origin", "https://www.tiktok.com")
        headers.putIfAbsent("Sec-Fetch-Site", "same-origin")
        headers.putIfAbsent("Sec-Fetch-Mode", "cors")
        headers.putIfAbsent("Sec-Fetch-Dest", "empty")
        val userAgent = capture.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?: Http.UA_IPHONE
        val page = runCatching {
            Http.get(
                url = capture.url,
                userAgent = userAgent,
                headers = headers,
                client = apiClient,
            )
        }.getOrNull()
        val body = page?.body.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            val parsed = parseItemList(json, referer, "captured")
            if (parsed.isNotEmpty()) return parsed
        }

        val pageBody = fetchCaptureViaPage(capture)
        if (!pageBody.isNullOrBlank()) {
            val pageJson = runCatching { JSONObject(pageBody) }.getOrNull()
            if (pageJson != null) {
                return parseItemList(pageJson, referer, "page-captured")
            }
        }
        return emptyList()
    }

    private suspend fun fetchCaptureViaPage(
        capture: com.snag.downloader.core.CapturedRequest,
    ): String? {
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
        return WebViewGate.evaluate(script, timeoutMs = 3_000)
    }

    fun parseItems(raw: String, referer: String): List<MediaBundle> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        return parseItemList(json, referer, "verified")
    }

    private fun parseItemList(
        json: JSONObject,
        referer: String,
        source: String,
    ): List<MediaBundle> {
        val list = json.optJSONArray("itemList")
        if (list == null) return emptyList()
        val expectedHandle = TikTokProfileUrl.handle(referer)
        val out = LinkedHashMap<String, MediaBundle>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            if (item.isRepostItem()) continue
            val bundle = runCatching {
                TikTokParser.toBundle(item, referer, "profile-$source")
            }.getOrNull() ?: continue
            val authorHandle = bundle.author?.handle?.removePrefix("@")
            if (expectedHandle != null && authorHandle != null &&
                !authorHandle.equals(expectedHandle, ignoreCase = true)
            ) continue
            if (bundle.variants.isEmpty()) continue
            out.putIfAbsent(bundle.id, bundle)
        }
        return out.values.toList()
    }

    private fun JSONObject.isRepostItem(): Boolean =
        optBoolean("isRepost") ||
            optBoolean("is_repost") ||
            optJSONObject("repostInfo") != null ||
            optJSONObject("repost_info") != null

    private suspend fun fetchItems(
        secUid: String,
        referer: String,
        useWebView: Boolean,
    ): List<MediaBundle> {
        val out = LinkedHashMap<String, MediaBundle>()
        var cursor = "0"
        var pages = 0

        while (pages < MAX_PAGES && out.size < MAX_ITEMS) {
            val body = try {
                if (useWebView) {
                    requestPageViaWebView(secUid, cursor) ?: requestPage(secUid, cursor, referer)
                } else {
                    requestPage(secUid, cursor, referer)
                }
            } catch (t: Throwable) {
                break
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json == null) {
                break
            }

            if (json.optInt("statusCode", -1) != 0) break
            parseItemList(
                json = json,
                referer = referer,
                source = if (useWebView) "webview" else "http",
            ).forEach { out.putIfAbsent(it.id, it) }

            cursor = json.optString("cursor", "")
            if (!json.optBoolean("hasMore") || cursor.isBlank()) break
            pages++
            delay(300)
        }

        return out.values.toList()
    }

    private fun itemListPath(secUid: String, cursor: String): String {
        val encoded = URLEncoder.encode(secUid, "UTF-8")
        return buildString {
            append("/api/post/item_list/?")
            append("aid=1988&app_language=en&app_name=tiktok_web&browser_language=en-US")
            append("&browser_name=Mozilla&browser_platform=Win32&browser_version=5.0")
            append("&channel=tiktok_web&cookie_enabled=true&count=30&cursor=").append(cursor)
            append("&device_platform=web_pc&focus_state=true&from_page=user&history_len=3")
            append("&is_fullscreen=false&is_page_visible=true&os=windows")
            append("&priority_region=US&referer=&region=US")
            append("&screen_height=1080&screen_width=1920")
            append("&secUid=").append(encoded)
            append("&tz_name=Etc/UTC&webcast_language=en")
        }
    }

    private suspend fun requestPageViaWebView(secUid: String, cursor: String): String? {
        val path = itemListPath(secUid, cursor)
        val script = """
            (function () {
              window.__snagItems = null;
              fetch(${JSONObject.quote(path)}, {
                credentials: 'include',
                headers: { 'Accept': 'application/json, text/plain, */*' }
              }).then(function (r) { return r.text(); })
                .then(function (t) { window.__snagItems = t; })
                .catch(function (e) { window.__snagItems = 'ERR:' + e; });
            })();
        """.trimIndent()
        val body = WebViewGate.evaluate(script)
        if (body.isNullOrBlank()) return null
        if (body.startsWith("ERR:")) return null
        return body
    }

    private suspend fun requestPage(secUid: String, cursor: String, referer: String): String {
        val query = "https://www.tiktok.com" + itemListPath(secUid, cursor)

        val headers = buildMap {
            put("Referer", referer)
            put("Accept", "application/json, text/plain, */*")
            put("Sec-Fetch-Site", "same-origin")
            put("Sec-Fetch-Mode", "cors")
            tiktokCookieHeader()?.let { put("Cookie", it) }
        }

        return Http.get(
            url = query,
            userAgent = Http.UA_DESKTOP,
            headers = headers,
            client = apiClient,
        ).body
    }

    private suspend fun tiktokCookieHeader(): String? = withContext(Dispatchers.Main.immediate) {
        runCatching {
            val manager = CookieManager.getInstance()
            val parts = linkedSetOf<String>()

            fun add(raw: String?) {
                raw?.split(';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let(parts::addAll)
            }

            add(manager.getCookie("https://www.tiktok.com/"))
            add(manager.getCookie("https://www.tiktok.com"))
            add(manager.getCookie("https://tiktok.com/"))
            parts.joinToString("; ").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private companion object {
        const val MAX_PAGES = 3
        const val MAX_ITEMS = 90
        const val MAX_CREATOR_PAGES = 20
        const val MAX_CREATOR_ITEMS = 200
        val deviceId: String =
            (7_250_000_000_000_000_000L +
                kotlin.random.Random.nextLong(75_099_899_999_994_577L)).toString()
        val DROP_HEADERS = setOf(
            "host",
            "content-length",
            "connection",
            "accept-encoding",
            "transfer-encoding",
        )
    }
}

/** Recognises `https://www.tiktok.com/@handle` and ignores video/photo URLs. */
object TikTokProfileUrl {

    private val profilePath = Regex("""^/@([A-Za-z0-9._]{2,24})/?$""")

    fun handle(url: String): String? {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return null
        return profilePath.find(path)?.groupValues?.get(1)
    }
}

package com.snag.downloader.extract

import com.snag.downloader.core.Author
import com.snag.downloader.core.Codec
import com.snag.downloader.core.ExtractionException
import com.snag.downloader.core.Extractor
import com.snag.downloader.core.Features
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.Strategy
import com.snag.downloader.core.WebViewGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * TikTok resolution.
 *
 * Strategy 1 ("tiktok-web"): request the public video page with a desktop UA and read
 * TikTok's own server-rendered `__UNIVERSAL_DATA_FOR_REHYDRATION__` payload. That gives
 * the quality ladder, the non-watermarked stream and photo carousels in one request.
 *
 * Strategy 2 ("tiktok-opengraph"): share-card fallback when the page is gated.
 */
class TikTokExtractor(
    private val fallbackEnabled: () -> Boolean = { true },
) : Extractor {

    override val platform: SourcePlatform = SourcePlatform.TIKTOK

    override fun strategies(): List<Strategy> = buildList {
        add(TikTokWebStrategy())
        add(TikTokWebViewStrategy())
        if (fallbackEnabled()) add(TikTokFallbackStrategy())
        add(OpenGraphStrategy(id = "tiktok-opengraph"))
    }
}

internal class TikTokWebStrategy : Strategy {

    override val id: String = "tiktok-web"
    override val timeoutMs: Long = 15_000

    override suspend fun extract(url: String): MediaBundle = withContext(Dispatchers.IO) {
        val page = Http.get(
            url = url,
            userAgent = Http.UA_DESKTOP,
            headers = mapOf(
                "Referer" to "https://www.tiktok.com/",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
            ),
        )

        if (TikTokParser.isWaf(page.body)) {
            throw ExtractionException.Blocked(id, "TikTok's bot check appeared instead of the page")
        }

        val item = TikTokParser.itemStruct(page.body)
            ?: throw ExtractionException.NoMedia(
                id,
                "page has no rehydration payload (region block, private post, or non-video page)",
            )

        TikTokParser.toBundle(item, page.finalUrl, id)
    }
}

/**
 * Last-resort TikTok reader.
 *
 * A WebView runs TikTok's bot challenge and the page's own JavaScript, then exposes the
 * settled rehydration payload or a direct video element. This is slower than the HTTP path
 * and only runs in the foreground; the chain still ends at OpenGraph if no browser is attached.
 */
internal class TikTokWebViewStrategy : Strategy {

    override val id: String = "tiktok-webview"
    override val timeoutMs: Long = 28_000

    override suspend fun extract(url: String): MediaBundle = withContext(Dispatchers.IO) {
        val snapshot = WebViewGate.snapshot(url)
            ?: throw ExtractionException.NoMedia(id, "in-app browser is not available")

        val item = snapshot.universalData?.let(TikTokParser::itemFromUniversal)
            ?: snapshot.apiData?.let(TikTokParser::itemFromApiData)
        if (item != null) {
            return@withContext TikTokParser.toBundle(item, snapshot.url.ifBlank { url }, id)
        }

        val direct = listOfNotNull(
            snapshot.videoUrl,
            snapshot.og("og:video"),
            snapshot.og("og:video:url"),
            snapshot.og("twitter:player:stream"),
        ).firstOrNull(::isPlayableMedia)
            ?: throw ExtractionException.NoMedia(
                id,
                if (snapshot.universalData != null) {
                    "browser loaded a shell without a stream"
                } else {
                    "browser did not expose a stream"
                },
            )

        MediaBundle(
            id = Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1) ?: url,
            platform = SourcePlatform.TIKTOK,
            sourceUrl = snapshot.url.ifBlank { url },
            title = snapshot.og("og:title") ?: snapshot.title,
            coverUrl = snapshot.posterUrl ?: snapshot.og("og:image"),
            variants = listOf(
                MediaVariant(kind = MediaKind.VIDEO, url = direct, label = "source"),
            ),
            provenance = id,
        )
    }

    /**
     * The generic app shell ships a promotional `playback1.mp4`. It is not the requested
     * post, so never treat login/player chrome as media.
     */
    private fun isPlayableMedia(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        if (url.contains("webapp-desktop/playback", ignoreCase = true)) return false
        if (url.contains("/tiktok_web_login_static/", ignoreCase = true)) return false
        return true
    }
}

/**
 * Shared parsing for video pages, the profile API and any future WebView payload.
 * The reference app normalises all three into one model; this is that normaliser.
 */
internal object TikTokParser {

    private val DETAIL_KEYS = listOf("webapp.video-detail", "webapp.reflow.video.detail")

    fun isWaf(html: String): Boolean =
        html.length < 6_000 && (html.contains("_wafchallengeid") || html.contains("Please wait"))

    /** `__DEFAULT_SCOPE__` of the rehydration payload, or null when the page was gated. */
    fun scope(html: String): JSONObject? {
        val marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
        val markerAt = html.indexOf(marker)
        if (markerAt < 0) return null

        val start = html.indexOf('>', markerAt)
        if (start < 0) return null
        val end = html.indexOf("</script>", start)
        if (end < 0) return null

        val root = runCatching { JSONObject(html.substring(start + 1, end)) }.getOrNull()
        return root?.optJSONObject("__DEFAULT_SCOPE__")
    }

    fun itemStruct(html: String): JSONObject? =
        scope(html)?.let(::itemFromScope) ?: apiData(html)?.let(::itemFromApiData)

    /**
     * The detail object has moved between `webapp.video-detail` and
     * `webapp.reflow.video.detail` in different TikTok renders. Accept both.
     */
    fun itemFromScope(scope: JSONObject): JSONObject? {
        for (key in DETAIL_KEYS) {
            val item = scope.optJSONObject(key)
                ?.optJSONObject("itemInfo")
                ?.optJSONObject("itemStruct")
            if (item != null) return item
        }
        return null
    }

    fun itemFromUniversal(raw: String): JSONObject? {
        val scope = runCatching { JSONObject(raw) }.getOrNull()
            ?.optJSONObject("__DEFAULT_SCOPE__")
            ?: return null
        return itemFromScope(scope)
    }

    /** Older/alternate pages expose `<script id="api-data">` with the same item model. */
    fun itemFromApiData(raw: String): JSONObject? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val detail = root.optJSONObject("videoDetail")
            ?: root.optJSONObject("video_detail")
            ?: root
        return detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: detail.optJSONObject("itemStruct")
            ?: detail.optJSONObject("item_struct")
    }

    private fun apiData(html: String): String? {
        val marker = "id=\"api-data\""
        val markerAt = html.indexOf(marker)
        if (markerAt < 0) return null
        val start = html.indexOf('>', markerAt)
        if (start < 0) return null
        val end = html.indexOf("</script>", start)
        if (end < 0) return null
        return html.substring(start + 1, end)
    }

    fun toBundle(item: JSONObject, sourceUrl: String, provenance: String): MediaBundle {
        val video = item.optJSONObject("video")
        val author = item.optJSONObject("author")
        val music = item.optJSONObject("music")

        val width = video?.optInt("width", 0) ?: 0
        val height = video?.optInt("height", 0) ?: 0

        val bucket = LinkedHashMap<String, MediaVariant>()
        fun offer(variant: MediaVariant) {
            if (variant.url.isBlank()) return
            bucket.putIfAbsent(variant.url, variant)
        }

        // 1. Quality ladder, when TikTok exposes it.
        video?.optJSONArray("bitrateInfo")?.forEachObject { entry ->
            val addr = entry.optJSONObject("PlayAddr") ?: return@forEachObject
            val streamUrl = addr.optJSONArray("UrlList")?.optString(0).orEmpty()
            if (streamUrl.isBlank()) return@forEachObject

            val w = addr.optInt("Width", width)
            val h = addr.optInt("Height", height)
            val codecType = entry.optString("CodecType").lowercase()
            val codec = when {
                codecType.contains("h265") || codecType.contains("bytevc1") ||
                    codecType.contains("hev1") || codecType.contains("hvc1") -> Codec.H265
                codecType.contains("h264") || codecType.contains("avc") -> Codec.H264
                else -> Codec.UNKNOWN
            }
            val urls = addr.optJSONArray("UrlList")
            val alternate = urls?.optString(2)?.takeIf { it.isNotBlank() && it != streamUrl }
            offer(
                MediaVariant(
                    kind = MediaKind.VIDEO,
                    url = streamUrl,
                    playbackUrl = alternate,
                    width = w,
                    height = h,
                    isFhd = h >= 1080,
                    codec = codec,
                    estimatedBytes = addr.optLong("DataSize", 0L).takeIf { it > 0 },
                )
            )
        }

        // 2. Primary stream (no watermark) and the share/download encode (watermarked).
        val playUrl = video?.optString("playAddr").orEmpty()
        if (playUrl.isNotBlank()) {
            offer(
                MediaVariant(
                    kind = MediaKind.VIDEO,
                    url = playUrl,
                    width = width,
                    height = height,
                    label = "source",
                    isFhd = height >= 1080,
                )
            )
        }

        val downloadUrl = video?.optString("downloadAddr").orEmpty()
        if (downloadUrl.isNotBlank() && downloadUrl != playUrl) {
            offer(
                MediaVariant(
                    kind = MediaKind.VIDEO,
                    url = downloadUrl,
                    width = width,
                    height = height,
                    label = "watermarked",
                    isFhd = height >= 1080,
                )
            )
        }

        // 3. Photo carousels.
        if (Features.IMAGE_POSTS) {
            item.optJSONObject("imagePost")?.optJSONArray("images")?.forEachObject { image ->
                val url = image.optJSONObject("imageURL")
                    ?.optJSONArray("urlList")
                    ?.optString(0)
                    .orEmpty()
                if (url.isBlank()) return@forEachObject
                offer(
                    MediaVariant(
                        kind = MediaKind.IMAGE,
                        url = url,
                        width = image.optInt("imageWidth", 0),
                        height = image.optInt("imageHeight", 0),
                        label = "image",
                    )
                )
            }
        }

        val variants = bucket.values.sortedWith(
            compareByDescending<MediaVariant> { it.kind == MediaKind.VIDEO }
                .thenByDescending { it.height }
        )

        if (variants.isEmpty()) {
            throw ExtractionException.NoMedia(provenance, "item parsed but no playable variant found")
        }

        return MediaBundle(
            id = item.optString("id").ifBlank { sourceUrl },
            platform = SourcePlatform.TIKTOK,
            sourceUrl = sourceUrl,
            title = item.optString("desc").takeIf { it.isNotBlank() },
            author = author?.let {
                Author(
                    handle = it.optString("uniqueId").takeIf { s -> s.isNotBlank() },
                    nickname = it.optString("nickname").takeIf { s -> s.isNotBlank() },
                    avatarUrl = it.optString("avatarThumb").takeIf { s -> s.isNotBlank() },
                )
            },
            coverUrl = listOf("cover", "originCover", "dynamicCover")
                .firstNotNullOfOrNull { key -> video?.optString(key)?.takeIf { s -> s.isNotBlank() } },
            variants = variants,
            audioUrl = music?.optString("playUrl")?.takeIf { s -> s.isNotBlank() },
            provenance = provenance,
        )
    }
}

/** Small helper so JSON arrays read like collections. */
internal inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
        optJSONObject(index)?.let(block)
    }
}

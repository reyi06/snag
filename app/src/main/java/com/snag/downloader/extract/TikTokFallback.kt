package com.snag.downloader.extract

import com.snag.downloader.core.Author
import com.snag.downloader.core.ExtractionException
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.Strategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Optional last-resort TikTok extractor.
 *
 * TikTok answers the on-device HTTP client with a WAF page and sometimes rate-limits the
 * WebView as well. This endpoint returns a normalised, download-ready payload for the same
 * public post; it is only consulted after the first-party strategies fail, and only when
 * the user has left "Allow fallback service" enabled.
 *
 * No cookies, accounts, or private content are sent: just the post URL the user pasted.
 */
internal class TikTokFallbackStrategy : Strategy {

    override val id: String = "tiktok-fallback"
    override val timeoutMs: Long = 18_000

    override suspend fun extract(url: String): MediaBundle = TikTokFallback.resolve(url)
}

object TikTokFallback {

    private const val ENDPOINT = "https://www.tikwm.com/api/?url="

    suspend fun resolve(url: String): MediaBundle = withContext(Dispatchers.IO) {
        val page = Http.get(
            url = ENDPOINT + URLEncoder.encode(url, "UTF-8"),
            userAgent = Http.UA_DESKTOP,
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "https://www.tikwm.com/",
            ),
        )

        val root = runCatching { JSONObject(page.body) }.getOrNull()
            ?: throw ExtractionException.NoMedia(
                "tiktok-fallback",
                "fallback returned a non-JSON response",
            )

        if (root.optInt("code", -1) != 0) {
            throw ExtractionException.NoMedia(
                "tiktok-fallback",
                root.optString("msg").takeIf { it.isNotBlank() } ?: "fallback failed",
            )
        }

        val data = root.optJSONObject("data")
            ?: throw ExtractionException.NoMedia("tiktok-fallback", "fallback returned no data")

        toBundle(data, url)
    }

    private fun toBundle(data: JSONObject, sourceUrl: String): MediaBundle {
        val variants = LinkedHashMap<String, MediaVariant>()

        fun offer(variant: MediaVariant) {
            val clean = normalise(variant.url) ?: return
            variants.putIfAbsent(clean, variant.copy(url = clean))
        }

        offer(
            MediaVariant(
                kind = MediaKind.VIDEO,
                url = data.optString("hdplay"),
                label = "HD",
            )
        )
        offer(
            MediaVariant(
                kind = MediaKind.VIDEO,
                url = data.optString("play"),
                label = "source",
            )
        )
        offer(
            MediaVariant(
                kind = MediaKind.VIDEO,
                url = data.optString("wmplay"),
                label = "watermarked",
            )
        )

        data.optJSONArray("images")?.forEachUrl { imageUrl ->
            offer(
                MediaVariant(
                    kind = MediaKind.IMAGE,
                    url = imageUrl,
                    label = "image",
                )
            )
        }

        if (variants.isEmpty()) {
            throw ExtractionException.NoMedia(
                "tiktok-fallback",
                "fallback found no downloadable stream",
            )
        }

        val author = data.optJSONObject("author")
        val music = data.opt("music")?.let { value ->
            when (value) {
                is JSONObject -> value.optString("play").ifBlank { value.optString("url") }
                else -> value.toString()
            }
        }?.let(::normalise)

        return MediaBundle(
            id = data.optString("id").ifBlank {
                Regex("""/video/(\d+)""").find(sourceUrl)?.groupValues?.get(1) ?: sourceUrl
            },
            platform = SourcePlatform.TIKTOK,
            sourceUrl = sourceUrl,
            title = data.optString("title").takeIf { it.isNotBlank() },
            author = author?.let {
                Author(
                    handle = it.optString("unique_id").takeIf { value -> value.isNotBlank() },
                    nickname = it.optString("nickname").takeIf { value -> value.isNotBlank() },
                    avatarUrl = it.optString("avatar").takeIf { value -> value.isNotBlank() },
                )
            },
            coverUrl = sequenceOf("origin_cover", "cover", "ai_dynamic_cover")
                .map { data.optString(it) }
                .mapNotNull(::normalise)
                .firstOrNull(),
            variants = variants.values.toList(),
            audioUrl = music,
            provenance = "fallback",
        )
    }

    private fun normalise(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value == "null") return null
        return when {
            value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            else -> null
        }
    }

    private inline fun JSONArray.forEachUrl(block: (String) -> Unit) {
        for (index in 0 until length()) {
            val value = opt(index)
            when (value) {
                is String -> block(value)
                is JSONObject -> {
                    val url = value.optString("url").ifBlank { value.optString("src") }
                    if (url.isNotBlank()) block(url)
                }
            }
        }
    }
}

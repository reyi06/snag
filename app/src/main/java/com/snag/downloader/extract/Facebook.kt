package com.snag.downloader.extract

import com.snag.downloader.core.ExtractionException
import com.snag.downloader.core.Extractor
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.Strategy
import com.snag.downloader.core.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Public Facebook videos, reels and watch links.
 *
 * Strategy 1 ("facebook-embed") reads Facebook's own video embed, which returns
 * playable HD/SD URLs for public posts without the feed shell. Strategy 2 reads the
 * page's OpenGraph card.
 */
class FacebookExtractor : Extractor {

    override val platform: SourcePlatform = SourcePlatform.FACEBOOK

    override fun strategies(): List<Strategy> = listOf(
        FacebookEmbedStrategy(),
        OpenGraphStrategy(id = "facebook-opengraph"),
    )
}

internal class FacebookEmbedStrategy : Strategy {

    override val id: String = "facebook-embed"
    override val timeoutMs: Long = 18_000

    override suspend fun extract(url: String): MediaBundle = withContext(Dispatchers.IO) {
        val canonical = resolveCanonical(url)
        val numericId = videoId(canonical)

        val embeds = buildList {
            if (numericId != null) {
                add("https://www.facebook.com/video/embed?video_id=$numericId")
            }
            val href = URLEncoder.encode(canonical, "UTF-8")
            add("https://www.facebook.com/plugins/video.php?href=$href&show_text=false&width=1280")
        }

        var loginWall = false
        for (embed in embeds) {
            val page = runCatching {
                Http.get(
                    url = embed,
                    userAgent = Http.UA_DESKTOP,
                    headers = mapOf("Referer" to canonical),
                )
            }.getOrNull() ?: continue

            val html = page.body
            if (html.contains("login_form") || html.contains("Log in to Facebook")) {
                loginWall = true
            }

            val meta = MetaParser.parse(html)
            val hd = firstUrl(html, "browser_native_hd_url", "playable_url_hd", "hd_src")
            val sd = firstUrl(html, "browser_native_sd_url", "playable_url", "sd_src")
            if (hd == null && sd == null) continue

            val variants = LinkedHashMap<String, MediaVariant>()
            fun offer(variant: MediaVariant) = variants.putIfAbsent(variant.url, variant)

            sd?.let {
                offer(MediaVariant(kind = MediaKind.VIDEO, url = it, label = if (hd == null) "source" else "SD"))
            }
            hd?.let {
                offer(MediaVariant(kind = MediaKind.VIDEO, url = it, label = "HD"))
            }

            val title = (meta["og:title"] ?: meta["twitter:title"])
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            return@withContext MediaBundle(
                id = numericId ?: canonical,
                platform = SourcePlatform.FACEBOOK,
                sourceUrl = canonical,
                title = title,
                coverUrl = meta["og:image"]
                    ?: firstUrl(html, "thumbnail_url", "preview_image_url"),
                variants = variants.values.toList(),
                provenance = id,
            )
        }

        throw ExtractionException.NoMedia(
            id,
            if (loginWall) "embed returned a login wall" else "no playable URL in Facebook embed",
        )
    }

    /** fb.watch links only carry the id in their redirect target. */
    private fun resolveCanonical(url: String): String {
        val host = UrlParser.hostOf(url)
        if (host == "fb.watch" || host == "fb.com" || host.endsWith(".fb.com")) {
            return runCatching { Http.get(url, Http.UA_DESKTOP).finalUrl }.getOrDefault(url)
        }
        return url
    }

    private fun videoId(url: String): String? =
        queryId.find(url)?.groupValues?.get(1)
            ?: pathId.find(url)?.groupValues?.get(1)
            ?: embedId.find(url)?.groupValues?.get(1)

    private fun firstUrl(html: String, vararg keys: String): String? {
        for (key in keys) {
            val match = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html) ?: continue
            val value = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003D", "=")
                .replace("\\u003d", "=")
                .replace("\\u0025", "%")
                .replace("&amp;", "&")
            if (value.startsWith("http")) return value
        }
        return null
    }

    private companion object {
        val queryId = Regex("""[?&]v=(\d{5,})""")
        val pathId = Regex("""/(?:videos|reel|reels)/(\d{5,})""")
        val embedId = Regex("""video_id=(\d{5,})""")
    }
}

package com.snag.downloader.extract

import com.snag.downloader.core.Author
import com.snag.downloader.core.ExtractionException
import com.snag.downloader.core.Extractor
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.Strategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Public Instagram posts and reels.
 *
 * Strategy 1 ("instagram-embed") reads the captioned embed page, which exposes the
 * original media URLs without the logged-out feed shell. Strategy 2 falls back to
 * the canonical page's share metadata.
 */
class InstagramExtractor : Extractor {

    override val platform: SourcePlatform = SourcePlatform.INSTAGRAM

    override fun strategies(): List<Strategy> = listOf(
        InstagramEmbedStrategy(),
        OpenGraphStrategy(id = "instagram-opengraph"),
    )
}

internal class InstagramEmbedStrategy : Strategy {

    override val id: String = "instagram-embed"
    override val timeoutMs: Long = 15_000

    override suspend fun extract(url: String): MediaBundle = withContext(Dispatchers.IO) {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val match = postPath.find(path)
            ?: throw ExtractionException.NoMedia(id, "not an Instagram post URL")

        val kind = match.groupValues[1]
        val code = match.groupValues[2]
        val canonical = "https://www.instagram.com/$kind/$code/"
        val embed = "https://www.instagram.com/$kind/$code/embed/captioned/"

        val page = Http.get(
            url = embed,
            userAgent = Http.UA_DESKTOP,
            headers = mapOf("Referer" to canonical),
        )
        val html = page.body
        val meta = MetaParser.parse(html)

        val videoUrls = mediaUrls(html, "video_url")
        val imageUrls = mediaUrls(html, "display_url").distinct()
        val username = usernameRegex.find(html)?.groupValues?.get(1)

        if (videoUrls.isEmpty() && imageUrls.isEmpty()) {
            throw ExtractionException.NoMedia(
                id,
                if (html.contains("LoginAndSignupPage") || html.contains("loginForm")) {
                    "embed returned a login wall"
                } else {
                    "embed has no media URLs"
                },
            )
        }

        val variants = if (videoUrls.isNotEmpty()) {
            videoUrls.distinct().mapIndexed { index, video ->
                MediaVariant(
                    kind = MediaKind.VIDEO,
                    url = video,
                    label = if (videoUrls.size == 1) "source" else "video ${index + 1}",
                )
            }
        } else {
            imageUrls.mapIndexed { index, image ->
                MediaVariant(
                    kind = MediaKind.IMAGE,
                    url = image,
                    label = "photo ${index + 1}",
                )
            }
        }

        val title = (meta["og:title"] ?: meta["twitter:title"])
            ?.substringBefore(" | Instagram")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        MediaBundle(
            id = code,
            platform = SourcePlatform.INSTAGRAM,
            sourceUrl = canonical,
            title = title,
            author = username?.let { Author(handle = it, nickname = it) },
            coverUrl = imageUrls.firstOrNull() ?: meta["og:image"],
            variants = variants,
            provenance = id,
        )
    }

    private fun mediaUrls(html: String, key: String): List<String> {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return regex.findAll(html).mapNotNull { match ->
            decodeUrl(match.groupValues[1])
        }.toList()
    }

    private fun decodeUrl(raw: String): String? {
        val value = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003d", "=")
            .replace("\\u0025", "%")
            .replace("&amp;", "&")
        return value.takeIf { it.startsWith("http") }
    }

    private companion object {
        val postPath = Regex("""/(p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
        val usernameRegex = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"")
    }
}

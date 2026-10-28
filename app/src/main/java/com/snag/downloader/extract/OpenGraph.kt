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
import com.snag.downloader.core.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Last-resort strategy: read what the page already tells crawlers.
 * Works for most sites that render share cards, and for sites that expose a
 * bare <video> element server-side.
 */
class OpenGraphStrategy(
    override val id: String = "opengraph",
    private val userAgent: String = Http.UA_MOBILE,
) : Strategy {

    override val timeoutMs: Long = 12_000

    override suspend fun extract(url: String): MediaBundle = withContext(Dispatchers.IO) {
        val page = Http.get(url, userAgent)
        val meta = MetaParser.parse(page.body)

        val video = meta["og:video:secure_url"]
            ?: meta["og:video:url"]
            ?: meta["og:video"]
            ?: HtmlVideo.firstSrc(page.body)
        val image = meta["og:image:secure_url"]
            ?: meta["og:image"]
            ?: HtmlVideo.firstPoster(page.body)

        if (video == null && image == null) {
            throw ExtractionException.NoMedia(id, "no og:video, og:image or <video> in page")
        }

        val variants = buildList {
            if (video != null) {
                add(MediaVariant(kind = MediaKind.VIDEO, url = video, label = "source"))
            }
            if (image != null) {
                add(MediaVariant(kind = MediaKind.IMAGE, url = image, label = "cover"))
            }
        }

        MediaBundle(
            id = page.finalUrl,
            platform = UrlParser.classify(page.finalUrl),
            sourceUrl = page.finalUrl,
            title = meta["og:title"] ?: meta["twitter:title"],
            author = (meta["author"] ?: meta["og:site_name"])?.let { Author(nickname = it) },
            coverUrl = image,
            variants = variants,
            provenance = id,
        )
    }
}

/** Catch-all extractor so any link gets a chance instead of an immediate rejection. */
class GenericExtractor : Extractor {
    override val platform: SourcePlatform = SourcePlatform.GENERIC
    override fun strategies(): List<Strategy> = listOf(OpenGraphStrategy())
}

internal object MetaParser {

    private val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val keyAttr = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val contentAttr = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    fun parse(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (match in metaTag.findAll(html)) {
            val tag = match.value
            val key = keyAttr.find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            val value = contentAttr.find(tag)?.groupValues?.get(1) ?: continue
            if (key.isBlank() || value.isBlank()) continue
            out.putIfAbsent(key, unescape(value))
        }
        return out
    }

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

internal object HtmlVideo {

    private val srcRegex = Regex("""<video\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val posterRegex = Regex("""<video\b[^>]*\bposter\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun firstSrc(html: String): String? = srcRegex.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun firstPoster(html: String): String? = posterRegex.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

package com.snag.downloader.core

import java.net.URI

/** Pulls a URL out of arbitrary shared text and classifies the host. */
object UrlParser {

    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** "Look at this https://vt.tiktok.com/ZSxx/ 😂" -> "https://vt.tiktok.com/ZSxx/" */
    fun firstUrl(text: String): String? {
        val raw = urlRegex.find(text)?.value ?: return null
        return raw.trimEnd('.', ',', ')', ']', '"', '\'', ';', ':', '!', '?')
    }

    fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()

    fun classify(url: String): SourcePlatform {
        val host = hostOf(url)
        if (host.isEmpty()) return SourcePlatform.UNKNOWN
        return when {
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> SourcePlatform.TIKTOK
            host == "instagram.com" || host.endsWith(".instagram.com") ||
                host == "instagr.am" -> SourcePlatform.INSTAGRAM
            host == "facebook.com" || host.endsWith(".facebook.com") ||
                host == "fb.watch" || host == "fb.com" || host.endsWith(".fb.com") ->
                SourcePlatform.FACEBOOK
            else -> SourcePlatform.GENERIC
        }
    }
}

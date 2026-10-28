package com.snag.downloader.core

import java.net.URI

/** Pulls a URL out of arbitrary shared text and classifies the host. */
object UrlParser {

    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** All URLs in arbitrary shared text, in order, without duplicates. */
    fun allUrls(text: String): List<String> =
        urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '"', '\'', ';', ':', '!', '?') }
            .distinct()
            .toList()

    /** "Look at this https://vt.tiktok.com/ZSxx/ 😂" -> "https://vt.tiktok.com/ZSxx/" */
    fun firstUrl(text: String): String? = allUrls(text).firstOrNull()

    fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()

    fun classify(url: String): SourcePlatform {
        val host = hostOf(url)
        if (host.isEmpty()) return SourcePlatform.UNKNOWN
        return when {
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> SourcePlatform.TIKTOK
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be" || host.endsWith(".youtu.be") ->
                SourcePlatform.YOUTUBE
            host == "instagram.com" || host.endsWith(".instagram.com") ||
                host == "instagr.am" -> SourcePlatform.INSTAGRAM
            host == "facebook.com" || host.endsWith(".facebook.com") ||
                host == "fb.watch" || host == "fb.com" || host.endsWith(".fb.com") ->
                SourcePlatform.FACEBOOK
            else -> SourcePlatform.GENERIC
        }
    }
}

package com.snag.downloader.extract

import android.content.Context
import com.snag.downloader.core.Author
import com.snag.downloader.core.ExtractionException
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.Strategy
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Public Instagram posts through yt-dlp.
 *
 * Instagram's captioned embed no longer includes display_url/video_url, so the
 * old embed strategy falls through to a login page. yt-dlp still resolves
 * public reels/videos and exposes carousel entries as metadata plus thumbnails.
 * Image-only entries are downloaded from their best thumbnail.
 */
internal class InstagramYtDlpStrategy(private val context: Context) : Strategy {

    override val id: String = "instagram-yt-dlp"
    override val timeoutMs: Long = 120_000

    override suspend fun extract(url: String): MediaBundle = withContext(Dispatchers.IO) {
        try {
            fetch(url)
        } catch (first: Throwable) {
            // A stale yt-dlp is a common cause of Instagram login/rate-limit
            // failures. Refresh once and give the extractor a second chance.
            if (first !is ExtractionException.Blocked &&
                first !is ExtractionException.NoMedia
            ) {
                throw first
            }
            if (!YouTubeClient.forceRefresh(context)) throw first
            fetch(url)
        }
    }

    private suspend fun fetch(url: String): MediaBundle {
        YouTubeClient.initialize(context)

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-warnings")
            addOption("--ignore-no-formats-error")
            addOption("--dump-single-json")
            addOption("--playlist-end", MAX_ITEMS)
            addOption("--socket-timeout", 20)
            addOption("--retries", 2)
        }

        val response = try {
            YoutubeDL.getInstance().execute(request, "instagram")
        } catch (t: Throwable) {
            throw ExtractionException.Failed(id, t)
        }

        val root = jsonPayload(response.out)
            ?: throw failure(response.err.ifBlank { response.out })

        val entries = parseEntries(root)
        val variants = LinkedHashMap<String, MediaVariant>()
        entries.take(MAX_ITEMS).forEachIndexed { index, entry ->
            val itemVariants = videoVariants(entry, index, entries.size)
            if (itemVariants.isNotEmpty()) {
                itemVariants.forEach { variant -> variants.putIfAbsent(variant.url, variant) }
            } else {
                imageVariant(entry, index, entries.size)?.let { variant ->
                    variants.putIfAbsent(variant.url, variant)
                }
            }
        }

        if (variants.isEmpty()) {
            throw failure(response.err.ifBlank { "no media formats" })
        }

        val cover = variants.values.firstOrNull { it.kind == MediaKind.IMAGE }?.url
            ?: root.optString("thumbnail").takeIf { it.isNotBlank() }
        val title = root.optString("title").takeIf { it.isNotBlank() }
            ?: root.optString("description").takeIf { it.isNotBlank() }
        val channel = root.optString("channel").takeIf { it.isNotBlank() }
            ?: root.optString("uploader_id").takeIf { it.isNotBlank() }
        val uploader = root.optString("uploader").takeIf { it.isNotBlank() }

        return MediaBundle(
            id = root.optString("id").ifBlank { "instagram" },
            platform = SourcePlatform.INSTAGRAM,
            sourceUrl = root.optString("webpage_url").ifBlank { url },
            title = title,
            author = channel?.let { Author(handle = it, nickname = uploader) }
                ?: uploader?.let { Author(nickname = it) },
            coverUrl = cover,
            variants = variants.values.toList(),
            provenance = id,
        )
    }

    private fun parseEntries(root: JSONObject): List<JSONObject> {
        val array = root.optJSONArray("entries")
            ?: return listOf(root)
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }.ifEmpty { listOf(root) }
    }

    private fun videoVariants(
        entry: JSONObject,
        index: Int,
        total: Int,
    ): List<MediaVariant> {
        val variants = LinkedHashMap<String, MediaVariant>()
        val formats = entry.optJSONArray("formats") ?: JSONArray()
        val videoFormats = mutableListOf<JSONObject>()
        val audioFormats = mutableListOf<JSONObject>()
        for (formatIndex in 0 until formats.length()) {
            val format = formats.optJSONObject(formatIndex) ?: continue
            if (format.optString("url").isBlank()) continue
            if (format.optString("vcodec") == "none") {
                audioFormats += format
            } else {
                videoFormats += format
            }
        }
        val audioFormatId = audioFormats
            .maxByOrNull { it.optInt("abr").takeIf { value -> value > 0 } ?: it.optInt("tbr") }
            ?.optString("format_id")
            ?.takeIf { it.isNotBlank() }

        for (format in videoFormats) {
            val mediaUrl = format.optString("url").takeIf { it.isNotBlank() } ?: continue
            val audioCodec = format.optString("acodec")
            val hasAudio = audioCodec.isNotBlank() && audioCodec != "none"
            val formatId = format.optString("format_id").takeIf { it.isNotBlank() }
            val selector = if (!hasAudio && formatId != null && audioFormatId != null) {
                "$formatId+$audioFormatId"
            } else {
                null
            }
            // Adaptive video-only streams are kept when yt-dlp can merge them.
            if (!hasAudio && selector == null) continue
            val height = format.optInt("height").takeIf { it > 0 }
                ?: entry.optInt("height").takeIf { it > 0 }
            val label = when {
                total > 1 && height != null -> "video ${index + 1} · ${height}p"
                total > 1 -> "video ${index + 1}"
                else -> "source"
            }
            variants.putIfAbsent(
                selector?.let { "format:$it" } ?: mediaUrl,
                MediaVariant(
                    kind = MediaKind.VIDEO,
                    url = mediaUrl,
                    downloadFormatId = selector,
                    width = format.optInt("width").takeIf { it > 0 }
                        ?: entry.optInt("width").coerceAtLeast(0),
                    height = height ?: 0,
                    label = label,
                    estimatedBytes = format.optLong("filesize").takeIf { it > 0 }
                        ?: format.optLong("filesize_approx").takeIf { it > 0 },
                    fileExtension = "mp4",
                ),
            )
        }

        val direct = entry.optString("url").takeIf { it.isNotBlank() }
        if (variants.isEmpty() && direct != null) {
            variants[direct] = MediaVariant(
                kind = MediaKind.VIDEO,
                url = direct,
                width = entry.optInt("width").coerceAtLeast(0),
                height = entry.optInt("height").coerceAtLeast(0),
                label = if (total > 1) "video ${index + 1}" else "source",
                fileExtension = "mp4",
            )
        }
        return variants.values.toList()
    }

    private fun imageVariant(
        entry: JSONObject,
        index: Int,
        total: Int,
    ): MediaVariant? {
        val imageUrl = bestImage(entry) ?: return null
        return MediaVariant(
            kind = MediaKind.IMAGE,
            url = imageUrl,
            width = entry.optInt("width").coerceAtLeast(0),
            height = entry.optInt("height").coerceAtLeast(0),
            label = if (total > 1) "photo ${index + 1}" else "source",
            fileExtension = imageExtension(imageUrl),
        )
    }

    private fun bestImage(entry: JSONObject): String? {
        val thumbnails = entry.optJSONArray("thumbnails")
        if (thumbnails != null && thumbnails.length() > 0) {
            for (index in thumbnails.length() - 1 downTo 0) {
                val url = thumbnails.optJSONObject(index)
                    ?.optString("url")
                    ?.takeIf { it.isNotBlank() }
                if (url != null) return url
            }
        }
        return entry.optString("thumbnail").takeIf { it.isNotBlank() }
    }

    private fun imageExtension(url: String): String = when {
        url.contains(".webp", ignoreCase = true) -> "webp"
        url.contains(".png", ignoreCase = true) -> "png"
        else -> "jpg"
    }

    private fun jsonPayload(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    private fun failure(raw: String): ExtractionException {
        val detail = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ERROR:", ignoreCase = true) }
            ?.removePrefix("ERROR:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: raw.trim().take(180)
        val lower = detail.lowercase()
        return when {
            "login" in lower || "rate-limit" in lower || "rate limit" in lower ||
                "not available to everyone" in lower || "private" in lower ->
                ExtractionException.Blocked(id, detail)
            "no video formats" in lower || "no media" in lower ->
                ExtractionException.NoMedia(id, detail)
            else -> ExtractionException.Failed(id, IOException(detail))
        }
    }

    private companion object {
        const val MAX_ITEMS = 20
    }
}

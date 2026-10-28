package com.snag.downloader.extract

import android.content.Context
import com.snag.downloader.core.Author
import com.snag.downloader.core.Codec
import com.snag.downloader.core.ExtractionException
import com.snag.downloader.core.Extractor
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.ProfileBundle
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.Strategy
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** yt-dlp initialization shared by YouTube videos and playlists. */
internal object YouTubeClient {

    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    private val infoCache = ConcurrentHashMap<String, CachedInfo>()

    @Volatile
    private var initialized = false

    suspend fun initialize(context: Context) {
        if (initialized) return
        withContext(Dispatchers.IO) {
            initMutex.withLock {
                if (initialized) return@withLock
                try {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    refreshYtDlp(context)
                    initialized = true
                } catch (t: Throwable) {
                    throw ExtractionException.Failed("yt-dlp", t)
                }
            }
        }
    }

    /**
     * The embedded yt-dlp becomes stale as Instagram and YouTube change.
     * Refresh at most once a day; a failed refresh keeps the bundled binary.
     */
    private fun refreshYtDlp(context: Context) {
        val prefs = context.getSharedPreferences("snag_ytdlp", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < UPDATE_INTERVAL_MS) return
        if (now - prefs.getLong("last_attempt", 0L) < RETRY_INTERVAL_MS) return
        prefs.edit().putLong("last_attempt", now).apply()
        val updated = runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE,
            )
        }.isSuccess
        if (updated) prefs.edit().putLong("last_check", now).apply()
    }

    /** Force a yt-dlp refresh and let an extractor retry immediately. */
    suspend fun forceRefresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) initialize(context)
        updateMutex.withLock {
            runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(
                    context.applicationContext,
                    YoutubeDL.UpdateChannel.STABLE,
                )
            }.isSuccess
        }
    }

    suspend fun getInfo(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        infoCache[url]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withContext it.info }
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val info = YoutubeDL.getInstance().getInfo(request)
        infoCache[url] = CachedInfo(info, now)
        info
    }

    private data class CachedInfo(val info: VideoInfo, val at: Long)

    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_INTERVAL_MS = 10 * 60 * 1000L
}

/** Single YouTube video. Video and audio are separate formats from yt-dlp. */
class YouTubeExtractor(private val context: Context) : Extractor {

    override val platform: SourcePlatform = SourcePlatform.YOUTUBE

    override fun strategies(): List<Strategy> = listOf(YouTubeStrategy(context))
}

internal class YouTubeStrategy(private val context: Context) : Strategy {

    override val id: String = "youtube-yt-dlp"
    override val timeoutMs: Long = 90_000

    override suspend fun extract(url: String): MediaBundle = withContext(Dispatchers.IO) {
        YouTubeClient.initialize(context)
        if (YouTubePlaylistUrl.isPlaylist(url)) {
            throw ExtractionException.NoMedia(id, "playlist handled by the playlist extractor")
        }
        val info = try {
            YouTubeClient.getInfo(url)
        } catch (t: Throwable) {
            throw ExtractionException.Failed(id, t)
        }
        YouTubeMedia.fromInfo(info, url)
            ?: throw ExtractionException.NoMedia(id, "yt-dlp returned no playable formats")
    }
}

/** Playlist metadata and full per-entry formats, shown in the shared batch sheet. */
class YouTubePlaylistExtractor(private val context: Context) {

    suspend fun fetch(
        url: String,
        onProgress: (String) -> Unit = {},
    ): ProfileBundle = withContext(Dispatchers.IO) {
        YouTubeClient.initialize(context)
        onProgress("Loading playlist page.")
        val page = runCatching {
            Http.get(
                url = url,
                userAgent = Http.UA_DESKTOP,
                headers = mapOf("Referer" to "https://www.youtube.com/"),
            )
        }.getOrElse { throw ExtractionException.Failed("youtube-playlist", it) }

        val root = ytInitialData(page.body)
            ?: throw ExtractionException.NoMedia("youtube-playlist", "playlist data was missing")
        val ids = linkedSetOf<String>()
        collectVideoIds(root, ids)
        if (ids.isEmpty()) {
            throw ExtractionException.NoMedia("youtube-playlist", "playlist has no video IDs")
        }

        val title = findText(root, "playlistHeaderRenderer", "title")
            ?: findText(root, "playlistHeaderRenderer", "titleText")
            ?: Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""")
                .find(page.body)?.groupValues?.get(1)
            ?: "YouTube playlist"
        val selected = ids.take(MAX_PLAYLIST_ITEMS)
        val semaphore = Semaphore(PLAYLIST_CONCURRENCY)
        val resolved = coroutineScope {
            selected.mapIndexed { index, id ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        onProgress("Resolving playlist ${index + 1}/${selected.size}.")
                        runCatching { YouTubeClient.getInfo("https://www.youtube.com/watch?v=$id") }
                            .getOrNull()
                            ?.let { info ->
                                YouTubeMedia.fromInfo(
                                    info,
                                    "https://www.youtube.com/watch?v=$id",
                                )
                            }
                    }
                }
            }.awaitAll()
        }
        val entries = resolved.filterNotNull().distinctBy { it.id }
        if (entries.isEmpty()) {
            throw ExtractionException.NoMedia("youtube-playlist", "playlist entries could not be resolved")
        }
        val playlistId = YouTubePlaylistUrl.id(url) ?: "youtube"
        val truncated = ids.size > selected.size || entries.size < selected.size
        ProfileBundle(
            platform = SourcePlatform.YOUTUBE,
            url = url,
            handle = "_$playlistId",
            nickname = title,
            avatarUrl = entries.firstOrNull()?.coverUrl,
            videoCount = entries.size.toLong(),
            items = entries,
            provenance = "yt-dlp-playlist",
            note = if (truncated) {
                "Showing ${entries.size} of ${maxOf(ids.size, selected.size)} playlist videos."
            } else {
                "YouTube playlist"
            },
        )
    }

    private fun ytInitialData(html: String): JSONObject? {
        val marker = html.indexOf("ytInitialData")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        val end = html.indexOf("};", start)
        if (end < 0) return null
        return runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull()
    }

    private fun collectVideoIds(value: Any?, out: MutableSet<String>) {
        when (value) {
            is JSONObject -> {
                val id = value.optString("videoId")
                if (id.isNotBlank()) out.add(id)
                for (key in value.keys()) collectVideoIds(value.opt(key), out)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectVideoIds(value.opt(index), out)
            }
        }
    }

    private fun findText(value: Any?, parentKey: String, childKey: String): String? {
        when (value) {
            is JSONObject -> {
                if (value.has(parentKey)) {
                    val parent = value.optJSONObject(parentKey)
                    val child = parent?.opt(childKey)
                    when (child) {
                        is String -> if (child.isNotBlank()) return child
                        is JSONObject -> {
                            val runs = child.optJSONArray("runs")
                            val text = runs?.optJSONObject(0)?.optString("text").orEmpty()
                            if (text.isNotBlank()) return text
                        }
                    }
                }
                for (key in value.keys()) {
                    findText(value.opt(key), parentKey, childKey)?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                findText(value.opt(index), parentKey, childKey)?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val MAX_PLAYLIST_ITEMS = 10
        const val PLAYLIST_CONCURRENCY = 3
    }
}

internal object YouTubeMedia {

    fun fromInfo(info: VideoInfo, originalUrl: String): MediaBundle? {
        val formats = info.formats.orEmpty()
        val videos = formats.filter { format ->
            format.vcodec?.let { it != "none" } == true &&
                !format.url.isNullOrBlank()
        }
        val audio = formats
            .filter { format ->
                format.acodec?.let { it != "none" } == true &&
                    (format.vcodec == null || format.vcodec == "none") &&
                    !format.url.isNullOrBlank()
            }
            .maxByOrNull { it.abr.takeIf { value -> value > 0 } ?: it.tbr }
        val audioFormatId = audio?.formatId?.takeIf { it.isNotBlank() }

        val variants = LinkedHashMap<String, MediaVariant>()
        for (format in videos.sortedByDescending { it.height }) {
            val url = format.url ?: continue
            val hasAudio = format.acodec
                ?.takeIf { it.isNotBlank() && it != "none" } != null
            val formatId = format.formatId?.takeIf { it.isNotBlank() }
            val selector = if (!hasAudio && formatId != null && audioFormatId != null) {
                "$formatId+$audioFormatId"
            } else {
                null
            }
            // Adaptive video without an audio stream is only useful when we can
            // hand the pair to yt-dlp for merging.
            if (!hasAudio && selector == null) continue
            variants.putIfAbsent(
                if (selector != null) "format:$selector" else url,
                MediaVariant(
                    kind = MediaKind.VIDEO,
                    url = url,
                    downloadFormatId = selector,
                    width = format.width,
                    height = format.height,
                    label = format.formatNote ?: "${format.height}p",
                    isFhd = format.height >= 1080,
                    codec = codecOf(format.vcodec),
                    estimatedBytes = format.fileSize.takeIf { it > 0 }
                        ?: format.fileSizeApproximate.takeIf { it > 0 },
                    fileExtension = format.ext?.takeIf { it in setOf("mp4", "webm") },
                )
            )
        }
        // Some videos only expose a video-only adaptive stream. It is better to
        // offer the playable selected URL than to show an empty result.
        val directUrl = info.url
        if (variants.isEmpty() && !directUrl.isNullOrBlank()) {
            variants[directUrl] = MediaVariant(
                kind = MediaKind.VIDEO,
                url = directUrl,
                width = info.width,
                height = info.height,
                label = info.format ?: "source",
                fileExtension = info.ext?.takeIf { it in setOf("mp4", "webm") },
            )
        }
        if (variants.isEmpty()) return null

        return MediaBundle(
            id = info.id.orEmpty().ifBlank { originalUrl },
            platform = SourcePlatform.YOUTUBE,
            sourceUrl = info.webpageUrl ?: originalUrl,
            title = info.title ?: info.fulltitle,
            author = Author(
                handle = info.uploaderId,
                nickname = info.uploader,
                avatarUrl = null,
            ),
            coverUrl = info.thumbnail,
            variants = variants.values.toList(),
            audioUrl = audio?.url,
            provenance = "yt-dlp",
        )
    }

    private fun codecOf(value: String?): Codec {
        val codec = value.orEmpty().lowercase()
        return when {
            codec.contains("avc") || codec.contains("h264") -> Codec.H264
            codec.contains("hvc") || codec.contains("hev") ||
                codec.contains("h265") || codec.contains("bytevc1") -> Codec.H265
            else -> Codec.UNKNOWN
        }
    }
}

object YouTubePlaylistUrl {

    fun isPlaylist(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.path == "/playlist") return true
        val query = uri.query.orEmpty()
        return query.split('&').any { it.startsWith("list=") } &&
            query.split('&').none { it.startsWith("v=") }
    }

    fun id(url: String): String? {
        val query = runCatching { URI(url).query }.getOrNull().orEmpty()
        return query.split('&')
            .firstOrNull { it.startsWith("list=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }
}

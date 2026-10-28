package com.snag.downloader.data

import android.content.Context
import com.snag.downloader.core.ExtractorRegistry
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.ProfileBundle
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.StrategyFailure
import com.snag.downloader.core.UrlParser
import com.snag.downloader.core.ExtractionException
import com.snag.downloader.extract.TikTokProfileExtractor
import com.snag.downloader.extract.TikTokProfileFallback
import com.snag.downloader.extract.TikTokProfileUrl
import com.snag.downloader.extract.YouTubePlaylistExtractor
import com.snag.downloader.extract.YouTubePlaylistUrl
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED }

data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String?,
    val platform: String,
    val variantLabel: String?,
    val fileName: String,
    val mimeType: String,
    val thumbnailUrl: String?,
    val localUri: String?,
    val status: DownloadStatus,
    val bytes: Long,
    val createdAt: Long,
    /** Short reason shown when [status] is FAILED. */
    val error: String? = null,
)

/**
 * v1 persistence: a JSON array in SharedPreferences.
 * Deliberately dependency-free; swap for Room behind the same API when history
 * grows search/filter needs. Nothing in the UI knows how this is stored.
 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("snag_history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<HistoryEntry> = read().sortedByDescending { it.createdAt }

    @Synchronized
    fun add(entry: HistoryEntry) {
        write(read() + entry)
    }

    /** Batch insert: one read and one write for a whole profile save. */
    @Synchronized
    fun addAll(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        write(read() + entries)
    }

    @Synchronized
    fun update(
        id: Long,
        status: DownloadStatus,
        localUri: String? = null,
        bytes: Long = 0L,
        error: String? = null,
    ) {
        write(
            read().map { entry ->
                if (entry.id != id) entry
                else entry.copy(
                    status = status,
                    localUri = localUri ?: entry.localUri,
                    bytes = if (bytes > 0) bytes else entry.bytes,
                    error = if (status == DownloadStatus.FAILED) error else null,
                )
            }
        )
    }

    @Synchronized
    fun get(id: Long): HistoryEntry? = read().firstOrNull { it.id == id }

    /** Records the quality actually downloaded after a fallback stream was used. */
    @Synchronized
    fun setVariantLabel(id: Long, label: String) {
        write(
            read().map { entry ->
                if (entry.id == id) entry.copy(variantLabel = label) else entry
            }
        )
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    @Synchronized
    fun create(bundle: MediaBundle, variant: MediaVariant): HistoryEntry {
        // Milliseconds are not unique inside a batch; keep ids strictly increasing.
        val id = nextId()
        return HistoryEntry(
            id = id,
            url = bundle.sourceUrl,
            title = bundle.title,
            platform = bundle.platform.id,
            variantLabel = variant.displayLabel,
            fileName = "snag_${id}.${variant.extension}",
            mimeType = variant.mimeType,
            thumbnailUrl = if (variant.kind == MediaKind.IMAGE) {
                variant.url
            } else {
                bundle.coverUrl ?: variant.url
            },
            localUri = null,
            status = DownloadStatus.QUEUED,
            bytes = 0L,
            createdAt = id,
        )
    }

    private var lastId = 0L

    @Synchronized
    private fun nextId(): Long {
        val now = System.currentTimeMillis()
        lastId = if (now > lastId) now else lastId + 1
        return lastId
    }

    private fun read(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index -> array.getJSONObject(index).toEntry() }
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun JSONObject.toEntry() = HistoryEntry(
        id = getLong("id"),
        url = getString("url"),
        title = optString("title").takeIf { it.isNotBlank() },
        platform = optString("platform"),
        variantLabel = optString("variantLabel").takeIf { it.isNotBlank() },
        fileName = optString("fileName"),
        mimeType = optString("mimeType", "video/mp4"),
        thumbnailUrl = optString("thumbnailUrl").takeIf { it.isNotBlank() },
        localUri = optString("localUri").takeIf { it.isNotBlank() },
        status = runCatching { DownloadStatus.valueOf(optString("status")) }
            .getOrDefault(DownloadStatus.QUEUED),
        bytes = optLong("bytes"),
        createdAt = optLong("createdAt"),
        error = optString("error").takeIf { it.isNotBlank() },
    )

    private fun HistoryEntry.toJson() = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title.orEmpty())
        put("platform", platform)
        put("variantLabel", variantLabel.orEmpty())
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("thumbnailUrl", thumbnailUrl.orEmpty())
        put("localUri", localUri.orEmpty())
        put("status", status.name)
        put("bytes", bytes)
        put("createdAt", createdAt)
        put("error", error.orEmpty())
    }

    private companion object {
        const val KEY = "entries"
    }
}

sealed interface Outcome {
    data class Success(val bundle: MediaBundle, val failures: List<StrategyFailure>) : Outcome
    data class Profile(
        val profile: ProfileBundle,
        val failures: List<StrategyFailure>,
        val elapsedMs: Long,
    ) : Outcome
    data class Failure(
        val url: String?,
        val message: String,
        val failures: List<StrategyFailure> = emptyList(),
    ) : Outcome
}

class MediaRepository(
    private val registry: ExtractorRegistry,
    private val profiles: TikTokProfileExtractor,
    private val history: HistoryStore,
    private val youtubePlaylists: YouTubePlaylistExtractor,
) {

    val historyStore: HistoryStore get() = history

    suspend fun resolve(text: String, forceFullProfile: Boolean = false): Outcome {
        val urls = UrlParser.allUrls(text)
        val url = urls.firstOrNull()
            ?: return Outcome.Failure(null, "No link found")

        if (urls.size > 1) {
            val items = mutableListOf<MediaBundle>()
            for (candidate in urls.take(20)) {
                val bundle = runCatching { registry.resolve(candidate).bundle }.getOrNull()
                if (bundle != null) items.add(bundle)
            }
            if (items.isNotEmpty()) {
                return Outcome.Profile(
                    profile = ProfileBundle(
                        platform = items.first().platform,
                        url = urls.first(),
                        handle = "batch",
                        nickname = "Batch",
                        videoCount = items.size.toLong(),
                        items = items,
                        provenance = "multi-link",
                        note = "Batch of ${items.size} links",
                    ),
                    failures = emptyList(),
                    elapsedMs = 0,
                )
            }
        }

        if (YouTubePlaylistUrl.isPlaylist(url)) {
            val started = System.currentTimeMillis()
            val playlist = runCatching {
                withTimeoutOrNull(180_000) { youtubePlaylists.fetch(url) }
            }.getOrNull()
            if (playlist != null) {
                return Outcome.Profile(
                    profile = playlist,
                    failures = emptyList(),
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
        }

        // Handles look like /@name; video links keep the normal pipeline.
        if (TikTokProfileUrl.handle(url) != null) {
            val started = System.currentTimeMillis()
            val directProfile = runCatching {
                withTimeoutOrNull(30_000) { profiles.fetchDirect(url, forceFullProfile) }
            }.getOrNull()
            if (directProfile != null) {
                return Outcome.Profile(
                    profile = directProfile,
                    failures = emptyList(),
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
            val tikwmProfile = runCatching { TikTokProfileFallback.fetch(url) }.getOrNull()
            if (tikwmProfile != null) {
                return Outcome.Profile(
                    profile = tikwmProfile,
                    failures = emptyList(),
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
            return try {
                val profile = profiles.fetch(url)
                Outcome.Profile(profile, emptyList(), System.currentTimeMillis() - started)
            } catch (e: ExtractionException.Blocked) {
                val handle = TikTokProfileUrl.handle(url)
                if (handle != null) {
                    Outcome.Profile(
                        ProfileBundle(
                            platform = SourcePlatform.TIKTOK,
                            url = "https://www.tiktok.com/@$handle",
                            handle = handle,
                            note = "TikTok's bot check appeared. Verify on TikTok to load the videos.",
                        ),
                        listOf(
                            StrategyFailure(
                                "profile",
                                e.message ?: "TikTok's bot check appeared",
                                System.currentTimeMillis() - started,
                            )
                        ),
                        System.currentTimeMillis() - started,
                    )
                } else {
                    Outcome.Failure(
                        url,
                        e.message ?: "Couldn't load profile",
                        listOf(
                            StrategyFailure(
                                "profile",
                                e.message ?: "failed",
                                System.currentTimeMillis() - started,
                            )
                        ),
                    )
                }
            } catch (e: ExtractionException) {
                Outcome.Failure(
                    url,
                    e.message ?: "Couldn't load profile",
                    listOf(
                        StrategyFailure(
                            "profile",
                            e.message ?: "failed",
                            System.currentTimeMillis() - started,
                        )
                    ),
                )
            } catch (t: Throwable) {
                Outcome.Failure(url, t.message ?: "Couldn't load profile")
            }
        }

        if (UrlParser.classify(url) == SourcePlatform.UNKNOWN) {
            return Outcome.Failure(url, "Unsupported link")
        }

        val resolution = registry.resolve(url)
        val bundle = resolution.bundle
            ?: return Outcome.Failure(
                url,
                "No media found",
                resolution.failures,
            )

        return Outcome.Success(bundle, resolution.failures)
    }
}

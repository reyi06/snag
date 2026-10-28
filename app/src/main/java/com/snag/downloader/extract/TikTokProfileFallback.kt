package com.snag.downloader.extract

import com.snag.downloader.core.Author
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.ProfileBundle
import com.snag.downloader.core.SourcePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Public profile reader backed by TikWM's user endpoints.
 *
 * TikTok's own profile page is increasingly gated by a bot check. The TikWM user feed
 * exposes the same public post objects and playable URLs without a WebView, so this is
 * tried before the authenticated page flow.
 */
internal object TikTokProfileFallback {

    private val bases = listOf(
        "https://www.tikwm.com/api",
        "https://tikwm.com/api",
    )

    suspend fun fetch(profileUrl: String): ProfileBundle? = withTimeoutOrNull(12_000) {
        withContext(Dispatchers.IO) {
            val requestedHandle = TikTokProfileUrl.handle(profileUrl) ?: return@withContext null
            val encoded = URLEncoder.encode(requestedHandle, "UTF-8")

            val infoRoot = request("user/info?unique_id=$encoded") ?: return@withContext null
            val info = infoRoot.optJSONObject("data") ?: return@withContext null
            val user = info.optJSONObject("user") ?: return@withContext null
            val stats = info.optJSONObject("stats")
            val handle = user.optString("uniqueId").ifBlank { requestedHandle }

            val items = LinkedHashMap<String, MediaBundle>()
            var cursor = "0"
            var pages = 0
            while (pages < MAX_PAGES) {
                val pageRoot = request(
                    "user/posts?unique_id=$encoded&count=30&cursor=" +
                        URLEncoder.encode(cursor, "UTF-8")
                ) ?: break
                val data = pageRoot.optJSONObject("data") ?: break
                val videos = data.optJSONArray("videos") ?: break

                for (index in 0 until videos.length()) {
                    val post = videos.optJSONObject(index) ?: continue
                    val bundle = postToBundle(post, handle) ?: continue
                    items.putIfAbsent(bundle.id, bundle)
                }

                cursor = data.optString("cursor")
                if (!data.optBoolean("hasMore") || cursor.isBlank()) break
                pages++
            }

            if (items.isEmpty()) return@withContext null

            ProfileBundle(
                platform = SourcePlatform.TIKTOK,
                url = "https://www.tiktok.com/@$handle",
                handle = handle,
                nickname = user.optString("nickname").takeIf { it.isNotBlank() },
                avatarUrl = sequenceOf("avatarLarger", "avatarMedium", "avatarThumb")
                    .map { user.optString(it) }
                    .firstOrNull(::isHttp),
                bio = user.optString("signature").takeIf { it.isNotBlank() },
                followerCount = stats?.optLong("followerCount", 0L) ?: 0L,
                videoCount = stats?.optLong("videoCount", 0L) ?: 0L,
                items = items.values.toList(),
                provenance = "tikwm-profile",
            )
        }
    }

    private suspend fun request(path: String): JSONObject? {
        for (base in bases) {
            val page = runCatching {
                Http.get(
                    url = "$base/$path",
                    userAgent = Http.UA_DESKTOP,
                    headers = mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "Referer" to "https://www.tikwm.com/",
                    ),
                )
            }.getOrNull() ?: continue

            val root = runCatching { JSONObject(page.body) }.getOrNull() ?: continue
            if (root.optInt("code", -1) == 0) return root
        }
        return null
    }

    private fun postToBundle(post: JSONObject, expectedHandle: String): MediaBundle? {
        val author = post.optJSONObject("author")
        val authorHandle = author?.optString("unique_id")
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
        if (authorHandle != null && !authorHandle.equals(expectedHandle, ignoreCase = true)) {
            return null
        }

        val id = post.optString("id").ifBlank { post.optString("video_id") }
        if (id.isBlank()) return null
        val handle = authorHandle ?: expectedHandle
        val variants = LinkedHashMap<String, MediaVariant>()

        fun offer(kind: MediaKind, url: String, label: String?) {
            if (!isHttp(url)) return
            variants.putIfAbsent(url, MediaVariant(kind = kind, url = url, label = label))
        }

        offer(MediaKind.VIDEO, post.optString("hdplay"), "HD")
        offer(MediaKind.VIDEO, post.optString("play"), "source")
        offer(MediaKind.VIDEO, post.optString("wmplay"), "watermarked")
        post.optJSONArray("images")?.forEach { image ->
            offer(MediaKind.IMAGE, image, "image")
        }

        if (variants.isEmpty()) return null

        val cover = sequenceOf("origin_cover", "cover", "ai_dynamic_cover")
            .map { post.optString(it) }
            .firstOrNull(::isHttp)

        return MediaBundle(
            id = id,
            platform = SourcePlatform.TIKTOK,
            sourceUrl = "https://www.tiktok.com/@$handle/video/$id",
            title = post.optString("title").takeIf { it.isNotBlank() },
            author = Author(
                handle = handle,
                nickname = author?.optString("nickname")?.takeIf { it.isNotBlank() },
                avatarUrl = author?.optString("avatar")?.takeIf(::isHttp),
            ),
            coverUrl = cover,
            variants = variants.values.toList(),
            audioUrl = post.optString("music").takeIf(::isHttp),
            provenance = "tikwm-profile",
        )
    }

    private fun JSONArray.forEach(block: (String) -> Unit) {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value is String && isHttp(value)) block(value)
        }
    }

    private fun isHttp(value: String?): Boolean =
        value?.startsWith("https://") == true || value?.startsWith("http://") == true

    private const val MAX_PAGES = 5
}

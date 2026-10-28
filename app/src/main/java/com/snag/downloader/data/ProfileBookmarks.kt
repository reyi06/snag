package com.snag.downloader.data

import android.content.Context
import com.snag.downloader.core.ProfileBundle
import com.snag.downloader.core.SourcePlatform
import org.json.JSONArray
import org.json.JSONObject

/** A profile the user can return to for its latest public videos. */
data class ProfileBookmark(
    val platform: String,
    val url: String,
    val handle: String,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
) {
    /** Stable identity across a profile URL's tracking parameters and display changes. */
    val key: String
        get() = "${platform}:${handle.trim().removePrefix("@").lowercase()}"

    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: "@$handle"

    companion object {
        fun fromProfile(profile: ProfileBundle): ProfileBookmark = ProfileBookmark(
            platform = profile.platform.id,
            url = profile.url,
            handle = profile.handle.trim().removePrefix("@"),
            nickname = profile.nickname,
            avatarUrl = profile.avatarUrl,
        )

        fun keyFor(platform: SourcePlatform, handle: String): String =
            "${platform.id}:${handle.trim().removePrefix("@").lowercase()}"
    }
}

/**
 * v1 bookmark persistence: a small JSON array in SharedPreferences, matching the
 * dependency-free style of [HistoryStore]. Only public profile metadata is stored.
 */
class ProfileBookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences("snag_profile_bookmarks", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<ProfileBookmark> = read().sortedByDescending { it.savedAt }

    @Synchronized
    fun contains(key: String): Boolean = read().any { it.key == key }

    /** Adds or refreshes a bookmark while keeping one entry per profile. */
    @Synchronized
    fun save(bookmark: ProfileBookmark) {
        write(read().filterNot { it.key == bookmark.key } + bookmark)
    }

    @Synchronized
    fun remove(key: String) {
        write(read().filterNot { it.key == key })
    }

    private fun read(): List<ProfileBookmark> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val objectValue = array.optJSONObject(index) ?: return@mapNotNull null
                objectValue.toBookmark()
            }
        }.getOrDefault(emptyList())
    }

    private fun write(bookmarks: List<ProfileBookmark>) {
        val array = JSONArray()
        bookmarks.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun JSONObject.toBookmark(): ProfileBookmark? {
        val url = optString("url")
        val handle = optString("handle")
        if (url.isBlank() || handle.isBlank()) return null
        return ProfileBookmark(
            platform = optString("platform", SourcePlatform.TIKTOK.id)
                .ifBlank { SourcePlatform.TIKTOK.id },
            url = url,
            handle = handle,
            nickname = optString("nickname").takeIf { it.isNotBlank() },
            avatarUrl = optString("avatarUrl").takeIf { it.isNotBlank() },
            savedAt = optLong("savedAt"),
        )
    }

    private fun ProfileBookmark.toJson() = JSONObject().apply {
        put("platform", platform)
        put("url", url)
        put("handle", handle)
        put("nickname", nickname.orEmpty())
        put("avatarUrl", avatarUrl.orEmpty())
        put("savedAt", savedAt)
    }

    private companion object {
        const val KEY = "profiles"
    }
}

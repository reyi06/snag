package com.snag.downloader.extract

import android.content.Context
import org.json.JSONObject

/** Metadata that stays stable enough to avoid the fragile creator/post embeds. */
data class TikTokIdentity(
    val handle: String,
    val secUid: String,
    val userId: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val followerCount: Long = 0L,
    val videoCount: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Small per-handle cache for the stable TikTok identity and public profile metadata.
 * The creator API only needs `secUid`, so a cache hit can skip the 503-prone embeds.
 */
class TikTokIdentityCache(context: Context) {

    private val prefs = context.getSharedPreferences("snag_profile_identities", Context.MODE_PRIVATE)

    @Synchronized
    fun get(handle: String): TikTokIdentity? {
        val key = key(handle)
        val raw = prefs.getString(key, null) ?: return null
        val identity = runCatching { fromJson(JSONObject(raw)) }.getOrNull() ?: return null
        val age = System.currentTimeMillis() - identity.updatedAt
        if (age < 0 || age > MAX_AGE_MS) {
            prefs.edit().remove(key).apply()
            return null
        }
        return identity
    }

    @Synchronized
    fun put(identity: TikTokIdentity) {
        if (identity.secUid.isBlank()) return
        prefs.edit()
            .putString(key(identity.handle), toJson(identity).toString())
            .apply()
    }

    @Synchronized
    fun remove(handle: String) {
        prefs.edit().remove(key(handle)).apply()
    }

    private fun key(handle: String): String = "identity_${handle.removePrefix("@").lowercase()}"

    private fun toJson(identity: TikTokIdentity): JSONObject = JSONObject().apply {
        put("handle", identity.handle)
        put("secUid", identity.secUid)
        put("userId", identity.userId.orEmpty())
        put("nickname", identity.nickname.orEmpty())
        put("avatarUrl", identity.avatarUrl.orEmpty())
        put("bio", identity.bio.orEmpty())
        put("followerCount", identity.followerCount)
        put("videoCount", identity.videoCount)
        put("updatedAt", identity.updatedAt)
    }

    private fun fromJson(json: JSONObject): TikTokIdentity = TikTokIdentity(
        handle = json.getString("handle"),
        secUid = json.getString("secUid"),
        userId = json.optString("userId").takeIf { it.isNotBlank() },
        nickname = json.optString("nickname").takeIf { it.isNotBlank() },
        avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() },
        bio = json.optString("bio").takeIf { it.isNotBlank() },
        followerCount = json.optLong("followerCount", 0L),
        videoCount = json.optLong("videoCount", 0L),
        updatedAt = json.optLong("updatedAt", 0L),
    )

    private companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}

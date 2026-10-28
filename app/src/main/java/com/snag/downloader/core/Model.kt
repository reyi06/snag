package com.snag.downloader.core

/**
 * Everything the UI and the downloader know about a resolved media item.
 * Nothing here is entitlement-aware: every variant is downloadable by design.
 */

enum class SourcePlatform(val id: String, val label: String) {
    TIKTOK("tiktok", "TikTok"),
    INSTAGRAM("instagram", "Instagram"),
    FACEBOOK("facebook", "Facebook"),
    GENERIC("generic", "Any link"),
    UNKNOWN("unknown", "Unknown");

    companion object {
        fun fromId(id: String?): SourcePlatform = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

enum class MediaKind { VIDEO, IMAGE, AUDIO }

enum class Codec { H264, H265, UNKNOWN }

data class Author(
    val handle: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
) {
    val display: String
        get() = listOfNotNull(nickname, handle?.let { "@$it" }).firstOrNull() ?: "unknown"
}

data class MediaVariant(
    val kind: MediaKind,
    val url: String,
    val playbackUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val label: String? = null,
    val isFhd: Boolean = false,
    val codec: Codec = Codec.UNKNOWN,
    val estimatedBytes: Long? = null,
    /** Explicit container when the URL knows better than [MediaKind] (for example mp3 audio). */
    val fileExtension: String? = null,
) {
    val extension: String
        get() = fileExtension ?: when (kind) {
            MediaKind.VIDEO -> "mp4"
            MediaKind.IMAGE -> "jpg"
            MediaKind.AUDIO -> "m4a"
        }

    val mimeType: String
        get() = when (extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "webp" -> "image/webp"
            "png" -> "image/png"
            else -> when (kind) {
                MediaKind.VIDEO -> "video/mp4"
                MediaKind.IMAGE -> "image/jpeg"
                MediaKind.AUDIO -> "audio/mp4"
            }
        }

    /** Human-readable row title in the variant picker. */
    val displayLabel: String
        get() = buildString {
            append(
                when {
                    height > 0 -> "${height}p"
                    !label.isNullOrBlank() -> label
                    else -> kind.name.lowercase()
                }
            )
            if (label == "watermarked") append(" · watermark")
            when (codec) {
                Codec.H265 -> append(" · H.265")
                Codec.H264 -> append(" · H.264")
                Codec.UNKNOWN -> Unit
            }
        }
}

data class MediaBundle(
    val id: String,
    val platform: SourcePlatform,
    val sourceUrl: String,
    val title: String? = null,
    val author: Author? = null,
    val coverUrl: String? = null,
    val variants: List<MediaVariant> = emptyList(),
    val audioUrl: String? = null,
    /** Which strategy produced this result: "api", "opengraph", "webview". */
    val provenance: String = "",
    val elapsedMs: Long = 0,
)

/** A public profile listing. Items are full media bundles, so batch needs no second resolve. */
data class ProfileBundle(
    val platform: SourcePlatform,
    val url: String,
    val handle: String,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val followerCount: Long = 0,
    val videoCount: Long = 0,
    val items: List<MediaBundle> = emptyList(),
    val provenance: String = "",
    /** True when only the creator embed's recent posts are available. */
    val isPartial: Boolean = false,
    /** Non-fatal explanation shown under the grid (for example, a partial listing). */
    val note: String? = null,
)

/**
 * Picks the variant a save should use.
 * Highest resolution by default; when [preferHighest] is off, takes the lowest resolution
 * up to 1080p to save space. Same-resolution streams follow [preferH265].
 */
fun MediaBundle.bestVariant(
    preferH265: Boolean = true,
    preferHighest: Boolean = true,
): MediaVariant? {
    val pool = variants.filter { it.kind == MediaKind.VIDEO }
        .ifEmpty { variants.filter { it.kind == MediaKind.IMAGE } }
        .ifEmpty { variants }
    if (pool.isEmpty()) return null

    val codecRank: (MediaVariant) -> Int = { v ->
        when (v.codec) {
            Codec.H265 -> if (preferH265) 0 else 1
            Codec.H264 -> if (preferH265) 1 else 0
            else -> 0
        }
    }

    if (preferHighest) {
        return pool.sortedWith(
            compareByDescending<MediaVariant> { it.height }
                .thenByDescending { it.width }
                .thenBy(codecRank)
        ).first()
    }

    val capped = pool.filter { it.height in 1..1080 }.ifEmpty { pool }
    return capped.sortedWith(
        compareBy<MediaVariant> { it.height }
            .thenBy { if ((it.codec == Codec.H265) == preferH265) 0 else 1 }
    ).first()
}

/**
 * Compile-time capability switches. These exist to hide unfinished work during
 * development — they are NOT a paywall. Everything shipped is enabled.
 */
object Features {
    const val WEBVIEW_STRATEGY: Boolean = true
    const val IMAGE_POSTS: Boolean = true
    const val AUDIO_EXTRACTION: Boolean = true
    const val BATCH_PASTE: Boolean = false
}

package com.snag.downloader.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/** The one quality preference used by auto-save, batch saves and result preselects. */
enum class QualityPreference(
    val key: String,
    val title: String,
) {
    HIGHEST_H265(
        key = "highest_h265",
        title = "Highest · H.265",
    ),
    HIGHEST_H264(
        key = "highest_h264",
        title = "Highest · H.264",
    ),
    SMALLEST(
        key = "smallest",
        title = "Smallest file",
    );

    val preferHighest: Boolean get() = this != SMALLEST
    val preferH265: Boolean get() = this == HIGHEST_H265

    companion object {
        fun fromKey(key: String?): QualityPreference =
            entries.firstOrNull { it.key == key } ?: HIGHEST_H265
    }
}

/** Durable, non-sensitive preferences. Everything ships enabled; these only tune defaults. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("snag_settings", Context.MODE_PRIVATE)
    private val scrollModeListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SCROLL_MODE) {
            val enabled = prefs.getBoolean(KEY_SCROLL_MODE, false)
            scrollModeListeners.forEach { it(enabled) }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    /** Observes the service's Stop action as well as changes made from Settings. */
    fun observeScrollMode(listener: (Boolean) -> Unit): () -> Unit {
        scrollModeListeners.add(listener)
        return { scrollModeListeners.remove(listener) }
    }

    /**
     * Highest + H.265 is the default for new installs and for installs upgrading from the
     * earlier pair of quality switches. The old boolean keys are intentionally ignored.
     */
    var quality: QualityPreference
        get() = QualityPreference.fromKey(prefs.getString(KEY_QUALITY, null))
        set(value) = prefs.edit().putString(KEY_QUALITY, value.key).apply()

    val preferHighest: Boolean get() = quality.preferHighest
    val preferH265: Boolean get() = quality.preferH265

    /** Fill the field and save the first supported clipboard link when the app opens. */
    var autoClipboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLIPBOARD, value).apply()

    /** Last-resort HTTP extractor used when the platform blocks on-device requests. */
    var useFallbackService: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_FALLBACK, value).apply()

    /** Floating clipboard capture: the crown is shown while this is enabled. */
    var scrollModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL_MODE, value).apply()

    /** Smaller black/white overlay instead of the maroon/gold crown. */
    var lowProfileCrown: Boolean
        get() = prefs.getBoolean(KEY_LOW_PROFILE_CROWN, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_PROFILE_CROWN, value).apply()

    /** Overlay opacity, clamped so the control cannot become effectively invisible. */
    var scrollOverlayOpacity: Float
        get() = prefs.getFloat(KEY_OVERLAY_OPACITY, 1f).coerceIn(MIN_OVERLAY_OPACITY, 1f)
        set(value) = prefs.edit()
            .putFloat(KEY_OVERLAY_OPACITY, value.coerceIn(MIN_OVERLAY_OPACITY, 1f))
            .apply()

    /** Returns true when this exact link was already auto-downloaded before a restart. */
    fun autoWasHandled(url: String): Boolean =
        prefs.getStringSet(KEY_AUTO_HANDLED, emptySet()).orEmpty().contains(url)

    fun markAutoHandled(url: String) {
        val next = prefs.getStringSet(KEY_AUTO_HANDLED, emptySet())
            .orEmpty()
            .toMutableSet()
        if (!next.add(url)) return
        prefs.edit().putStringSet(KEY_AUTO_HANDLED, next).apply()
    }

    companion object {
        const val MIN_OVERLAY_OPACITY = 0.35f

        private const val KEY_QUALITY = "quality_preference"
        private const val KEY_AUTO_CLIPBOARD = "auto_clipboard"
        private const val KEY_FALLBACK = "use_fallback_service"
        private const val KEY_SCROLL_MODE = "scroll_mode_enabled"
        private const val KEY_LOW_PROFILE_CROWN = "low_profile_crown"
        private const val KEY_OVERLAY_OPACITY = "scroll_overlay_opacity"
        private const val KEY_AUTO_HANDLED = "auto_handled_urls"
    }
}

package com.snag.downloader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snag.downloader.SnagApp
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.ProfileBundle
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.StrategyFailure
import com.snag.downloader.core.UrlParser
import com.snag.downloader.core.bestVariant
import com.snag.downloader.data.DownloadStatus
import com.snag.downloader.data.HistoryEntry
import com.snag.downloader.data.Outcome
import com.snag.downloader.data.ProfileBookmark
import com.snag.downloader.data.QualityPreference
import com.snag.downloader.data.TikTokSession
import com.snag.downloader.download.DownloadJob
import com.snag.downloader.download.DownloadScheduler
import com.snag.downloader.extract.TikTokProfileUrl
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    SAVE("Save"),
    LIBRARY("Library"),
    SETTINGS("Settings"),
}

sealed interface ResolveState {
    data object Idle : ResolveState
    data object Loading : ResolveState
    data class Media(val bundle: MediaBundle, val failures: List<StrategyFailure>) : ResolveState
    data class Profile(val profile: ProfileBundle) : ResolveState
    data class Failed(val message: String, val failures: List<StrategyFailure>) : ResolveState
}

class SnagViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SnagApp
    private val repository = app.repository
    private val store = app.history
    private val prefs = app.settings
    private val bookmarkStore = app.profileBookmarks

    var tab by mutableStateOf(Tab.SAVE)
        private set

    var input by mutableStateOf("")
        private set

    var state by mutableStateOf<ResolveState>(ResolveState.Idle)
        private set

    var quality by mutableStateOf(prefs.quality)
        private set

    val preferHighest: Boolean get() = quality.preferHighest
    val preferH265: Boolean get() = quality.preferH265

    var autoClipboard by mutableStateOf(prefs.autoClipboard)
        private set

    var useFallbackService by mutableStateOf(prefs.useFallbackService)
        private set

    var scrollModeEnabled by mutableStateOf(prefs.scrollModeEnabled)
        private set

    var lowProfileCrown by mutableStateOf(prefs.lowProfileCrown)
        private set

    var scrollOverlayOpacity by mutableStateOf(prefs.scrollOverlayOpacity)
        private set

    var tiktokSignedIn by mutableStateOf(TikTokSession.hasSessionCookie())
        private set

    /** Transient user-facing note; the root shows it once and clears it. */
    var message by mutableStateOf<String?>(null)
        private set

    val history = mutableStateListOf<HistoryEntry>()

    /** Saved profiles, newest first. Tapping one reloads its current public videos. */
    val profileBookmarks = mutableStateListOf<ProfileBookmark>()

    /** Incremented when a bookmark request has loaded a profile ready for its sheet. */
    var profileSheetRequest by mutableStateOf(0)
        private set

    /** Profile item ids that are ticked for batch save. */
    val selected = mutableStateMapOf<String, Boolean>()

    init {
        refreshHistory()
        refreshProfileBookmarks()
        prefs.observeScrollMode { enabled -> scrollModeEnabled = enabled }
        if (tiktokSignedIn) {
            viewModelScope.launch { validateTikTokSession() }
        }
    }

    fun selectTab(tab: Tab) {
        this.tab = tab
        refreshHistory()
    }

    fun onInputChanged(value: String) {
        input = value
    }

    fun consumeMessage() {
        message = null
    }

    fun refreshHistory() {
        history.clear()
        history.addAll(store.all())
    }

    fun refreshProfileBookmarks() {
        profileBookmarks.clear()
        profileBookmarks.addAll(bookmarkStore.all())
    }

    /** Share sheet / VIEW intent entry point. */
    fun handleIncoming(text: String?) {
        val url = text?.let { UrlParser.firstUrl(it) } ?: return
        tab = Tab.SAVE
        input = url
        resolve(url)
    }

    /** Clipboard paste, with the same "no link in the text" guard as the share path. */
    fun paste(text: String?) {
        val url = text?.let { UrlParser.firstUrl(it) }
        if (url == null) {
            message = "No link in clipboard"
            return
        }
        input = url
        resolve(url)
    }

    /**
     * Clipboard entry point used when the app gains focus.
     *
     * Only known TikTok/Instagram/Facebook links are accepted. The same clipboard value is
     * handled once per process so returning to the app does not queue the same file twice.
     * Profile links resolve normally but are not auto-downloaded; the user still picks what
     * to save from the grid.
     */
    fun autoFromClipboard(text: String?): Boolean {
        val url = text?.let { UrlParser.firstUrl(it) } ?: return false
        if (!isAutoUrl(url)) return false
        val key = autoKey(url)
        val alreadyInHistory = store.all().any { autoKey(it.url) == key }
        if (prefs.autoWasHandled(key) || alreadyInHistory || url == input) {
            prefs.markAutoHandled(key)
            return false
        }
        prefs.markAutoHandled(key)
        tab = Tab.SAVE
        input = url
        resolve(url, autoSave = true)
        return true
    }

    private fun autoKey(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/')

    private fun isAutoUrl(url: String): Boolean {
        if (TikTokProfileUrl.handle(url) != null) return true
        return UrlParser.classify(url) in AUTO_PLATFORMS
    }

    fun clearInput() {
        input = ""
        state = ResolveState.Idle
        selected.clear()
    }

    fun resolve(text: String = input) {
        resolve(text, autoSave = false)
    }

    /** Retry the current profile after its browser verification, opening the batch picker on success. */
    fun reloadVerifiedProfile() {
        val current = (state as? ResolveState.Profile)?.profile ?: return
        resolve(current.url, autoSave = false, openProfile = true)
    }

    /** Retry a partial profile without returning another recent-only result. */
    fun retryFullProfile() {
        val current = (state as? ResolveState.Profile)?.profile ?: return
        resolve(current.url, autoSave = false, openProfile = true, forceFullProfile = true)
    }

    private fun resolve(
        text: String,
        autoSave: Boolean,
        openProfile: Boolean = false,
        forceFullProfile: Boolean = false,
    ) {
        val value = text.trim()
        if (value.isEmpty() || state is ResolveState.Loading) return

        state = ResolveState.Loading
        selected.clear()
        viewModelScope.launch {
            state = try {
                when (val outcome = repository.resolve(value, forceFullProfile)) {
                    is Outcome.Success -> {
                        if (autoSave) queueBestAvailable(outcome.bundle)
                        ResolveState.Media(outcome.bundle, outcome.failures)
                    }
                    is Outcome.Profile -> {
                        if (openProfile) {
                            if (outcome.profile.items.isNotEmpty()) {
                                profileSheetRequest += 1
                            } else {
                                message = "Verify to load this profile's latest videos"
                            }
                        }
                        ResolveState.Profile(outcome.profile)
                    }
                    is Outcome.Failure -> ResolveState.Failed(outcome.message, outcome.failures)
                }
            } catch (t: Throwable) {
                ResolveState.Failed(
                    t.message ?: "Couldn't read link",
                    emptyList(),
                )
            }
        }
    }

    /** Auto-save prefers photo carousels over the generated video TikTok may also expose. */
    private fun queueBestAvailable(bundle: MediaBundle) {
        val images = bundle.variants.filter { it.kind == MediaKind.IMAGE }
        if (images.isNotEmpty()) {
            enqueue(images.map { bundle to it })
            message = if (images.size == 1) "Saving photo" else "Saving ${images.size} photos"
            return
        }

        val videos = bundle.variants.filter { it.kind == MediaKind.VIDEO }
        val clean = videos.filter { it.label != "watermarked" }.ifEmpty { videos }
        val best = bundle.copy(variants = clean)
            .bestVariant(preferH265 = preferH265, preferHighest = preferHighest)
        if (best != null) {
            enqueue(listOf(bundle to best))
            message = "Saving ${best.displayLabel}"
            return
        }

        message = "Nothing downloadable"
    }

    /* ---------------------------------------------------------------- saving */

    fun save(bundle: MediaBundle, variant: MediaVariant) {
        enqueue(listOf(bundle to variant))
        message = "Queued"
    }

    fun saveAllImages(bundle: MediaBundle, images: List<MediaVariant>) {
        if (images.isEmpty()) return
        enqueue(images.map { bundle to it })
        message = if (images.size == 1) "Queued" else "Queued ${images.size} photos"
    }

    fun saveAudio(bundle: MediaBundle) {
        val url = bundle.audioUrl ?: return
        enqueue(listOf(bundle to audioVariant(url)))
        message = "Queued audio"
    }

    fun selectedCount(): Int = selected.count { it.value }

    fun allSelected(profile: ProfileBundle): Boolean =
        profile.items.isNotEmpty() && profile.items.all { selected[it.id] == true }

    fun toggleSelection(id: String) {
        if (selected.containsKey(id)) selected.remove(id) else selected[id] = true
    }

    fun selectAll(profile: ProfileBundle) {
        if (allSelected(profile)) {
            selected.clear()
        } else {
            profile.items.forEach { selected[it.id] = true }
        }
    }

    fun saveSelected(profile: ProfileBundle) {
        val chosen = profile.items.filter { selected[it.id] == true }
        if (chosen.isEmpty()) {
            message = "Select at least one post"
            return
        }

        val pairs = chosen.flatMap(::bestPairs)
        if (pairs.isEmpty()) {
            message = "Nothing downloadable"
            return
        }

        enqueue(pairs)
        selected.clear()
        message = if (pairs.size == 1) "Queued" else "Queued ${pairs.size} files"
    }

    fun saveAll(profile: ProfileBundle) {
        val pairs = profile.items.flatMap(::bestPairs)
        if (pairs.isEmpty()) {
            message = "Nothing downloadable"
            return
        }

        enqueue(pairs)
        selected.clear()
        message = if (pairs.size == 1) "Queued" else "Queued ${pairs.size} files"
    }

    fun applyProfileItems(raw: String) {
        val current = (state as? ResolveState.Profile)?.profile ?: return
        val items = runCatching { app.profiles.parseItems(raw, current.url) }
            .getOrDefault(emptyList())
        if (items.isEmpty()) {
            message = "No videos found"
            return
        }
        state = ResolveState.Profile(current.copy(items = items, note = null))
        profileSheetRequest += 1
        message = "Loaded ${items.size} videos"
    }

    private fun bestPairs(item: MediaBundle): List<Pair<MediaBundle, MediaVariant>> {
        val images = item.variants.filter { it.kind == MediaKind.IMAGE }
        if (images.isNotEmpty()) {
            return images.map { image -> item to image }
        }

        val videos = item.variants.filter { it.kind == MediaKind.VIDEO }
        val clean = videos.filter { it.label != "watermarked" }.ifEmpty { videos }
        val variant = item.copy(variants = clean)
            .bestVariant(preferH265 = preferH265, preferHighest = preferHighest)
            ?: return emptyList()
        return listOf(item to variant)
    }

    private fun enqueue(pairs: List<Pair<MediaBundle, MediaVariant>>) {
        if (pairs.isEmpty()) return
        val entries = pairs.map { (bundle, variant) -> store.create(bundle, variant) }
        store.addAll(entries)
        DownloadScheduler.enqueue(
            app,
            entries.mapIndexed { index, entry ->
                val (bundle, variant) = pairs[index]
                DownloadJob(
                    url = variant.url,
                    fileName = entry.fileName,
                    mimeType = entry.mimeType,
                    historyId = entry.id,
                    fallbackUrl = variant.playbackUrl,
                    sourceUrl = bundle.sourceUrl,
                )
            },
        )
        refreshHistory()
    }

    private fun audioVariant(url: String): MediaVariant {
        val extension = url.substringBefore('?').substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS }
            ?: "m4a"
        return MediaVariant(
            kind = MediaKind.AUDIO,
            url = url,
            label = "audio",
            fileExtension = extension,
        )
    }

    /* ---------------------------------------------------------------- library */

    fun openEntry(entry: HistoryEntry) {
        when (entry.status) {
            DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                message = "Still saving"
                return
            }
            DownloadStatus.FAILED -> {
                message = entry.error?.takeIf { it.isNotBlank() } ?: "Download failed"
                return
            }
            DownloadStatus.DONE -> Unit
        }

        val uri = entry.localUri
        if (uri.isNullOrBlank()) {
            message = "File not found"
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { message = "No app can open this file" }
    }

    fun openProfile(profile: ProfileBundle) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(profile.url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { message = "No app can open this profile" }
    }

    /* -------------------------------------------------------------- bookmarks */

    fun isBookmarked(profile: ProfileBundle): Boolean {
        val key = ProfileBookmark.fromProfile(profile).key
        return profileBookmarks.any { it.key == key }
    }

    fun toggleBookmark(profile: ProfileBundle) {
        val bookmark = ProfileBookmark.fromProfile(profile)
        if (profileBookmarks.any { it.key == bookmark.key }) {
            bookmarkStore.remove(bookmark.key)
            message = "Removed @${bookmark.handle}"
        } else {
            bookmarkStore.save(bookmark)
            message = "Saved @${bookmark.handle}"
        }
        refreshProfileBookmarks()
    }

    fun removeBookmark(bookmark: ProfileBookmark) {
        bookmarkStore.remove(bookmark.key)
        refreshProfileBookmarks()
        message = "Removed @${bookmark.handle}"
    }

    /** Return to a saved profile and fetch its current posts, then open the picker. */
    fun openBookmark(bookmark: ProfileBookmark) {
        tab = Tab.SAVE
        input = bookmark.url
        resolve(bookmark.url, autoSave = false, openProfile = true)
    }

    /** Open the creator attached to a resolved post and fetch their latest videos. */
    fun openAuthorProfile(bundle: MediaBundle) {
        if (bundle.platform != SourcePlatform.TIKTOK) {
            message = "Profile batch is available for TikTok links"
            return
        }
        val handle = bundle.author?.handle
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
        if (handle == null) {
            message = "No profile attached to this post"
            return
        }
        val url = "https://www.tiktok.com/@$handle"
        tab = Tab.SAVE
        input = url
        resolve(url, autoSave = false, openProfile = true)
    }

    fun clearHistory() {
        store.clear()
        refreshHistory()
        message = "History cleared"
    }

    /* --------------------------------------------------------------- settings */

    fun updateQuality(value: QualityPreference) {
        prefs.quality = value
        quality = value
    }

    fun updateAutoClipboard(value: Boolean) {
        prefs.autoClipboard = value
        autoClipboard = value
    }

    fun updateUseFallbackService(value: Boolean) {
        prefs.useFallbackService = value
        useFallbackService = value
    }

    fun updateScrollMode(value: Boolean) {
        prefs.scrollModeEnabled = value
        scrollModeEnabled = value
    }

    fun updateLowProfileCrown(value: Boolean) {
        prefs.lowProfileCrown = value
        lowProfileCrown = value
    }

    fun updateScrollOverlayOpacity(value: Float) {
        val clamped = value.coerceIn(com.snag.downloader.data.SettingsStore.MIN_OVERLAY_OPACITY, 1f)
        prefs.scrollOverlayOpacity = clamped
        scrollOverlayOpacity = clamped
    }

    fun refreshScrollMode() {
        scrollModeEnabled = prefs.scrollModeEnabled
        lowProfileCrown = prefs.lowProfileCrown
        scrollOverlayOpacity = prefs.scrollOverlayOpacity
    }

    /**
     * The cookie store can contain a guest or expired TikTok session. Only the account
     * endpoint is treated as proof of login; this is also how an expired cookie is caught.
     */
    suspend fun validateTikTokSession(): Boolean {
        return when (TikTokSession.validate()) {
            TikTokSession.Status.Valid -> {
                tiktokSignedIn = true
                true
            }

            TikTokSession.Status.Expired -> {
                tiktokSignedIn = false
                message = "TikTok session expired. Sign in again."
                false
            }

            TikTokSession.Status.Unknown -> {
                message = "Couldn't verify TikTok session"
                tiktokSignedIn
            }
        }
    }

    fun markTikTokSignedIn() {
        tiktokSignedIn = true
        message = "Signed in to TikTok"
    }

    fun signOutTikTok() {
        TikTokSession.signOut()
        tiktokSignedIn = false
        message = "Signed out of TikTok"
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav")
        val AUTO_PLATFORMS = setOf(
            SourcePlatform.TIKTOK,
            SourcePlatform.INSTAGRAM,
            SourcePlatform.FACEBOOK,
        )
    }
}

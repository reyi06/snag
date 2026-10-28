package com.snag.downloader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.snag.downloader.core.ExtractorRegistry
import com.snag.downloader.data.HistoryStore
import com.snag.downloader.data.MediaRepository
import com.snag.downloader.data.ProfileBookmarkStore
import com.snag.downloader.data.SettingsStore
import com.snag.downloader.extract.FacebookExtractor
import com.snag.downloader.extract.GenericExtractor
import com.snag.downloader.extract.InstagramExtractor
import com.snag.downloader.extract.TikTokExtractor
import com.snag.downloader.extract.TikTokIdentityCache
import com.snag.downloader.extract.TikTokProfileExtractor
import com.snag.downloader.extract.YouTubeExtractor
import com.snag.downloader.extract.YouTubePlaylistExtractor
import com.snag.downloader.ui.SnagMotion

/**
 * Manual composition root. Deliberately tiny: no DI framework, no service locator
 * indirection — the graph is four objects and every dependency is visible here.
 */
class SnagApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(SnagMotion.ms(220))
        .build()

    val history: HistoryStore by lazy { HistoryStore(this) }

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val profileBookmarks: ProfileBookmarkStore by lazy { ProfileBookmarkStore(this) }

    val profileIdentities: TikTokIdentityCache by lazy { TikTokIdentityCache(this) }

    val profiles: TikTokProfileExtractor by lazy { TikTokProfileExtractor(profileIdentities) }

    val youtubePlaylists: YouTubePlaylistExtractor by lazy { YouTubePlaylistExtractor(this) }

    val repository: MediaRepository by lazy {
        MediaRepository(
            registry = ExtractorRegistry(
                listOf(
                    TikTokExtractor(fallbackEnabled = { settings.useFallbackService }),
                    YouTubeExtractor(this),
                    InstagramExtractor(this),
                    FacebookExtractor(),
                    GenericExtractor(),
                )
            ),
            profiles = profiles,
            history = history,
            youtubePlaylists = youtubePlaylists,
        )
    }
}

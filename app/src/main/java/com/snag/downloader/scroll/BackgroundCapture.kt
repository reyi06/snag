package com.snag.downloader.scroll

import android.content.Context
import com.snag.downloader.SnagApp
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.UrlParser
import com.snag.downloader.core.bestVariant
import com.snag.downloader.data.Outcome
import com.snag.downloader.download.DownloadJob
import com.snag.downloader.download.DownloadScheduler
import com.snag.downloader.extract.TikTokProfileUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves a TikTok link copied by the user and queues the best downloadable media.
 * Kept outside the activity so the transparent clipboard relay can disappear immediately.
 */
internal object BackgroundCapture {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    fun capture(context: Context, text: String) {
        val app = context.applicationContext as? SnagApp ?: return
        val url = UrlParser.firstUrl(text)
        if (url == null || UrlParser.classify(url) != SourcePlatform.TIKTOK) {
            CaptureFeedback.emit(CaptureFeedbackState.Error("Copy a TikTok link first"))
            return
        }
        if (TikTokProfileUrl.handle(url) != null) {
            CaptureFeedback.emit(CaptureFeedbackState.Error("Copy a video link"))
            return
        }
        if (!busy.compareAndSet(false, true)) return
        CaptureFeedback.emit(CaptureFeedbackState.Capturing)

        scope.launch {
            try {
                val outcome = app.repository.resolve(url)
                when (outcome) {
                    is Outcome.Success -> queue(app, outcome.bundle)
                    is Outcome.Profile ->
                        CaptureFeedback.emit(CaptureFeedbackState.Error("Copy a video link"))
                    is Outcome.Failure -> CaptureFeedback.emit(
                        CaptureFeedbackState.Error(
                            outcome.message.takeIf { it.isNotBlank() } ?: "Couldn't read that link"
                        )
                    )
                }
            } catch (t: Throwable) {
                CaptureFeedback.emit(
                    CaptureFeedbackState.Error(t.message?.take(80) ?: "Couldn't read that link")
                )
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun queue(app: SnagApp, bundle: com.snag.downloader.core.MediaBundle) {
        val sourceKey = bundle.sourceUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        val alreadySaved = app.history.all().any { entry ->
            entry.url.substringBefore('?').substringBefore('#').trimEnd('/') == sourceKey &&
                entry.status != com.snag.downloader.data.DownloadStatus.FAILED
        }
        if (alreadySaved) {
            CaptureFeedback.emit(CaptureFeedbackState.Success)
            return
        }

        val images = bundle.variants.filter { it.kind == MediaKind.IMAGE }
        val pairs = if (images.isNotEmpty()) {
            images.map { image -> bundle to image }
        } else {
            val videos = bundle.variants.filter { it.kind == MediaKind.VIDEO }
            val clean = videos.filter { it.label != "watermarked" }.ifEmpty { videos }
            val chosen = bundle.copy(variants = clean).bestVariant(
                preferH265 = app.settings.preferH265,
                preferHighest = app.settings.preferHighest,
            ) ?: run {
                CaptureFeedback.emit(CaptureFeedbackState.Error("No downloadable media"))
                return
            }
            listOf(bundle to chosen)
        }

        val entries = pairs.map { (item, variant) -> app.history.create(item, variant) }
        app.history.addAll(entries)
        DownloadScheduler.enqueue(
            app,
            entries.mapIndexed { index, entry ->
                val (item, variant) = pairs[index]
                DownloadJob(
                    url = variant.url,
                    fileName = entry.fileName,
                    mimeType = entry.mimeType,
                    historyId = entry.id,
                    fallbackUrl = variant.playbackUrl,
                    sourceUrl = item.sourceUrl,
                )
            },
        )
        CaptureFeedback.emit(CaptureFeedbackState.Success)
    }
}

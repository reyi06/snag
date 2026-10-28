package com.snag.downloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.snag.downloader.MainActivity
import com.snag.downloader.SnagApp
import com.snag.downloader.core.Http
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.UrlParser
import com.snag.downloader.data.DownloadStatus
import com.snag.downloader.extract.TikTokFallback
import com.snag.downloader.extract.YouTubeClient
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

private const val KEY_JOBS = "jobs"
private const val KEY_QUEUE_FILE = "queue_file"
const val DOWNLOAD_PROGRESS = "progress"
const val DOWNLOAD_INDEX = "index"
const val DOWNLOAD_TOTAL = "total"
const val DOWNLOAD_BYTES = "bytes"
const val DOWNLOAD_TOTAL_BYTES = "total_bytes"
const val DOWNLOAD_SPEED = "speed_bps"
const val DOWNLOAD_INDETERMINATE = "indeterminate"
const val DOWNLOAD_MUX = "mux"

private const val CHANNEL = "snag_downloads"
private const val NOTIFICATION_ID = 42
private const val DONE_NOTIFICATION_ID = 43
const val DOWNLOAD_QUEUE_NAME = "snag-download-queue"

/**
 * One file in a batch. Immutable and JSON-encodable so the whole queue survives
 * process death inside WorkManager input data.
 *
 * [fallbackUrl] is a second CDN URL for the same stream (TikTok returns several hosts).
 * [sourceUrl] lets the worker re-resolve through the optional fallback extractor if both
 * CDN URLs are refused.
 */
data class DownloadJob(
    val url: String,
    val fileName: String,
    val mimeType: String,
    val historyId: Long,
    val fallbackUrl: String? = null,
    val sourceUrl: String? = null,
    /** When set, yt-dlp downloads this format pair and merges it with FFmpeg. */
    val ytDlpFormat: String? = null,
) {
    fun encode(): JSONObject = JSONObject().apply {
        put("url", url)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("historyId", historyId)
        put("fallbackUrl", fallbackUrl.orEmpty())
        put("sourceUrl", sourceUrl.orEmpty())
        put("ytDlpFormat", ytDlpFormat.orEmpty())
    }

    companion object {
        fun decode(raw: String): DownloadJob? = runCatching {
            val json = JSONObject(raw)
            DownloadJob(
                url = json.getString("url"),
                fileName = json.getString("fileName"),
                mimeType = json.getString("mimeType"),
                historyId = json.getLong("historyId"),
                fallbackUrl = json.optString("fallbackUrl").takeIf { it.isNotBlank() },
                sourceUrl = json.optString("sourceUrl").takeIf { it.isNotBlank() },
                ytDlpFormat = json.optString("ytDlpFormat").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}

/**
 * Sequential batch worker.
 *
 * All files of one save action go into a single job so progress is coherent
 * ("2 of 5"), the queue survives the UI being closed, and Android only has to
 * manage one foreground service. Failures are per-file: one 404 never kills
 * the rest of the batch.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * Downloads deliberately do not use the shared extraction client's cookie jar.
     * The signed media URL expects the WebView's cookies; OkHttp's jar would otherwise
     * overwrite the manually forwarded Cookie header with the extraction session's cookies.
     */
    private val downloadClient by lazy {
        Http.client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    override suspend fun doWork(): Result {
        val queueFile = queueFile()
        val jobs = decodeJobs(inputData.getString(KEY_JOBS)).ifEmpty {
            queueFile?.let { file ->
                runCatching { decodeJobs(file.readText()) }.getOrDefault(emptyList())
            }.orEmpty()
        }
        if (jobs.isEmpty()) {
            queueFile?.delete()
            return Result.failure()
        }

        try {
            val app = applicationContext as SnagApp
            val history = app.history
            ensureChannel()

            var saved = 0
            var failed = 0

            for ((index, job) in jobs.withIndex()) {
                if (isStopped) {
                    markRemaining(history, jobs, index, "Cancelled")
                    failed += jobs.size - index
                    break
                }

                history.update(job.historyId, DownloadStatus.RUNNING)
                runCatching {
                    setForeground(foreground(index, jobs.size, job.fileName, 0))
                }
                setProgress(
                    workDataOf(
                        DOWNLOAD_INDEX to index,
                        DOWNLOAD_TOTAL to jobs.size,
                        DOWNLOAD_PROGRESS to 0,
                        DOWNLOAD_INDETERMINATE to true,
                    )
                )

                try {
                    transfer(job, index, jobs.size)
                    saved++
                } catch (c: CancellationException) {
                    history.update(job.historyId, DownloadStatus.FAILED, error = "Cancelled")
                    markRemaining(history, jobs, index + 1, "Cancelled")
                    throw c
                } catch (t: Throwable) {
                    history.update(
                        job.historyId,
                        DownloadStatus.FAILED,
                        error = describe(t),
                    )
                    failed++
                }
            }

            notifyFinished(saved, failed)
            return Result.success()
        } finally {
            queueFile?.delete()
        }
    }

    private fun queueFile(): File? {
        val name = inputData.getString(KEY_QUEUE_FILE)?.takeIf { it.isNotBlank() } ?: return null
        if (name.contains('/') || name.contains('\\')) return null
        return File(applicationContext.filesDir, name).takeIf { it.isFile }
    }

    private fun markRemaining(
        history: com.snag.downloader.data.HistoryStore,
        jobs: List<DownloadJob>,
        from: Int,
        reason: String,
    ) {
        for (index in from until jobs.size) {
            history.update(jobs[index].historyId, DownloadStatus.FAILED, error = reason)
        }
    }

    /**
     * Tries the primary URL, the alternate CDN host, then the optional extraction service.
     * Each candidate gets a small transient-error retry before moving on.
     */
    private suspend fun transfer(job: DownloadJob, index: Int, total: Int) {
        if (job.ytDlpFormat != null) {
            transferWithYtDlp(job, index, total)
            return
        }

        val app = applicationContext as SnagApp
        val fallback = job.fallbackUrl?.takeIf { it != job.url }
        val candidates = buildList {
            // TikTok's signed `v16-webapp-prime` CDN URL is tied to the browser's TLS
            // session and returns 403 to OkHttp. The `aweme/v1/play` URL in UrlList[2]
            // redirects to the same quality and works with the WebView cookies, so try
            // that first when it is available.
            val preferFallback = fallback?.contains("/aweme/v1/play") == true
            if (preferFallback) {
                add(fallback)
                add(job.url)
            } else {
                add(job.url)
                fallback?.let(::add)
            }
        }

        var last: Throwable? = null
        for (url in candidates) {
            try {
                transferWithRetry(job, url, index, total)
                return
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
            }
        }

        if (app.settings.useFallbackService && job.sourceUrl != null &&
            UrlParser.classify(job.sourceUrl) == SourcePlatform.TIKTOK &&
            job.mimeType.startsWith("video/")
        ) {
            val bundle = runCatching { TikTokFallback.resolve(job.sourceUrl) }.getOrNull()
            val alternative = bundle?.variants
                ?.filter { it.kind == MediaKind.VIDEO }
                ?.let { videos ->
                    videos.firstOrNull { it.label != "watermarked" } ?: videos.firstOrNull()
                }
            if (alternative != null) {
                try {
                    transferWithRetry(job, alternative.url, index, total)
                    app.history.setVariantLabel(
                        job.historyId,
                        "fallback · ${alternative.displayLabel}",
                    )
                    return
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    last = t
                }
            }
        }

        throw last ?: IOException("Download failed")
    }

    private suspend fun transferWithRetry(
        job: DownloadJob,
        url: String,
        index: Int,
        total: Int,
    ) {
        var attempt = 0
        while (true) {
            try {
                transferOnce(job, url, index, total)
                return
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (attempt >= MAX_RETRIES || !isTransient(t)) throw t
                delay(700L * (attempt + 1))
                attempt++
            }
        }
    }

    /** Streams one file; throws on any failure after cleaning up its pending row. */
    private suspend fun transferOnce(job: DownloadJob, url: String, index: Int, total: Int) {
        val resolver = applicationContext.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, job.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, job.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Snag")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
            ?: throw IOException("Could not create the file in Downloads")

        try {
            val userAgent = mediaUserAgent(url)
            val cookies = cookieHeader(url)
            val referer = refererFor(url)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply { referer?.let { header("Referer", it) } }
                .apply { referer?.let { header("Origin", it.trimEnd('/')) } }
                .apply { cookies?.let { header("Cookie", it) } }
                .header("Sec-Fetch-Dest", "video")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "cross-site")
                .build()

            var written = 0L
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        response.body?.string()?.take(256)
                    }.getOrNull()
                    throw HttpStatusException(response.code, detail)
                }

                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.startsWith("text/", ignoreCase = true)) {
                    throw IOException("Server returned an error page")
                }

                val body = response.body ?: throw IOException("Empty response")
                val length = body.contentLength()

                val output = resolver.openOutputStream(target)
                    ?: throw IOException("Could not open the file for writing")

                output.use { sink ->
                    body.byteStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        var lastTick = 0L
                        var lastSampleAt = System.currentTimeMillis()
                        var lastSampleBytes = 0L
                        var speedBps = 0.0
                        while (true) {
                            if (isStopped) throw CancellationException("Stopped by the user")
                            val read = source.read(buffer)
                            if (read == -1) break
                            sink.write(buffer, 0, read)
                            written += read

                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 250L) {
                                val elapsed = now - lastSampleAt
                                if (elapsed > 0L) {
                                    val sample = (written - lastSampleBytes) * 1000.0 / elapsed
                                    speedBps =
                                        if (speedBps <= 0.0) sample
                                        else speedBps * 0.7 + sample * 0.3
                                    lastSampleAt = now
                                    lastSampleBytes = written
                                }
                                lastTick = now
                                val itemFraction =
                                    if (length > 0) written.toFloat() / length else 0f
                                publishProgress(
                                    index = index,
                                    total = total,
                                    itemFraction = itemFraction,
                                    fileName = job.fileName,
                                    bytes = written,
                                    totalBytes = length,
                                    speedBps = speedBps.toLong(),
                                )
                            }
                        }
                        publishProgress(
                            index = index,
                            total = total,
                            itemFraction = if (length > 0) 1f else 0f,
                            fileName = job.fileName,
                            bytes = written,
                            totalBytes = length,
                            speedBps = speedBps.toLong(),
                        )
                    }
                }
            }

            if (written <= 0L) throw IOException("The server sent an empty file")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            (applicationContext as SnagApp).history.update(
                job.historyId,
                DownloadStatus.DONE,
                target.toString(),
                written,
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            throw t
        }
    }

    /**
     * Downloads an adaptive video+audio pair with yt-dlp and lets its bundled
     * FFmpeg merge them before importing the result into MediaStore.
     */
    private suspend fun transferWithYtDlp(job: DownloadJob, index: Int, total: Int) {
        val resolver = applicationContext.contentResolver
        val tempDir = File(applicationContext.cacheDir, "snag-ytdlp-${UUID.randomUUID()}")
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, job.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, job.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Snag")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
            ?: throw IOException("Could not create the file in Downloads")

        try {
            // Remove orphaned merge directories left by an interrupted build/run.
            applicationContext.cacheDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("snag-ytdlp-") }
                ?.forEach { old -> if (old != tempDir) old.deleteRecursively() }
            tempDir.mkdirs()
            YouTubeClient.initialize(applicationContext)
            val request = YoutubeDLRequest(job.sourceUrl ?: job.url).apply {
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--merge-output-format", "mp4")
                addOption("-f", job.ytDlpFormat!!)
                addOption("-o", File(tempDir, "%(id)s.%(ext)s").absolutePath)
            }
            val response = withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(
                    request,
                    "snag-${job.historyId}",
                ) { progress, _, _ ->
                    runBlocking { publishMuxProgress(index, total, progress) }
                }
            }
            if (response.exitCode != 0) {
                throw IOException(response.err.trim().take(200).ifBlank { "yt-dlp failed" })
            }

            val merged = tempDir.listFiles()
                ?.filter { it.isFile && it.length() > 0L }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("yt-dlp produced no merged file")
            publishMuxProgress(index, total, 1f)

            val output = resolver.openOutputStream(target)
                ?: throw IOException("Could not open the file for writing")
            var written = 0L
            output.use { sink ->
                merged.inputStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw CancellationException("Stopped by the user")
                        val read = source.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            if (written <= 0L) throw IOException("Merged file was empty")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            (applicationContext as SnagApp).history.update(
                job.historyId,
                DownloadStatus.DONE,
                target.toString(),
                written,
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            throw t
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun publishMuxProgress(index: Int, total: Int, fraction: Float) {
        val overall = (((index + fraction.coerceIn(0f, 1f)) / total) * 100)
            .toInt()
            .coerceIn(0, 100)
        setProgress(
            workDataOf(
                DOWNLOAD_INDEX to index,
                DOWNLOAD_TOTAL to total,
                DOWNLOAD_PROGRESS to overall,
                DOWNLOAD_INDETERMINATE to false,
                DOWNLOAD_MUX to true,
            )
        )
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(
                NOTIFICATION_ID,
                notification(
                    title = "Downloading ${index + 1}/$total",
                    text = if (overall >= 100) "Merging video + audio" else "Downloading video + audio",
                    progress = overall,
                    indeterminate = false,
                ),
            )
        }
    }

    private fun isTransient(t: Throwable): Boolean = when (t) {
        is HttpStatusException ->
            t.code == 408 || t.code == 425 || t.code == 429 || t.code >= 500
        is IOException -> true
        else -> false
    }

    private fun describe(t: Throwable): String {
        val message = when (t) {
            is HttpStatusException -> "Server returned HTTP ${t.code}"
            is IOException -> t.message ?: "Network error"
            else -> t.message ?: t.javaClass.simpleName
        }
        return message.replace('\n', ' ').take(160)
    }

    /**
     * The WebView solved the platform challenge, so its cookies are the ones the CDN
     * expects for the signed stream URL. Send them explicitly because media downloads do
     * not go through the WebView cookie store.
     */
    private suspend fun cookieHeader(url: String): String? = withContext(Dispatchers.Main.immediate) {
        runCatching {
            val manager = CookieManager.getInstance()
            val host = Uri.parse(url).host.orEmpty()
            val parts = linkedSetOf<String>()

            fun add(raw: String?) {
                raw?.split(';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let(parts::addAll)
            }

            add(manager.getCookie(url))
            if (host.isNotBlank()) add(manager.getCookie("https://$host/"))
            if (host.endsWith("tiktok.com")) {
                add(manager.getCookie("https://www.tiktok.com/"))
            }
            parts.joinToString("; ").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * TikTok signs the CDN URL for the browser session, including its UA. Use the exact
     * WebView UA for platform media; generic hosts keep the desktop UA.
     */
    private suspend fun mediaUserAgent(url: String): String {
        val host = Uri.parse(url).host.orEmpty()
        val platform = host.contains("tiktok") ||
            host.contains("instagram") ||
            host.contains("cdninstagram") ||
            host.contains("facebook") ||
            host.contains("fbcdn")
        if (!platform) return Http.UA_DESKTOP
        return withContext(Dispatchers.Main.immediate) {
            runCatching { WebSettings.getDefaultUserAgent(applicationContext) }
                .getOrDefault(Http.UA_MOBILE)
        }
    }

    private fun decodeJobs(raw: String?): List<DownloadJob> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                DownloadJob.decode(array.optString(index))
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun publishProgress(
        index: Int,
        total: Int,
        itemFraction: Float,
        fileName: String,
        bytes: Long,
        totalBytes: Long,
        speedBps: Long,
    ) {
        val fraction = itemFraction.coerceIn(0f, 1f)
        val overall = (((index + fraction) / total) * 100).toInt().coerceIn(0, 100)
        setProgress(
            workDataOf(
                DOWNLOAD_INDEX to index,
                DOWNLOAD_TOTAL to total,
                DOWNLOAD_PROGRESS to overall,
                DOWNLOAD_BYTES to bytes,
                DOWNLOAD_TOTAL_BYTES to totalBytes,
                DOWNLOAD_SPEED to speedBps,
                DOWNLOAD_INDETERMINATE to (totalBytes <= 0L),
            )
        )
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(
                    NOTIFICATION_ID,
                    notification(
                        title = "Downloading ${index + 1}/$total",
                        text = fileName,
                        progress = overall,
                        indeterminate = false,
                    ),
                )
        }
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    private fun foreground(index: Int, total: Int, fileName: String, progress: Int) =
        ForegroundInfo(
            NOTIFICATION_ID,
            notification(
                title = "Downloading ${index + 1}/$total",
                text = fileName,
                progress = progress,
                indeterminate = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private fun notification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("${progress.coerceIn(0, 100)}%")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()

    private fun notifyFinished(saved: Int, failed: Int) {
        val body = when {
            failed == 0 && saved == 1 -> "1 file saved to Downloads/Snag"
            failed == 0 -> "$saved files saved to Downloads/Snag"
            saved == 0 -> "No files were saved"
            else -> "$saved saved · $failed failed"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (failed == 0) "Saved" else "Partly saved")
            .setContentText(body)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(DONE_NOTIFICATION_ID, notification)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun refererFor(url: String): String? {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        return when {
            host.contains("tiktok") -> "https://www.tiktok.com/"
            host.contains("instagram") || host.contains("cdninstagram") -> "https://www.instagram.com/"
            host.contains("facebook") || host.contains("fbcdn") -> "https://www.facebook.com/"
            else -> null
        }
    }

    private class HttpStatusException(
        val code: Int,
        detail: String? = null,
    ) : IOException(
        buildString {
            append("HTTP ").append(code)
            val clean = detail
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(120)
            if (!clean.isNullOrBlank()) append(": ").append(clean)
        }
    )

    private companion object {
        const val MAX_RETRIES = 2
    }
}

object DownloadScheduler {

    /** Appends [jobs] to the single download queue, in order. */
    fun enqueue(context: Context, jobs: List<DownloadJob>) {
        if (jobs.isEmpty()) return

        // WorkManager caps input Data at 10 KB. TikTok signed URLs can exceed that
        // for a six-file batch, so keep the JSON in app-private storage and pass
        // only the queue file name through the worker.
        val payload = JSONArray().apply { jobs.forEach { put(it.encode()) } }.toString()
        val queueFile = File(context.filesDir, "download-queue-${UUID.randomUUID()}.json")
        queueFile.writeText(payload)

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_QUEUE_FILE to queueFile.name))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        try {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(DOWNLOAD_QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        } catch (t: Throwable) {
            queueFile.delete()
            throw t
        }
    }
}

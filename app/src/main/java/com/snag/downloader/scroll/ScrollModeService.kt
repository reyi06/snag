package com.snag.downloader.scroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.snag.downloader.MainActivity
import com.snag.downloader.R
import com.snag.downloader.SnagApp

/**
 * Keeps the Scroll mode notification and floating crown alive while the user browses
 * TikTok. It never reads the clipboard itself; the crown opens ClipboardRelayActivity.
 */
class ScrollModeService : Service() {

    private var overlay: QuickSaveOverlay? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
        if (settings.scrollModeEnabled && Settings.canDrawOverlays(this)) {
            showOverlay()
        } else if (!settings.scrollModeEnabled) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.scrollModeEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SAVE -> ClipboardRelayActivity.launch(this)
            else -> {
                if (settings.scrollModeEnabled && Settings.canDrawOverlays(this)) {
                    showOverlay()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay?.detach()
        overlay = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlay != null) return
        overlay = QuickSaveOverlay(this) {
            ClipboardRelayActivity.launch(this)
        }.also { it.attach() }
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notification(): Notification {
        val save = PendingIntent.getActivity(
            this,
            REQUEST_SAVE,
            Intent(this, ClipboardRelayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ScrollModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_crown)
            .setContentTitle("Scroll mode ready")
            .setContentText("Copy a TikTok link, then tap the crown")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Save copied link", save)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Scroll mode", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    private val settings: com.snag.downloader.data.SettingsStore
        get() = (applicationContext as SnagApp).settings

    companion object {
        private const val CHANNEL = "snag_scroll_mode"
        private const val NOTIFICATION_ID = 44
        private const val REQUEST_SAVE = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_OPEN = 3
        const val ACTION_SAVE = "com.snag.downloader.scroll.SAVE"
        const val ACTION_STOP = "com.snag.downloader.scroll.STOP"

        /** Starts or stops the overlay to match the persisted setting and permission state. */
        fun sync(context: Context) {
            val app = context.applicationContext as? SnagApp ?: return
            val enabled = app.settings.scrollModeEnabled &&
                Settings.canDrawOverlays(context)
            if (enabled) {
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ScrollModeService::class.java),
                    )
                }
            } else {
                runCatching {
                    context.stopService(Intent(context, ScrollModeService::class.java))
                }
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext as? SnagApp ?: return
            app.settings.scrollModeEnabled = false
            runCatching { context.stopService(Intent(context, ScrollModeService::class.java)) }
        }
    }
}

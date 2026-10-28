package com.snag.downloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.snag.downloader.core.WebViewGate
import com.snag.downloader.scroll.ScrollModeService
import com.snag.downloader.ui.SnagRoot
import com.snag.downloader.ui.SnagTheme
import com.snag.downloader.ui.SnagViewModel

class MainActivity : ComponentActivity() {

    private val vm: SnagViewModel by viewModels()
    private var gateView: WebView? = null
    private var explicitIntentHandled = false
    private var clipboardChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SnagTheme {
                SnagRoot(vm)
            }
        }
        installWebGate()
        askForNotifications()
        handleIntent(intent)
    }

    override fun onDestroy() {
        gateView?.let { view ->
            WebViewGate.detach(view)
            view.destroy()
        }
        gateView = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        clipboardChecked = false
        vm.refreshHistory()
        vm.refreshScrollMode()
        ScrollModeService.sync(this)
        window.decorView.postDelayed({ checkClipboard() }, 450)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) {
            explicitIntentHandled = true
            vm.handleIncoming(text)
        }
    }

    /**
     * Reads the clipboard only after the window has focus. Android 10+ rejects clipboard
     * reads from a background app, and the explicit share/VIEW path always wins over it.
     */
    private fun checkClipboard() {
        if (clipboardChecked || !hasWindowFocus()) return
        clipboardChecked = true
        if (explicitIntentHandled) {
            explicitIntentHandled = false
            return
        }
        if (!vm.autoClipboard) return

        val clip = runCatching {
            getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        }.getOrNull()
        vm.autoFromClipboard(clip)
    }

    private fun installWebGate() {
        runCatching {
            val view = WebView(this)
            WebViewGate.configure(view)
            view.alpha = 0.01f
            view.isClickable = false
            view.isFocusable = false
            addContentView(view, ViewGroup.LayoutParams(2, 2))
            WebViewGate.attach(view)
            gateView = view
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private companion object {
        const val NOTIFICATION_REQUEST = 1001
    }
}

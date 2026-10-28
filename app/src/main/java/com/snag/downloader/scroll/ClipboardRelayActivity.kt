package com.snag.downloader.scroll

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.UrlParser
import com.snag.downloader.extract.TikTokProfileUrl

/**
 * A tiny transparent activity whose only purpose is to receive focus so Android allows
 * the app to read the clipboard. It queues the TikTok link through ScrollModeService and
 * finishes immediately, returning the user to TikTok.
 */
class ClipboardRelayActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
        })
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({ readClipboard() }, 120)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) readClipboard()
    }

    private fun readClipboard() {
        if (handled) return
        handled = true

        val clip = runCatching {
            getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        }.getOrNull()

        val url = clip?.let(UrlParser::firstUrl)
        when {
            url == null || UrlParser.classify(url) != SourcePlatform.TIKTOK ->
                Toast.makeText(this, "Copy a TikTok link first", Toast.LENGTH_SHORT).show()

            TikTokProfileUrl.handle(url) != null ->
                Toast.makeText(this, "Copy a video link, not a profile", Toast.LENGTH_SHORT).show()

            else -> BackgroundCapture.capture(this, url)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, ClipboardRelayActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            runCatching { context.startActivity(intent) }
        }
    }
}

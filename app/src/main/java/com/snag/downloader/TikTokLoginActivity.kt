package com.snag.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.snag.downloader.data.TikTokProfileHandoff
import com.snag.downloader.data.TikTokSession
import com.snag.downloader.ui.SnagTheme
import com.snag.downloader.ui.TikTokLoginScreen

/**
 * TikTok's own login page, in a visible WebView. Credentials never pass through app code;
 * the resulting session remains in Android's WebView cookie store and is reused by the
 * profile loader in the main process.
 */
class TikTokLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val profileUrl = intent.getStringExtra(EXTRA_PROFILE_URL)
        setContent {
            SnagTheme {
                TikTokLoginScreen(
                    profileUrl = profileUrl,
                    onClose = { finish() },
                    onSignedIn = {
                        TikTokSession.flush()
                        setResult(RESULT_OK)
                        finish()
                    },
                    onItems = { raw ->
                        TikTokProfileHandoff.raw = raw
                        setResult(RESULT_OK)
                        finish()
                    },
                    onVerified = {
                        TikTokSession.flush()
                        setResult(RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_URL = "profile_url"
    }
}

package com.snag.downloader.data

/**
 * Carries the verified profile JSON between the visible TikTok verification activity and
 * the main process UI. The payload can be close to the Binder transaction limit, so it is
 * intentionally kept in memory instead of being placed in an Intent extra.
 */
object TikTokProfileHandoff {
    var raw: String? = null
}

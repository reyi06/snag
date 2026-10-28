package com.snag.downloader.data

import android.webkit.CookieManager
import com.snag.downloader.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The TikTok web session lives in Android's WebView cookie store. The app never reads
 * credentials or copies the session token into preferences; it only checks whether the
 * browser session is present and validates it against TikTok's account endpoint.
 *
 * Cookie names alone are not proof of login: TikTok can issue a guest or partial session.
 * [validate] is the source of truth and also catches expired cookies.
 */
object TikTokSession {

    enum class Status { Valid, Expired, Unknown }

    private const val ACCOUNT_INFO =
        "https://www.tiktok.com/passport/web/account/info/" +
            "?aid=1459&app_name=tiktok_web&app_language=en&browser_language=en-US" +
            "&browser_name=Mozilla&browser_platform=Win32&browser_version=5.0" +
            "&channel=tiktok_web&cookie_enabled=true&device_platform=web_pc" +
            "&focus_state=true&from_page=user&history_len=3&is_fullscreen=false" +
            "&is_page_visible=true&os=windows&priority_region=US&referer=&region=US" +
            "&screen_height=1080&screen_width=1920&tz_name=Etc/UTC&webcast_language=en"

    private val validationClient by lazy {
        Http.client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun hasSessionCookie(): Boolean = hasSessionCookie(readCookie())

    fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }

    suspend fun validate(): Status {
        val cookie = readCookie()
        if (!hasSessionCookie(cookie)) {
            return Status.Expired
        }

        val page = withContext(Dispatchers.IO) {
            runCatching {
                Http.get(
                    url = ACCOUNT_INFO,
                    userAgent = Http.UA_DESKTOP,
                    headers = mapOf(
                        "Referer" to "https://www.tiktok.com/",
                        "Accept" to "application/json, text/plain, */*",
                        "Cookie" to cookie,
                    ),
                    client = validationClient,
                )
            }.getOrNull()
        } ?: run {
            return Status.Unknown
        }

        val root = runCatching { JSONObject(page.body) }.getOrNull()
            ?: return Status.Unknown
        val message = root.optString("message")
        val data = root.optJSONObject("data")
        val errorCode = data?.optInt("error_code", 0) ?: 0
        val errorName = data?.optString("name").orEmpty()

        val status = when {
            message == "success" && data != null -> Status.Valid
            errorCode == 13 || errorName == "session_expired" -> Status.Expired
            else -> Status.Unknown
        }
        return status
    }

    fun signOut() {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
        }
    }

    private fun readCookie(): String =
        runCatching {
            CookieManager.getInstance()
                .getCookie("https://www.tiktok.com/")
                .orEmpty()
        }.getOrDefault("")

    private fun hasSessionCookie(cookie: String): Boolean =
        cookie.split(';')
            .map { it.trim() }
            .any {
                it.startsWith("sessionid=") ||
                    it.startsWith("sessionid_ss=") ||
                    it.startsWith("sid_tt=")
            }
}

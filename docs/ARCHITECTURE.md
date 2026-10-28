# Snag architecture

Snag is a single-module Android app built with Kotlin and Jetpack Compose. All extraction and downloads happen on the device; there is no Snag backend, account system, analytics, or billing.

Current release: **0.8.2** (`versionCode` 14).

## Layers

```
ui/          Compose screens and the view model
core/        Media models, extractor contracts, HTTP, WebView gate
extract/     TikTok, YouTube, Instagram, Facebook, OpenGraph
data/        Repository, history, settings, bookmarks
download/    WorkManager queue and MediaStore writes
scroll/      Floating crown, clipboard relay, background capture
```

The UI does not talk to extractors directly. `SnagViewModel` uses `MediaRepository`, which owns strategy ordering and profile resolution.

## Link resolution

`UrlParser` extracts the first URL and classifies the platform. `MediaRepository` returns one of:

- a `MediaBundle` for a post,
- a `ProfileBundle` for a creator page,
- a typed failure with the strategies that were tried.

Each extractor owns independent strategies. The registry returns the first success and preserves the failure list for diagnostics.

## TikTok profiles

Public profiles use the no-login path:

1. Read the creator embed at `/embed/@handle`.
2. Parse `__FRONTITY_CONNECT_STATE__` for creator metadata and recent posts.
3. Read the stable `secUid` from the first post's `/embed/v2/<id>` payload.
4. Page `/api/creator/item_list/` using the anonymous `ttwid` cookie from the embed.
5. Filter out reposts, other authors, and posts without playable media.

The WebView verification path and the optional TikWM fallback remain last-resort fallbacks.

## YouTube

YouTube uses the embedded `youtubedl-android` runtime, which bundles yt-dlp, Python, and FFmpeg. `YouTubeExtractor` resolves a single video and exposes progressive video variants plus the best audio-only format. `YouTubePlaylistExtractor` reads the playlist page for video IDs, resolves the first 10 entries through yt-dlp with up to three concurrent lookups, and returns a `ProfileBundle` so playlists use the same batch sheet, queue, history, and progress notification as other platforms. Resolved video metadata is cached in memory for 10 minutes. The runtime is initialized lazily on the first YouTube request.

Long playlist resolution reports progress to `SnagViewModel` and can be cancelled; a cancelled load restores the previous result instead of showing a failure.

## Instagram

Instagram's captioned embed used to expose `display_url` and `video_url` directly; it now returns a login shell. The primary strategy is therefore `InstagramYtDlpStrategy`, which asks yt-dlp for `--dump-single-json` with `--ignore-no-formats-error`:

- single videos and reels use progressive formats with audio;
- carousel entries with video formats become video variants;
- image-only entries use the best thumbnail URL as the photo;
- the old embed and OpenGraph strategies remain fallbacks.

yt-dlp is refreshed from the stable GitHub release at most once per day. The refresh is best-effort: failures keep the bundled binary, and the timestamp prevents a retry loop.

`TikTokIdentityCache` stores the stable `secUid` plus public profile metadata per handle
for 30 days. A cache hit lets the extractor call `/api/creator/item_list/` immediately,
even while the embeds are temporarily returning `503`. If both API scans and the fresh
embed retries fail, the recent `videoList` is returned with `isPartial = true` and a
**Retry full profile** action; it is never presented as a complete profile.

## Scroll mode

Scroll mode is optional and off by default.

- `ScrollModeService` is a low-priority `specialUse` foreground service.
- It owns a draggable `TYPE_APPLICATION_OVERLAY` button. The default style is maroon/gold; the low-profile style is a smaller black button with a white crown. Opacity is user-configurable.
- Tapping the button launches `ClipboardRelayActivity`, a transparent activity that gets focus long enough to read the clipboard.
- The URL is passed to `BackgroundCapture`, which resolves it through the normal repository and enqueues the download.
- `CaptureFeedback` drives a spinner, green success pulse, or red error shake on the button without success toasts.
- The service notification offers **Save copied link** and **Stop**.
- If Display over other apps is denied, the service still runs and the notification action remains available.

There is no AccessibilityService, automatic scrolling, share-sheet automation, or continuous clipboard monitoring. Android does not allow a normal background app to read the clipboard while another app is focused, so the explicit relay is required.

## Downloads

`DownloadScheduler` stores the batch JSON in app-private storage and gives WorkManager only a queue filename. This avoids WorkManager's 10 KB input-data limit for long CDN URLs.

`DownloadWorker` streams files sequentially into `MediaStore.Downloads/Snag`, retries transient failures, tries an alternate CDN URL, and updates history and the progress notification. Photo carousels are saved as individual images.

The worker publishes work progress with the current batch index, overall percentage, transferred bytes, total bytes when the server reports `Content-Length`, and a smoothed transfer speed. `SnagViewModel` observes the unique WorkManager queue and renders the in-app download bar above the navigation bar on every tab. When the queue empties, history is refreshed once so completed rows appear without waiting for a screen change.

## Local data

- History and profile bookmarks are JSON in `SharedPreferences`.
- Settings are local preferences.
- WebView cookies stay in the Android WebView store.
- Downloaded media is written through MediaStore.
- No analytics or telemetry are included.

## Permissions

- `INTERNET` and `ACCESS_NETWORK_STATE` for extraction and downloads.
- `POST_NOTIFICATIONS` for download and Scroll mode notifications.
- `SYSTEM_ALERT_WINDOW` for the optional floating crown.
- Foreground-service permissions for the download queue and Scroll mode.
- No Accessibility permission is requested.

## Build

- JDK 17
- Android SDK 36
- Gradle wrapper (`gradlew.bat` / `./gradlew`)

```powershell
.\gradlew.bat assembleDebug
```

Release signing is not committed. Build and sign the release APK with your own key.

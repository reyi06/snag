# Snag architecture

Snag is a single-module Android app built with Kotlin and Jetpack Compose. All extraction and downloads happen on the device; there is no Snag backend, account system, analytics, or billing.

Current release: **0.7.0** (`versionCode` 11).

## Layers

```
ui/          Compose screens and the view model
core/        Media models, extractor contracts, HTTP, WebView gate
extract/     TikTok, Instagram, Facebook, OpenGraph
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

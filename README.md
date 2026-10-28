# Snag

Snag is an Android media downloader for public social posts. It resolves links on-device, shows the available quality, and saves files to `Downloads/Snag` without ads, accounts, a backend, or an API key.

Current release: **0.8.3**

## What it does

- Saves public TikTok videos, photo carousels, and audio.
- Saves YouTube videos, YouTube audio, and YouTube playlists through an embedded yt-dlp runtime, including 1080p, 1440p, and 2160p adaptive streams merged with FFmpeg.
- Loads public TikTok profiles and creator batches without signing in. Profiles use TikTok's creator embed and creator item-list API.
- Saves Instagram reels, video posts, and photos, including carousel images; Facebook post links remain supported.
- Refreshes the bundled yt-dlp binary at most once per day so extractor fixes reach existing installs.
- Accepts multiple pasted links as one batch and uses the same queue, history, progress, and file manager.
- Shows an in-app download bar with the current file, percentage, size, and transfer speed while the shared queue runs.
- Keeps a local library and download history.
- Provides a **Scroll mode** floating crown for saving a TikTok link while browsing.

## Install

1. Download `Snag-0.8.3.apk` from the [latest release](https://github.com/reyi06/snag/releases/latest).
2. Allow installs from unknown sources for the browser or file manager you use.
3. Android 10 or newer on a 64-bit ARM device is required. The published APK is `arm64-v8a`, which covers most Android phones.

The APK is signed with the Snag release key. The signing key is not stored in this repository.

### Play Protect

Because Snag is installed from a signed APK rather than Google Play, Android or Play Protect may warn about an unknown source. Verify the SHA-256 checksum published with the release before installing. Only install it if you trust the source.

## Legal

- [Privacy policy](PRIVACY.md)
- [Disclaimer and legal notice](DISCLAIMER.md)
- [Security policy](SECURITY.md)
- [GPL-3.0 license](LICENSE)

Snag is not affiliated with TikTok, ByteDance, YouTube, Google, Instagram, Meta, or Facebook. Use it only for media you are allowed to save, and follow the source platform's terms. Snag does not grant any rights to downloaded content.

## Scroll mode

Scroll mode is optional and off by default.

1. Open **Settings → Scroll mode** and enable it.
2. Allow **Display over other apps**.
3. Choose the default maroon/gold crown or the smaller black/white low-profile button, and set its opacity.
4. Scroll TikTok normally and copy a video link from TikTok's Share sheet.
5. Tap the floating button; it shows an in-progress animation, then a green success pulse.

If your phone denies Display over other apps, Scroll mode keeps its notification active. Use the notification's **Save copied link** action instead.

Snag reads the clipboard only when you tap the floating button or **Save copied link**. It does not continuously monitor the clipboard, automate TikTok, or require Accessibility access.

The persistent notification provides **Save copied link** and **Stop** actions.

## Privacy

Snag has no accounts, analytics, ads, or server of its own. Links are resolved directly from the source platform. WebView cookies and download history stay on the device. Files are written through Android MediaStore.

Snag does not grant rights to media you do not own. Use it only for content you are allowed to save, and follow the source platform's terms.

## Build

Requirements:

- JDK 17
- Android SDK 36
- Gradle wrapper included in the repository

```powershell
.\gradlew.bat assembleDebug
```

For a release build, create your own signing setup and sign `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk` with `apksigner`. The project's release key and password are intentionally kept outside the repository.

## Project layout

- `app/src/main/java/com/snag/downloader/core` — models, HTTP, extraction contracts
- `app/src/main/java/com/snag/downloader/extract` — TikTok, Instagram, Facebook, OpenGraph
- `app/src/main/java/com/snag/downloader/data` — repository, history, settings
- `app/src/main/java/com/snag/downloader/download` — MediaStore download queue
- `app/src/main/java/com/snag/downloader/scroll` - Scroll mode overlay and clipboard relay
- `app/src/main/java/com/snag/downloader/ui` - Compose screens

## Changelog

See [CHANGELOG.md](CHANGELOG.md). Release notes for this build are in [docs/releases/0.8.3.md](docs/releases/0.8.3.md).

## Credits

Public-profile protocol informed by [tt-dlp](https://github.com/bajaam/tt-dlp) under the MIT License. Full notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

Snag is free software licensed under the **GNU General Public License v3.0**. The source is available under that license; redistribution and modification must comply with the GPL. See [LICENSE](LICENSE). The yt-dlp Android wrapper (`youtubedl-android`) is GPL-3.0; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

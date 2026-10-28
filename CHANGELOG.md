# Changelog

All notable changes to Snag are recorded here.

## Versioning policy

Snag follows [Semantic Versioning](https://semver.org/) and [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

- `PATCH` — backward-compatible bug fixes, copy corrections, and visual polish.
- `MINOR` — backward-compatible features and new user-facing capability.
- `MAJOR` — breaking changes to storage, exported intents, or supported Android versions.
- `versionCode` is a monotonically increasing integer and must be higher than every previously shipped build.
- `versionName` uses `MAJOR.MINOR.PATCH` with no `v` prefix.
- Every release gets an entry here in the same change as the version bump.
- Dates use `YYYY-MM-DD`.

## [Unreleased]

## [0.6.1] - 2026-10-28

### Fixed

- Public TikTok profiles now cache the stable `secUid` and metadata per handle. Later opens can call the creator item-list API directly when the creator or post embed returns a transient `503`.
- Creator and post embeds retry with exponential backoff; post embeds try several recent posts before giving up.
- The creator API gets a fresh scan after a short delay before Snag accepts the recent-only fallback.
- A recent-only profile is now marked as partial, shows the known full video count when available, and displays a **Recent posts only** banner instead of silently looking complete.
- Partial profiles include a **Retry full profile** action that bypasses the recent-only result and runs the full retry path again.

## [0.6.0] - 2026-10-28

### Added

- **Scroll mode**: a draggable floating crown stays on screen while enabled. Copy a TikTok video link, tap the crown, and Snag reads the clipboard through a transparent relay activity, resolves the post, and downloads it in the background.
- The floating button uses the maroon/gold crown treatment, can be dragged, and is backed by a low-priority foreground notification with **Save copied link** and **Stop** actions.
- Settings now exposes Scroll mode, the display-over-other-apps permission, and the floating-crown status.

### Changed

- Scroll mode reads the clipboard only on an explicit crown or notification action. It does not monitor the clipboard continuously, automate TikTok’s share sheet, or scroll TikTok for the user.

### Fixed

- Stopping Scroll mode from its notification immediately clears the Settings switch through the shared preference listener.
- Duplicate crown taps cannot queue the same TikTok post twice; already-saved posts show a short "Already saved" message.

## [0.5.1] - 2026-10-28

### Fixed

- Photo carousel library tiles now use the actual slide URL for every image instead of reusing the post cover. A six-slide carousel no longer renders the first slide six times.
- Completed image entries in Library and Recent prefer the saved MediaStore file, so previously downloaded carousels also resolve to their own local slide rather than the shared remote cover.

## [0.5.0] - 2026-10-28

### Added

- No-key public profile loading through TikTok's creator embed and creator item-list API. Profiles resolve with the creator's own recent posts, complete photo carousels, and playable variants without a login or a third-party API key.
- The profile extractor resolves the creator's stable `secUid` from the first post embed, then pages through `/api/creator/item_list/` using the same request shape as `tt-dlp`.
- If the creator API is unavailable, the profile card falls back to the posts exposed by the creator embed and labels them as recent only.
- Download notifications now show a determinate progress bar, a `Downloading 1/32` current-file count, and a percentage that updates while the file streams. The completion notification summarises saved and failed files.

### Changed

- Profile loading no longer opens the verification browser on the normal path. The WebView and TikWM paths remain as fallbacks only.
- The direct profile request uses the same anonymous session cookies picked up from the embed page, desktop user agent, and pagination cursor as `tt-dlp`.

### Fixed

- Profile batches now contain the creator's own 49-post test profile instead of a short list of placeholder rows, and carousel images download as images beside their video/single-post siblings.
- The profile card keeps the correct avatar, handle, nickname, follower count, and full video count when the creator API succeeds.
- Large batches no longer crash before starting. The queue JSON is written to app-private storage and WorkManager receives only its filename, avoiding the 10 KB input-data limit for long TikTok CDN URLs.
- Creator-embed and post-embed requests retry transient `503` responses and try several recent posts when the first embed is gated.

## [0.4.1] - 2026-10-28

### Fixed

- Captcha verification now opens the batch picker automatically when the harvested own-video list is returned.
- If the desktop captcha succeeds but its item-list request is not usable, the verification activity switches to the mobile harvester, waits up to 18 seconds, then retries the profile with the now-verified WebView cookies and opens the picker on success.
- Profile item parsing rejects repost entries and entries authored by a different handle, and ignores posts without playable media so the batch grid cannot fill with empty or repost tiles.
- The harvester prefers captured own-post list requests, ignores repost endpoints case-insensitively, and limits retries to the latest three requests.

## [0.4.0] - 2026-10-28

### Added

- Profile bookmarks. Save a profile from its preview card or batch sheet, then return to it from the new **Profiles** section in Library. Opening a bookmark reloads the creator's latest public videos and opens the batch picker.
- The creator name on a TikTok video result is now a link that opens their profile and the batch picker directly.
- The quality selector is now a compact **Quality** row with a gear. The gear opens a white default-quality card with the same three preferences used by Settings; the save button follows that default.
- Skeleton launch pages for Save, Library, and Settings, replacing the branded splash moment with placeholders that match each screen.
- Photos-style saved grids in Library and Save's **Recent** section. Tiles show the thumbnail and a clear play/queued/downloading/failed state instead of a captioned row.

### Changed

- The ambient layer is now a very light gold gradient over the warm white ground, with a matching dark-theme wash. Media palette extraction and the old drifting colour lights were removed.
- The launcher icon is now the gold crown alone, centred inside Android's adaptive-icon safe zone so round and squircle masks do not crop it.
- Bottom navigation uses filled icons and a spring scale for the active destination; inactive destinations stay outlined.
- Tab changes are a fast crossfade instead of the slower horizontal slide, so switching feels immediate.
- Video captions are no longer shown in result cards, Recent, or Library. Titles remain in accessibility descriptions and download history.
- The Save header no longer repeats the supported-platform line under **Snag**.
- TikTok profile bios are capped at two lines.
- TikTok's visible sign-in/verification browser now uses the Android mobile Chrome user agent, opens mixed-content assets in compatibility mode, and shows a retry card instead of a silent white page when the main document fails.
- TikTok's visible sign-in/verification browser now uses the Android mobile Chrome user agent, opens mixed-content assets in compatibility mode, and shows a retry card instead of a silent white page when the main document fails.
- Profile harvesting ignores TikTok's `repost/item_list` stream, so batch saves take the creator's own videos rather than reposts.
- A successful captcha harvest now opens the batch picker automatically. If mobile harvesting doesn't return an original video list, verification closes after its retry window and re-fetches the profile with the verified session.
- TikTok sign-in stays on the mobile login page, while **Verify on TikTok** opens the desktop page for the slider check. After the check, verification switches to the mobile harvester automatically when profile videos appear. If the item list still does not arrive within five seconds, it closes and re-fetches the profile with the verified cookies instead of leaving the page open. **Load videos** is a manual fallback.
- A profile blocked by TikTok's bot check now returns the profile preview with **Verify on TikTok** instead of a dead “Couldn't read link” error.
- The TikTok login WebView corrects the zero-height layout some Android devices give TikTok's `height:100%` page, dismisses the "Get the full app experience" sheet, and offers a **Desktop/Mobile** fallback in the header.
- External TikTok schemes are blocked inside the WebView, so **Open TikTok** / app-store links cannot escape into the system browser or Play Store.
- The hidden extraction browser no longer autoplays media without a user gesture, so profile loading cannot start audio by itself.

### Fixed

- TikTok photo carousels are saved as image files instead of the generated MP4. This covers single-post saves, clipboard auto-save, and profile batch downloads.
- Clipboard auto-download is now idempotent across app restarts: a link already auto-saved, or already present in history, is not downloaded again when Snag is reopened.

### Removed

- The Palette dependency, ambient palette extraction, and the branded Android 12 splash icon.
- Temporary `SnagProfile`, `SnagVerify`, `SnagSession`, and `SnagDownload` debug logging.

## [0.3.1] - 2026-10-27

### Fixed

- TikTok sign-in no longer closes on the first `sessionid`/`sid_tt` cookie. It validates the session against TikTok's account endpoint and only returns success when the account is actually authenticated.
- Guest and expired TikTok sessions are rejected. An expired session updates the app state to signed-out and asks the user to sign in again.
- Profile verification now requires a valid session before it can open.
- The sign-in state is shared through the view model so Save and Settings stay consistent after validation, sign-in, expiry, or sign-out.

## [0.3.0] - 2026-10-27

### Added

- Ambient mode: the current cover's strongest colours bleed behind the Save screen as slowly drifting light, with a gold/maroon fallback when no palette is available.
- Cover and avatar images crossfade in.
- Tab changes crossfade and slide, and resolve states transition in and out instead of snapping.
- Press springs on primary/secondary buttons and selection crossfades on chips and quality rows.

### Changed

- Motion runs at 1.4x the baseline speed for a faster, more immediate feel.
- Motion is always enabled; the previous reduced-motion notice/gating was removed per product direction.

## [0.2.0] - 2026-10-27

### Added

- Royal maroon, gold, and warm-white visual system, with Host Grotesk display type, Instrument Sans reading type, adaptive launcher icon, and a first-class dark scheme.
- Quality preference selector: **Highest · H.265** (default), **Highest · H.264**, and **Smallest file**.
- Profile preview card plus a full-height batch sheet with per-post selection, **Download selected**, and **Download all**.
- TikTok web sign-in through TikTok's own page. The session is kept in Android's WebView cookie store; the app never reads credentials and does not copy the session token into preferences.
- Account controls in Settings, including session status and sign-out.
- Visible TikTok profile verification screen for completing TikTok's own slider check while authenticated.
- "Open on TikTok" recovery action when a profile feed is withheld.
- `CHANGELOG.md` and the version policy above.

### Changed

- Save opens with a compact link field, a three-platform-to-file diagram, and a ruled facts strip.
- Library uses grouped white panels with ruled rows instead of a card per item.
- Settings uses direct quality rows and switch rows without explanatory captions.
- Supports copy, routes, and existing download behavior remain unchanged.
- Auto-save and profile batch prefer the highest clean stream; profile batch excludes watermarked variants when a clean stream exists.

### Fixed

- `bestVariant` now ranks H.265 ahead of H.264 when H.265 is preferred.
- Variant labels no longer repeat `FHD` alongside the resolution.
- TikWM fallback labels now use the actual variant label.
- TikWM re-resolve is restricted to TikTok source URLs.
- Dark theme now provides the correct content color for screen headers and cards.
- History refreshes when returning to a tab and shows a failure reason on its own line.
- Profile loading now tries the authenticated WebView first and uses bounded timeouts.

### Security

- TikTok credentials are entered only into TikTok's own web page.
- The TikTok session remains in the app's on-device WebView cookie store.
- No TikTok token is written to app preferences or logs.

### Known limitations

- TikTok can withhold a profile feed or present a slider verification even when authenticated; the visible verification screen exists to let the user complete that check.
- TikTok profile listing remains subject to account, region, and platform changes.
- The profile verifier returns the first page of posts (up to the platform's page size).

## [0.1.0] - 2026-10-27

- Initial Snag scaffold: Compose shell, extractor SPI, TikTok WebView and OpenGraph strategies, WorkManager downloads to `Downloads/Snag`, JSON history, clipboard auto-save, and TikWM fallback.

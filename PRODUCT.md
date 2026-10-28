# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

People saving public social video and photo posts (TikTok first) onto their own phone, usually in short bursts from the share sheet, sometimes to archive a creator's recent work. One person, one device, no account.

## Product Purpose

Snag resolves a link or a public profile on-device and saves the best available media to the device. Success is: share or paste → see exactly what will be saved → the file lands in `Downloads/Snag`, with no sign-in, ads, or paywall.

## Positioning

Everything the category charges for — FHD, H.265, watermark-free source selection, batch, unlimited history — is base functionality, resolved on-device through an inspectable strategy pipeline rather than a hidden scraper backend.

## Operating Context

Used one-handed, often at night, from a share sheet or a copied link. Profiles are browsed when archiving a creator. Network conditions vary and platform pages change; failures must be legible and retryable instead of silent.

## Capabilities and Constraints

- TikTok links and profiles; Instagram and Facebook post links; generic OpenGraph fallback.
- Auto-paste and auto-save supported post links from the clipboard when Snag opens (opt-out).
- Variant picker, profile batch selection from a creator's own videos (not reposts), background downloads to MediaStore, history.
- Bookmark profiles for later; opening one reloads the creator's latest public videos and the batch picker.
- A creator link on a TikTok result goes straight to that profile's batch picker.
- Default quality is one tap from a video result through the **Quality** gear; the same preference drives Settings and every save.
- Profile bookmarks and saved files are presented as light gallery sections rather than caption-heavy lists.
- One opt-out third-party fallback (TikWM) when first-party extraction is blocked.
- No accounts, no server of our own, no billing, no ads, no analytics.
- Android 10+ (minSdk 29), Kotlin + Compose, single module.

## Brand Commitments

- Product name: **Snag**.
- Package name: `com.snag.downloader`.
- Voice: plain and calm; never "premium", never hype.

## Evidence on Hand

No production assets. Cover images and titles come from the resolved media at runtime; anything else is clearly synthetic.

## Product Principles

1. Show the work: every result names its source and the strategy that produced it.
2. Automatic when the intent is obvious (clipboard post link), explicit when it is not (profiles, batches).
3. Nothing is gated.
4. Failures are content, not dead ends — name the cause and the recovery.
5. Files belong to the user: they land in the public Downloads folder and open with the system viewer.

## Accessibility & Inclusion

Touch targets ≥48dp with ≥8dp separation; body text ≥4.5:1 contrast; status never conveyed by color alone; layout survives 1.3× font scale and edge-to-edge insets.

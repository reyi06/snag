# Snag - design

Snag is a warm-white utility surface with maroon structure, restrained gold accents, and
royal marks used sparingly. The media is the colour; the chrome stays quiet.

## Palette

| Token | Light | Dark |
|---|---|---|
| Ground | `#FCFBF8` | `#140B0E` |
| Panel | `#FFFFFF` | `#1E1215` |
| Muted | `#F7F1EA` | `#26181C` |
| Rule | `#E4D9CC` | `#3A282D` |
| Ink | `#1D1114` | `#FFF8F0` |
| Accent (maroon) | `#6E1B2A` | `#E0B56A` |
| Gold | `#B08A45` | `#E0B56A` |
| Field | `#2A0E13` | `#2A0E13` |
| On field | `#FFF8F0` | `#FFF8F0` |

Gold is an accent, not a surface flood. The ambient layer is a single diagonal linear wash:
6% gold over the light ground and 8% over the dark ground, fading through transparent. There
is no media-palette extraction and no drifting colour light.

## Type

- Display and headings: Host Grotesk.
- Reading and controls: Instrument Sans.
- Titles are semibold; headings can be tight; body copy never goes below `bodySmall`.
- Video captions are not shown in result cards, Recent, or Library. Titles remain in
  accessibility descriptions and download history.

## Shape and spacing

- Cards and fields: 8-12 dp corners.
- Gallery tiles: 10 dp corners, 8 dp gutters, three columns.
- Full-height batch sheet: 12 dp cards, clear drag handle, sticky action bar.
- Minimum 48 dp touch targets; each control gets at least 8 dp separation.

## Motion

`SnagMotion.Speed = 1.4f`; every custom duration is divided by it, and spring stiffness is
multiplied by its square. Motion is always on.

- Tab changes: fast crossfade, about 110 ms. No horizontal slide.
- Resolve states: short fade and small vertical lift.
- Buttons: press spring; chips crossfade.
- Navbar: filled icon and spring scale for the active tab; outlined icons when inactive.
- Launch: skeleton shimmer replaces a branded splash; content loads behind it.

## Screen patterns

### Save

Compact wordmark, dark maroon hero with one field and two actions, ruled fact strip, then the
current result. Results show author/platform, a compact **Quality** row with a gear, and save
actions. The gear opens a white default-quality card rather than a chip wall. Profile results
show a preview card with follower/video counts and a bookmark control. Recent is an uncaptioned
three-up gallery.

### Library

Header with saved count and size, then **Profiles** bookmark rows, file filters, and day groups
rendered as white photo-album cards. Each tile carries a status badge only: play, queued,
downloading, or failed.

### Settings

Direct rows, no explanatory captions. Quality, saving switches, account state, history, and
about. Bookmark data lives with profiles, not account credentials.

### TikTok sign-in

The login page is shown in TikTok's own WebView, using the mobile Chrome user agent, with a
Desktop/Mobile toggle in the login header. Profile verification is separate: it opens the
desktop page for TikTok's slider check, then switches to the mobile harvester after the
profile videos appear; **Load videos** is the manual fallback. A small local layout correction
gives the page a real pixel viewport height; login also dismisses TikTok's install-app sheet.
The app does not prefill an email, store a password, or submit the form itself.

Profile verification is a fallback. The app tries the backend/page capture first and goes
straight to the downloadable profile when items arrive. The real TikTok page is shown only
when that data is withheld or a slider check is required. Do not cover the verification
WebView with an opaque loading overlay; an obscured page can make TikTok reject the flow.

## Accessibility

- Focus rings use the maroon accent at 2 dp.
- Status is never conveyed by colour alone; icons and labels carry meaning.
- Layouts must survive 1.3x font scale and edge-to-edge insets.
- Dark theme uses the same hierarchy with a dark ground and gold accent.

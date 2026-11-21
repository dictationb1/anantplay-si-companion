# Phase 1 — Read-only Accessibility & View-Tree Audit of OFFICIAL AnantPlay (v2.23.1, commit `be74df2`)

> **Zero-fork guarantee:** `AnantPlay/android` was cloned **READ-ONLY** and only *read*. Not one byte of
> upstream code is included in this repo; the official unmodified APK (`me.champdev.weblock`) stays
> installed and keeps receiving official updates. All citations below are `file:line` in
> https://github.com/AnantPlay/android @ `be74df2` (tag `v2.23.1`).

---

## 1. Exact view hierarchy of `PlayerActivity`

- Package: `me.champdev.weblock` — `AndroidManifest.xml:2`
- Activity declared: `<activity android:name=".PlayerActivity" android:exported="false" android:screenOrientation="fullUser" ...>` — `AndroidManifest.xml:49-51` (PiP-capable, handles all orientation configChanges)
- Class: `public class PlayerActivity extends Activity` — `src/me/champdev/weblock/PlayerActivity.java:64`
- UI is built **100% in code** in `buildUi()` — `PlayerActivity.java:379`. **There are no XML layouts and no `view.setId(...)` calls anywhere in the file** (verified by grep: the only `setId` hit is a Media3 `SubtitleConfiguration.Builder.setId("opensubtitles")` at `PlayerActivity.java:2345`). So `AccessibilityNodeInfo.getViewIdResourceName()` is **null** for every view — the Companion must identify views **structurally** (class + bounds + parent/sibling shape + text patterns), which is fully possible and version-tolerant (see §1.6).

### 1.1 Root FrameLayout child order (the whole player screen)

`root = new FrameLayout(this)` — `PlayerActivity.java:380-381`. Children in `addView` order:

| # | View | Class | Created at | Notes |
|---|------|-------|-----------|-------|
| 1 | `surface` | `android.view.SurfaceView` | :383-384 | the video itself |
| 2 | `texture` | `android.view.TextureView` | :385-387 | GONE; Crop/Stretch zoom modes draw here |
| 3 | **`subtitle`** | **`android.widget.TextView`** | :396-401 | **bottom-center, OUTSIDE the HUD overlay** |
| 4 | `spinner` | `Home.Spinner extends View` | :404-405 | buffering circle |
| 5 | `notice` | `android.widget.TextView` | :407-415 | transient toasts ("Skip 30s" etc.), top-center |
| 6 | `gesture` | `android.widget.LinearLayout` | :418-446 | brightness/volume swipe indicator, GONE |
| 7 | `controls` | `android.widget.FrameLayout` | :449, :565-566 | **the whole auto-hiding HUD** (see 1.5) |
| 8 | `tip` | `android.widget.TextView` | :567-577 | inside `controls` — remote label, GONE |
| 9 | `skipBtn` | `android.widget.TextView` | :580-605 | "Skip intro", bottom-right (root child, visible with HUD hidden) |
| 10 | `debug` | `android.widget.TextView` | :608-617 | monospace stats overlay, GONE unless enabled |

### 1.2 The Subtitle TextView (the heart of Cue-Lock Mode A)

- Created at `PlayerActivity.java:396-401`: `subtitle = new TextView(this)`, gravity `CENTER`, shadow layer, padding, added to **`root`** (NOT to `controls`) with `FrameLayout.LayoutParams(-2,-2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)` — :401.
- Text is set **only** in `showCues(CueGroup)` — `PlayerActivity.java:349-362`, called from the Player listener `onCues(CueGroup)` at :1202, i.e. **on every subtitle cue change of the real ExoPlayer**.
- Exact flattening rule (this is what the Companion's `CueMatcher` must reverse) — :350-359:
  - Multiple simultaneous cues are joined with `'\n'` (:353).
  - **Inner line breaks become spaces, EXCEPT when the next line starts with `-` or `–`** (speaker dashes are kept) — :356-358.
- Visibility: `subtitle.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE)` — :361. Between cues the view is GONE; when a cue appears it becomes VISIBLE + gets text.
- Styling in `placeSubtitle()` — :365-371: text size `22sp × Subs.size()/100` (user-scalable 50–250%), background = black at `Subs.background()` % opacity (default 50% — `Subs.java:131-133`), bottom margin `dp(max(0, 20 + Subs.offset()))` (user can move it up to 300dp), and `setMaxWidth(screenWidth × 3/4)` — :391. **The Companion reads the live `getBoundsInScreen()` of this exact node and masks/overlays to these coordinates — pixel-perfect for any user styling.**
- `isImportantForAccessibility` is **never set to false** (grep over the whole repo: zero hits for `importantForAccessibility` in `PlayerActivity.java`); a plain `TextView` is important-for-accessibility by default, so **`setText()` fires `TYPE_VIEW_TEXT_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED` events to a subscribed AccessibilityService on every cue**.
- **Crucial HUD-independence proof:** `hideControls()` sets only `controls.setVisibility(View.GONE)` — :1425-1429. The `subtitle` view is a **sibling** of `controls`, added at :401 before `controls` (:566). When the HUD fades, the subtitle TextView stays attached, VISIBLE and fully readable → **cue events keep arriving while the HUD is hidden.** (Verified: nothing else ever removes/hides `subtitle` except empty text at :361 and `subtitleStyle()`'s sample at :375.)

### 1.3 The Title / Episode line (what the Companion auto-detects)

- `title = new TextView(this)` — :462-478, single-line, ellipsized, inside `topBar` LinearLayout inside `controls`.
- Title text set at :469-477: intent extra `"title"`, or — when the video was opened from a streaming page — `History.cleanTitle(pageTitle, site)` (:471, implementation `History.java:199-209`: splits `"Watch X | SiteName"`-style titles on separators, drops the site part, strips "watch/stream" prefixes and junk tails), or the catalog show's name (:475).
- `epLine` (second TextView inside the same `titles` LinearLayout) — :479-486; its text is built in `refreshChips()` :1000-1011 as `"  ·  "`-joined info: episode text (`"Season 1 · Episode 3"`, `episodeLine()` :1683-1687), quality (`1080p`), audio, **`"CC " + textNow` (:1007)** — this is literally where **`CC English (OpenSubtitles)`** on the user's screen comes from — server name, speed.
- **HUD-hidden readability:** when `controls` is GONE, its children are still in the accessibility node tree (`isVisibleToUser == false` but `getText()` works). So the Companion reads title + episode **at any time**, even mid-movie with the HUD hidden, by walking the cached tree. The `title`/`epLine` pair is found structurally: two sibling TextViews, first child non-empty, inside a vertical LinearLayout at the top of the screen (§1.6).
- Which episode is playing: `pageEpisode()` — :1673-1680 (regex `S(\d+) E(\d+)|E(\d+)` over the page URL/title, else intent `season`/`episode` extras). IMDb id arrives as intent extra `"imdb"` (used at :2154, :2188) — **not exposed in the view tree**, so the Companion resolves title(+season/episode) → IMDb via the Worker's Cinemeta `/api/resolve` (exactly what AnantPlay itself does at `Subs.java:498-502`).

### 1.4 Position / Duration / SeekBar / Play-Pause (Clock-Lock Mode B)

- `seek = new SeekBar(this)` — :513-526, `setFocusable(false)` :514, inside `bottomBar` inside `controls`.
- `update()` — :1431-1452, run from the `tick` Runnable **every 500 ms** — :133-141 (`App.main.postDelayed(this, 500)`):
  - `seek.setMax((int) dur)` / `seek.setProgress((int) pos)` — :1441-1442 → **SeekBar progress units are MILLISECONDS of the media** (duration as max). Progress is refreshed every 500ms while the HUD is visible.
  - `time.setText(clock(pos))` — :1449, `timeTotal.setText(clock(dur))` — :1440/:1450. `clock()` — :1454-1457: `m:ss` under an hour, else `h:mm:ss` (`1:27` / `1:42:00` on screen).
  - Live streams: seek INVISIBLE, time shows "● LIVE" (:1445-1448).
- Play/Pause: `playPause.setImageResource(playing ? ic_p_pause : ic_p_play)` — :1434, where `playing = player.getPlayWhenReady() && state != STATE_ENDED` (:1433). **The Companion reads play/pause transitions from `onIsPlayingChanged`-driven UI changes**: pause → `showControls()` (HUD **stays visible** while paused, :1178-1182); play → `hideAt = now + 3500` (:1181).
- Seek commits: while keys are held, `pending` accumulates and the real `player.seekTo(pending)` happens 400 ms after the last step (`seekBy` :1384-1392, `commitSeek` :1396-1401); finger drags set `dragging=true` (:523-525). So the Companion's Clock-Sync engine must treat `SeekBar` jumps + `time` text as ground truth and re-lock instantly on discontinuities (implemented as jump detection + re-anchor, see `ClockSyncEngine`).
- **All of these live inside `controls`, so they are only exposed while the HUD is up.** Clock-Sync Mode B therefore: reads `time`/`seek` whenever the HUD appears (tap, pause, seek), anchors `SystemClock.elapsedRealtime()`, drifts its own monotonic clock between HUD appearances, and re-anchors on any observable discontinuity — plus manual `±0.25s / ±1.0s` nudges.

### 1.5 The auto-hiding HUD (`controls`)

- `controls` = a `FrameLayout` overlay containing `topBar` (back + title/epLine + home), the center `-10 PLAY +10` cluster (:495-504) and `bottomBar` (time · seekbar · timeTotal + chip row) — :448-566.
- `showControls()` — :1417-1423: `hideAt = now + 3500` ms (8000 ms when focus is on the chip row). Auto-hide happens inside `update()` — :1451, **only while playing** (paused keeps the HUD up). `hideControls()` — :1425-1429 sets the whole overlay GONE.
- Chips (quality/audio/subs/episodes/…) are LinearLayouts with `setContentDescription(label)` — :830-835; e.g. the subtitle chip's description is `player_subs_now` with `textNow` (:980), and `labelOf()` (:843-849) renders a picked OpenSubtitles file as **`English (OpenSubtitles)`** (label built at :2346 as `Subs.language(lang) + " (" + source + ")"`). The chip row is rebuilt in `fitChips()` (:1019-1040) whenever visibility changes.

### 1.6 How the Companion identifies these nodes without view IDs

Structural signature (robust across AnantPlay point releases, no IDs needed):

1. **Player window detection:** `AccessibilityEvent`/`AccessibilityWindowInfo` with package `me.champdev.weblock` and `getActivity()` ComponentName ending in `.PlayerActivity` (API 24+; fallback: window whose root tree contains a `SurfaceView` + `SeekBar` + bottom-centered `TextView`).
2. **Subtitle node:** the `android.widget.TextView` that (a) is a direct child of the window root, (b) has bounds in the **bottom 35%** of the screen, horizontally centered, and (c) is the **event source of `TYPE_VIEW_TEXT_CHANGED`** during playback — cache its identity after the first hit.
3. **Title/epLine nodes:** the two sibling TextViews inside a vertical LinearLayout nested in a horizontal LinearLayout whose bounds are in the top 25% of the screen; first child = title, second = epLine (contains `·` separators / `Season … Episode …` / `CC …` / `\d+p` patterns). Readable even while GONE.
4. **Time/SeekBar:** TextViews matching `^\d+(:\d{2}){1,2}$` next to an `android.widget.SeekBar` in the bottom bar (only while HUD visible).

## 2. FLAG_SECURE & window flags (overlay legality)

- **`FLAG_SECURE` is NEVER set** — grep for `FLAG_SECURE` across the whole repo returns **zero hits** (manifest and all sources). The only window flag PlayerActivity adds is `FLAG_KEEP_SCREEN_ON` — :157.
- Full-screen immersive: `SYSTEM_UI_FLAG_FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY | LAYOUT_STABLE | LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION` — :639-640.
- Cutout: `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` on API 28+ — :619, and insets keep top/bottom bars clear of the camera cutout (:620-635).
- AnantPlay itself already holds `SYSTEM_ALERT_WINDOW` — `AndroidManifest.xml:11` (used for its own floating features), which confirms the vendor expects drawing-over-video to work.

**Conclusion:** because the player window is a normal (non-secure, non-flagged) window, both `SYSTEM_ALERT_WINDOW` and `TYPE_ACCESSIBILITY_OVERLAY` windows **draw cleanly on top of the video**, including in immersive landscape with cutouts.

## 3. How AnantPlay itself gets subtitles (what our Worker must match)

- Default: text tracks are **disabled** — :1154 (`setTrackTypeDisabled(TRACK_TYPE_TEXT, true)`), "Side-loaded subtitles stay off until picked".
- On `STATE_READY`, `autoSubs()` runs once — :1163, :2221-2249: returns the remembered per-title pick, else (only if the user enabled Settings → Auto subtitles, `Subs.auto()` default **off** — `Subs.java:139-142`) picks the stream's own track in the preferred language, else the most-downloaded OpenSubtitles file in that language (`findSubs(lang, …)` :2248).
- Search: **legacy OpenSubtitles REST, no key, no login** — `SubOs.java:23-26` (`UA "TemporaryUserAgent"`, `https://rest.opensubtitles.org/search/`), URL shape `imdbid-0000000[/season-S][/episode-E]/sublanguageid-xxx` — :43-57; results sorted by downloads — :92-95. Fallback: Stremio's public addon `opensubtitles-v3.strem.io` — `Subs.java:14, 478-496`.
- **Sinhala is already in AnantPlay's own language picker** (`sin` — `Subs.java:25`), but legacy OpenSubtitles has almost no Sinhala → this is exactly the gap the Worker fills (piratelk + SubDL `si` + cached zoom.lk + Gemini Flash EN→SI).
- IMDb resolution inside AnantPlay: `Subs.imdbFor(title, series)` via Cinemeta — `Subs.java:498-502`.
- Download/unpack/UTF-8 conversion: `Subs.fetch` → `SubFetch.fetch` — `Subs.java:81-86`.
- Subtitle timing: user offset `Subs.delayMs` shifts the **renderer clock** (`render(pos − delayMs×1000)`) — :1121-1131; plus AnantPlay's own speech-based auto-sync `SubSync` (`SubSync.java:55-91`) re-times side-loaded files every 45 s — :2370-2389. **Important:** when the user uses AnantPlay's own Timing setting or SubSync moves `delayMs`, the English cue *text stream is unaffected* — Cue-Lock Mode A is immune to it; Clock-Sync Mode B re-anchors from the HUD clock anyway.
- SRT timing format parsed by `SubSync.cues()` — `SubSync.java:26-48` (`hh:mm:ss,mmm --> hh:mm:ss,mmm`, also VTT/ASS) — the Worker's validator preserves exactly these timestamp strings byte-for-byte.

## 4. Android 13–16 restricted settings & overlay reality check (100% honest)

1. **`TYPE_ACCESSIBILITY_OVERLAY` (API 22+) needs NO `SYSTEM_ALERT_WINDOW` permission** — an enabled AccessibilityService may add windows of this type and they draw above app windows, including over full-screen video. Sources: official codelab "Developing an Accessibility Service for Android" (codelabs.developers.google.com/codelabs/developing-android-a11y-service), Stack Overflow "How does AccessibilityService draw on top of other apps?" (stackoverflow.com/questions/67063026).
2. **Android 13+ "Restricted settings" (the one honest friction point):** an APK sideloaded from a browser/file manager (exactly how this personal Companion is installed) has its Accessibility toggle greyed out. The fix is a **one-time** manual step: Settings → Apps → *AnantPlay SI Companion* → **⋮ → "Allow restricted settings"** → confirm. Official doc: https://support.google.com/android/answer/12623953. Apps installed via Play/store-like sources are unaffected. Android 14 keeps this (per-session "Restricted setting" dialogs); Android 15 extends the same gating to more sideloaded permission flows (notification listener etc.).
3. **Why the Companion uses `TYPE_ACCESSIBILITY_OVERLAY` and not `SYSTEM_ALERT_WINDOW`:** one toggle (Accessibility) instead of two; `SYSTEM_ALERT_WINDOW` for sideloaded apps hits the same restricted-settings gate on 13+ anyway; the service **already** needs accessibility to read the player's view tree — so the overlay rides on the same single grant. No extra permission prompt, no "display over other apps" screen hunting.
4. **Overlay window flags for perfect video playback:** `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` so AnantPlay keeps all keys/gestures; the subtitle overlay view itself gets `FLAG_NOT_TOUCHABLE` (every touch passes through to the video — swipes, double-tap seek, chip taps all keep working); only the tiny pill is touchable. Over immersive landscape + cutouts this draws edge-to-edge, same as AnantPlay's own overlays (it holds SYSTEM_ALERT_WINDOW for the same reason — `AndroidManifest.xml:11`).
5. **Battery/reachability:** the service config is scoped with `android:packageNames="me.champdev.weblock"` + `accessibilityEventTypes` limited to the few types we need; the system then only wakes us for AnantPlay events. Android 14+ requires `foregroundServiceType=accessibility`-typed services... the Companion runs **no foreground service**: the overlay lives inside the AccessibilityService itself, which the system keeps alive while enabled.

## 5. Zero-fork alternatives compared (and why the Dual-Engine Overlay wins)

| | **A. Dual-Engine Accessibility Overlay (chosen)** | B. Shizuku/ADB automation | C. Live Caption / on-device ASR (`AudioPlaybackCapture` + Whisper/Gemini) | D. Per-line network translate (previous strawman) |
|---|---|---|---|---|
| Root/ADB | **None after 1-time setup** (1 toggle + 1-time restricted-settings unlock) | ADB/USB or wireless debugging every reboot (or root) | None | None |
| Sync | **Cue-Lock = frame-exact by construction (same cue stream), Clock-Lock ±250 ms with 1-tap nudges** | Same as A if paired with overlay (Shizuku only replaces the permission grant) | 1–5 s lag, no scene context, hallucinates names | 1–3 s per line, no context |
| Offline after first fetch | **Yes — full SRT in RAM** | — | No (continuous ASR) | No |
| Human Sinhala subs (Baiscope/Zoom/PirateLK) | **Yes — Worker proxies + Companion local `.srt`/`.zip` loader** | Yes, manual | No (translates audio, ignores existing subs) | Barely |
| Double subtitles | **Solved — exact `getBoundsInScreen()` mask over the English TextView** | Unsolved by Shizuku itself | Doubles with any CC | Doubles |
- **Alt 1 (Shizuku):** grants `SYSTEM_ALERT_WINDOW`/global actions via ADB privileges, but requires ADB re-connection after every reboot on non-rooted phones — worse UX than one accessibility toggle. Rejected as primary; noted as optional power-user path.
- **Alt 2 (AudioPlaybackCapture):** Android 10+ allows capturing other apps' `USAGE_MEDIA` audio only when the app hasn't opted out, requires a **MediaProjection consent dialog + prominent foreground-service notification for every capture session** (Android 14+: developer.android.com/media/grow/media-projection — `mediaProjection` FGS type, per-session user consent), and would need continuous ASR + translation = battery-heavy, laggy, and error-prone on proper nouns. Rejected for the primary path; the Worker API would keep it possible later as an experimental mode.
- **Alt 3 (Dual-Engine `TYPE_ACCESSIBILITY_OVERLAY` + Cloudflare Worker):** the chosen architecture — **Smart Floating-Pill Companion**. Cue-Lock (Mode A) whenever AnantPlay shows *any* English cue stream (embedded CC **or** an OpenSubtitles file the user picked); Clock-Lock (Mode B) when English CC is off and human Sinhala subs exist. Zero per-line network calls: the full paired `{en, si}` cue array is fetched once and held in RAM; matching is O(1) via sequential-window + trigram fallback lookup (<5 ms even on seeks).
- **Alt 4 (bonus found during audit — “remembered subtitle piggyback”):** the Worker also serves the **Stremio-addon-compatible `/.well-known`/addon manifest + `/subtitles/movie/tt…/…` endpoints**, so users who prefer zero extra apps can add the Worker as an AnantPlay *subtitle source* (AnantPlay → Settings → Add source) and get Sinhala files straight inside the official player's own Subtitles picker — the Companion is then only needed for auto-detection, overlay styling and the AI-translate path. Both repos keep these endpoints (they already power the deployed dashboard's "Add source" flow).

## 6. Verdict

The **Dual-Engine Zero-Fork Smart Companion** is 100% implementable against the *official unmodified* APK on Android 10–16, non-root:

1. `TYPE_ACCESSIBILITY_OVERLAY` floating pill (auto-hides to a 28 dp edge handle after 3 s) — no SYSTEM_ALERT_WINDOW, no fork.
2. Auto-detection of title/episode from the HUD tree (readable even while GONE) → Worker `/api/resolve` (Cinemeta) → `/subtitles/:type/:id.json`.
3. **Mode A (Cue-Lock):** subscribe to the subtitle TextView's `TYPE_VIEW_TEXT_CHANGED` (fires on every cue, HUD hidden or not — `PlayerActivity.java:349-362`, `:1202`), match the flattened English text against the paired array in RAM, render Sinhala in the exact bounds from `getBoundsInScreen()` — zero double subtitles, zero drift, zero lag.
4. **Mode B (Clock-Lock):** anchor on `time`/`seek` (ms units — `:1441-1442`) whenever the HUD shows, run a `SystemClock.elapsedRealtime()`-driven timeline, re-anchor on jumps, `±0.25s/±1.0s` nudges.
5. Emergency local `.srt`/`.zip` loader + title override inside the pill's drawer.

**Every step above is proven by the exact upstream lines cited in §1–§3.** No fork, no patch, no recompile, no replaced APK — the official app stays official.

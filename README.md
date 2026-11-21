# AnantPlay SI Companion — සිංහල උපසිරැසි සහායකයා (Zero-Fork)

**100% zero-fork Sinhala subtitles for the OFFICIAL, UNMODIFIED AnantPlay app** — a tiny
standalone Android helper (`lk.techworld.anantplay.sicomp`) that:

- **never forks, patches, recompiles or replaces AnantPlay** — you keep the official APK
  (`me.champdev.weblock`) and receive official updates forever;
- reads **only** the AnantPlay player screen through a **scoped accessibility service**
  (`android:packageNames="me.champdev.weblock"` — it cannot see any other app);
- draws its Sinhala overlay with `TYPE_ACCESSIBILITY_OVERLAY` (no `SYSTEM_ALERT_WINDOW`
  permission, no root, Android 8–16);
- talks to your own **Cloudflare Worker** —
  [anantplay-sinhala-bridge v3](https://github.com/dictationb1/anantplay-sinhala-bridge) —
  which finds **human Sinhala subs first** (OpenSubtitles `sin` / SubDL `si`) and, when
  none exist (e.g. *Clayface*), **translates the whole movie** with Gemini Flash in 4–6
  large context batches, keeping every timestamp byte-identical.

| | |
|---|---|
| **Sync Mode A — Cue-Lock** | When AnantPlay shows an English CC line (its own or OpenSubtitles'), the exact millisecond that line appears in AnantPlay's subtitle `TextView`, the companion matches it in RAM (`CueMatcher`) and shows the paired Sinhala line — **frame-exact, <10 ms, zero network, zero drift**, and it works with the player's controls hidden. The Sinhala box is placed **exactly over AnantPlay's own subtitle box** (`getBoundsInScreen`), so there are **never double subtitles**. |
| **Sync Mode B — Clock-Sync** | For human Sinhala subs (Baiscope/ChameE/Zoom) the companion follows the player's own clock (the HUD's SeekBar progress **is** the media position in milliseconds) with a monotonic timeline, seek/pause re-anchoring, and one-tap **±0.25 s / ±1 s** nudges. |
| **Emergency** | A local `.srt`/`.zip`/`.gz` loader (hand-downloaded Baiscope/Zoom files) and a title-search override live in the pill's panel. |

The full architecture, the audit of official AnantPlay's view tree (with `file:line`
citations) and the alternatives comparison live in
[`docs/PHASE1-ANANTPLAY-AUDIT.md`](docs/PHASE1-ANANTPLAY-AUDIT.md).

---

## ස්ථාපනය · Setup (5 minutes)

### 1 · Build (or download) the APK

```bash
# needs a JDK 17+ and an Android SDK (platform 34 + build-tools 34)
ANDROID_HOME=/path/to/sdk ./build.sh
# → dist/AnantPlay-SI-Companion.apk
```

Or grab the CI-built APK: every push runs
`.github/workflows/build-apk.yml` (Sync Lab + JDK 17 + Android SDK 34 build,
`apksigner verify` included) and uploads the signed **`AnantPlay-SI-Companion.apk`**
as the workflow artifact `anantplay-si-companion-apk` — download it from the run's
"Artifacts" section (Actions → run → Artifacts).

### 2 · Install it on your phone (Android 8–16)

Copy the APK to the phone and open it (a file manager or browser download works).
**Do not** uninstall or touch official AnantPlay — it stays exactly as it is.

### 3 · ONE-TIME: "Allow restricted settings" (Android 13+ only)

Because the APK was installed from outside a store, Android 13+ blocks its
Accessibility toggle until you unlock it once:

> **Settings → Apps → AnantPlay SI Companion → ⋮ (top-right menu) →
> "Allow restricted settings" → Allow**

(Android 8–12: skip this step.)

### 4 · Enable the service

> **Settings → Accessibility → Installed apps → AnantPlay SI Companion → On**

The service description says exactly what it does. It is scoped to
`me.champdev.weblock` only.

### 5 · Play anything

Open **official AnantPlay**, play any movie or episode (e.g. *Clayface* on OnlyFlix).
The **අ** pill appears at the right edge and fades to low opacity after 3 seconds —
the movie is never obstructed. Tap it anytime for the mini-panel.

- The companion auto-detects the title from the player screen (no typing), asks your
  worker for Sinhala subs, and shows the state right in the panel
  (`Human Sinhala found ✓` / `Translating batch 2/5…` / `Synced ✓`).
- **For AI-translated movies (Mode A):** turn on AnantPlay's own English CC once
  (player → Subtitles → English — e.g. *CC English (OpenSubtitles)*). The companion
  locks to those English lines and overlays the Sinhala pair on top. English CC off →
  it automatically switches to clock mode.
- **For human Sinhala subs (Mode B):** nothing to turn on — the clock follows the player.
  Use the **±0.25 s / ±1 s** buttons in the panel if the file's timing is slightly off.
- Font size and mask opacity sliders are in the same panel; the worker URL and the
  auto-fetch toggle are in the app's launcher icon.

### Worker setup (already live by default)

The companion is pre-pointed at
`https://anantplay-si-subs-bridge.techworldlk.workers.dev`. To enable AI translation
for titles with no human Sinhala sub, set the Gemini secret on the worker repo:

```bash
git clone https://github.com/dictationb1/anantplay-sinhala-bridge
cd anantplay-sinhala-bridge
npx wrangler secret put GEMINI_API_KEY     # free key: aistudio.google.com/apikey
npx wrangler kv namespace create SUBS_KV    # optional but recommended: permanent cache
# paste the id into wrangler.toml, then:
npx wrangler deploy
```

No key is ever typed on the phone.

---

## විශ්වාසය · Trust & privacy

- The accessibility service receives events **only** from `me.champdev.weblock` —
  no other app is ever monitored (enforced by `res/xml/accessibility_service_config.xml`).
- The only network traffic is: the detected movie title + season/episode → your worker,
  and the subtitle files back. No analytics, no ads, no background anything.
- `NotoSansSinhala-Regular.ttf` is bundled (OFL license) so Sinhala ligatures render
  razor-sharp on every device.

## Verification labs (everything is proven, not promised)

```bash
# the engines that ship in the APK — on a bare JDK:
./lab/run_companion_lab.sh
#   → 38 PASS: cue-lock ≥99.5% & <5 ms, ±40-min seek re-locks, fuzzy window,
#     clock pause/seek/nudge math, .srt/.zip/.gz loader, malformed-input safety

# against a REAL /api/paired/<jobId>.json produced by the live worker:
./lab/run_companion_lab.sh --paired clayface-paired.json
#   → 46 PASS incl. 906/906 exact matches (avg 0.012 ms) on the real OnlyFlix track

# the APK build itself (JDK 17 + Android SDK 34, no Gradle — AnantPlay's own recipe):
ANDROID_HOME=/path/to/sdk ./build.sh
```

CI runs the lab and builds + verifies the APK on every push
(`.github/workflows/build-apk.yml`).

## Repository layout

```
AndroidManifest.xml          INTERNET + scoped accessibility service + <queries> weblock
src/…/sicomp/
  SinhalaOverlayService.java  the service: pill, panel, overlay, Mode A/B wiring
  PlayerViewFinder.java        structural view-tree finder (no IDs — audit-proven shapes)
  CueMatcher.java              the <10 ms cue-lock engine (pure Java, lab-tested)
  ClockSyncEngine.java         Mode B monotonic clock (pure Java, lab-tested)
  Cue.java / MiniJson.java     paired-cue model + tiny JSON parser (pure Java)
  SrtLoader.java               emergency .srt/.zip/.gz loader (pure Java)
  BridgeApi.java               worker HTTP client (pure java.net)
  SubtitleStore.java           permanent on-device cache
  FilePickActivity.java        transparent SAF trampoline
  MainActivity.java            setup + settings screen
assets/fonts/                  NotoSansSinhala-Regular.ttf (OFL)
res/xml/accessibility_service_config.xml   packageNames="me.champdev.weblock"
lab/                           the verification lab + runner
docs/PHASE1-ANANTPLAY-AUDIT.md the read-only audit of official AnantPlay v2.23.1
build.sh                       aapt2 + javac + d8 + apksigner (no Gradle)
```

## License

MIT (the bundled Noto Sans Sinhala font is SIL OFL — see its header).

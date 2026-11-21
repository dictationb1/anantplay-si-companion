package lk.techworld.anantplay.sicomp;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* THE ZERO-FORK COMPANION — the scoped accessibility service.
 *
 * It watches ONLY official AnantPlay (`me.champdev.weblock`, android:packageNames in
 * res/xml/accessibility_service_config.xml — zero battery or privacy exposure of any
 * other app), reads the player's view tree exactly as audited in
 * docs/PHASE1-ANANTPLAY-AUDIT.md, and draws with TYPE_ACCESSIBILITY_OVERLAY (no
 * SYSTEM_ALERT_WINDOW permission needed on Android 10–16):
 *
 *   · a tiny edge pill that auto-fades 3 s after the last interaction (28 dp handle)
 *   · a dark mini-panel (auto-detected title, live status, mode toggle, ±0.25 s / ±1 s
 *     nudges, font-size and mask-opacity sliders, emergency local .srt/.zip loader and
 *     a title-search override)
 *   · the Sinhala subtitle view itself — positioned over AnantPlay's own subtitle
 *     TextView bounds (getBoundsInScreen) with an opaque backing, FLAG_NOT_TOUCHABLE so
 *     every touch passes through to the video. ZERO double subtitles.
 *
 * Sync Mode A (Cue-Lock): every TYPE_VIEW_TEXT_CHANGED of AnantPlay's subtitle TextView
 * (PlayerActivity.java:349-362 — fires with the HUD hidden too) is matched against the
 * worker's paired cues held in RAM → the Sinhala cue appears the same frame. No network
 * in the loop, no clock drift, <10 ms.
 *
 * Sync Mode B (Clock-Lock): the HUD's SeekBar progress (MILLISECONDS —
 * PlayerActivity.java:1441-1442) and "1:27" TextViews anchor a SystemClock.elapsedRealtime()
 * timeline that drives the whole .srt; seeks/pauses are re-anchored; nudges adjust it.
 */
public class SinhalaOverlayService extends AccessibilityService {

    static final String PREFS = "sicomp";
    static final String KEY_WORKER_URL = "workerUrl";
    static final String KEY_AUTO_FETCH = "autoFetch";
    static final String KEY_FONT_SP = "fontSp";
    static final String KEY_MASK_OPACITY = "maskOpacity";
    static final String KEY_MODE = "mode"; // "auto" | "clock"

    /** The one running instance (the file picker activity hands its result here). */
    static volatile SinhalaOverlayService instance;

    private WindowManager wm;
    private Typeface siFont;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bg;
    private BridgeApi api;
    private SharedPreferences prefs;

    // overlay views
    private FrameLayout pill;
    private TextView pillDot;
    private LinearLayout panel;
    private TextView panelTitle;
    private TextView panelStatus;
    private Button btnMode;
    private LinearLayout clockRow;
    private TextView subView;
    private boolean overlaysAdded;
    private boolean panelOpen;

    // player state
    private boolean playerOnScreen;
    private long lastScanAt;
    private String currentTitle = "";
    private String currentKey = "";      // type:imdb:s:e or local:<name>
    private String pendingTitle = "";
    private int currentSeason, currentEpisode;
    private boolean seriesGuess;

    // sync state
    // volatile: written by the bg fetch thread (applyCues), read by the main-thread
    // accessibility-event path — plain fields could publish a half-built sync state
    private volatile List<Cue> cues = new ArrayList<>();
    private volatile CueMatcher matcher;
    private volatile ClockSyncEngine clock;
    private volatile boolean clockMode;          // manual override or graceful fallback
    private volatile boolean fetching;
    private int cueMisses;
    private String state = "idle";               // idle|resolving|translating|ready|local|needs-key|not-found|error
    private String stateDetail = "";
    private final Rect subBounds = new Rect();
    private boolean haveSubBounds;
    private int lastShownIdx = -2;
    private long lastHudPosMs = -1;
    private long lastHudPosAt;

    // ───────────────────────────────────────────────────────────────────────────
    // lifecycle
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        api = new BridgeApi(prefs.getString(KEY_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL));
        try {
            siFont = Typeface.createFromAsset(getAssets(), "fonts/NotoSansSinhala-Regular.ttf");
        } catch (Throwable t) {
            siFont = Typeface.DEFAULT;
        }
        bgThread = new HandlerThread("sicomp-bg");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        buildOverlays();
        addOverlays();
        startTicker();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (bgThread != null) bgThread.quitSafely();
        removeOverlays();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        removeOverlays();
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
        // the system asks us to stop feedback: hide the overlay, stay alive
        main.post(() -> { if (subView != null) subView.setVisibility(View.GONE); });
    }

    // ───────────────────────────────────────────────────────────────────────────
    // accessibility events — the ONLY input from official AnantPlay
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!PlayerViewFinder.isPlayerPackage(e.getPackageName())) {
            if (playerOnScreen) onPlayerGone();
            return;
        }
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            CharSequence t = join(e.getText());
            if (t != null && t.length() > 0) onLiveSubtitleText(Cue.flatten(t.toString()));
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastScanAt < 250) return; // AnantPlay ticks its HUD every 500 ms (:133-141)
            lastScanAt = now;
            main.post(this::scanTree);
        }
    }

    private static CharSequence join(List<CharSequence> list) {
        if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        StringBuilder sb = new StringBuilder();
        for (CharSequence c : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(c);
        }
        return sb;
    }

    /** The tree walk: player detection, title/episode, clock anchors, subtitle bounds. */
    private void scanTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !PlayerViewFinder.looksLikePlayerRoot(root)) {
            if (playerOnScreen) onPlayerGone();
            return;
        }
        if (!playerOnScreen) onPlayerAppeared();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        PlayerViewFinder.PlayerViews v = PlayerViewFinder.find(root, dm.widthPixels, dm.heightPixels);

        // title/episode — the HUD's pair stays readable even while GONE (audit §1.3)
        String title = v.titleText.trim();
        int[] ep = PlayerViewFinder.parseSeasonEpisode(v.epLineText);
        if (title.length() >= 2 && !title.equals(currentTitle)) {
            seriesGuess = ep != null;
            onNewVideo(title, ep == null ? 0 : ep[0], ep == null ? 0 : ep[1]);
        }

        // Clock-Sync anchors: the SeekBar's progress IS the media position in ms.
        // Pause detection: AnantPlay keeps its HUD visible while paused (update() only
        // auto-hides while playing — PlayerActivity.java:1451), so a HUD position that
        // stops advancing for a few seconds means the video is paused.
        long posMs = -1;
        if (v.seek != null) {
            try {
                AccessibilityNodeInfo.RangeInfo r = v.seek.getRangeInfo();
                if (r != null && r.getMax() > 0) posMs = (long) r.getCurrent();
            } catch (Throwable ignored) { }
        }
        if (posMs < 0 && v.time != null) posMs = PlayerViewFinder.clockToMs(PlayerViewFinder.textOf(v.time));
        if (posMs >= 0 && clock != null) {
            boolean advancing = posMs != lastHudPosMs;
            long now = SystemClock.elapsedRealtime();
            if (!advancing && lastHudPosAt > 0 && now - lastHudPosAt > 3500) {
                clock.setPaused(true);
            } else {
                clock.setPaused(false);
                clock.anchor(posMs);   // re-anchors; jumps > 1.2 s are detected as seeks
            }
            if (advancing) lastHudPosAt = now;
            lastHudPosMs = posMs;
        }

        // the exact bounds of AnantPlay's subtitle TextView → our mask position
        if (!v.subtitleBounds.isEmpty()) {
            Rect nb = v.subtitleBounds;
            if (!haveSubBounds || Math.abs(nb.centerY() - subBounds.centerY()) > 4
                || Math.abs(nb.width() - subBounds.width()) > 8) {
                subBounds.set(nb);
                haveSubBounds = true;
                positionSubtitleOverAnantPlay();
            }
        }
    }

    private void onPlayerAppeared() {
        playerOnScreen = true;
        main.post(() -> {
            if (overlaysAdded) {
                pill.setVisibility(View.VISIBLE);
                pill.setAlpha(0.92f);
                schedulePillFade();
                if (panelOpen) panel.setVisibility(View.VISIBLE);
            }
        });
    }

    private void onPlayerGone() {
        playerOnScreen = false;
        main.post(() -> {
            if (subView != null) subView.setVisibility(View.GONE);
            if (pill != null) pill.setVisibility(View.GONE);
            if (panel != null) panel.setVisibility(View.GONE);
            panelOpen = false;
        });
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Sync Mode A — cue-lock (the <10 ms path)
    // ───────────────────────────────────────────────────────────────────────────

    private void onLiveSubtitleText(String flattened) {
        if (matcher == null || cues.isEmpty()) return;
        if (!playerOnScreen) return;
        Cue hit = matcher.match(flattened);
        if (hit != null) {
            cueMisses = 0;
            showSinhala(hit);
        } else {
            cueMisses++;
            if (clockMode) return; // clock drives the display; cue text only re-locks
            if (cueMisses == 1 && clock != null) {
                // our pair exists but this line isn't in it (e.g. the CC comes from the
                // stream, not the OpenSubtitles file): let the clock keep it readable
                clockMode = true;
                updateModeButton();
                showStatus();
            } else if (matcher.isLost()) {
                clockMode = true;
                updateModeButton();
                showStatus();
            }
        }
    }

    private void showSinhala(Cue cue) {
        main.post(() -> {
            if (subView == null || cue == null) return;
            if (cue.i == lastShownIdx && subView.getVisibility() == View.VISIBLE
                && cue.si.contentEquals(subView.getText())) return;
            lastShownIdx = cue.i;
            subView.setTypeface(siFont);
            subView.setText(cue.si == null || cue.si.isEmpty() ? cue.en : cue.si);
            positionSubtitleOverAnantPlay();
            subView.setVisibility(View.VISIBLE);
        });
    }

    private void hideSinhala() {
        main.post(() -> {
            if (subView != null) subView.setVisibility(View.GONE);
            lastShownIdx = -2;
        });
    }

    /** Put our Sinhala view EXACTLY over AnantPlay's subtitle box (zero double subtitles). */
    private void positionSubtitleOverAnantPlay() {
        if (subView == null || !haveSubBounds || !overlaysAdded) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) subView.getLayoutParams();
        lp.width = Math.max(subBounds.width(), 200);
        lp.x = subBounds.left + (subBounds.width() - lp.width) / 2;
        lp.y = subBounds.top - dp(2);
        try {
            wm.updateViewLayout(subView, lp);
        } catch (Throwable ignored) { }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Sync Mode B — the clock ticker (4 Hz, cheap)
    // ───────────────────────────────────────────────────────────────────────────

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (playerOnScreen && clockMode && clock != null && !cues.isEmpty()) {
                int idx = ClockSyncEngine.cueAt(cues, clock.position());
                if (idx >= 0) showSinhala(cues.get(idx));
                else hideSinhala();
            }
            main.postDelayed(this, 250);
        }
    };

    private void startTicker() {
        main.postDelayed(ticker, 250);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // the fetch pipeline (bg thread) — title → resolve → subtitles → paired cues
    // ───────────────────────────────────────────────────────────────────────────

    private void onNewVideo(final String title, final int season, final int episode) {
        currentTitle = title;
        currentSeason = season;
        currentEpisode = episode;
        resetSync();
        boolean auto = prefs.getBoolean(KEY_AUTO_FETCH, true);
        showStatus("චිත්‍රපටිය හඳුනාගත්තා: " + title + (episode > 0 ? " · S" + Math.max(1, season) + "E" + episode : ""), auto ? "resolving" : "idle");
        if (auto) bg.post(() -> fetchForTitle(title, season, episode, false));
    }

    /** Called by the panel's Go button (title override). */
    void manualFetch(final String title, final int season, final int episode) {
        bg.post(() -> fetchForTitle(title, season, episode, true));
    }

    private void fetchForTitle(String title, int season, int episode, boolean manual) {
        if (fetching) return;
        fetching = true;
        try {
            boolean series = seriesGuess || episode > 0 || season > 0;
            String type = series ? "series" : "movie";

            // 1. the permanent local cache first (instant, offline)
            String key = type + ":t:" + title.toLowerCase(Locale.ROOT) + ":" + season + ":" + episode;
            List<Cue> cached = SubtitleStore.load(this, key);
            if (cached != null && !cached.isEmpty()) {
                applyCues(cached, key, "cached");
                return;
            }

            // 2. resolve → subtitles
            setState("resolving", title);
            Map<String, Object> r = api.resolve(title, series, season, episode);
            if (r == null) {
                setState("not-found", "Cinemeta couldn't match \"" + title + "\" — use the title override in the panel");
                return;
            }
            String imdb = String.valueOf(r.get("imdb"));
            Map<String, Object> job = api.subtitles(type, imdb, season, episode, title);
            String status = String.valueOf(job.get("status"));

            if ("needs-key".equals(status)) {
                setState("needs-key", "සිංහල උපසිරැසි නැහැ + Gemini key එකක් සකසා නැහැ. Worker එකේ `wrangler secret put GEMINI_API_KEY` කරන්න.");
                return;
            }
            if ("not-found".equals(status)) {
                setState("not-found", "මෙම තේමාවට උපසිරැසි හමු නොවීය — Baiscope/Zoom ගොනුවක් තියෙනවා නම් \"Load local .srt\" බාවිතා කරන්න.");
                return;
            }

            String jobId = String.valueOf(job.get("jobId"));
            currentKey = jobId;

            // 3. translating? poll /api/paired until done (202 → 200)
            List<Cue> paired = null;
            for (int poll = 0; poll < 240; poll++) {
                try {
                    paired = api.paired(jobId);
                    break;
                } catch (BridgeApi.HttpException he) {
                    if (he.status != 202) {
                        setState("error", he.getMessage());
                        return;
                    }
                    Map<String, Object> j = Cue.statusOf(he.body);
                    Object done = j.get("done"), total = j.get("total");
                    setState("translating", "Gemini පරිවර්තනය · batch " + done + "/" + total);
                } catch (Exception ex) {
                    setState("error", ex.getMessage());
                    return;
                }
                SystemClock.sleep(4000);
            }
            if (paired == null || paired.isEmpty()) {
                setState("error", "translation did not finish");
                return;
            }
            applyCues(paired, jobId, String.valueOf(job.get("source")));
            SubtitleStore.save(this, key, paired);
        } catch (Exception ex) {
            setState("error", String.valueOf(ex.getMessage()));
        } finally {
            fetching = false;
        }
    }

    private void applyCues(List<Cue> loaded, String key, String how) {
        cues = loaded;
        matcher = new CueMatcher(cues);
        clock = new ClockSyncEngine(SystemClock::elapsedRealtime);
        currentKey = key;
        clockMode = "clock".equals(prefs.getString(KEY_MODE, "auto")) || cueOnly(cues);
        lastShownIdx = -2;
        boolean hasEn = false;
        for (Cue c : cues) if (c.en != null && !c.en.isEmpty()) { hasEn = true; break; }
        setState("ready", cues.size() + " cues · " + (hasEn ? "Cue-Lock" : "Clock-Sync") + " (" + how + ")");
        updateModeButton();
    }

    private static boolean cueOnly(List<Cue> cs) {
        for (Cue c : cs) if (c.en != null && !c.en.isEmpty()) return false;
        return true;
    }

    /** The emergency local loader path (FilePickActivity hands the parsed cues here). */
    void onLocalCues(List<Cue> loaded, String fileName) {
        main.post(() -> {
            cues = loaded;
            matcher = null;              // no English pair → Clock-Sync mode
            clock = new ClockSyncEngine(SystemClock::elapsedRealtime);
            clockMode = true;
            currentKey = "local:" + fileName;
            lastShownIdx = -2;
            setState("local", fileName + " · " + loaded.size() + " cues · Clock-Sync");
            updateModeButton();
        });
    }

    private void resetSync() {
        cues = new ArrayList<>();
        matcher = null;
        clock = null;
        clockMode = false;
        cueMisses = 0;
        lastShownIdx = -2;
        hideSinhala();
    }

    private void setState(String s, String detail) {
        state = s;
        stateDetail = detail == null ? "" : detail;
        main.post(this::showStatus);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // the overlay UI: pill, panel, subtitle view
    // ───────────────────────────────────────────────────────────────────────────

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private WindowManager.LayoutParams overlayParams(int w, int h, int gravity, boolean touchable, boolean focusable) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | (touchable ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        return lp;
    }

    private void buildOverlays() {
        // ── the Sinhala subtitle view (over AnantPlay's own subtitle box) ──
        subView = new TextView(this);
        subView.setTypeface(siFont);
        subView.setGravity(Gravity.CENTER);
        subView.setTextColor(Color.WHITE);
        subView.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        int sp = prefs.getInt(KEY_FONT_SP, 22);
        subView.setTextSize(sp);
        applyMask(subView, prefs.getInt(KEY_MASK_OPACITY, 92));
        subView.setPadding(dp(10), dp(4), dp(10), dp(4));
        subView.setVisibility(View.GONE);

        // ── the edge pill ──
        pill = new FrameLayout(this);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(14));
        pillBg.setColor(0xE6141B2E);
        pillBg.setStroke(dp(1), 0x66FFFFFF);
        pill.setBackground(pillBg);
        pillDot = new TextView(this);
        pillDot.setText("අ");   // "AnantPlay සිංහල"
        pillDot.setTypeface(siFont);
        pillDot.setTextColor(0xFF7FE7FF);
        pillDot.setGravity(Gravity.CENTER);
        pillDot.setTextSize(13);
        pill.addView(pillDot, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pill.setOnTouchListener(new DragAndTap());

        // ── the mini panel ──
        panel = buildPanel();
    }

    private LinearLayout buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF20E1424);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setPadding(dp(16), dp(14), dp(16), dp(14));

        panelTitle = new TextView(this);
        panelTitle.setTextColor(Color.WHITE);
        panelTitle.setTextSize(15);
        panelTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panelTitle.setMaxLines(2);
        p.addView(panelTitle);

        panelStatus = new TextView(this);
        panelStatus.setTextColor(0xFF9FB0D8);
        panelStatus.setTextSize(12.5f);
        panelStatus.setMaxLines(4);
        panelStatus.setPadding(0, dp(4), 0, dp(8));
        p.addView(panelStatus);

        btnMode = new Button(this);
        btnMode.setAllCaps(false);
        btnMode.setTextSize(13);
        btnMode.setOnClickListener(v -> {
            clockMode = !clockMode;
            prefs.edit().putString(KEY_MODE, clockMode ? "clock" : "auto").apply();
            updateModeButton();
            showStatus();
        });
        p.addView(btnMode, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setPadding(0, dp(8), 0, 0);
        String[] labels = {"−1s", "−0.25s", "+0.25s", "+1s"};
        long[] deltas = {-1000, -250, 250, 1000};
        for (int i = 0; i < labels.length; i++) {
            final long d = deltas[i];
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(labels[i]);
            b.setTextSize(12);
            b.setOnClickListener(v -> {
                if (clock != null) {
                    clock.nudgeMs(d);
                    showStatus();
                }
            });
            clockRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        p.addView(clockRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        p.addView(label("අකුරේ ප්‍රමාණය · Font size"));
        SeekBar font = new SeekBar(this);
        font.setMax(18); // 16..34
        font.setProgress(prefs.getInt(KEY_FONT_SP, 22) - 16);
        font.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onStopTrackingTouch(SeekBar s) {
                int sp = s.getProgress() + 16;
                prefs.edit().putInt(KEY_FONT_SP, sp).apply();
                subView.setTextSize(sp);
            }
        });
        p.addView(font);

        p.addView(label("පසුබිම් අඳුන · Mask opacity"));
        SeekBar mask = new SeekBar(this);
        mask.setMax(100);
        mask.setProgress(prefs.getInt(KEY_MASK_OPACITY, 92));
        mask.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(KEY_MASK_OPACITY, s.getProgress()).apply();
                applyMask(subView, s.getProgress());
            }
        });
        p.addView(mask);

        // title override + Go
        final EditText titleIn = new EditText(this);
        titleIn.setHint("title override · නම අතින්");
        titleIn.setTextSize(13);
        titleIn.setSingleLine();
        titleIn.setImeOptions(EditorInfo.IME_ACTION_GO);
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setPadding(0, dp(10), 0, 0);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        seekRow.addView(titleIn, el);
        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText("Go");
        go.setTextSize(12);
        go.setOnClickListener(v -> {
            String t = titleIn.getText().toString().trim();
            if (t.length() >= 2) {
                currentTitle = t;
                resetSync();
                panelOpen = false;
                panel.setVisibility(View.GONE);
                reFocusPanel(false);
                manualFetch(t, currentSeason, currentEpisode);
            }
        });
        titleIn.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { go.performClick(); return true; }
            return false;
        });
        seekRow.addView(go);
        p.addView(seekRow);

        // local file loader
        Button load = new Button(this);
        load.setAllCaps(false);
        load.setText("Load local .srt / .zip");
        load.setTextSize(12);
        load.setOnClickListener(v -> {
            Intent i = new Intent(this, FilePickActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            panelOpen = false;
            panel.setVisibility(View.GONE);
            reFocusPanel(false);
        });
        p.addView(load);

        Button close = new Button(this);
        close.setAllCaps(false);
        close.setText("වසන්න · Close");
        close.setTextSize(12);
        close.setOnClickListener(v -> {
            panelOpen = false;
            panel.setVisibility(View.GONE);
            reFocusPanel(false);
        });
        p.addView(close);
        return p;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF9FB0D8);
        t.setTextSize(11.5f);
        t.setPadding(dp(2), dp(10), 0, dp(2));
        return t;
    }

    private void applyMask(TextView v, int pct) {
        int alpha = Math.max(0, Math.min(100, pct)) * 255 / 100;
        v.setBackgroundColor((alpha << 24)); // black backing — masks AnantPlay's English text
    }

    private void updateModeButton() {
        main.post(() -> {
            if (btnMode == null) return;
            btnMode.setText(clockMode ? "ස්ථිර මාදිලිය: Clock-Sync — Cue-Lock ට මාරු කරන්න"
                                       : "ස්ථිර මාදිලිය: Cue-Lock — Clock-Sync ට මාරු කරන්න");
            if (clockRow != null) clockRow.setVisibility(clockMode ? View.VISIBLE : View.GONE);
        });
    }

    private void showStatus(String detail, String s) {
        stateDetail = detail == null ? "" : detail;
        state = s == null ? state : s;
        main.post(this::showStatus);
    }

    private void showStatus() {
        main.post(() -> {
            if (panelTitle != null) panelTitle.setText(currentTitle.isEmpty() ? "AnantPlay · සිංහල උපසිරැසි" : currentTitle);
            if (panelStatus != null) {
                String dot = "ready".equals(state) || "local".equals(state) || "cached".equals(state) ? "✓ " : "… ";
                panelStatus.setText(dot + stateDetail);
            }
            if (pillDot != null) {
                pillDot.setTextColor("ready".equals(state) || "local".equals(state) || "cached".equals(state) ? 0xFF8CFF8C : 0xFF7FE7FF);
            }
        });
    }

    private void addOverlays() {
        try {
            WindowManager.LayoutParams subLp = overlayParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START, false, false);
            subLp.width = Math.max(dp(280), getResources().getDisplayMetrics().widthPixels * 3 / 4);
            wm.addView(subView, subLp);

            WindowManager.LayoutParams pillLp = overlayParams(
                dp(34), dp(48), Gravity.END | Gravity.CENTER_VERTICAL, true, false);
            wm.addView(pill, pillLp);

            WindowManager.LayoutParams panelLp = overlayParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL, true, true);
            panelLp.width = dp(300);
            wm.addView(panel, panelLp);
            panel.setVisibility(View.GONE);

            overlaysAdded = true;
            pill.setVisibility(View.GONE); // appears when the player appears
        } catch (Throwable t) {
            overlaysAdded = false;
        }
    }

    private void removeOverlays() {
        overlaysAdded = false;
        for (View v : new View[]{pill, panel, subView}) {
            if (v != null) {
                try { wm.removeView(v); } catch (Throwable ignored) { }
            }
        }
    }

    /** While the panel is open it must receive keys (EditText typing) — toggle FLAG_NOT_FOCUSABLE. */
    private void reFocusPanel(boolean focusable) {
        if (panel == null || !overlaysAdded) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) panel.getLayoutParams();
            if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            wm.updateViewLayout(panel, lp);
        } catch (Throwable ignored) { }
    }

    private final Runnable pillFade = new Runnable() {
        @Override public void run() {
            if (pill != null && !panelOpen) pill.animate().alpha(0.30f).setDuration(600).start();
        }
    };

    private void schedulePillFade() {
        main.removeCallbacks(pillFade);
        main.postDelayed(pillFade, 3000);
    }

    /** Pill: tap = open/close the panel; drag vertically = move it out of the way. */
    private class DragAndTap implements View.OnTouchListener {
        private float downY, moveStartY;
        private long downAt;
        private boolean moved;

        @Override public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    moveStartY = lp.y;
                    downAt = SystemClock.uptimeMillis();
                    moved = false;
                    v.animate().cancel();
                    v.setAlpha(0.95f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - downY;
                    if (Math.abs(dy) > dp(6)) moved = true;
                    lp.y = (int) (moveStartY + dy);
                    try { wm.updateViewLayout(v, lp); } catch (Throwable ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    schedulePillFade();
                    if (!moved && SystemClock.uptimeMillis() - downAt < 400) togglePanel();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void togglePanel() {
        panelOpen = !panelOpen;
        if (panelOpen) {
            showStatus();
            updateModeButton();
            panel.setVisibility(View.VISIBLE);
            reFocusPanel(true);
        } else {
            panel.setVisibility(View.GONE);
            reFocusPanel(false);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        haveSubBounds = false; // rotation: re-read the subtitle bounds on the next scan
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
    }
}

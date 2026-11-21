package lk.techworld.anantplay.sicomp;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* Finds the views of official AnantPlay's PlayerActivity in the accessibility tree —
 * with ZERO view IDs (AnantPlay sets none; verified in the Phase 1 audit) and ZERO
 * dependence on AnantPlay's code. Everything is identified structurally, exactly as
 * documented in docs/PHASE1-ANANTPLAY-AUDIT.md §1:
 *
 *   - the player window: package me.champdev.weblock + activity ends with .PlayerActivity
 *     (AccessibilityWindowInfo.getActivity(), API 24+); fallback: a root with a
 *     SurfaceView + a SeekBar (PlayerActivity.java:384, :513)
 *   - the subtitle TextView: bottom ~35% of the screen, centered, direct child of the
 *     root (created :396-401 — OUTSIDE the auto-hiding HUD overlay, so it keeps firing
 *     TYPE_VIEW_TEXT_CHANGED with the HUD hidden)
 *   - the title + episode line: two sibling TextViews in a vertical LinearLayout in the
 *     top bar (:459-487); readable even while GONE (the node tree keeps their text)
 *   - the time TextViews ("1:27", "1:42:00") + the SeekBar (progress in MILLISECONDS,
 *     :1441-1442) in the bottom bar — the Clock-Sync anchors */
final class PlayerViewFinder {

    static final String PLAYER_PACKAGE = "me.champdev.weblock";
    static final String PLAYER_ACTIVITY = ".PlayerActivity";

    static boolean isPlayerPackage(CharSequence pkg) {
        return pkg != null && PLAYER_PACKAGE.contentEquals(pkg);
    }

    /** The structural check on the root node: SurfaceView + SeekBar + the player package. */
    static boolean looksLikePlayerRoot(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        if (!PLAYER_PACKAGE.contentEquals(root.getPackageName())) return false;
        boolean hasSurface = false, hasSeek = false;
        int n = root.getChildCount();
        for (int i = 0; i < n && !(hasSurface && hasSeek); i++) {
            AccessibilityNodeInfo c = root.getChild(i);
            if (c == null || c.getClassName() == null) continue;
            String cls = c.getClassName().toString();
            if (cls.contains("SurfaceView") || cls.contains("TextureView")) hasSurface = true;
            if (cls.contains("SeekBar")) hasSeek = true;
        }
        return hasSurface && hasSeek;
    }

    /** Everything the service needs from one tree walk. */
    static final class PlayerViews {
        AccessibilityNodeInfo subtitle;   // the bottom-center TextView that shows cues
        AccessibilityNodeInfo title;      // the HUD title (readable while GONE)
        AccessibilityNodeInfo epLine;     // "Season 1 · Episode 3 · 1080p · CC English (OpenSubtitles)"
        AccessibilityNodeInfo time;       // "1:27"
        AccessibilityNodeInfo timeTotal;  // "1:42:00"
        AccessibilityNodeInfo seek;       // SeekBar (progress = media ms)
        Rect subtitleBounds = new Rect();
        String titleText = "";
        String epLineText = "";
    }

    /** Walk the root once and collect all player views (null fields when not found). */
    static PlayerViews find(AccessibilityNodeInfo root, int screenW, int screenH) {
        PlayerViews out = new PlayerViews();
        if (root == null) return out;
        walk(root, screenW, screenH, out, 0);
        if (out.subtitle != null) {
            try { out.subtitle.getBoundsInScreen(out.subtitleBounds); } catch (Throwable ignored) { }
        }
        out.titleText = textOf(out.title);
        out.epLineText = textOf(out.epLine);
        return out;
    }

    private static void walk(AccessibilityNodeInfo node, int screenW, int screenH, PlayerViews out, int depth) {
        if (node == null || depth > 8) return;
        CharSequence cls0 = node.getClassName();
        String cls = cls0 == null ? "" : cls0.toString();

        if (out.subtitle == null && "android.widget.TextView".equals(cls)) {
            Rect r = new Rect();
            try { node.getBoundsInScreen(r); } catch (Throwable ignored) { return; }
            if (r.height() > 0 && r.centerY() > screenH * 0.62 && r.width() > 0
                && !hasContentDescription(node)) {
                // the bottom-center TextView = the cue view (subtitle/notice are the only
                // low TextViews; notice is TOP-anchored at 80dp — :413-414 — far above 62%)
                CharSequence t = node.getText();
                if (t != null && t.length() > 0) out.subtitle = node;
            }
        }

        if (out.title == null && "android.widget.TextView".equals(cls)) {
            // title: first TextView inside the two-sibling pair (title + epLine) in the top bar
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null && "android.widget.LinearLayout".equals(String.valueOf(parent.getClassName()))
                && parent.getChildCount() == 2) {
                AccessibilityNodeInfo second = parent.getChild(1);
                Rect r = new Rect();
                try { node.getBoundsInScreen(r); } catch (Throwable ignored) { }
                if (second != null && r.top < screenH * 0.25 && r.top > 0
                    && textOf(node).length() > 0) {
                    out.title = node;
                    out.epLine = second;
                }
            }
        }

        if (out.seek == null && cls.contains("SeekBar")) out.seek = node;

        if ((out.time == null || out.timeTotal == null) && "android.widget.TextView".equals(cls)) {
            CharSequence t = node.getText();
            if (t != null && CLOCK_RE.matcher(t.toString()).matches()) {
                Rect r = new Rect();
                try { node.getBoundsInScreen(r); } catch (Throwable ignored) { }
                if (r.centerY() > screenH * 0.55) {
                    if (out.time == null) out.time = node;
                    else if (out.timeTotal == null) out.timeTotal = node;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), screenW, screenH, out, depth + 1);
        }
    }

    private static final Pattern CLOCK_RE = Pattern.compile("^\\d{1,3}(:\\d{2}){1,2}$");

    static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        return t == null ? "" : t.toString();
    }

    private static boolean hasContentDescription(AccessibilityNodeInfo n) {
        try { return n.getContentDescription() != null; } catch (Throwable e) { return false; }
    }

    /** "1:27" / "1:42:00" → ms (AnantPlay's clock() format — PlayerActivity.java:1454-1457). */
    static long clockToMs(String s) {
        if (s == null) return -1;
        String[] parts = s.trim().split(":");
        if (parts.length < 2 || parts.length > 3) return -1;
        try {
            long h = parts.length == 3 ? Long.parseLong(parts[0]) : 0;
            long m = Long.parseLong(parts[parts.length - 2]);
            long sec = Long.parseLong(parts[parts.length - 1]);
            return (h * 3600 + m * 60 + sec) * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final Pattern SEASON_RE = Pattern.compile("S(?:eason\\s*)?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_RE = Pattern.compile("E(?:pisode\\s*)?(\\d+)", Pattern.CASE_INSENSITIVE);

    /** "Season 1 · Episode 3" / "S01E02" / "Episode 4" → {season, episode}; null if none. */
    static int[] parseSeasonEpisode(String epLineText) {
        if (epLineText == null || epLineText.isEmpty()) return null;
        Matcher s = SEASON_RE.matcher(epLineText);
        Matcher e = EPISODE_RE.matcher(epLineText);
        int season = s.find() ? Integer.parseInt(s.group(1)) : 0;
        int episode = e.find() ? Integer.parseInt(e.group(1)) : 0;
        return season > 0 || episode > 0 ? new int[]{season, episode} : null;
    }

    /** Does the epLine say an English CC track is currently ON? ("CC English (OpenSubtitles)",
     *  "CC English" — refreshChips() PlayerActivity.java:1007). */
    static boolean englishCcOn(String epLineText) {
        if (epLineText == null) return false;
        String t = epLineText.toLowerCase(Locale.ROOT);
        return t.contains("cc") && (t.contains("english") || t.contains("eng"));
    }
}

package lk.techworld.anantplay.sicomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* THE <10 ms CUE-LOCK ENGINE (Sync Mode A).
 *
 * The companion holds the worker's paired {en, si} cues in RAM. Every time AnantPlay's
 * subtitle TextView changes (TYPE_VIEW_TEXT_CHANGED — PlayerActivity.java:349-362
 * showCues()), the displayed (flattened) English line goes in here and the matching
 * Sinhala cue index comes out — no network, no clock, frame-exact by construction.
 *
 * Matching strategy (in order):
 *   1. EXACT: a precomputed HashMap of the normalized flattened English → index. O(1),
 *      handles seeks to anywhere in the movie instantly (a 40-minute jump re-locks on
 *      the very next spoken line).
 *   2. WINDOW: if the exact map misses (a slightly different transcription — the CC the
 *      user has on may come from the stream itself, not the OpenSubtitles file), search
 *      the sequential window [last-2 .. last+8] with trigram similarity; the user's
 *      subtitle stream advances in order, so this is where the next line lives.
 *   3. FULL SCAN: on lock loss (window exhausted), fall back to a clock-hinted scan
 *      (±60 cues around the expected position) and, as the last resort, a full-movie
 *      exact+trigram pass. Only then does the matcher declare "lost".
 *
 * Normalization (applied identically to both sides):
 *   - AnantPlay's own flattening is already in `enf` (Cue.flatten)
 *   - lowercase, strip HTML tags (<i>, <b>, <font …>), strip speaker dashes at line
 *     starts, drop punctuation, collapse whitespace, keep all letters (incl. Sinhala).
 *
 * Plain Java — the verification lab (lab/verify_companion_sync.java) runs this exact
 * class on a bare JDK against the REAL paired JSON from the worker. */
public final class CueMatcher {

    /** Trigram similarity below which a window candidate is rejected. */
    static final double FUZZY_THRESHOLD = 0.55;
    /** Consecutive misses before the lock is declared lost (the service then falls back
     *  to Clock-Sync mode or re-resolves). */
    static final int LOSE_AFTER = 25;

    private final List<Cue> cues;
    private final Map<String, Integer> exact = new HashMap<>();
    private int lastIdx = -1;
    private int misses = 0;
    private boolean lost = false;

    public CueMatcher(List<Cue> cues) {
        this.cues = cues == null ? new ArrayList<Cue>() : cues;
        for (int pos = 0; pos < this.cues.size(); pos++) {
            Cue c = this.cues.get(pos);
            String n = normalize(c.enf);
            c.normenf = n;
            String n2 = normalize(c.en);
            c.normen = n2;
            // map to the LIST POSITION (not c.i) so a malformed paired file with
            // non-sequential i values can never make cues.get(hit) throw
            if (!n.isEmpty() && !exact.containsKey(n)) exact.put(n, pos);
            if (!n2.isEmpty() && !n2.equals(n) && !exact.containsKey(n2)) exact.put(n2, pos);
        }
    }

    public int size() { return cues.size(); }

    public Cue get(int idx) {
        return idx >= 0 && idx < cues.size() ? cues.get(idx) : null;
    }

    public boolean isLost() { return lost; }

    public int lastMatchedIndex() { return lastIdx; }

    /** A seek/pause shock: forget the sequential position but keep the exact map. */
    public void resetLock() {
        lastIdx = -1;
        misses = 0;
        lost = false;
    }

    /** The live (already flattened by AnantPlay) English text → the matched cue, or null.
     *  Order: (1) EXACT inside the sequential window (keeps playback position, prefers the
     *  right copy of a repeated line like "What?"), (2) the exact map — O(1) full-movie
     *  re-lock after ANY seek, (3) fuzzy inside the window (a slightly different
     *  transcription, still sequential), (4) a full-movie fuzzy scan. */
    public Cue match(String liveText) {
        if (cues.isEmpty() || liveText == null) return null;
        String n = normalize(liveText);
        if (n.isEmpty()) return null;

        // 1. exact within the sequential window
        if (lastIdx >= 0) {
            int lo = Math.max(0, lastIdx - 2);
            int hi = Math.min(cues.size() - 1, lastIdx + 8);
            for (int i = lo; i <= hi; i++) {
                if (n.equals(cues.get(i).normenf)) { lastIdx = i; misses = 0; lost = false; return cues.get(i); }
            }
        }

        // 2. exact map — re-locks anywhere in the movie after any seek (40-minute jumps included)
        Integer hit = exact.get(n);
        if (hit != null) {
            lastIdx = hit;
            misses = 0;
            lost = false;
            return cues.get(hit);
        }

        // 3. fuzzy within the sequential window
        if (lastIdx >= 0) {
            int lo = Math.max(0, lastIdx - 2);
            int hi = Math.min(cues.size() - 1, lastIdx + 8);
            int best = -1;
            double bestScore = 0;
            for (int i = lo; i <= hi; i++) {
                double s = similarity(n, cues.get(i));
                if (s > bestScore) { bestScore = s; best = i; }
            }
            if (best >= 0 && bestScore >= FUZZY_THRESHOLD) {
                lastIdx = best;
                misses = 0;
                lost = false;
                return cues.get(best);
            }
        }

        // 4. full-movie fuzzy fallback (e.g. a stream-embedded CC with a different transcription)
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < cues.size(); i++) {
            double s = similarity(n, cues.get(i));
            if (s > bestScore) { bestScore = s; best = i; }
            if (bestScore > 0.95) break; // good enough, stop early
        }
        if (best >= 0 && bestScore >= FUZZY_THRESHOLD) {
            lastIdx = best;
            misses = 0;
            lost = false;
            return cues.get(best);
        }

        // no match: this live line is not in our pair (or the stream's CC differs too much)
        misses++;
        if (misses >= LOSE_AFTER) lost = true;
        return null;
    }

    /** Clock-hinted relock (used when Clock-Sync knows roughly where we are): try the
     *  cues around `positionMs` first — much cheaper than a full scan. */
    public Cue matchNear(long positionMs) {
        if (cues.isEmpty()) return null;
        int lo = 0, hi = cues.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (cues.get(mid).t0 <= positionMs) lo = mid;
            else hi = mid - 1;
        }
        lastIdx = lo;
        misses = 0;
        lost = false;
        return cues.get(lo);
    }

    // ── normalization + trigram similarity ─────────────────────────────────────

    /** Lowercase, strip HTML tags and speaker dashes, drop punctuation, collapse spaces. */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]*>", " ");          // <i>, <b>, <font …>
        StringBuilder sb = new StringBuilder(t.length());
        boolean atLineStart = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n') { atLineStart = true; sb.append(' '); continue; }
            if (atLineStart && (c == '-' || c == '\u2013')) { atLineStart = false; continue; } // speaker dash
            if (!atLineStart || !Character.isWhitespace(c)) atLineStart = false;
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
            else if (c == '\'' || c == '\u2019') { /* keep I'm, don't */ }
            else sb.append(' ');
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** Jaccard similarity over character trigrams — fast and robust for subtitle lines. */
    public static double similarity(String a, Cue cand) {
        String b = cand.normenf != null ? cand.normenf : normalize(cand.enf);
        if (a.equals(b)) return 1.0;
        if (a.length() < 3 || b.length() < 3) return a.equals(b) ? 1.0 : 0.0;
        Set<String> ta = trigrams(a), tb = trigrams(b);
        int inter = 0;
        for (String g : ta) if (tb.contains(g)) inter++;
        int union = ta.size() + tb.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    private static Set<String> trigrams(String s) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 3 <= s.length(); i++) out.add(s.substring(i, i + 3));
        return out;
    }
}

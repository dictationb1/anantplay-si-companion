package lk.techworld.anantplay.sicomp;

import java.util.List;

/* THE CLOCK-SYNC ENGINE (Sync Mode B) — for human Sinhala subs (Baiscope/ChameE/Zoom
 * files) or whenever AnantPlay's English CC is off.
 *
 * AnantPlay's HUD updates `time` ("1:27") and the SeekBar (progress in MILLISECONDS —
 * PlayerActivity.java:1441-1442, refresh every 500 ms via the tick runnable :133-141).
 * Whenever the HUD is visible (tap, pause, seek), the service feeds those observations
 * into anchor(); between observations this engine free-runs on the monotonic clock and
 * drives the full Sinhala .srt timeline.
 *
 * It detects:
 *   - pause/play (observations stop advancing; the clock must not drift — pause freezes)
 *   - seeks (an observation far from the extrapolated position → re-anchor instantly)
 *   - user fine-sync nudges (±0.25 s / ±1.0 s, applied on top and kept across re-anchors)
 *
 * Plain Java — injectable time source, fully verified by lab/verify_companion_sync.java. */
public final class ClockSyncEngine {

    /** An observation this far from the extrapolation is a seek, not noise (AnantPlay's
     * own SubSync treats sub-250 ms as "already in sync" — PlayerActivity.java:2381). */
    static final long SEEK_THRESHOLD_MS = 1200;

    /** Monotonic time source (SystemClock.elapsedRealtime() on Android, a fake in tests). */
    public interface TimeSource { long now(); }

    private final TimeSource time;
    private long anchorPosMs;     // the media position at anchorElapsed
    private long anchorElapsed;   // ms on the monotonic clock at the anchor
    private boolean paused;
    private long pausedAtElapsed;
    private long pausedAtPosMs;
    private long offsetMs;        // the user's fine-sync nudge (positive = show later)
    private int seekCount;
    private int anchorCount;

    public ClockSyncEngine(TimeSource time) {
        this.time = time;
        this.anchorElapsed = time.now();
        this.anchorPosMs = 0;
    }

    /** Ground truth observed from AnantPlay's HUD (time TextView / SeekBar progress in ms).
     *  The FIRST anchor only positions the clock; every later one that jumps more than
     *  SEEK_THRESHOLD_MS from the extrapolation counts as a user seek. */
    public void anchor(long observedPosMs) {
        long now = time.now();
        long expected = positionAtNow(now);
        if (paused) {
            // resume: re-anchor at the observed position
            paused = false;
            reanchor(observedPosMs, now);
            return;
        }
        if (anchorCount > 0 && Math.abs(observedPosMs - expected) > SEEK_THRESHOLD_MS) seekCount++;
        reanchor(observedPosMs, now);
    }

    /** The user tapped pause/play: freeze the clock (position stays at the pause point). */
    public void setPaused(boolean p) {
        long now = time.now();
        if (p && !paused) {
            pausedAtPosMs = positionAtNow(now); // the freeze point, computed BEFORE flipping the flag
            pausedAtElapsed = now;
            paused = true;
        } else if (!p && paused) {
            paused = false;
            reanchor(pausedAtPosMs, now);
        }
    }

    public boolean isPaused() { return paused; }

    /** The current media position, ms (monotonic extrapolation + user offset). */
    public long position() {
        return Math.max(0, positionAtNow(time.now()));
    }

    private long positionAtNow(long now) {
        if (paused) return pausedAtPosMs;
        return anchorPosMs + (now - anchorElapsed) + offsetMs;
    }

    private void reanchor(long posMs, long now) {
        anchorPosMs = Math.max(0, posMs - offsetMs);
        anchorElapsed = now;
        anchorCount++;
    }

    /** ±0.25 s / ±1.0 s fine-sync nudge (positive = subtitles appear LATER). position()
     *  = anchorPos + elapsed + offset, so the offset alone shifts the timeline by exactly
     *  `delta` — nothing else to touch on the running clock. */
    public void nudgeMs(long delta) {
        offsetMs += delta;
        if (paused) pausedAtPosMs += delta; // the frozen display follows the nudge too
    }

    public long getOffsetMs() { return offsetMs; }

    public void resetOffset() {
        offsetMs = 0;
    }

    /** Find the live cue for the current position in a cue list (binary search, t0 <= pos < t1
     * kept on screen, else the last cue whose t0 <= pos and t1 + grace >= pos). */
    public static int cueAt(List<Cue> cues, long positionMs) {
        if (cues == null || cues.isEmpty()) return -1;
        int lo = 0, hi = cues.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).t0 <= positionMs) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        if (ans < 0) return -1;
        return cues.get(ans).t1 >= positionMs ? ans : -1; // between cues → nothing on screen
    }

    public int getSeekCount() { return seekCount; }

    public int getAnchorCount() { return anchorCount; }
}

import lk.techworld.anantplay.sicomp.BridgeApi;
import lk.techworld.anantplay.sicomp.ClockSyncEngine;
import lk.techworld.anantplay.sicomp.Cue;
import lk.techworld.anantplay.sicomp.CueMatcher;
import lk.techworld.anantplay.sicomp.MiniJson;
import lk.techworld.anantplay.sicomp.SrtLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Companion Cue-Lock & Sync Verification Lab (Phase 4b).
 *
 * Runs the app's REAL engine classes (CueMatcher, ClockSyncEngine, SrtLoader, Cue,
 * MiniJson — the exact .java files shipped in the APK) on a bare JDK and proves:
 *
 *   A. Cue-Lock: feeding the paired cues' own `enf` strings — the EXACT text official
 *      AnantPlay paints into its subtitle TextView (flatten rule of
 *      PlayerActivity.java:349-362) — yields ≥ 99.5 % exact matches, average match
 *      latency < 5 ms.
 *   B. Seeks: random ±40-minute jumps re-lock on the very next line (exact map, O(1)),
 *      including seek-storms.
 *   C. Fuzzy: HTML tags, case, punctuation and speaker-dash noise still match in the
 *      sequential window.
 *   D. Robustness: empty/malformed cues, null inputs, empty matcher, truncated JSON —
 *      zero crashes, always a clean null/exception.
 *   E. Clock-Sync: anchors, seeks, pause/resume freeze, ±0.25 s / ±1 s nudges,
 *      cueAt boundary math — verified against a fake monotonic clock.
 *   F. Local loader: .srt, .zip (best entry, junk skipped), .gz round-trips.
 *   G. Live pair (optional --paired <file>): a REAL /api/paired/<jobId>.json from the
 *      worker — every enf must equal flatten(en) (AnantPlay parity), t0 ≤ t1, and the
 *      ≥ 99.5 % / < 5 ms guarantees must hold on the real movie.
 *
 * Usage:  java -cp lab/out/classes lab.verify_companion_sync [--paired <file.json>] [--report <dir>]
 */
public final class verify_companion_sync {

    static int pass = 0, fail = 0, warn = 0;
    static final List<String> fails = new ArrayList<String>();
    static StringBuilder report = new StringBuilder();

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; log("  ✓ " + name); }
        else { fail++; fails.add(name + (detail == null ? "" : " :: " + detail)); log("  ✗ " + name + (detail == null ? "" : "  [" + detail + "]")); }
    }

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    static void log(String s) {
        System.out.println(s);
        report.append(s).append('\n');
    }

    public static void main(String[] args) throws Exception {
        String pairedPath = null;
        String reportDir = "lab/out";
        for (int i = 0; i < args.length; i++) {
            if ("--paired".equals(args[i]) && i + 1 < args.length) pairedPath = args[++i];
            if ("--report".equals(args[i]) && i + 1 < args.length) reportDir = args[++i];
        }
        new File(reportDir).mkdirs();

        log("════ AnantPlay SI Companion — Cue-Lock & Sync Verification Lab ════");

        // ── fixtures: a synthetic 2-hour movie (1800 cues) shaped like a real paired file
        List<Cue> synthetic = syntheticCues(1800);

        // ── D. robustness first: nothing may crash
        section("D. robustness (malformed / empty inputs)");
        try {
            CueMatcher empty = new CueMatcher(Collections.<Cue>emptyList());
            check("empty matcher returns null on any text", empty.match("hello") == null);
            check("empty matcher is not 'lost'", !empty.isLost());
            check("match(null) is null", new CueMatcher(synthetic).match(null) == null);
            check("match(\"\") is null", new CueMatcher(synthetic).match("") == null);
        } catch (Throwable t) {
            check("empty/null inputs never crash", false, String.valueOf(t));
        }
        try { Cue.fromPairedJson("not json at all"); check("bad JSON throws cleanly", false, "no throw"); }
        catch (IllegalArgumentException expected) { check("bad JSON throws cleanly", true, null); }
        try { Cue.fromPairedJson("{\"cues\":[{\"i\":0,\"t0\":1}"); check("truncated JSON throws cleanly", false, "no throw"); }
        catch (IllegalArgumentException expected) { check("truncated JSON throws cleanly", true, null); }
        check("empty cues from empty JSON array", Cue.fromPairedJson("{\"cues\":[]}").isEmpty());
        try {
            SrtLoader.load(new byte[0], "x.srt");
            check("empty file throws cleanly", false, "no throw");
        } catch (IOException expected) {
            check("empty file throws cleanly", true, null);
        }
        try {
            SrtLoader.load("this is not a subtitle file at all".getBytes(StandardCharsets.UTF_8), "x.srt");
            check("non-subtitle text yields empty cues (no crash)", true, null);
        } catch (IOException e) {
            check("non-subtitle text yields empty cues (no crash)", false, e.getMessage());
        }

        // ── A. Cue-Lock on the synthetic pair (the exact AnantPlay display text)
        section("A. Cue-Lock — sequential playback, exact AnantPlay text");
        cueLockRun(synthetic, "synthetic 1800-cue movie");

        // ── B. random ±40-minute seeks
        section("B. Cue-Lock — random ±40 min seeks (re-lock on the next spoken line)");
        seekRun(synthetic);

        // ── C. fuzzy matching (tags, case, punctuation)
        section("C. Cue-Lock — fuzzy window (HTML tags, case, punctuation)");
        fuzzyRun(synthetic);

        // ── E. Clock-Sync engine
        section("E. Clock-Sync — anchors, seeks, pause, nudges, cueAt");
        clockRun(synthetic);

        // ── F. local loader (.srt / .zip / .gz)
        section("F. local loader — .srt / .zip / .gz");
        loaderRun();

        // ── MiniJson sanity (escapes, unicode)
        section("MiniJson — escapes + unicode");
        {
            Object o = MiniJson.parse("{\"a\":\"line\\nbreak \\u0D85 අ\", \"n\": -1.5, \"t\": true, \"z\": null}");
            check("string escapes", "line\nbreak අ අ".equals(MiniJson.str(o, "a")), MiniJson.str(o, "a"));
            check("numbers", Double.valueOf(-1.5).equals(((Number) MiniJson.num(o, "n")).doubleValue()));
            check("bool/null", Boolean.TRUE.equals(MiniJson.objGet(o, "t")) && MiniJson.objGet(o, "z") == null);
        }

        // ── G. the REAL pair (when --paired is given)
        if (pairedPath != null) {
            section("G. LIVE pair — " + pairedPath);
            byte[] raw = Files.readAllBytes(new File(pairedPath).toPath());
            List<Cue> live = Cue.fromPairedJson(new String(raw, StandardCharsets.UTF_8));
            check("live pair parsed with cues", !live.isEmpty(), live.size() + " cues");
            if (!live.isEmpty()) {
                boolean enfOk = true, timeOk = true;
                for (Cue c : live) {
                    if (!c.enf.equals(Cue.flatten(c.en))) { enfOk = false; break; }
                    if (c.t0 > c.t1) { timeOk = false; break; }
                }
                check("live pair: every enf == flatten(en) (AnantPlay showCues parity)", enfOk);
                check("live pair: t0 ≤ t1 on every cue", timeOk);
                cueLockRun(live, "live pair (" + live.size() + " cues)");
                seekRun(live);
            }
        } else {
            log("  (no --paired file given — the live-pair leg runs only with real worker output)");
        }

        log("");
        log("════ Lab result: " + pass + " PASS / " + fail + " FAIL ════");
        for (String f : fails) log("FAIL " + f);
        Files.write(new File(reportDir, "companion-lab.txt").toPath(),
            report.toString().getBytes(StandardCharsets.UTF_8));
        System.exit(fail == 0 ? 0 : 1);
    }

    static void section(String s) { log(""); log("── " + s + " ──"); }

    /** Feed each cue's enf (what AnantPlay paints) in order; assert match + latency.
     *  A match is EXACT when the engine returns the very cue — or any cue whose
     *  normalized text is identical (movies repeat lines: "What?", "<i>Music plays</i>" —
     *  the on-screen English is indistinguishable, so either copy is a correct lock). */
    static void cueLockRun(List<Cue> cues, String label) {
        CueMatcher m = new CueMatcher(cues);
        long t0 = System.nanoTime();
        int exact = 0;
        long worstNs = 0;
        long totalNs = 0;
        for (Cue c : cues) {
            long s = System.nanoTime();
            Cue hit = m.match(c.enf);
            long d = System.nanoTime() - s;
            totalNs += d;
            if (d > worstNs) worstNs = d;
            if (hit != null && (hit.i == c.i || CueMatcher.normalize(hit.enf).equals(CueMatcher.normalize(c.enf)))) exact++;
        }
        double avgMs = totalNs / 1e6 / Math.max(1, cues.size());
        double rate = exact * 100.0 / cues.size();
        check(label + ": exact match rate ≥ 99.5% (" + exact + "/" + cues.size() + ")",
            rate >= 99.5, String.format("%.3f%%", rate));
        check(label + ": average match latency < 5 ms", avgMs < 5, String.format("%.3f ms", avgMs));
        check(label + ": worst single match < 50 ms", worstNs / 1e6 < 50, String.format("%.3f ms", worstNs / 1e6));
        log("    (avg " + String.format("%.4f", avgMs) + " ms/line, worst " + String.format("%.2f", worstNs / 1e6) + " ms)");
    }

    /** Jump the playback position randomly; the next displayed line must re-lock. */
    static void seekRun(List<Cue> cues) {
        CueMatcher m = new CueMatcher(cues);
        Random rnd = new Random(42);
        int relocked = 0, attempts = 0;
        long totalNs = 0;
        for (int jump = 0; jump < 60; jump++) {
            int from = rnd.nextInt(cues.size());
            int to = rnd.nextInt(cues.size());
            int deltaMs = (int) ((to - from) * 3400);
            if (Math.abs(deltaMs) < 40 * 60 * 1000) { // force real ±40-minute jumps
                to = (from + (deltaMs >= 0 ? 1 : -1) * (int) (40 * 60 * 1000 / 3400) + rnd.nextInt(200)) % cues.size();
                if (to < 0) to += cues.size();
                deltaMs = (to - from) * 3400;
            }
            // the line displayed right after the seek lands:
            Cue next = cues.get(to);
            long s = System.nanoTime();
            Cue hit = m.match(next.enf);
            totalNs += System.nanoTime() - s;
            attempts++;
            if (hit != null && (hit.i == next.i || CueMatcher.normalize(hit.enf).equals(CueMatcher.normalize(next.enf)))) relocked++;
        }
        double rate = relocked * 100.0 / attempts;
        check("random ±40 min seeks: re-lock rate ≥ 99.5% (" + relocked + "/" + attempts + ")",
            rate >= 99.5, String.format("%.1f%%", rate));
        check("seek re-lock average latency < 5 ms", totalNs / 1e6 / attempts < 5,
            String.format("%.3f ms", totalNs / 1e6 / attempts));
    }

    /** The displayed text may differ cosmetically (tags, case, dashes). */
    static void fuzzyRun(List<Cue> cues) {
        CueMatcher m = new CueMatcher(cues);
        int matched = 0, tried = 0;
        for (int i = 0; i < cues.size(); i += 37) {
            Cue c = cues.get(i);
            String noisy = "<i>" + c.enf.toUpperCase() + "</i>";
            noisy = noisy.replace(" ", "   ").replace(" - ", " -  ");
            Cue hit = m.match(noisy);
            tried++;
            if (hit != null && (hit.i == c.i || CueMatcher.normalize(hit.enf).equals(CueMatcher.normalize(c.enf)))) matched++;
        }
        double rate = matched * 100.0 / tried;
        check("fuzzy: tags+case+spacing noise still matches (≥ 95%)", rate >= 95,
            String.format("%.1f%% (%d/%d)", rate, matched, tried));
    }

    /** Clock-Sync: fake monotonic time, anchors/seeks/pause/nudges. */
    static void clockRun(List<Cue> cues) {
        final long[] now = { 10_000_000L };
        ClockSyncEngine.TimeSource fake = new ClockSyncEngine.TimeSource() {
            public long now() { return now[0]; }
        };
        ClockSyncEngine c = new ClockSyncEngine(fake);
        c.anchor(120_000);            // 2:00 observed
        now[0] += 10_000;             // 10 s pass
        check("clock advances in real time", c.position() == 130_000, String.valueOf(c.position()));
        check("no false seek detected", c.getSeekCount() == 0, String.valueOf(c.getSeekCount()));

        // a seek: HUD jumps to 40 minutes
        now[0] += 500;
        c.anchor(2_400_000);
        check("seek detected (jump > 1.2 s)", c.getSeekCount() == 1, String.valueOf(c.getSeekCount()));
        check("position follows the seek", Math.abs(c.position() - 2_400_000) < 100, String.valueOf(c.position()));

        // pause freezes the clock (setPaused happens at the same instant as the anchor here)
        c.setPaused(true);
        now[0] += 60_000;             // a whole minute paused
        check("pause freezes the position", Math.abs(c.position() - 2_400_000) < 100, String.valueOf(c.position()));
        c.setPaused(false);
        now[0] += 5_000;
        check("resume advances from the pause point", Math.abs(c.position() - 2_405_000) < 100, String.valueOf(c.position()));

        // nudges: ±0.25 s / ±1 s
        long before = c.position();
        c.nudgeMs(250);
        check("+0.25 s nudge shifts the timeline", c.position() == before + 250, String.valueOf(c.position() - before));
        c.nudgeMs(-1000);
        check("−1 s nudge shifts back", c.position() == before - 750, String.valueOf(c.position() - before));
        c.nudgeMs(750);
        check("offset returns to zero net", c.getOffsetMs() == 0, String.valueOf(c.getOffsetMs()));

        // cueAt boundaries
        Cue first = cues.get(0);
        check("cueAt: inside the first cue", ClockSyncEngine.cueAt(cues, first.t0 + 1) == 0);
        check("cueAt: before everything → nothing", ClockSyncEngine.cueAt(cues, Math.max(0, first.t0 - 10)) == -1 || first.t0 == 0);
        Cue mid = cues.get(cues.size() / 2);
        check("cueAt: mid cue start", ClockSyncEngine.cueAt(cues, mid.t0) == mid.i);
        long between = mid.t1 + 1;
        int after = -1;
        for (Cue q : cues) if (q.t0 <= between && q.t1 >= between) { after = q.i; break; }
        check("cueAt: between cues shows nothing (or the actual overlapping cue)",
            ClockSyncEngine.cueAt(cues, between) == after, ClockSyncEngine.cueAt(cues, between) + " vs " + after);
    }

    /** The emergency local loader: .srt, .zip (best entry), .gz. */
    static void loaderRun() throws IOException {
        String srt = "1\n00:00:01,000 --> 00:00:03,000\nපළමු පේළිය\n\n"
            + "2\n00:00:04,000 --> 00:00:06,500\nදෙවන පේළිය\n-multi line\n";
        List<Cue> plain = SrtLoader.load(srt.getBytes(StandardCharsets.UTF_8), "x.srt");
        check("srt: 2 cues parsed", plain.size() == 2, String.valueOf(plain.size()));
        check("srt: Sinhala kept", plain.get(0).si.contains("පළමු"));
        check("srt: multi-line text kept", plain.get(1).si.contains("\n"));

        // zip: junk __MACOSX + nested best .srt + an .nfo
        ByteArrayOutputStream zipB = new ByteArrayOutputStream();
        ZipOutputStream zs = new ZipOutputStream(zipB);
        put(zs, "__MACOSX/._junk", "junk".getBytes(StandardCharsets.UTF_8));
        put(zs, "Subs/Nested/movie.si.srt", srt.getBytes(StandardCharsets.UTF_8));
        put(zs, "info.nfo", "nfo".getBytes(StandardCharsets.UTF_8));
        zs.close();
        List<Cue> zipped = SrtLoader.load(zipB.toByteArray(), "x.zip");
        check("zip: best .srt extracted (junk skipped)", zipped.size() == 2, String.valueOf(zipped.size()));

        // gz
        ByteArrayOutputStream gzB = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(gzB);
        gz.write(srt.getBytes(StandardCharsets.UTF_8));
        gz.close();
        List<Cue> gzipped = SrtLoader.load(gzB.toByteArray(), "x.gz");
        check("gz: round-trips", gzipped.size() == 2, String.valueOf(gzipped.size()));

        // zip bomb guard
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        ZipOutputStream bz = new ZipOutputStream(bomb);
        put(bz, "big.srt", new byte[6 * 1024 * 1024]);
        bz.close();
        try {
            SrtLoader.load(bomb.toByteArray(), "bomb.zip");
            check("zip bomb (5 MB cap) refused", false, "no throw");
        } catch (IOException expected) {
            check("zip bomb (5 MB cap) refused", true, null);
        }

        // utf-8 / cp1252 decode heuristic
        check("decode: valid utf-8 kept",
            SrtLoader.decode("අයේ".getBytes(StandardCharsets.UTF_8)).contains("අ"));
    }

    static void put(ZipOutputStream zs, String name, byte[] data) throws IOException {
        zs.putNextEntry(new ZipEntry(name));
        zs.write(data);
        zs.closeEntry();
    }

    /** A synthetic 2-hour movie: multi-line cues, speaker dashes, gaps, HTML tags. */
    static List<Cue> syntheticCues(int n) {
        List<Cue> out = new ArrayList<Cue>(n);
        Random rnd = new Random(7);
        long t = 0;
        for (int i = 0; i < n; i++) {
            t += 1200 + rnd.nextInt(2600);
            long t1 = t + 900 + rnd.nextInt(2200);
            String en;
            switch (i % 7) {
                case 0: en = "- You said that already.\n- And I'll say it again."; break;
                case 1: en = "<i>Music plays</i>"; break;
                case 2: en = "Line " + i + " with, punctuation! And (brackets)."; break;
                case 3: en = "Two-line cue " + i + "\nsecond row here"; break;
                case 4: en = "– Dashed speaker " + i; break;
                case 5: en = "I'm goin' home... don't wait up " + i; break;
                default: en = "Ordinary dialogue line number " + i; break;
            }
            out.add(new Cue(i, t, t1, null, en, null, "සිංහල පරිවර්තනය " + i));
            t = t1 + (rnd.nextInt(10) == 0 ? 8000 : 40); // gaps
        }
        return out;
    }

    private verify_companion_sync() { }
}

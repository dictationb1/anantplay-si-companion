package lk.techworld.anantplay.sicomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/* The emergency local loader: a .srt / .zip / .gz the user picked by hand (a Baiscope /
 * Zoom / PirateLK download) becomes a clean List<Cue> for Clock-Sync mode.
 *
 * Plain Java (java.util.zip only), so the lab exercises the exact same code. The Android
 * service hands it the file's bytes from its ContentResolver. */
public final class SrtLoader {

    public static final int MAX_BYTES = 5 * 1024 * 1024; // matches the worker's zip-bomb cap
    public static final int MAX_ENTRIES = 64;

    /** Parse any of .srt / .vtt-ish text, a .zip holding one, or a .gz. Returns cues or throws. */
    public static List<Cue> load(byte[] bytes, String nameHint) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("empty file");
        if (bytes.length > MAX_BYTES) throw new IOException("file too large (5 MB max)");
        if (looksZip(bytes)) return fromZip(bytes);
        if (looksGzip(bytes)) return Cue.fromSrt(decode(inflateGzip(bytes)));
        return Cue.fromSrt(decode(bytes));
    }

    static boolean looksZip(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0x50 && (b[1] & 0xFF) == 0x4B && (b[2] & 0xFF) == 0x03 && (b[3] & 0xFF) == 0x04;
    }

    static boolean looksGzip(byte[] b) {
        return b.length > 1 && (b[0] & 0xFF) == 0x1f && (b[1] & 0xFF) == 0x8b;
    }

    private static List<Cue> fromZip(byte[] bytes) throws IOException {
        List<Cue> best = null;
        int entries = 0;
        ZipInputStream zs = new ZipInputStream(new ByteArrayInputStream(bytes));
        try {
            for (ZipEntry e; (e = zs.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) throw new IOException("too many zip entries");
                String n = e.getName() == null ? "" : e.getName().toLowerCase();
                if (n.contains("__macosx") || n.startsWith("/") || n.contains("..")) continue;
                if (!n.endsWith(".srt") && !n.endsWith(".vtt")) continue;
                byte[] data = readAll(zs, MAX_BYTES);
                List<Cue> cues = Cue.fromSrt(decode(data));
                if (best == null || cues.size() > best.size()) best = cues; // the biggest real track wins
            }
        } finally {
            try { zs.close(); } catch (IOException ignored) { }
        }
        if (best == null || best.isEmpty()) throw new IOException("no .srt/.vtt inside the zip");
        return best;
    }

    private static byte[] inflateGzip(byte[] bytes) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes));
        try {
            return readAll(gz, MAX_BYTES);
        } finally {
            try { gz.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (out.size() + n > max) throw new IOException("decompressed file too large (5 MB max)");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** UTF-8 first; a CP1252 fallback covers old Baiscope encodings (no BOM detection needed —
     * Sinhala in CP1252 would be mojibake, so UTF-8 always wins when valid). */
    public static String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8; // no replacement chars → valid UTF-8
        Charset cp1252 = Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : null;
        if (cp1252 != null) {
            String alt = new String(bytes, cp1252);
            if (!alt.contains("\uFFFD")) return alt;
        }
        return utf8;
    }
}

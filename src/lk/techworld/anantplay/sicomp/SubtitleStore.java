package lk.techworld.anantplay.sicomp;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/* The on-device permanent cache: one paired-cues JSON per title key, under
 * <cacheDir>/subs/<safe-key>.json. A movie fetched once is offline forever (the lab's
 * "cached second fetch" promise, on the phone side). Plain file I/O, bg thread only. */
final class SubtitleStore {

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "subs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, String key) {
        return new File(dir(c), key.replaceAll("[^a-z0-9:_-]", "_") + ".json");
    }

    static void save(Context c, String key, List<Cue> cues) {
        try {
            StringBuilder sb = new StringBuilder(64 * 1024);
            sb.append("{\"status\":\"done\",\"cues\":[");
            for (int i = 0; i < cues.size(); i++) {
                Cue q = cues.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"i\":").append(q.i)
                  .append(",\"t0\":").append(q.t0)
                  .append(",\"t1\":").append(q.t1)
                  .append(",\"en\":").append(jsonStr(q.en))
                  .append(",\"enf\":").append(jsonStr(q.enf))
                  .append(",\"si\":").append(jsonStr(q.si))
                  .append('}');
            }
            sb.append("]}");
            File f = fileFor(c, key);
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            tmp.renameTo(f);
        } catch (IOException ignored) {
            // the cache is best-effort; the live fetch still works without it
        }
    }

    static List<Cue> load(Context c, String key) {
        File f = fileFor(c, key);
        if (!f.exists()) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
                List<Cue> cues = Cue.fromPairedJson(new String(out.toByteArray(), StandardCharsets.UTF_8));
                return cues.isEmpty() ? null : cues;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private SubtitleStore() { }
}

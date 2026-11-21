package lk.techworld.anantplay.sicomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/* The bridge (Cloudflare Worker) client. Pure java.net — no Android classes — so the lab
 * can drive it on a bare JDK too. All calls are BLOCKING: the service runs them on its
 * worker thread, never on the main thread.
 *
 * Endpoints (worker v3):
 *   GET /api/resolve?title=Clayface&type=movie[&season=&episode=]  → {imdb,name,year,…}
 *   GET /subtitles/{movie|series}/{tt…}.json[?season&episode&title] → job status (done |
 *        translating{progress} | needs-key | not-found), si/en token urls, paired path
 *   GET /api/paired/{jobId}.json   → 200 {cues:[{i,t0,t1,en,enf,si}]} | 202 progress
 *   GET /sub/{token}.srt           → 200 SRT text | 202 progress JSON
 *   GET /api/status, /health
 */
public final class BridgeApi {

    public static final String DEFAULT_WORKER_URL = "https://anantplay-si-subs-bridge.techworldlk.workers.dev";

    private final String base;
    private final int timeoutMs;

    public BridgeApi(String baseUrl) {
        String b = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_WORKER_URL : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.timeoutMs = 20000;
    }

    public String base() { return base; }

    public static class HttpException extends IOException {
        public final int status;
        public final String body;
        HttpException(int status, String body) { super("HTTP " + status + ": " + body); this.status = status; this.body = body; }
    }

    // ── typed calls ─────────────────────────────────────────────────────────────

    /** Title → IMDb. Returns null when Cinemeta has no match. */
    public Map<String, Object> resolve(String title, boolean series, int season, int episode) throws IOException {
        String q = enc(title) + "&type=" + (series ? "series" : "movie");
        if (season > 0) q += "&season=" + season;
        if (episode > 0) q += "&episode=" + episode;
        String body = getJson("/api/resolve?title=" + q);
        Map<String, Object> j = Cue.statusOf(body);
        return Boolean.TRUE.equals(j.get("ok")) ? j : null;
    }

    /** The main job call. Returns the parsed body either way (status field says what happened). */
    public Map<String, Object> subtitles(String type, String imdb, int season, int episode, String title) throws IOException {
        String q = "/subtitles/" + type + "/" + imdb + ".json";
        boolean first = true;
        if (season > 0) { q += (first ? "?" : "&") + "season=" + season; first = false; }
        if (episode > 0) { q += (first ? "?" : "&") + "episode=" + episode; first = false; }
        if (title != null && !title.isEmpty()) { q += (first ? "?" : "&") + "title=" + enc(title); first = false; }
        return Cue.statusOf(getJson(q));
    }

    /** The cue-lock payload: 200 → cues; 202 → {status:'translating', progress}; throws on 4xx/5xx. */
    public List<Cue> paired(String jobId) throws IOException {
        String body = getJson("/api/paired/" + jobId.replace(":", "%3A") + ".json");
        Map<String, Object> j = Cue.statusOf(body);
        if (Boolean.TRUE.equals(j.get("ok")) && "done".equals(j.get("status"))) {
            return Cue.fromPairedJson(body);
        }
        throw new HttpException(202, body); // still translating / error — body carries the reason
    }

    /** A raw .srt from the worker (the human-Sinhala file or the English pair). */
    public String srt(String token) throws IOException {
        return new String(getBytes("/sub/" + token + ".srt"), StandardCharsets.UTF_8);
    }

    public Map<String, Object> status() throws IOException {
        return Cue.statusOf(getJson("/api/status"));
    }

    // ── transport ───────────────────────────────────────────────────────────────

    String getJson(String path) throws IOException {
        return new String(getBytes(path), StandardCharsets.UTF_8);
    }

    byte[] getBytes(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Accept", "application/json, text/*, */*");
        c.setRequestProperty("User-Agent", "AnantPlaySiCompanion/1.0");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = readAll(in == null ? new ByteArrayInputStream(new byte[0]) : in, 8 * 1024 * 1024);
            if (code >= 400) throw new HttpException(code, new String(body, StandardCharsets.UTF_8));
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (out.size() + n > max) throw new IOException("response too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}

package lk.techworld.anantplay.sicomp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.util.List;

/* A transparent trampoline: opens the system document picker for .srt/.zip/.gz and
 * hands the parsed cues straight to the running service (its static instance). The
 * emergency path for manually downloaded Baiscope / Zoom / PirateLK files. */
public class FilePickActivity extends Activity {

    private static final int PICK = 41;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/x-subrip", "text/plain", "application/zip", "application/gzip", "application/octet-stream" });
        try {
            startActivityForResult(i, PICK);
        } catch (Exception e) {
            toast("ගොනු තෝරන්නාව විවෘත කළ නොහැකි විය");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK) { finish(); return; }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { finish(); return; }
        final Uri uri = data.getData();
        final String name = nameOf(uri);
        new Thread(() -> {
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("cannot open " + name);
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
                }
                List<Cue> cues = SrtLoader.load(out.toByteArray(), name);
                if (cues.isEmpty()) throw new java.io.IOException("no cues found in " + name);
                SinhalaOverlayService svc = SinhalaOverlayService.instance;
                if (svc != null) {
                    svc.onLocalCues(cues, name);
                    runOnUiThread(() -> toast(name + " · " + cues.size() + " cues ✓"));
                } else {
                    runOnUiThread(() -> toast("සේවාව ක්‍රියාත්මක නැහැ — Accessibility සකසන්න"));
                }
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "read error" : e.getMessage();
                runOnUiThread(() -> toast("දෝෂය: " + msg));
            } finally {
                finish();
            }
        }, "sicomp-filepick").start();
    }

    private String nameOf(Uri uri) {
        String n = uri.getLastPathSegment();
        return n == null ? "local.srt" : n;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}

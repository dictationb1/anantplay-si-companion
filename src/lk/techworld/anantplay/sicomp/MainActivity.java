package lk.techworld.anantplay.sicomp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/* The launcher screen: the one-time setup (enable the accessibility service; on
 * Android 13+ first "Allow restricted settings") and the few settings the companion
 * has — Worker URL (pre-pointed at the live bridge), auto-fetch toggle. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(SinhalaOverlayService.PREFS, Context.MODE_PRIVATE);
        setContentView(buildUi());
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(0xFF0B1020);
        scroll.addView(root);

        TextView h = title("AnantPlay · සිංහල උපසිරැසි සහායකයා", 22);
        root.addView(h);
        root.addView(caption("SI Companion — for the 100% OFFICIAL, UNMODIFIED AnantPlay app. "
            + "This helper never touches AnantPlay itself: it only reads the player screen "
            + "(scoped accessibility) and draws its own Sinhala overlay."));

        // ── step 1: restricted settings (Android 13+) ──
        root.addView(title("1 · (Android 13+ only) Allow restricted settings", 16));
        root.addView(caption("Apps installed from outside a store (like this APK) are blocked from "
            + "Accessibility by Android 13+. One-time unlock:\n"
            + "Settings → Apps → AnantPlay SI Companion → ⋮ (top-right) → \"Allow restricted settings\"."));

        // ── step 2: enable the service ──
        root.addView(title("2 · Enable the service · සේවාව සකසන්න", 16));
        root.addView(caption("Settings → Accessibility → Installed apps → AnantPlay SI Companion → On. "
            + "Then just play any movie in official AnantPlay — the අ pill appears at the right edge."));
        Button open = button("Accessibility settings විවෘත කරන්න", true);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                toast("Cannot open settings");
            }
        });
        root.addView(open);

        root.addView(title("Worker URL", 16));
        root.addView(caption("The Cloudflare Worker that finds human Sinhala subtitles and, when none "
            + "exist (e.g. Clayface), machine-translates the full movie with Gemini Flash. "
            + "The default is already live — change it only if you deploy your own."));
        final EditText url = new EditText(this);
        url.setText(prefs.getString(SinhalaOverlayService.KEY_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL));
        url.setTextSize(13);
        url.setBackground(rounded(0xFF0E1526));
        url.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(url);

        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        Button autoBtn = button("Auto-fetch: " + (autoOn() ? "ON ✓" : "OFF"), false);
        autoBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean(SinhalaOverlayService.KEY_AUTO_FETCH, !autoOn()).apply();
            autoBtn.setText("Auto-fetch: " + (autoOn() ? "ON ✓" : "OFF"));
        });
        toggles.addView(autoBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toggles);

        Button save = button("සුරකින්න · Save", true);
        save.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            prefs.edit().putString(SinhalaOverlayService.KEY_WORKER_URL, u).apply();
            toast("සුරැකිණි ✓ — worker: " + (u.isEmpty() ? BridgeApi.DEFAULT_WORKER_URL : u));
        });
        root.addView(save);

        root.addView(title("How it works · ක්‍රියාත්මක වන ආකාරය", 16));
        TextView how = caption(
            "1. Play anything in official AnantPlay (e.g. Clayface on OnlyFlix).\n"
          + "2. The අ pill appears at the right edge and fades to 30% after 3 s — tap it anytime.\n"
          + "3. The companion reads the title from the player screen (zero typing), asks the "
          + "worker for Sinhala subs, and — for AI-translated titles — asks you to turn on "
          + "AnantPlay's own English CC once (player → Subtitles → English).\n"
          + "4. Sync Mode A (Cue-Lock): each English line AnantPlay paints is matched in RAM to "
          + "its paired Sinhala line — same frame, <10 ms, works with the player HUD hidden. "
          + "Our Sinhala box sits exactly on top of AnantPlay's English box, so you never see "
          + "double subtitles.\n"
          + "5. Sync Mode B (Clock-Sync): for human Sinhala subs (Baiscope/ChameE/Zoom) the "
          + "companion follows the player's own clock; ±0.25 s / ±1 s nudges are in the pill's panel.\n"
          + "6. No Sinhala anywhere? Load a local .srt/.zip from the panel, or set "
          + "GEMINI_API_KEY on your worker to let it translate.");
        how.setMovementMethod(new ScrollingMovementMethod());
        root.addView(how);

        root.addView(caption(String.format(Locale.US,
            "Companion v%s · zero fork: official AnantPlay (me.champdev.weblock) stays untouched.",
            AppInfo.VERSION_NAME)));
        return scroll;
    }

    private boolean autoOn() {
        return prefs.getBoolean(SinhalaOverlayService.KEY_AUTO_FETCH, true);
    }

    private TextView title(String text, float sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private TextView caption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF9FB0D8);
        t.setTextSize(13f);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(primary ? 0xFF3B82F6 : 0xFF1A2440));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

package app.ottotv.connect.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.ottotv.connect.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The home screen's safety net. Android always comes back to the home
 * screen, so one that keeps crashing leaves the TV on a black screen with
 * no way out. After two crashes in two minutes, the home screen opens as a
 * plain list of apps instead, drawn with Android's own widgets, with a
 * button to try the full one again.
 */
final class SafeHome {
    private static final String PREFS = "crashes";
    private static final long WINDOW_MS = 120_000;
    private static boolean installed;

    private SafeHome() {
    }

    /** Counts this process's crashes from now on. */
    static void install(Context c) {
        if (installed) {
            return;
        }
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                record(app, System.currentTimeMillis());
            } catch (Throwable ignored) {
                // the crash itself matters more
            }
            if (previous != null) {
                previous.uncaughtException(t, e);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    static void record(Context c, long now) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // commit, not apply: the process is about to die.
        p.edit().putLong("before", p.getLong("last", 0)).putLong("last", now).commit();
    }

    /** Whether the last two crashes came within two minutes of now. */
    static boolean looping(Context c, long now) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long before = p.getLong("before", 0);
        return before > 0 && now - before < WINDOW_MS && now >= before;
    }

    static void reset(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** Fills the activity with the plain list. */
    static void show(Activity a, Runnable retry) {
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(48 * dp);
        list.setPadding(pad, Math.round(32 * dp), pad, Math.round(32 * dp));

        TextView title = new TextView(a);
        title.setText(R.string.safe_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        list.addView(title);
        TextView note = new TextView(a);
        note.setText(R.string.safe_note);
        note.setTextColor(0xB3FFFFFF);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        note.setPadding(0, Math.round(8 * dp), 0, Math.round(16 * dp));
        list.addView(note);

        Button again = button(a, a.getString(R.string.safe_retry), null, dp);
        again.setOnClickListener(v -> retry.run());
        list.addView(again);

        PackageManager pm = a.getPackageManager();
        for (ResolveInfo ri : launchable(a)) {
            Drawable icon = null;
            try {
                icon = ri.loadIcon(pm);
            } catch (RuntimeException | OutOfMemoryError e) {
                // a button without an icon
            }
            Button b = button(a, String.valueOf(ri.loadLabel(pm)), icon, dp);
            String pkg = ri.activityInfo.packageName;
            String name = ri.activityInfo.name;
            b.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_MAIN).setClassName(pkg, name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    a.startActivity(i);
                } catch (ActivityNotFoundException | SecurityException e) {
                    // gone since the list was made
                }
            });
            list.addView(b);
        }

        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        scroll.addView(list);
        a.setContentView(scroll);
        again.requestFocus();
    }

    private static Button button(Activity a, String text, Drawable icon, float dp) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        if (icon != null) {
            int s = Math.round(40 * dp);
            icon.setBounds(0, 0, s, s);
            b.setCompoundDrawablesRelative(icon, null, null, null);
            b.setCompoundDrawablePadding(Math.round(16 * dp));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(4 * dp);
        b.setLayoutParams(lp);
        return b;
    }

    /** Every app with a TV or phone entry point, once each, by name. */
    private static List<ResolveInfo> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : categories) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> found;
            try {
                found = pm.queryIntentActivities(i, 0);
            } catch (RuntimeException e) {
                continue;
            }
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(c.getPackageName()) && seen.add(pkg)) {
                    out.add(ri);
                }
            }
        }
        Collator byName = Collator.getInstance();
        out.sort((x, y) -> byName.compare(String.valueOf(x.loadLabel(pm)), String.valueOf(y.loadLabel(pm))));
        return out;
    }
}

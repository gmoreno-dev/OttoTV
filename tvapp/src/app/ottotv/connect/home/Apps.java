package app.ottotv.connect.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.provider.Settings;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The installed apps the launcher shows: TV apps (LEANBACK_LAUNCHER) first,
 * phone apps (LAUNCHER) too, each with its banner or icon drawn once at the
 * size the cards need and the color that covers most of it. Sorted by name.
 * Runs off the UI thread; drawings are kept between reloads.
 */
final class Apps {
    /** Tests put a ready list here. */
    static volatile List<AppInfo> fake;

    private static final Map<String, AppInfo> CACHE = new HashMap<>();

    private Apps() {
    }

    static List<AppInfo> load(Context c, Ui ui) {
        List<AppInfo> f = fake;
        if (f != null) {
            return new ArrayList<>(f);
        }
        PackageManager pm = c.getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(c.getPackageName()) && !found.containsKey(pkg)) {
                    found.put(pkg, ri);
                }
            }
        }
        // Android TV's settings only answer the settings intent, not a launcher one.
        ResolveInfo settings = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        if (settings != null && settings.activityInfo != null
                && !"android".equals(settings.activityInfo.packageName)
                && !found.containsKey(settings.activityInfo.packageName)) {
            found.put(settings.activityInfo.packageName, settings);
        }

        int bannerW = ui.px(388), bannerH = ui.px(218), iconSize = ui.px(120);
        List<AppInfo> apps = new ArrayList<>();
        synchronized (CACHE) {
            Map<String, AppInfo> keep = new HashMap<>();
            for (ResolveInfo ri : found.values()) {
                String key;
                try {
                    PackageInfo pi = pm.getPackageInfo(ri.activityInfo.packageName, 0);
                    key = ri.activityInfo.packageName + "/" + ri.activityInfo.name + "@" + pi.lastUpdateTime
                            + ":" + bannerW + ":" + Locale.getDefault();
                } catch (PackageManager.NameNotFoundException e) {
                    continue; // removed while we looked
                }
                AppInfo a = CACHE.get(key);
                if (a == null) {
                    a = describe(pm, ri, bannerW, bannerH, iconSize);
                }
                if (a != null) {
                    keep.put(key, a);
                    apps.add(a);
                }
            }
            CACHE.clear();
            CACHE.putAll(keep);
        }
        sort(apps);
        return apps;
    }

    static void sort(List<AppInfo> apps) {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
    }

    private static AppInfo describe(PackageManager pm, ResolveInfo ri, int bw, int bh, int is) {
        try {
            String pkg = ri.activityInfo.packageName;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            CharSequence label = ri.loadLabel(pm);
            Drawable bannerArt = ri.activityInfo.loadBanner(pm);
            Drawable iconArt = ri.loadIcon(pm);
            Bitmap banner = bannerArt == null ? null : draw(bannerArt, bw, bh);
            Bitmap icon = iconArt == null ? null : draw(iconArt, is, is);
            int main;
            if (bannerArt != null) {
                main = Tone.main(pixels(draw(bannerArt, 64, 36)));
            } else if (iconArt != null) {
                main = Tone.main(pixels(draw(iconArt, 48, 48)));
            } else {
                main = 0x606E86;
            }
            boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            return new AppInfo(AppInfo.APP, pkg, cn, label == null ? pkg : label.toString().trim(),
                    banner, icon, system, main);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null; // one broken app shouldn't take the home screen down
        }
    }

    static Bitmap draw(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    static int[] pixels(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return px;
    }

    static AppInfo find(List<AppInfo> apps, String pkg) {
        if (pkg == null) {
            return null;
        }
        for (AppInfo a : apps) {
            if (pkg.equals(a.pkg)) {
                return a;
            }
        }
        return null;
    }
}

package app.ottotv.connect.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the launcher remembers: the favorites, in the user's order, and the
 * apps opened last (most recent first) for "Continue".
 */
final class Store {
    private static final int RECENT = 8;

    private final SharedPreferences prefs;

    Store(Context c) {
        prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    List<String> favorites() {
        return list("favorites");
    }

    void setFavorites(List<String> pkgs) {
        prefs.edit().putString("favorites", TextUtils.join(",", pkgs)).putBoolean("seeded", true).apply();
    }

    /** False until the first favorites are picked (a fresh system starts with a few). */
    boolean seeded() {
        return prefs.getBoolean("seeded", false);
    }

    List<String> recent() {
        return list("recent");
    }

    void opened(String pkg) {
        List<String> r = recent();
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > RECENT) {
            r.remove(r.size() - 1);
        }
        prefs.edit().putString("recent", TextUtils.join(",", r)).apply();
    }

    /** Usage events already read, so each one is only counted once. */
    long usageReadUntil() {
        return prefs.getLong("usageUntil", 0);
    }

    void setUsageReadUntil(long t) {
        prefs.edit().putLong("usageUntil", t).apply();
    }

    private List<String> list(String key) {
        String s = prefs.getString(key, "");
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) {
            out.addAll(Arrays.asList(s.split(",")));
        }
        return out;
    }
}

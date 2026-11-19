package app.ottotv.connect.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.ottotv.connect.R;

/**
 * Updates from the TV itself. Every few hours this looks at the latest
 * release on GitHub (the repository otto-remote names), has otto-remote
 * check its signed manifest (otto-tv-update.json) and, when it is newer,
 * offers it in the quick panel. Installing streams the update archive from
 * GitHub straight into otto-remote, which checks it against the manifest
 * and installs it as root (remote/update). The new version runs after a
 * restart.
 *
 * Android does the networking here (DNS, TLS, proxies); otto-remote only
 * installs what the signature vouches for.
 */
final class Updates {
    static final int NONE = 0;
    static final int AVAILABLE = 1;
    static final int DOWNLOADING = 2;
    static final int INSTALLING = 3;
    static final int DONE = 4;
    static final int FAILED = 5;

    private static final String TAG = "OttoUpdates";
    private static final String MANIFEST = "otto-tv-update.json";
    private static final long EVERY = 6 * 3600_000L;
    private static final long RETRY = 10 * 60_000L;

    /** The quick panel, while it shows. */
    interface Listener {
        void onUpdates();
    }

    // Read on the main thread; written there too (set).
    static int state = NONE;
    /** The version on offer (or installed, when DONE). */
    static String version = "";
    /** 0 to 100 while DOWNLOADING. */
    static int progress;
    /** Why it FAILED: network, download, signature, live, not_newer, busy or install. */
    static String error = "";
    static Listener listener;

    private static JSONObject manifest;
    private static String archiveURL;
    private static long checkAt;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updates() {
    }

    /** Looks for an update, if it's been a while. */
    static void check(Context c) {
        if (state != NONE && state != AVAILABLE) {
            return;
        }
        // Tests set the state themselves.
        if (Remote.fake != null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < checkAt) {
            return;
        }
        checkAt = now + EVERY;
        Context app = c.getApplicationContext();
        IO.execute(() -> look(app));
    }

    private static void look(Context app) {
        try {
            JSONObject info = Remote.request("GET", "/app/update", null, 3000);
            if (info == null) {
                retrySoon();
                return;
            }
            String installed = info.optString("installed");
            if (!installed.isEmpty()) {
                MAIN.post(() -> set(DONE, installed));
                return;
            }
            String repo = info.optString("repo");
            if (repo.isEmpty() || info.optBoolean("live")) {
                return; // no updates here: a live system, or a build without a repository
            }
            JSONObject release = fetchJSON("https://api.github.com/repos/" + repo + "/releases/latest");
            JSONArray assets = release.optJSONArray("assets");
            String manifestURL = asset(assets, MANIFEST);
            if (manifestURL == null) {
                return;
            }
            JSONObject m = fetchJSON(manifestURL);
            JSONObject answer = Remote.request("POST", "/app/update/check", m, 5000);
            String url = asset(assets, m.optString("file"));
            if (answer == null || !answer.optBoolean("newer") || url == null) {
                return;
            }
            MAIN.post(() -> {
                if (state == NONE || state == AVAILABLE) {
                    manifest = m;
                    archiveURL = url;
                    set(AVAILABLE, m.optString("version"));
                    tell(app);
                }
            });
        } catch (IOException | JSONException | RuntimeException e) {
            Log.i(TAG, "no update: " + e);
            retrySoon();
        }
    }

    private static void retrySoon() {
        checkAt = SystemClock.elapsedRealtime() + RETRY;
    }

    /** The quick panel's button: download and install (or try again). */
    static void install() {
        if ((state != AVAILABLE && state != FAILED) || manifest == null || archiveURL == null) {
            return;
        }
        JSONObject m = manifest;
        String url = archiveURL;
        progress = 0;
        set(DOWNLOADING, version);
        IO.execute(() -> {
            String err = stream(m, url);
            MAIN.post(() -> {
                if (err == null) {
                    set(DONE, m.optString("version"));
                } else {
                    error = err;
                    set(FAILED, version);
                }
            });
        });
    }

    /** From GitHub into otto-remote, without a file in between. Null when installed. */
    private static String stream(JSONObject m, String url) {
        long size = m.optLong("size");
        HttpURLConnection from = null;
        HttpURLConnection to = null;
        try {
            from = open(url, 30_000);
            if (from.getResponseCode() != 200) {
                return "network";
            }
            to = (HttpURLConnection) new URL(Remote.BASE + "/app/update/install").openConnection();
            to.setConnectTimeout(2000);
            // otto-remote answers once it has installed the update.
            to.setReadTimeout(180_000);
            to.setRequestMethod("POST");
            to.setDoOutput(true);
            to.setUseCaches(false);
            to.setFixedLengthStreamingMode(size);
            to.setRequestProperty("Content-Type", "application/x-tar");
            to.setRequestProperty("Otto-Manifest", m.toString());
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                byte[] buf = new byte[64 << 10];
                long done = 0;
                int n;
                int shown = 0;
                while (done < size && (n = in.read(buf, 0, (int) Math.min(buf.length, size - done))) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    int p = (int) (done * 100 / size);
                    if (p != shown) {
                        shown = p;
                        MAIN.post(() -> {
                            if (state == DOWNLOADING) {
                                progress = p;
                                changed();
                            }
                        });
                    }
                }
                if (done < size) {
                    return "network"; // and closing the upload short tells otto-remote too
                }
            }
            MAIN.post(() -> set(INSTALLING, version));
            int code = to.getResponseCode();
            if (code / 100 == 2) {
                return null;
            }
            JSONObject answer = readJSON(to.getErrorStream());
            return answer == null ? "install" : answer.optString("error", "install");
        } catch (IOException | JSONException e) {
            Log.i(TAG, "update failed: " + e);
            return "network";
        } finally {
            if (from != null) {
                from.disconnect();
            }
            if (to != null) {
                to.disconnect();
            }
        }
    }

    private static void set(int s, String v) {
        state = s;
        version = v;
        changed();
    }

    private static void changed() {
        if (listener != null) {
            listener.onUpdates();
        }
    }

    /** Once per version, a notice on the home screen. */
    private static void tell(Context app) {
        SharedPreferences p = app.getSharedPreferences("updates", Context.MODE_PRIVATE);
        if (version.equals(p.getString("told", "")) || HomeActivity.shown == null) {
            return;
        }
        p.edit().putString("told", version).apply();
        HomeActivity.shown.showToast(Icons.DOWNLOAD, app.getString(R.string.update_found),
                app.getString(R.string.update_found_sub, version));
    }

    // ---- HTTP -----------------------------------------------------------------------

    private static HttpURLConnection open(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(true); // release files redirect to GitHub's storage
        c.setRequestProperty("User-Agent", "OttoTV");
        c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "*/*");
        return c;
    }

    private static JSONObject fetchJSON(String url) throws IOException, JSONException {
        HttpURLConnection c = open(url, 15_000);
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException(url + ": HTTP " + c.getResponseCode());
            }
            JSONObject j = readJSON(c.getInputStream());
            if (j == null) {
                throw new IOException(url + ": empty");
            }
            return j;
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject readJSON(InputStream in) throws IOException, JSONException {
        if (in == null) {
            return null;
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                if (out.size() > 512 << 10) {
                    throw new IOException("answer too long");
                }
                out.write(buf, 0, n);
            }
            return out.size() == 0 ? null : new JSONObject(out.toString("UTF-8"));
        }
    }

    private static String asset(JSONArray assets, String name) {
        if (assets == null || name == null || name.isEmpty()) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && name.equals(a.optString("name"))) {
                String url = a.optString("browser_download_url");
                return url.startsWith("https://") ? url : null;
            }
        }
        return null;
    }
}

package app.ottotv.connect.home;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to otto-remote, the service on the TV itself (remote/, running as
 * root): it knows the phones, where the sound goes and the laptop screen,
 * and does what an app may not (switch the sound, restart, turn off). Its
 * /app/ routes only answer this app (it checks which app is calling).
 */
final class Remote {
    static final String BASE = "http://127.0.0.1:8686";

    /** Tests answer in otto-remote's place. */
    interface Fake {
        JSONObject answer(String method, String path, JSONObject body);
    }

    static volatile Fake fake;

    /** The last status the home screen read: the quick panel lays out with it at once. */
    static volatile Status last;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The answer, on the UI thread; null when otto-remote couldn't be reached. */
    interface Done {
        void done(JSONObject answer);
    }

    /** What the home screen shows about the TV. */
    static final class Status {
        final boolean reachable;
        final int phones;
        final boolean paired;
        /** "tv", "notebook" or "" (no choice: a desktop, or no TV sound found). */
        final String audio;
        /** "on", "off" or "" (no laptop screen). */
        final String laptop;
        final String ssid;

        Status(JSONObject j) {
            reachable = j != null;
            phones = j == null ? 0 : j.optInt("phones");
            paired = j != null && j.optBoolean("paired");
            audio = j == null ? "" : j.optString("audio");
            laptop = j == null ? "" : j.optString("laptop");
            ssid = j == null ? "" : j.optString("ssid");
        }
    }

    private Remote() {
    }

    static void get(String path, Done done) {
        call("GET", path, null, 3000, done);
    }

    static void post(String path, JSONObject body, Done done) {
        call("POST", path, body, 15000, done);
    }

    static JSONObject body(String key, Object value) {
        JSONObject j = new JSONObject();
        try {
            j.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return j;
    }

    private static void call(String method, String path, JSONObject body, int timeout, Done done) {
        IO.execute(() -> {
            JSONObject answer = null;
            try {
                Fake f = fake;
                answer = f != null ? f.answer(method, path, body) : request(method, path, body, timeout);
            } catch (IOException | JSONException | RuntimeException e) {
                answer = null;
            }
            final JSONObject result = answer;
            if (done != null) {
                MAIN.post(() -> done.done(result));
            }
        });
    }

    static JSONObject request(String method, String path, JSONObject body, int timeout)
            throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setConnectTimeout(1000);
            c.setReadTimeout(timeout);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            }
            if (c.getResponseCode() / 100 != 2) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < (1 << 20)) {
                    out.write(buf, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }
}

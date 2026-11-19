package app.ottotv.connect;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Shows the "Connect phone" screen that otto-remote serves on the TV, which
 * is also Otto TV's first-run screen (Wi-Fi first, then the phone). All the UI
 * lives in that page; this app gives it a launcher tile, a full screen and a
 * small bridge to system settings. If the service is still starting, it
 * keeps retrying.
 *
 * The helpers are static nested classes, not lambdas or inner classes: the
 * build has no Gradle and no desugaring, and the d8 in build-tools 34 crashes
 * on the parameter metadata recent javac writes for inner class constructors.
 */
public class ConnectActivity extends Activity {
    static final String PAGE = "http://127.0.0.1:8686/tv";
    static final long RETRY_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean paused;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF1A2130);
        web.getSettings().setJavaScriptEnabled(true);
        // The page calls OttoApp.close() once a phone has connected.
        web.addJavascriptInterface(new Bridge(this), "OttoApp");
        web.setWebViewClient(new Client(handler, new Reload(web)));
        setContentView(web);
        web.loadUrl(PAGE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (paused) {
            // Back from Wi-Fi settings: the page may now have a QR code to show.
            paused = false;
            web.reload();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    /** Retries while otto-remote is still starting. */
    static final class Client extends WebViewClient {
        private final Handler handler;
        private final Runnable reload;

        Client(Handler handler, Runnable reload) {
            this.handler = handler;
            this.reload = reload;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                WebResourceError error) {
            if (request.isForMainFrame()) {
                view.loadUrl("about:blank");
                handler.postDelayed(reload, RETRY_MS);
            }
        }
    }

    static final class Reload implements Runnable {
        private final WebView web;

        Reload(WebView web) {
            this.web = web;
        }

        @Override
        public void run() {
            web.loadUrl(PAGE);
        }
    }

    static final class Close implements Runnable {
        private final Activity activity;

        Close(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            activity.finish();
        }
    }

    /** Opens a system settings screen, falling back to Settings itself. */
    static final class OpenSettings implements Runnable {
        private final Activity activity;
        private final String action;

        OpenSettings(Activity activity, String action) {
            this.activity = activity;
            this.action = action;
        }

        @Override
        public void run() {
            try {
                activity.startActivity(new Intent(action));
            } catch (ActivityNotFoundException e) {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }

    /** What the page may call: close this screen or open Wi-Fi/Bluetooth settings. */
    static final class Bridge {
        private final Activity activity;

        Bridge(Activity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void close() {
            activity.runOnUiThread(new Close(activity));
        }

        @JavascriptInterface
        public void openWifi() {
            activity.runOnUiThread(new OpenSettings(activity, Settings.ACTION_WIFI_SETTINGS));
        }

        @JavascriptInterface
        public void openBluetooth() {
            activity.runOnUiThread(new OpenSettings(activity, Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }
}

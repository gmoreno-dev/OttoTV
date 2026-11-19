package app.ottotv.connect;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Shows the "Connect phone" screen that otto-remote serves on the TV, which
 * is also Otto TV's first-run screen (Wi-Fi first, then the phone). All the UI
 * lives in that page; this app gives it a launcher tile, a full screen and a
 * small bridge to system settings. If the service is still starting, it
 * keeps retrying.
 *
 * The WebView takes long to start the first time: 17 seconds of black screen
 * on the first boot of 0.4.4. So Otto ("Loading...") shows first, the
 * WebView is made once he is on screen, under him, and he goes away when
 * the page has loaded.
 *
 * The helpers are static nested classes, not lambdas or inner classes: the
 * build has no Gradle and no desugaring, and the d8 in build-tools 34 crashes
 * on the parameter metadata recent javac writes for inner class constructors.
 */
public class ConnectActivity extends Activity {
    static final String PAGE = "http://127.0.0.1:8686/tv";
    static final long RETRY_MS = 1500;
    /** In case the window never reports focus. */
    static final long START_ANYWAY_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private View waiting;
    private WebView web;
    private boolean paused;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Waiting.NIGHT);
        waiting = Waiting.view(this, R.drawable.loading, R.string.loading_title, 0);
        root.addView(waiting, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        handler.postDelayed(new Start(this), START_ANYWAY_MS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && web == null) {
            // Otto is on screen: now the slow part.
            handler.post(new Start(this));
        }
    }

    void start() {
        if (web != null || isFinishing()) {
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(Waiting.NIGHT);
        web.getSettings().setJavaScriptEnabled(true);
        // The page calls OttoApp.close() once a phone has connected.
        web.addJavascriptInterface(new Bridge(this), "OttoApp");
        web.setWebViewClient(new Client(this, handler, new Reload(web)));
        root.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(PAGE);
    }

    /** The page has loaded: Otto steps aside. */
    void reveal() {
        if (waiting != null) {
            root.removeView(waiting);
            waiting = null;
            web.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (paused && web != null) {
            // Back from Wi-Fi settings: the page may now have a QR code to show.
            paused = false;
            web.reload();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    static final class Start implements Runnable {
        private final ConnectActivity activity;

        Start(ConnectActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            activity.start();
        }
    }

    /** Retries while otto-remote is still starting; reveals the page once it loads. */
    static final class Client extends WebViewClient {
        private final ConnectActivity activity;
        private final Handler handler;
        private final Runnable reload;
        private boolean failed;

        Client(ConnectActivity activity, Handler handler, Runnable reload) {
            this.activity = activity;
            this.handler = handler;
            this.reload = reload;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (url.startsWith(PAGE)) {
                failed = false;
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!failed && url.startsWith(PAGE)) {
                activity.reveal();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                WebResourceError error) {
            if (request.isForMainFrame()) {
                failed = true;
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

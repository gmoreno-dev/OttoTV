package app.ottotv.connect.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import app.ottotv.connect.ConnectActivity;
import app.ottotv.connect.R;

/**
 * Quick notices from otto-remote, over whatever is on screen (it runs
 *   am broadcast -n app.ottotv.connect/.home.ToastReceiver --es kind phone):
 *   phone        a phone was just paired
 *   screen_off   the laptop screen went dark (phone remote or Fn key)
 *   screen_on    and lit again
 *   closed       Alt+F4 closed an app (--es app <package>)
 * Only senders allowed to change secure settings get in (root, the shell).
 *
 * In the quick panel or on the home screen the notice joins them; over
 * other apps it is a small window of its own, in the top right corner
 * (init-hooks.sh lets this app draw over others).
 */
public class ToastReceiver extends BroadcastReceiver {
    private static final long SHOWN_MS = ToastView.SHOWN_MS + 400;

    @Override
    public void onReceive(Context context, Intent intent) {
        String kind = intent.getStringExtra("kind");
        if (kind == null) {
            return;
        }
        String icon, title, sub = null;
        switch (kind) {
            case "phone":
                // "Connect phone" says it on its own screen.
                if (ConnectActivity.visible) {
                    return;
                }
                icon = Icons.PHONE;
                title = context.getString(R.string.toast_phone);
                sub = context.getString(R.string.toast_phone_sub);
                break;
            case "screen_off":
                icon = Icons.LAPTOP_OFF;
                title = context.getString(R.string.toast_screen_off);
                sub = context.getString(R.string.toast_screen_off_sub);
                break;
            case "screen_on":
                icon = Icons.LAPTOP;
                title = context.getString(R.string.toast_screen_on);
                break;
            case "closed":
                icon = Icons.CLOSE_APP;
                title = context.getString(R.string.toast_closed);
                sub = label(context, intent.getStringExtra("app"));
                break;
            default:
                return;
        }
        if (PanelActivity.shown != null) {
            PanelActivity.shown.showToast(icon, title, sub);
        } else if (HomeActivity.shown != null) {
            HomeActivity.shown.showToast(icon, title, sub);
        } else {
            overlay(context, icon, title, sub, goAsync());
        }
    }

    private static String label(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Over other apps: a window that can't be focused or touched, gone after 3 s. */
    private static void overlay(Context context, String icon, String title, String sub, PendingResult pending) {
        Context app = context.getApplicationContext();
        Ui ui = Ui.of(app);
        WindowManager wm = app.getSystemService(WindowManager.class);
        ToastView toast = new ToastView(app, ui);
        FrameLayout frame = new FrameLayout(app);
        frame.setClipChildren(false);
        frame.setPadding(ui.px(40), ui.px(40), ui.px(40), ui.px(40));
        frame.setClipToPadding(false);
        frame.addView(toast, new FrameLayout.LayoutParams(-2, -2));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ui.px(ToastView.WIDTH + 80), ui.px(ToastView.height(true) + 120),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = ui.left + ui.px(1204 - 40);
        lp.y = ui.top + ui.px(130 - 40);
        lp.setTitle("Otto TV notice");
        Handler main = new Handler(Looper.getMainLooper());
        try {
            wm.addView(frame, lp);
        } catch (RuntimeException e) {
            // Not allowed to draw over other apps (yet): Android's own toast.
            Toast.makeText(app, sub == null ? title : title + "\n" + sub, Toast.LENGTH_LONG).show();
            pending.finish();
            return;
        }
        toast.show(icon, title, sub);
        main.postDelayed(() -> {
            try {
                wm.removeView(frame);
            } catch (RuntimeException e) {
                // already gone
            }
            pending.finish();
        }, SHOWN_MS);
    }
}

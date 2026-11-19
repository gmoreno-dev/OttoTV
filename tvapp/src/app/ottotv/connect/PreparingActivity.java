package app.ottotv.connect;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * "Almost ready": what a first boot shows between the end of the boot
 * animation and the first-run screen. Android has the home screen up by
 * then, but it stays empty for a minute or more while the user is unlocked
 * and the system sets things up, and an empty screen looks stuck.
 *
 * init-hooks.sh starts it when the boot animation ends (directBootAware: it
 * may open before Android unlocks the user, unlike the rest of the app) and
 * sends READY once boot complete is done. It also goes away when anything
 * covers it, the "Connect phone" screen above all, on Back, and after
 * fifteen minutes whatever happens.
 */
public class PreparingActivity extends Activity {
    static final String READY = "app.ottotv.connect.READY";
    static final long GIVE_UP_MS = 15 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver ready = new Ready(this);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(Waiting.view(this, R.drawable.preparing, R.string.preparing_title, R.string.preparing_text));
        handler.postDelayed(new ConnectActivity.Close(this), GIVE_UP_MS);
        IntentFilter filter = new IntentFilter(READY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ready, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(ready, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Covered by the first-run screen (or anything else): its job is done.
        // Not when Android recreates it for a configuration change: on the
        // first boot of 0.4.4 that came 2 seconds in (new overlays after the
        // user unlocked), and closed it right there.
        if (!isChangingConfigurations()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(ready);
        super.onDestroy();
    }

    static final class Ready extends BroadcastReceiver {
        private final Activity activity;

        Ready(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            activity.finish();
        }
    }
}

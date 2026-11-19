package app.ottotv.connect;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF161B22);

        ImageView otto = new ImageView(this);
        otto.setImageResource(R.drawable.preparing);
        box.addView(otto, new LinearLayout.LayoutParams(dp(200), dp(200)));

        TextView title = text(R.string.preparing_title, 34, 0xFFF4F6F9);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        box.addView(title, wrap(dp(12)));
        box.addView(text(R.string.preparing_text, 18, 0xFFA9B3C1), wrap(dp(8)));

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(0xFFF2B25C));
        LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT);
        barSize.topMargin = dp(28);
        box.addView(bar, barSize);

        setContentView(box);
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
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(ready);
        super.onDestroy();
    }

    private TextView text(int res, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

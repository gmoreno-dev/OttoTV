package app.ottotv.connect;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The app's waiting screen: Otto, a title, a line of text and a moving bar,
 * centered on the night background. "Almost ready" on a first boot, and
 * "Connect phone" while its page starts.
 */
final class Waiting {
    static final int NIGHT = 0xFF161B22;

    private Waiting() {
    }

    /** text may be 0 for none. */
    static View view(Activity a, int image, int title, int text) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(NIGHT);

        ImageView otto = new ImageView(a);
        otto.setImageResource(image);
        box.addView(otto, new LinearLayout.LayoutParams(dp(a, 200), dp(a, 200)));

        TextView t = text(a, title, 34, 0xFFF4F6F9);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        box.addView(t, wrap(dp(a, 12)));
        if (text != 0) {
            box.addView(text(a, text, 18, 0xFFA9B3C1), wrap(dp(a, 8)));
        }

        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(0xFFF2B25C));
        LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(dp(a, 320), LinearLayout.LayoutParams.WRAP_CONTENT);
        barSize.topMargin = dp(a, 28);
        box.addView(bar, barSize);
        return box;
    }

    private static TextView text(Activity a, int res, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}

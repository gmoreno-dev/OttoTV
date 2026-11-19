package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * The design's tokens, and the scale that maps it onto the screen.
 *
 * The launcher is drawn in the design's own units, a 1920x1080 TV screen
 * (960x540 dp at 2x), and every size goes through {@link #px} on its way to
 * the screen: on 1366x768 the same layout comes out at 0.71x, so nothing
 * moves around between a TV and a laptop panel.
 */
final class Ui {
    static final float W = 1920f;
    static final float H = 1080f;

    // Colors, from the design's token list.
    static final int BG = 0xFF161B22;
    static final int TEXT = 0xFFF4F6F9;
    static final int TEXT2 = 0xFFA9B3C1;
    static final int DIM = 0xFF7F8A99;
    static final int HEAD = 0xFFC9D1DB;
    static final int SOFT = 0xFFD3DAE3;
    static final int ICON = 0xFFDCE2E9;
    static final int ACCENT = 0xFFF2B25C;
    static final int ON_ACCENT = 0xFF15191F;
    static final int GAP = 0xFF0B0E12;
    static final int PANEL = 0xFF1D232C;
    static final int ITEM = 0xFF262D38;
    static final int ITEM_FOCUS = 0xFF2F3744;
    static final int ON = 0xFFE3E8EE;
    static final int ON_FOCUS = 0xFFF4F6F9;
    static final int ON_TEXT = 0xFF151A21;
    static final int ON_SUB = 0xFF4A5361;
    static final int GLOW = 0x52F2B25C; // the accent at 0.32
    static final int TOAST = 0xFF252C37;
    static final int TOAST_ICON = 0xFF36404E;
    static final int TOAST_SUB = 0xFFB4BECB;

    /** Animations: 150 to 200 ms, transform and opacity only. */
    static final int FAST = 160;
    static final int MOVE = 180;
    static final int SLOW = 200;

    private static Typeface base;

    final float k;
    /** Where the 1920x1080 stage starts on the screen (letterboxing). */
    final int left;
    final int top;
    final Context context;

    Ui(Context context, int width, int height) {
        this.context = context;
        k = Math.min(width / W, height / H);
        left = Math.round((width - W * k) / 2);
        top = Math.round((height - H * k) / 2);
    }

    static Ui of(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return new Ui(c, Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels));
    }

    int px(float design) {
        return Math.round(design * k);
    }

    float f(float design) {
        return design * k;
    }

    /**
     * Inter, the brand's typeface: a variable font, so one file gives every
     * weight. Paints pick the weight and the optical size (tighter letters
     * for big titles), like browsers do with font-optical-sizing: auto.
     */
    static Typeface font(Context c) {
        if (base == null) {
            try {
                base = Typeface.createFromAsset(c.getAssets(), "inter.ttf");
            } catch (RuntimeException e) {
                base = Typeface.SANS_SERIF;
            }
        }
        return base;
    }

    /** The variation settings for text of this size (design px) and weight. */
    static String axes(float size, int weight) {
        float opsz = Math.max(14, Math.min(32, size));
        return "'wght' " + weight + ", 'opsz' " + opsz;
    }

    /** Sets a paint's type: Inter at a size in design px and a weight from 500 to 800. */
    void type(Paint p, float size, int weight) {
        p.setTypeface(font(context));
        p.setTextSize(f(size));
        p.setFontVariationSettings(axes(size, weight));
    }

    /** A one-line label in the design's type: size in design px, letter spacing in em. */
    TextView text(CharSequence s, float size, int weight, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, f(size));
        t.setTypeface(font(context));
        t.setFontVariationSettings(axes(size, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** Paragraph text: wraps, with a line height in design px. */
    TextView paragraph(CharSequence s, float size, int weight, int color, float lineHeight) {
        TextView t = text(s, size, weight, color);
        t.setSingleLine(false);
        t.setGravity(Gravity.TOP);
        t.setLineHeight(px(lineHeight));
        return t;
    }

    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0xFFFFFF);
    }

    static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

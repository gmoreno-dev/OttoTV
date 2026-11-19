package app.ottotv.connect.home;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Drawing pieces the design repeats: the focus ring (amber border, a glow,
 * and on cards a dark gap so it shows even around a white banner), soft
 * shadows and the top sheen. All on the UI thread.
 */
final class Draw {
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF R = new RectF();

    private Draw() {
    }

    /** CSS blur radius to Android's shadow radius (Skia: sigma = 0.57735 r + 0.5). */
    static float blur(float cssBlur) {
        return Math.max(0.01f, (cssBlur / 2f - 0.5f) / 0.57735f);
    }

    static Paint paint() {
        P.reset();
        P.setAntiAlias(true);
        return P;
    }

    static void round(Canvas c, RectF r, float radius, int color) {
        Paint p = paint();
        p.setColor(color);
        c.drawRoundRect(r, radius, radius, p);
    }

    /** A box-shadow under r: offset dy, CSS blur, color with its alpha. */
    static void shadow(Canvas c, RectF r, float radius, float dy, float cssBlur, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        Paint p = paint();
        p.setColor(0xFF000000 | (color & 0xFFFFFF));
        p.setAlpha(Math.max(1, (color >>> 24)));
        p.setShadowLayer(blur(cssBlur), 0, dy, color);
        // The shape itself is covered by whatever is drawn on top of it.
        c.drawRoundRect(r, radius, radius, p);
    }

    /**
     * The focus ring around box (radius = box's corner radius), all sizes in
     * screen pixels: the ring's outer edge sits `inset` outside the box, its
     * border is `border` wide, and with gap the space between box and border
     * is filled dark.
     */
    static void ring(Canvas c, RectF box, float radius, float inset, float border, float glow,
            boolean gap, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        Paint p = paint();
        p.setStyle(Paint.Style.STROKE);
        if (gap && inset > border) {
            float w = inset - border;
            R.set(box);
            R.inset(-w / 2, -w / 2);
            p.setStrokeWidth(w);
            p.setColor(Ui.alpha(Ui.GAP, alpha));
            c.drawRoundRect(R, radius + w / 2, radius + w / 2, p);
        }
        float mid = inset - border / 2;
        R.set(box);
        R.inset(-mid, -mid);
        p.setStrokeWidth(border);
        p.setColor(Ui.alpha(Ui.ACCENT, alpha));
        if (glow > 0) {
            p.setShadowLayer(blur(glow), 0, 0, Ui.alpha(Ui.ACCENT, 0.32f * alpha));
        }
        c.drawRoundRect(R, radius + mid, radius + mid, p);
        p.clearShadowLayer();
    }

    /** The light from the top on a focused card: white 12% fading out by 55%. */
    static void sheen(Canvas c, RectF r, float radius, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        Paint p = paint();
        p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f,
                Ui.alpha(0xFFFFFF, 0.12f * alpha), 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
    }

    /** A vertical two-color gradient fill, like the design's system tiles. */
    static void vertical(Canvas c, RectF r, float radius, int top, int bottom) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
    }

    /** Fits text into width with an ellipsis. */
    static String fit(String s, TextPaint p, float width) {
        if (p.measureText(s) <= width) {
            return s;
        }
        return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
    }

    /** Baseline that centers a line of text of this paint on y. */
    static float centerBaseline(Paint p, float y) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return y - (fm.ascent + fm.descent) / 2;
    }
}

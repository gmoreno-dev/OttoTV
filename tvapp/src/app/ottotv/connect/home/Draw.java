package app.ottotv.connect.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drawing pieces the design repeats: the focus ring (amber border, a glow,
 * and on cards a dark gap so it shows even around a white banner), soft
 * shadows and the top sheen. All on the UI thread.
 *
 * Shadows and the ring's glow are blurs, which old GPUs (Intel HD 4000)
 * pay for on every frame, and again at every size while a card grows on
 * focus. So each is blurred once, on the CPU, into an alpha mask kept in
 * MASKS, and drawn as a tinted bitmap: a plain texture for the GPU.
 */
final class Draw {
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint MASK = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF R = new RectF();
    private static final int MAX_MASKS = 48;
    private static final Map<String, Mask> MASKS = new LinkedHashMap<>(64, 0.75f, true);

    /** A blurred shape as an alpha bitmap, the shape's box at (pad, pad) in it. */
    static final class Mask {
        final Bitmap bitmap;
        final float pad;

        Mask(Bitmap bitmap, float pad) {
            this.bitmap = bitmap;
            this.pad = pad;
        }
    }

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
        Mask m = mask(r.width(), r.height(), radius, 0, blur(cssBlur));
        draw(c, m, r.left, r.top + dy, color);
    }

    /**
     * The blurred mask of a w x h round rect (stroke 0: filled; else a
     * border that wide, centered on the rect's edge), cached by size.
     */
    static Mask mask(float w, float h, float radius, float stroke, float blurRadius) {
        int iw = Math.max(1, Math.round(w));
        int ih = Math.max(1, Math.round(h));
        String key = iw + "x" + ih + "r" + Math.round(radius * 4) + "s" + Math.round(stroke * 4)
                + "b" + Math.round(blurRadius * 4);
        Mask m = MASKS.get(key);
        if (m != null) {
            return m;
        }
        // Skia's blur reaches about 3 sigma, sigma = 0.57735 radius + 0.5.
        float pad = (float) Math.ceil(3 * (0.57735f * blurRadius + 0.5f) + stroke / 2 + 1);
        int bw = iw + Math.round(2 * pad);
        int bh = ih + Math.round(2 * pad);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
        Canvas mc = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setMaskFilter(new BlurMaskFilter(Math.max(0.01f, blurRadius), BlurMaskFilter.Blur.NORMAL));
        if (stroke > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
        }
        mc.drawRoundRect(new RectF(pad, pad, pad + iw, pad + ih), radius, radius, p);
        m = new Mask(b, pad);
        MASKS.put(key, m);
        if (MASKS.size() > MAX_MASKS) {
            MASKS.remove(MASKS.keySet().iterator().next());
        }
        return m;
    }

    /** Draws a mask with its shape's box at (left, top), in color (with its alpha). */
    static void draw(Canvas c, Mask m, float left, float top, int color) {
        MASK.setColor(color);
        c.drawBitmap(m.bitmap, left - m.pad, top - m.pad, MASK);
    }

    static int masksForTest() {
        return MASKS.size();
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
        if (glow > 0) {
            Mask m = mask(R.width(), R.height(), radius + mid, border, blur(glow));
            draw(c, m, R.left, R.top, Ui.alpha(Ui.ACCENT, 0.32f * alpha));
            p = paint();
            p.setStyle(Paint.Style.STROKE);
        }
        p.setStrokeWidth(border);
        p.setColor(Ui.alpha(Ui.ACCENT, alpha));
        c.drawRoundRect(R, radius + mid, radius + mid, p);
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

package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * The screen behind everything: night blue, lit from the top right with the
 * focused app's color, which cross-fades when the focus moves. Darkened
 * from the left and fading to plain night blue at the bottom, so text and
 * the lower rows always sit on calm ground.
 */
final class Backdrop extends View {
    private static final int NIGHT = 0x161B22;

    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Anim mix = new Anim(1, Ui.SLOW);
    private Layer current;
    private Layer previous;
    private Shader across;
    private Shader down;

    Backdrop(Context c, Ui ui) {
        super(c);
        this.ui = ui;
    }

    /** rgb: the tint (Tone.SCREEN), soft: weaker, for Otto's welcome. */
    void setTint(int rgb, boolean soft, boolean animate) {
        if (current != null && current.rgb == (rgb & 0xFFFFFF) && current.soft == soft) {
            return;
        }
        previous = animate ? current : null;
        current = new Layer(ui, rgb & 0xFFFFFF, soft);
        mix.set(0, false);
        mix.set(1, animate);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int c = NIGHT;
        across = new LinearGradient(ui.left, 0, ui.left + ui.f(Ui.W), 0,
                new int[] {Ui.alpha(c, 0.78f), Ui.alpha(c, 0.34f), Ui.alpha(c, 0)},
                new float[] {0, 0.38f, 0.62f}, Shader.TileMode.CLAMP);
        down = new LinearGradient(0, ui.top, 0, ui.top + ui.f(Ui.H),
                new int[] {Ui.alpha(c, 0), Ui.alpha(c, 0.86f), Ui.alpha(c, 1)},
                new float[] {0.26f, 0.46f, 0.63f}, Shader.TileMode.CLAMP);
        if (current != null) {
            current.shaders = null;
        }
        if (previous != null) {
            previous.shaders = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF000000 | NIGHT);
        float m = mix.get();
        if (previous != null && m < 1) {
            draw(c, previous, 1);
        }
        if (current != null) {
            draw(c, current, m);
        }
        paint.setAlpha(255);
        paint.setShader(across);
        c.drawPaint(paint);
        paint.setShader(down);
        c.drawPaint(paint);
        paint.setShader(null);
        if (mix.running()) {
            postInvalidateOnAnimation();
        } else {
            previous = null;
        }
    }

    private void draw(Canvas c, Layer l, float alpha) {
        if (l.shaders == null) {
            l.shaders = l.make();
        }
        paint.setAlpha(Math.round(alpha * 255));
        for (Shader s : l.shaders) {
            paint.setShader(s);
            c.drawPaint(paint);
        }
    }

    /** One app's light: two soft ellipses, from the design's radial gradients. */
    static final class Layer {
        final Ui ui;
        final int rgb;
        final boolean soft;
        Shader[] shaders;

        Layer(Ui ui, int rgb, boolean soft) {
            this.ui = ui;
            this.rgb = rgb;
            this.soft = soft;
        }

        Shader[] make() {
            float k = soft ? 0.6f : 1;
            // radial-gradient(1200px 820px at 80% 10%, c .8, c .22 52%, c 0 78%)
            Shader a = ellipse(0.80f * Ui.W, 0.10f * Ui.H, 1200, 820,
                    new int[] {Ui.alpha(rgb, 0.8f * k), Ui.alpha(rgb, 0.22f * k), Ui.alpha(rgb, 0)},
                    new float[] {0, 0.52f, 0.78f});
            // radial-gradient(1000px 640px at 16% -10%, c .3, c 0 70%)
            Shader b = ellipse(0.16f * Ui.W, -0.10f * Ui.H, 1000, 640,
                    new int[] {Ui.alpha(rgb, 0.3f * k), Ui.alpha(rgb, 0)},
                    new float[] {0, 0.70f});
            return new Shader[] {a, b};
        }

        private Shader ellipse(float cx, float cy, float rx, float ry, int[] colors, float[] stops) {
            RadialGradient g = new RadialGradient(0, 0, ui.f(rx), colors, stops, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1, ry / rx);
            m.postTranslate(ui.left + ui.f(cx), ui.top + ui.f(cy));
            g.setLocalMatrix(m);
            return g;
        }
    }
}

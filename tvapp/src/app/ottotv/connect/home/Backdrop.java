package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * The screen behind everything: night blue, lit from the top right with the
 * focused app's color, which cross-fades when the focus moves. Darkened
 * from the left and fading to plain night blue at the bottom, so text and
 * the lower rows always sit on calm ground.
 *
 * That is five full-screen gradients, and old GPUs (Intel HD 4000) can't
 * fill the screen five times on every frame of every card's animation. So
 * the scene is drawn once per color into a GPU layer, and a frame only
 * copies it: two scenes take turns, the new one fading in over the old.
 */
final class Backdrop extends FrameLayout {
    private static final int NIGHT = 0x161B22;

    private final Scene[] scenes = new Scene[2];
    private int front;

    Backdrop(Context c, Ui ui) {
        super(c);
        for (int i = 0; i < 2; i++) {
            scenes[i] = new Scene(c, ui);
            scenes[i].setLayerType(LAYER_TYPE_HARDWARE, null);
            scenes[i].setVisibility(i == 0 ? VISIBLE : INVISIBLE);
            addView(scenes[i], new LayoutParams(-1, -1));
        }
    }

    /** rgb: the tint (Tone.SCREEN), soft: weaker, for Otto's welcome. */
    void setTint(int rgb, boolean soft, boolean animate) {
        Scene now = scenes[front];
        rgb &= 0xFFFFFF;
        if (now.has(rgb, soft)) {
            return;
        }
        if (!animate || !now.tinted()) {
            now.animate().cancel();
            now.setTint(rgb, soft);
            now.setAlpha(1);
            now.setVisibility(VISIBLE);
            scenes[1 - front].setVisibility(INVISIBLE);
            return;
        }
        // The other scene takes the new color and fades in on top.
        front = 1 - front;
        Scene next = scenes[front];
        Scene old = now;
        old.animate().cancel();
        old.setAlpha(1);
        old.setTranslationZ(0);
        next.animate().cancel();
        next.setTint(rgb, soft);
        next.setTranslationZ(1);
        next.setAlpha(0);
        next.setVisibility(VISIBLE);
        next.animate().alpha(1).setDuration(Ui.SLOW).setInterpolator(new LinearInterpolator())
                .withEndAction(() -> old.setVisibility(INVISIBLE)).start();
    }

    /** One color's scene: the night, the app's light and the two shades. */
    static final class Scene extends View {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private int rgb = -1;
        private boolean soft;
        private Shader[] light;
        private Shader across;
        private Shader down;

        Scene(Context c, Ui ui) {
            super(c);
            this.ui = ui;
        }

        boolean tinted() {
            return rgb >= 0;
        }

        boolean has(int rgb, boolean soft) {
            return this.rgb == rgb && this.soft == soft;
        }

        void setTint(int rgb, boolean soft) {
            this.rgb = rgb;
            this.soft = soft;
            light = null;
            invalidate(); // the layer is drawn again, once
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
            light = null;
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF000000 | NIGHT);
            if (rgb >= 0) {
                if (light == null) {
                    light = light();
                }
                for (Shader s : light) {
                    paint.setShader(s);
                    c.drawPaint(paint);
                }
            }
            paint.setShader(across);
            c.drawPaint(paint);
            paint.setShader(down);
            c.drawPaint(paint);
            paint.setShader(null);
        }

        /** The app's light: two soft ellipses, from the design's radial gradients. */
        private Shader[] light() {
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

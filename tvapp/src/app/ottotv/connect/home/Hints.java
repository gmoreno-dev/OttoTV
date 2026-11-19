package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/** The small hints at the bottom of the home screen. */
final class Hints {
    private Hints() {
    }

    /** "[F1] Shortcuts": a key cap and what it does. Clicking it does it. */
    static final class Key extends View {
        private final Ui ui;
        private final String key;
        private final String label;
        private final TextPaint keyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Key(Context c, Ui ui, String key, String label) {
            super(c);
            this.ui = ui;
            this.key = key;
            this.label = label;
            ui.type(keyPaint, 24, 700);
            keyPaint.setColor(Ui.HEAD);
            keyPaint.setTextAlign(Paint.Align.CENTER);
            ui.type(labelPaint, 24, 500);
            labelPaint.setColor(Ui.DIM);
            setContentDescription(key + " " + label);
        }

        private float chip() {
            return Math.max(ui.f(46), keyPaint.measureText(key) + ui.f(20));
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) Math.ceil(chip() + ui.f(10) + labelPaint.measureText(label)), ui.px(36));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            r.set(0, 0, chip(), h);
            Draw.round(c, r, ui.f(10), Ui.alpha(0xFFFFFF, 0.08f));
            c.drawText(key, r.centerX(), Draw.centerBaseline(keyPaint, h / 2), keyPaint);
            c.drawText(label, r.right + ui.f(10), Draw.centerBaseline(labelPaint, h / 2), labelPaint);
        }
    }

    /** Move mode: "<-> Arrows to move, Enter to finish", outlined in amber. */
    static final class Move extends View {
        private final Ui ui;
        private final String text;
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Move(Context c, Ui ui, String text) {
            super(c);
            this.ui = ui;
            this.text = text;
            ui.type(paint, 24, 600);
            paint.setColor(Ui.TEXT);
            setContentDescription(text);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) Math.ceil(ui.f(18 + 26 + 14 + 26) + paint.measureText(text)), ui.px(50));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float b = ui.f(2);
            r.set(b / 2, b / 2, w - b / 2, h - b / 2);
            Draw.round(c, r, h / 2, Ui.ITEM);
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(b);
            p.setColor(Ui.ACCENT);
            c.drawRoundRect(r, h / 2 - b / 2, h / 2 - b / 2, p);
            float is = ui.f(26);
            Icons.draw(c, Icons.MOVE, ui.f(18), (h - is) / 2, is, 2.2f, Ui.TEXT, false);
            c.drawText(text, ui.f(18) + is + ui.f(14), Draw.centerBaseline(paint, h / 2), paint);
        }
    }

    /** No favorites yet: a dashed box with Otto peeking out of the laptop. */
    static final class Empty extends View {
        private final Ui ui;
        private final android.graphics.Bitmap otto;
        private final String text;
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();

        Empty(Context c, Ui ui, android.graphics.Bitmap otto, String text) {
            super(c);
            this.ui = ui;
            this.otto = otto;
            this.text = text;
            ui.type(paint, 32, 700);
            paint.setLetterSpacing(-0.015f);
            paint.setColor(Ui.TEXT);
            setContentDescription(text);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float b = ui.f(2);
            r.set(b / 2, b / 2, w - b / 2, h - b / 2);
            Draw.round(c, r, ui.f(22), Ui.alpha(0xFFFFFF, 0.02f));
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(b);
            p.setColor(Ui.alpha(0xFFFFFF, 0.18f));
            p.setPathEffect(new android.graphics.DashPathEffect(new float[] {ui.f(7), ui.f(5)}, 0));
            c.drawRoundRect(r, ui.f(22), ui.f(22), p);
            p.setPathEffect(null);
            float is = ui.f(176), gap = ui.f(36);
            String s = Draw.fit(text, paint, w - is - gap - ui.f(80));
            float tw = paint.measureText(s);
            float x = (w - (is + gap + tw)) / 2;
            r.set(x, (h - is) / 2, x + is, (h + is) / 2);
            if (otto != null) {
                c.drawBitmap(otto, null, r, bitmapPaint);
            }
            c.drawText(s, x + is + gap, Draw.centerBaseline(paint, h / 2), paint);
        }
    }
}

package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * Text laid out like the design's CSS: a line box of a fixed height with
 * the text centered in it, never clipped (big titles' accents reach above
 * their line box, as on the web). One line, cut with an ellipsis at
 * maxWidth, or wrapped into lines when wrap is set.
 */
final class Label extends View {
    private final Ui ui;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float lineHeight;
    private String text;
    private float maxWidth = Float.MAX_VALUE;
    private float wrapWidth;
    private StaticLayout layout;
    private int align = 0; // 0 start, 1 center

    Label(Context c, Ui ui, String text, float size, int weight, int color, float lineHeight) {
        super(c);
        this.ui = ui;
        this.text = text;
        this.lineHeight = lineHeight;
        ui.type(paint, size, weight);
        paint.setColor(color);
        setContentDescription(text);
    }

    Label tracking(float em) {
        paint.setLetterSpacing(em);
        return this;
    }

    Label maxWidth(float design) {
        maxWidth = ui.f(design);
        return this;
    }

    /** Wraps into lines of at most this width (design px). */
    Label wrap(float design) {
        wrapWidth = ui.f(design);
        layout = null;
        return this;
    }

    Label center() {
        align = 1;
        return this;
    }

    Label features(String f) {
        paint.setFontFeatureSettings(f);
        return this;
    }

    void setText(String t) {
        if (!t.equals(text)) {
            text = t;
            layout = null;
            setContentDescription(t);
            requestLayout();
            invalidate();
        }
    }

    void setColor(int color) {
        if (paint.getColor() != color) {
            paint.setColor(color);
            invalidate();
        }
    }

    String text() {
        return text;
    }

    int lines() {
        return wrapWidth > 0 ? layout().getLineCount() : 1;
    }

    /** Height in design px, for laying out what comes below. */
    float designHeight() {
        return lineHeight * lines();
    }

    private StaticLayout layout() {
        if (layout == null) {
            Paint.FontMetrics fm = paint.getFontMetrics();
            float natural = fm.descent - fm.ascent;
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.round(wrapWidth))
                    .setAlignment(align == 1 ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(ui.f(lineHeight) - natural, 1)
                    .setIncludePad(false)
                    .build();
        }
        return layout;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w;
        if (wrapWidth > 0) {
            StaticLayout l = layout();
            float widest = 0;
            for (int i = 0; i < l.getLineCount(); i++) {
                widest = Math.max(widest, l.getLineWidth(i));
            }
            w = align == 1 ? Math.round(wrapWidth) : (int) Math.ceil(widest);
        } else {
            w = (int) Math.ceil(Math.min(maxWidth, paint.measureText(text)));
        }
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) {
            w = MeasureSpec.getSize(wSpec);
        }
        setMeasuredDimension(w, Math.round(ui.f(lineHeight) * lines()));
    }

    @Override
    protected void onDraw(Canvas c) {
        float line = ui.f(lineHeight);
        if (wrapWidth > 0) {
            StaticLayout l = layout();
            Paint.FontMetrics fm = paint.getFontMetrics();
            int save = c.save();
            // CSS puts half the leading above the first line as well.
            c.translate(0, (line - (fm.descent - fm.ascent)) / 2);
            l.draw(c);
            c.restoreToCount(save);
            return;
        }
        String s = Draw.fit(text, paint, Math.min(maxWidth, getWidth() + 0.5f));
        float x = align == 1 ? (getWidth() - paint.measureText(s)) / 2 : 0;
        c.drawText(s, x, Draw.centerBaseline(paint, line / 2), paint);
    }
}

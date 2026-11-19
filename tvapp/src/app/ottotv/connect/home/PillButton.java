package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * The design's round buttons ("Open", "Connect phone", "Choose a Wi-Fi
 * network"): amber with dark text when focused, translucent white when
 * not, an optional icon before the text and an optional key chip after it
 * ("Close  Esc"). Focused they grow 1.06 and get the ring, 7 px out.
 */
final class PillButton extends View {
    private final Ui ui;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF chipBox = new RectF();
    private final Anim ring = new Anim(0, Ui.FAST);
    private final Anim grow = new Anim(0, Ui.MOVE);

    private String text;
    private String icon;
    private boolean iconFill;
    private float iconStroke = 2.2f;
    private float iconSize = 28;
    private String chip;
    private final float height;
    private final float padLeft;
    private final float padRight;
    private float pivot = 0.5f;
    private int bgOff = Ui.alpha(0xFFFFFF, 0.16f);
    private int fgOff = 0xFFFFFFFF;
    private int bgOn = Ui.ACCENT;
    private int fgOn = Ui.ON_ACCENT;
    private boolean focused;

    PillButton(Context c, Ui ui, String text, float height, float padLeft, float padRight, float textSize) {
        super(c);
        this.ui = ui;
        this.text = text;
        this.height = height;
        this.padLeft = padLeft;
        this.padRight = padRight;
        ui.type(paint, textSize, 700);
        ui.type(chipPaint, 24, 700);
        setContentDescription(text);
    }

    PillButton icon(String d, boolean fill, float stroke, float size) {
        icon = d;
        iconFill = fill;
        iconStroke = stroke;
        iconSize = size;
        return this;
    }

    PillButton chip(String key) {
        chip = key;
        return this;
    }

    PillButton colors(int bgOff, int fgOff, int bgOn, int fgOn) {
        this.bgOff = bgOff;
        this.fgOff = fgOff;
        this.bgOn = bgOn;
        this.fgOn = fgOn;
        return this;
    }

    /** Where it grows from: 0 left edge, 0.5 center, 1 right edge. */
    PillButton pivot(float x) {
        pivot = x;
        return this;
    }

    void setText(String t) {
        if (!t.equals(text)) {
            text = t;
            setContentDescription(t);
            requestLayout();
            invalidate();
        }
    }

    void setFocus(boolean f, boolean animate) {
        boolean changed = focused != f;
        focused = f;
        changed |= ring.set(f ? 1 : 0, animate);
        changed |= grow.set(f ? 1 : 0, animate);
        if (changed) {
            invalidate();
        }
    }

    /** Width in screen pixels for the current text. */
    int measuredWidthPx() {
        float w = ui.f(padLeft) + paint.measureText(text) + ui.f(padRight);
        if (icon != null) {
            w += ui.f(iconSize) + ui.f(12);
        }
        if (chip != null) {
            w += ui.f(14) + chipWidth();
        }
        return (int) Math.ceil(w);
    }

    private float chipWidth() {
        return Math.max(ui.f(40), chipPaint.measureText(chip) + ui.f(20));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(measuredWidthPx(), ui.px(height));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = 1 + 0.06f * grow.get();
        int save = c.save();
        c.scale(s, s, w * pivot, h / 2);
        box.set(0, 0, w, h);
        Draw.round(c, box, h / 2, focused ? bgOn : bgOff);
        int fg = focused ? fgOn : fgOff;
        float x = ui.f(padLeft);
        if (icon != null) {
            float is = ui.f(iconSize);
            Icons.draw(c, icon, x, (h - is) / 2, is, iconStroke, fg, iconFill);
            x += is + ui.f(12);
        }
        paint.setColor(fg);
        c.drawText(text, x, Draw.centerBaseline(paint, h / 2), paint);
        if (chip != null) {
            float cx = x + paint.measureText(text) + ui.f(14);
            float cw = chipWidth(), ch = ui.f(40);
            chipBox.set(cx, (h - ch) / 2, cx + cw, (h + ch) / 2);
            Draw.round(c, chipBox, ui.f(10), Ui.alpha(0x15191F, 0.14f));
            chipPaint.setColor(fg);
            chipPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(chip, chipBox.centerX(), Draw.centerBaseline(chipPaint, h / 2), chipPaint);
        }
        Draw.ring(c, box, h / 2, ui.f(7), ui.f(3), ui.f(20), false, ring.get());
        c.restoreToCount(save);
        if (ring.running() || grow.running()) {
            postInvalidateOnAnimation();
        }
    }
}

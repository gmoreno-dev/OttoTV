package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * A quick notice ("Phone connected", "Laptop screen off"...): a dark card
 * with an icon in a circle, a title and an optional second line. It slides
 * down into place and goes away on its own after 3 seconds.
 */
final class ToastView extends View {
    static final int WIDTH = 620;
    static final long SHOWN_MS = 2800;

    private final Ui ui;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Anim shown = new Anim(0, Ui.SLOW);
    private final Runnable hide = () -> setShown(false);
    private String icon = Icons.PHONE;
    private String titleText = "";
    private String subText = "";

    ToastView(Context c, Ui ui) {
        super(c);
        this.ui = ui;
        ui.type(title, 28, 700);
        title.setLetterSpacing(-0.015f);
        title.setColor(Ui.TEXT);
        ui.type(sub, 24, 500);
        sub.setColor(Ui.TOAST_SUB);
    }

    static int height(boolean twoLines) {
        return twoLines ? 104 : 96;
    }

    void show(String icon, String title, String sub) {
        show(icon, title, sub, SHOWN_MS);
    }

    /** For longer: news worth reading in full (an update). */
    void show(String icon, String title, String sub, long ms) {
        this.icon = icon;
        this.titleText = title;
        this.subText = sub == null ? "" : sub;
        setContentDescription(sub == null || sub.isEmpty() ? title : title + ". " + sub);
        requestLayout();
        setShown(true);
        removeCallbacks(hide);
        postDelayed(hide, ms);
        announceForAccessibility(getContentDescription());
    }

    void hideNow() {
        removeCallbacks(hide);
        setShown(false);
    }

    private void setShown(boolean on) {
        shown.set(on ? 1 : 0, true);
        invalidate();
    }

    boolean visible() {
        return shown.target() > 0;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(ui.px(WIDTH), ui.px(height(!subText.isEmpty())) + ui.px(40));
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = shown.get();
        if (s <= 0.004f) {
            if (shown.running()) {
                postInvalidateOnAnimation();
            }
            return;
        }
        float w = getWidth();
        float h = ui.f(height(!subText.isEmpty()));
        float m = ui.f(40);
        int save = c.saveLayerAlpha(-m, -m, w + m, getHeight() + m, Math.round(s * 255));
        c.translate(0, -ui.f(16) * (1 - s));
        box.set(0, 0, w, h);
        Draw.shadow(c, box, ui.f(30), ui.f(10), ui.f(24), Ui.alpha(0x000000, 0.35f));
        Draw.round(c, box, ui.f(30), Ui.TOAST);
        Paint p = Draw.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, ui.f(1)));
        p.setColor(Ui.alpha(0xFFFFFF, 0.09f));
        c.drawRoundRect(box, ui.f(30), ui.f(30), p);

        float cx = ui.f(18 + 30), cy = h / 2;
        p = Draw.paint();
        p.setColor(Ui.TOAST_ICON);
        c.drawCircle(cx, cy, ui.f(30), p);
        float is = ui.f(30);
        Icons.draw(c, icon, cx - is / 2, cy - is / 2, is, 2, Ui.TEXT, false);

        float x = ui.f(18 + 60 + 18);
        float maxW = w - x - ui.f(28);
        float tl = ui.f(28 * 1.25f), sl = ui.f(24 * 1.3f), gap = ui.f(2);
        float top = (h - (subText.isEmpty() ? tl : tl + gap + sl)) / 2;
        c.drawText(Draw.fit(titleText, title, maxW), x, Draw.centerBaseline(title, top + tl / 2), title);
        if (!subText.isEmpty()) {
            c.drawText(Draw.fit(subText, sub, maxW), x, Draw.centerBaseline(sub, top + tl + gap + sl / 2), sub);
        }
        c.restoreToCount(save);
        if (shown.running()) {
            postInvalidateOnAnimation();
        }
    }
}

package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * "System details", opened next to the quick panel: CPU temperature and
 * use, free memory, time since it was turned on, the TV's address, the
 * graphics mode and network debugging. Two columns, the last one full width.
 * Otto TV's version sits across from the title.
 */
final class DetailsCard extends View {
    static final float WIDTH = 640;
    static final float HEIGHT = 494;

    private final Ui ui;
    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private String title = "";
    private String corner = "";
    private String[] labels = new String[0];
    private String[] values = new String[0];

    DetailsCard(Context c, Ui ui) {
        super(c);
        this.ui = ui;
        ui.type(head, 26, 700);
        head.setColor(Ui.HEAD);
        ui.type(label, 24, 500);
        label.setColor(Ui.TEXT2);
        ui.type(value, 28, 700);
        value.setLetterSpacing(-0.01f);
        value.setColor(Ui.TEXT);
    }

    /** Across from the title: "Otto TV 0.7.0". */
    void setCorner(String text) {
        corner = text == null ? "" : text;
        invalidate();
    }

    void set(String title, String[] labels, String[] values) {
        this.title = title;
        this.labels = labels;
        this.values = values;
        StringBuilder d = new StringBuilder(title);
        for (int i = 0; i < labels.length; i++) {
            d.append(", ").append(labels[i]).append(' ').append(values[i]);
        }
        setContentDescription(d);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        r.set(0, 0, w, h);
        Draw.round(c, r, ui.f(40), Ui.PANEL);
        Paint p = Draw.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, ui.f(1)));
        p.setColor(Ui.alpha(0xFFFFFF, 0.08f));
        c.drawRoundRect(r, ui.f(40), ui.f(40), p);

        float x = ui.f(32), y = ui.f(28);
        c.drawText(title, x, Draw.centerBaseline(head, y + ui.f(17)), head);
        if (!corner.isEmpty()) {
            float cw = label.measureText(corner);
            c.drawText(corner, w - x - cw, Draw.centerBaseline(label, y + ui.f(17)), label);
        }
        y += ui.f(34 + 12);
        float gap = ui.f(12);
        float cw = (w - 2 * x - gap) / 2, ch = ui.f(88);
        for (int i = 0; i < labels.length; i++) {
            boolean full = i == labels.length - 1 && labels.length % 2 == 1;
            int col = i % 2, row = i / 2;
            float left = x + col * (cw + gap);
            float top = y + row * (ch + gap);
            r.set(left, top, full ? w - x : left + cw, top + ch);
            Draw.round(c, r, ui.f(22), 0xFF252C37);
            float lh = ui.f(24 * 1.21f), vh = ui.f(28 * 1.21f);
            float ty = top + (ch - (lh + ui.f(2) + vh)) / 2;
            float maxW = r.width() - ui.f(40);
            c.drawText(Draw.fit(labels[i], label, maxW), r.left + ui.f(20), Draw.centerBaseline(label, ty + lh / 2), label);
            c.drawText(Draw.fit(values[i], value, maxW), r.left + ui.f(20),
                    Draw.centerBaseline(value, ty + lh + ui.f(2) + vh / 2), value);
        }
    }
}

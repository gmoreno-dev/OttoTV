package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.FrameLayout;

/**
 * One app store on "More apps": its icon, name, what it offers and an Open
 * button, on a card lit with the store's color from the top right.
 */
final class StoreCard extends FrameLayout {
    static final float WIDTH = 852;
    static final float HEIGHT = 480;

    private final Ui ui;
    final PillButton open;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint desc = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private String name = "";
    private String text = "";
    private String glyph = Icons.BAG;
    private Bitmap icon;
    private int tint = 0x2A8A84;
    private boolean focused;
    private StaticLayout layout;
    private Shader light;

    StoreCard(Context c, Ui ui, String openLabel) {
        super(c);
        this.ui = ui;
        setWillNotDraw(false);
        setClipChildren(false);
        ui.type(title, 48, 800);
        title.setLetterSpacing(-0.025f);
        title.setColor(Ui.TEXT);
        ui.type(desc, 30, 500);
        desc.setColor(Ui.SOFT);
        open = new PillButton(c, ui, openLabel, 64, 30, 38, 28).icon(Icons.OPEN, false, 2.4f, 26).pivot(0);
        LayoutParams lp = new LayoutParams(-2, ui.px(64));
        lp.leftMargin = ui.px(48);
        lp.topMargin = ui.px(HEIGHT - 48 - 64);
        addView(open, lp);
    }

    void set(String name, String text, Bitmap icon, String glyph, int tint) {
        this.name = name;
        this.text = text;
        this.icon = icon;
        this.glyph = glyph;
        this.tint = tint & 0xFFFFFF;
        layout = null;
        light = null;
        setContentDescription(name);
        invalidate();
    }

    void setFocus(boolean f, boolean animate) {
        if (focused != f) {
            focused = f;
            invalidate();
        }
        open.setFocus(f, animate);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float rad = ui.f(32);
        r.set(0, 0, w, h);
        Draw.round(c, r, rad, 0xFF222935);
        if (light == null) {
            // radial-gradient(760px 460px at 100% 0%, color .55, transparent 72%)
            RadialGradient g = new RadialGradient(0, 0, ui.f(760),
                    new int[] {Ui.alpha(tint, 0.55f), Ui.alpha(tint, 0)}, new float[] {0, 0.72f},
                    Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1, 460f / 760f);
            m.postTranslate(w, 0);
            g.setLocalMatrix(m);
            light = g;
        }
        Paint p = Draw.paint();
        p.setShader(light);
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        float bw = ui.f(2);
        p.setStrokeWidth(bw);
        p.setColor(Ui.alpha(0xFFFFFF, focused ? 0.24f : 0.06f));
        r.inset(bw / 2, bw / 2);
        c.drawRoundRect(r, rad - bw / 2, rad - bw / 2, p);

        float x = ui.f(48), y = ui.f(48), box = ui.f(112);
        r.set(x, y, x + box, y + box);
        if (icon != null) {
            c.drawBitmap(icon, null, r, bitmapPaint);
        } else {
            Draw.round(c, r, ui.f(30), Ui.alpha(0xFFFFFF, 0.1f));
            float is = ui.f(60);
            Icons.draw(c, glyph, x + (box - is) / 2, y + (box - is) / 2, is, 1.6f, Ui.TEXT, false);
        }
        y += box + ui.f(28);
        float tl = ui.f(48 * 1.15f);
        c.drawText(Draw.fit(name, title, w - 2 * x), x, Draw.centerBaseline(title, y + tl / 2), title);
        y += tl + ui.f(12);
        if (layout == null) {
            int width = (int) Math.min(ui.f(700), w - 2 * x);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), desc, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(ui.f(30 * 1.4f) - desc.getFontMetrics(null), 1)
                    .setIncludePad(false)
                    .build();
        }
        int save = c.save();
        // CSS puts the half-leading above the first line too.
        c.translate(x, y + (ui.f(30 * 1.4f) - desc.getFontMetrics(null)) / 2);
        layout.draw(c);
        c.restoreToCount(save);
    }
}

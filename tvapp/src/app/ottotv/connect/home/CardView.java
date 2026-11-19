package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;

/**
 * An app card, the way the design draws it:
 *  - with a banner, the banner fills the card; its name only shows under the
 *    card while focused (it's already in the art);
 *  - with only an icon, the icon over the app's color, darkened, and the
 *    name under the icon;
 *  - the launcher's own tiles ("See all", "More apps"): a line icon and text.
 * Focused: scale 1.06, the amber ring with a dark gap, a shadow and, without
 * a banner, a sheen. The ring, shadow and label are drawn outside the
 * view's bounds, so its parents don't clip their children.
 */
final class CardView extends View {
    static final int FAVORITE = 0;
    static final int ROW = 1;

    private final Ui ui;
    private final int type;
    private final float radius;
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tilePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF card = new RectF();
    private final RectF tmp = new RectF();

    private final Anim ring = new Anim(0, Ui.FAST);
    private final Anim up = new Anim(0, Ui.MOVE);
    private final Anim lift = new Anim(0, Ui.MOVE);
    private final Anim raise = new Anim(0, Ui.MOVE);
    private final Anim fade = new Anim(1, Ui.MOVE);
    private final Anim label = new Anim(0, Ui.FAST);

    private AppInfo app;
    private String title;
    private boolean focused;
    private boolean arrows;
    private boolean canLeft;
    private boolean canRight;
    private BitmapShader bannerShader;

    CardView(Context c, Ui ui, int type) {
        super(c);
        this.ui = ui;
        this.type = type;
        radius = ui.f(type == FAVORITE ? 22 : 20);
        ui.type(namePaint, type == FAVORITE ? 28 : 24, 700);
        namePaint.setLetterSpacing(-0.01f);
        ui.type(labelPaint, type == FAVORITE ? 26 : 24, 700);
        labelPaint.setColor(Ui.TEXT);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        ui.type(tilePaint, 24, 700);
        tilePaint.setLetterSpacing(-0.01f);
        setOutlineProvider(null);
    }

    static float width(int type) {
        return type == FAVORITE ? 368 : 268;
    }

    static float height(int type) {
        return type == FAVORITE ? 207 : 151;
    }

    AppInfo app() {
        return app;
    }

    void bind(AppInfo a, String title) {
        if (a != app || (title != null && !title.equals(this.title))) {
            app = a;
            this.title = title;
            bannerShader = null;
            if (a.banner != null && a.isApp()) {
                bannerShader = new BitmapShader(a.banner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            setContentDescription(title != null ? title : a.label);
            invalidate();
        }
    }

    /**
     * focused: has the focus ring; lifted: scaled up (focused or being moved);
     * raised: moved up (move mode); dimmed: the other cards in move mode.
     */
    void state(boolean focused, boolean lifted, boolean raised, boolean dimmed,
            boolean canLeft, boolean canRight, boolean animate) {
        boolean changed = this.focused != focused || this.canLeft != canLeft || this.canRight != canRight;
        this.focused = focused;
        this.arrows = raised;
        this.canLeft = canLeft;
        this.canRight = canRight;
        changed |= ring.set(lifted ? 1 : 0, animate);
        changed |= up.set(lifted ? 1 : 0, animate);
        changed |= lift.set(lifted ? 1 : 0, animate);
        changed |= raise.set(raised ? 1 : 0, animate);
        changed |= fade.set(dimmed ? 0.45f : 1, animate);
        changed |= label.set(lifted && hasBanner() ? 1 : 0, animate);
        setTranslationZ(lifted ? 2 : 0);
        if (changed) {
            invalidate();
        }
    }

    private boolean hasBanner() {
        return app != null && app.isApp() && app.banner != null;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (app == null) {
            return;
        }
        float w = getWidth(), h = getHeight();
        float s = 1 + 0.06f * up.get();
        float dy = -ui.f(16) * raise.get();
        float alpha = fade.get();
        card.set(0, 0, w, h);

        int save = c.save();
        c.translate(0, dy);
        c.scale(s, s, w / 2, h / 2);
        if (alpha < 0.999f) {
            tmp.set(-ui.f(60), -ui.f(60), w + ui.f(60), h + ui.f(80));
            c.saveLayerAlpha(tmp, Math.round(alpha * 255));
        }
        float l = lift.get();
        if (l > 0.01f) {
            Draw.shadow(c, card, radius, ui.f(20), ui.f(40), Ui.alpha(0x000000, 0.55f * l));
        }
        drawCard(c);
        if (!hasBanner()) {
            Draw.sheen(c, card, radius, l);
        }
        Draw.ring(c, card, radius, ui.f(9), ui.f(3), ui.f(20), true, ring.get());
        if (arrows) {
            if (canLeft) {
                arrow(c, ui.f(10) + ui.f(22), h / 2, Icons.CHEVRON_LEFT);
            }
            if (canRight) {
                arrow(c, w - ui.f(10) - ui.f(22), h / 2, Icons.CHEVRON_RIGHT);
            }
        }
        c.restoreToCount(save);

        float la = label.get();
        if (la > 0.01f) {
            String name = Draw.fit(app.label, labelPaint, w + ui.f(120));
            labelPaint.setAlpha(Math.round(la * 255));
            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            float top = ui.f(type == FAVORITE ? 229 : 171);
            float line = ui.f(30);
            float base = top + line / 2 - (fm.ascent + fm.descent) / 2;
            c.drawText(name, w / 2, base, labelPaint);
        }
        if (ring.running() || up.running() || lift.running() || raise.running() || fade.running()
                || label.running()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawCard(Canvas c) {
        float w = card.width(), h = card.height();
        if (hasBanner()) {
            Bitmap b = app.banner;
            matrix.setScale(w / b.getWidth(), h / b.getHeight());
            bannerShader.setLocalMatrix(matrix);
            Paint p = Draw.paint();
            p.setFilterBitmap(true);
            p.setShader(bannerShader);
            c.drawRoundRect(card, radius, radius, p);
            p.setShader(null);
            return;
        }
        if (app.isApp()) {
            Draw.round(c, card, radius, focused ? app.cardFocused : app.card);
            float icon = ui.f(type == FAVORITE ? 88 : 60);
            float gap = ui.f(type == FAVORITE ? 12 : 10);
            float line = namePaint.getTextSize() * 1.2f;
            float top = (h - (icon + gap + line)) / 2;
            if (app.icon != null) {
                tmp.set((w - icon) / 2, top, (w + icon) / 2, top + icon);
                c.drawBitmap(app.icon, null, tmp, bitmapPaint);
            }
            namePaint.setColor(focused ? 0xFFFFFFFF : Ui.TEXT);
            namePaint.setTextAlign(Paint.Align.CENTER);
            String name = Draw.fit(app.label, namePaint, ui.f(type == FAVORITE ? 336 : 236));
            c.drawText(name, w / 2, Draw.centerBaseline(namePaint, top + icon + gap + line / 2), namePaint);
            return;
        }
        // The launcher's tiles.
        if (focused) {
            Draw.vertical(c, card, radius, 0xFF3A4352, 0xFF2E3542);
        } else {
            Draw.round(c, card, radius, Ui.alpha(0xFFFFFF, app.kind == AppInfo.MORE ? 0.03f : 0.06f));
        }
        if (app.kind == AppInfo.MORE) {
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(ui.f(2));
            p.setColor(Ui.alpha(0xFFFFFF, 0.26f));
            p.setPathEffect(new DashPathEffect(new float[] {ui.f(7), ui.f(5)}, 0));
            tmp.set(card);
            tmp.inset(ui.f(1), ui.f(1));
            c.drawRoundRect(tmp, radius - ui.f(1), radius - ui.f(1), p);
            p.setPathEffect(null);
        }
        int color = focused ? 0xFFFFFFFF : 0xFFD7DDE5;
        tilePaint.setColor(color);
        String text = title != null ? title : app.label;
        float icon = ui.f(38), gap = ui.f(12);
        float tw = tilePaint.measureText(text);
        float x = (w - (icon + gap + tw)) / 2;
        Icons.draw(c, app.kind == AppInfo.MORE ? Icons.PLUS : Icons.GRID, x, (h - icon) / 2, icon, 1.8f, color, false);
        c.drawText(text, x + icon + gap, Draw.centerBaseline(tilePaint, h / 2), tilePaint);
    }

    private void arrow(Canvas c, float cx, float cy, String d) {
        Paint p = Draw.paint();
        p.setColor(Ui.GAP);
        c.drawCircle(cx, cy, ui.f(22 + 4), p);
        p.setColor(Ui.ACCENT);
        c.drawCircle(cx, cy, ui.f(22), p);
        float s = ui.f(24);
        Icons.draw(c, d, cx - s / 2, cy - s / 2, s, 2.8f, Ui.ON_ACCENT, false);
    }
}

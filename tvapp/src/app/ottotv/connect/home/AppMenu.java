package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * An app's options (hold Enter on it): the app's real icon and name on top,
 * then Pin or Unpin, Move (favorites only), App info and Uninstall (not for
 * system apps). It opens next to the card, never over it, over a dimmed
 * screen.
 */
final class AppMenu extends FrameLayout {
    interface Listener {
        void onMenuHover(int item);

        void onMenuPick(int item);

        void onMenuDismiss();
    }

    static final float WIDTH = 520;

    private final Ui ui;
    private final Box box;
    private final Anim open = new Anim(0, Ui.FAST);
    private final Listener listener;

    AppMenu(Context c, Ui ui, Listener listener) {
        super(c);
        this.ui = ui;
        this.listener = listener;
        setWillNotDraw(false);
        setClipChildren(false);
        box = new Box(c, ui, this);
        addView(box, new LayoutParams(ui.px(WIDTH), ui.px(height(4))));
        setVisibility(GONE);
    }

    static float height(int items) {
        return 20 + 72 + 12 + items * 72 + (items - 1) * 8 + 20;
    }

    /** x, y: the box's top left, in design px. */
    void show(Bitmap icon, String name, String sub, String[] labels, String[] icons, int focus,
            float x, float y) {
        box.set(icon, name, sub, labels, icons, focus);
        LayoutParams lp = (LayoutParams) box.getLayoutParams();
        lp.leftMargin = ui.px(x);
        lp.topMargin = ui.px(y);
        lp.height = ui.px(height(labels.length));
        box.setLayoutParams(lp);
        setVisibility(VISIBLE);
        open.set(1, true);
        invalidate();
        box.invalidate();
    }

    void focus(int item) {
        box.focus(item);
    }

    void hide(boolean animate) {
        open.set(0, animate);
        invalidate();
        box.invalidate();
        if (!animate) {
            setVisibility(GONE);
        }
    }

    boolean shown() {
        return open.target() > 0;
    }

    @Override
    protected void onDraw(Canvas c) {
        float o = open.get();
        c.drawColor(Ui.alpha(0x080A0E, 0.55f * o));
        if (open.running()) {
            postInvalidateOnAnimation();
        } else if (o == 0) {
            setVisibility(GONE);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // A click outside the menu closes it.
        if (e.getAction() == MotionEvent.ACTION_UP && shown()) {
            listener.onMenuDismiss();
        }
        return shown();
    }

    float openness() {
        return open.get();
    }

    static final class Box extends View {
        private final Ui ui;
        private final AppMenu menu;
        private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint item = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private Anim[] focusAnims = new Anim[0];
        private Bitmap icon;
        private String title = "";
        private String subtitle;
        private String[] labels = new String[0];
        private String[] icons = new String[0];
        private int focus;

        Box(Context c, Ui ui, AppMenu menu) {
            super(c);
            this.ui = ui;
            this.menu = menu;
            ui.type(name, 28, 700);
            name.setLetterSpacing(-0.015f);
            name.setColor(Ui.TEXT);
            ui.type(sub, 24, 500);
            sub.setColor(Ui.TEXT2);
            ui.type(item, 26, 600);
            item.setColor(Ui.TEXT);
            setOnHoverListener((v, e) -> {
                int i = itemAt(e.getY());
                if (i >= 0 && (e.getAction() == MotionEvent.ACTION_HOVER_ENTER
                        || e.getAction() == MotionEvent.ACTION_HOVER_MOVE) && i != focus) {
                    menu.listener.onMenuHover(i);
                }
                return true;
            });
        }

        void set(Bitmap icon, String title, String subtitle, String[] labels, String[] icons, int focus) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.labels = labels;
            this.icons = icons;
            this.focus = focus;
            focusAnims = new Anim[labels.length];
            for (int i = 0; i < labels.length; i++) {
                focusAnims[i] = new Anim(i == focus ? 1 : 0, Ui.FAST);
            }
            invalidate();
        }

        void focus(int i) {
            focus = i;
            for (int k = 0; k < focusAnims.length; k++) {
                focusAnims[k].set(k == i ? 1 : 0, true);
            }
            invalidate();
        }

        int itemAt(float y) {
            float top = ui.f(20 + 72 + 12);
            for (int i = 0; i < labels.length; i++) {
                float y0 = top + ui.f(80) * i;
                if (y >= y0 && y < y0 + ui.f(72)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int i = itemAt(e.getY());
                if (i >= 0) {
                    menu.listener.onMenuPick(i);
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            float o = menu.openness();
            if (o <= 0.004f) {
                if (menu.open.running()) {
                    postInvalidateOnAnimation();
                }
                return;
            }
            float w = getWidth(), h = getHeight();
            float m = ui.f(60);
            int save = c.saveLayerAlpha(-m, -m, w + m, h + m, Math.round(o * 255));
            float s = 0.96f + 0.04f * o;
            c.scale(s, s, w / 2, h / 2);
            r.set(0, 0, w, h);
            Draw.shadow(c, r, ui.f(32), ui.f(18), ui.f(40), Ui.alpha(0x000000, 0.45f));
            Draw.round(c, r, ui.f(32), Ui.PANEL);
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, ui.f(1)));
            p.setColor(Ui.alpha(0xFFFFFF, 0.1f));
            c.drawRoundRect(r, ui.f(32), ui.f(32), p);

            // Header: icon and name.
            float hx = ui.f(28), hy = ui.f(20), hh = ui.f(72), is = ui.f(56);
            if (icon != null) {
                r.set(hx, hy + (hh - is) / 2, hx + is, hy + (hh + is) / 2);
                c.drawBitmap(icon, null, r, bitmapPaint);
            }
            float tx = hx + is + ui.f(16), maxW = w - tx - ui.f(28);
            float nl = ui.f(28 * 1.25f), sl = ui.f(24 * 1.3f);
            float th = subtitle == null ? nl : nl + sl;
            float ty = hy + (hh - th) / 2;
            c.drawText(Draw.fit(title, name, maxW), tx, Draw.centerBaseline(name, ty + nl / 2), name);
            if (subtitle != null) {
                c.drawText(Draw.fit(subtitle, sub, maxW), tx, Draw.centerBaseline(sub, ty + nl + sl / 2), sub);
            }

            boolean running = false;
            float top = ui.f(20 + 72 + 12);
            for (int i = 0; i < labels.length; i++) {
                float f = focusAnims[i].get();
                running |= focusAnims[i].running();
                r.set(ui.f(20), top + ui.f(80) * i, w - ui.f(20), top + ui.f(80) * i + ui.f(72));
                int item = c.save();
                float sc = 1 + 0.03f * f;
                c.scale(sc, sc, r.centerX(), r.centerY());
                if (i == focus) {
                    Draw.round(c, r, ui.f(20), Ui.ITEM_FOCUS);
                }
                float ic = ui.f(28);
                Icons.draw(c, icons[i], r.left + ui.f(20), r.centerY() - ic / 2, ic, 2, Ui.TEXT, false);
                c.drawText(labels[i], r.left + ui.f(20) + ic + ui.f(16), Draw.centerBaseline(this.item, r.centerY()),
                        this.item);
                Draw.ring(c, r, ui.f(20), ui.f(6), ui.f(3), ui.f(18), false, f);
                c.restoreToCount(item);
            }
            c.restoreToCount(save);
            if (running || menu.open.running()) {
                postInvalidateOnAnimation();
            }
        }
    }
}

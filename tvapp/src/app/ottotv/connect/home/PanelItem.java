package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * One item of the quick panel: sound output, laptop screen, Wi-Fi, phone,
 * ad blocking, power, an update (only when there is one) or "Show
 * details". Each draws itself like the design's panel: a rounded tile,
 * lighter when focused, grown 1.04 with the amber ring.
 */
final class PanelItem extends View {
    static final int SOUND = 0;
    static final int SCREEN = 1;
    static final int WIFI = 2;
    static final int PHONE = 3;
    static final int POWER = 4;
    static final int DETAILS = 5;
    static final int UPDATE = 6;
    static final int ADBLOCK = 7;

    private final Ui ui;
    final int kind;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint state = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF p = new RectF();
    private final Path clip = new Path();
    private final Anim focus = new Anim(0, Ui.FAST);
    private final Anim grow = new Anim(0, Ui.MOVE);
    private final Anim knob = new Anim(1, Ui.MOVE);
    private final Anim chevron = new Anim(0, Ui.MOVE);
    private boolean focused;

    // What it shows.
    String titleText = "";
    String subText = "";
    String[] options = new String[0];
    int selected;
    boolean on;
    boolean online = true;
    int bars = 3;
    boolean cable;
    String stateText = "";
    /** UPDATE: its icon, and how far the download is (0 to 1; -1: no bar). */
    String icon = Icons.DOWNLOAD;
    float progress = -1;
    private StaticLayout subLayout;

    PanelItem(Context c, Ui ui, int kind) {
        super(c);
        this.ui = ui;
        this.kind = kind;
        ui.type(title, 26, 700);
        title.setColor(Ui.TEXT);
        ui.type(sub, 24, 500);
        sub.setColor(Ui.TEXT2);
        ui.type(pill, 24, 700);
        pill.setTextAlign(Paint.Align.CENTER);
        ui.type(state, 24, 700);
        state.setColor(Ui.TEXT);
    }

    static float height(int kind) {
        switch (kind) {
            case SOUND:
            case POWER:
                return 124;
            case SCREEN:
                return 128;
            case ADBLOCK:
                return 104;
            case WIFI:
            case PHONE:
                return 168;
            default:
                return 64;
        }
    }

    void setFocus(boolean f, boolean animate) {
        boolean changed = focused != f;
        focused = f;
        changed |= focus.set(f ? 1 : 0, animate);
        changed |= grow.set(f ? 1 : 0, animate);
        if (changed) {
            invalidate();
        }
    }

    boolean focused() {
        return focused;
    }

    void update(boolean animate) {
        knob.set(on ? 1 : 0, animate);
        chevron.set(on ? 1 : 0, animate);
        subLayout = null;
        setContentDescription(titleText + (subText.isEmpty() ? "" : ", " + subText)
                + (options.length > 0 ? ", " + options[selected] : "") + (stateText.isEmpty() ? "" : ", " + stateText));
        invalidate();
    }

    /** Which option (of the pills) is at x, or -1. */
    int optionAt(float x) {
        if (options.length == 0) {
            return -1;
        }
        float[] cols = columns();
        float left = ui.f(20);
        for (int i = 0; i < options.length; i++) {
            if (x >= left && x < left + cols[i]) {
                return i;
            }
            left += cols[i] + ui.f(12);
        }
        return -1;
    }

    private float[] columns() {
        float inner = getWidth() - ui.f(40) - ui.f(12) * (options.length - 1);
        if (kind == SOUND) {
            // grid-template-columns: 1fr 2.3fr
            return new float[] {inner / 3.3f, inner * 2.3f / 3.3f};
        }
        float[] c = new float[options.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = inner / c.length;
        }
        return c;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float f = focus.get();
        float s = 1 + 0.04f * grow.get();
        int save = c.save();
        c.scale(s, s, w / 2, h / 2);
        r.set(0, 0, w, h);
        boolean light = (kind == WIFI || kind == PHONE || kind == UPDATE) && on;
        float rad = kind == DETAILS || kind == UPDATE ? h / 2 : ui.f(28);
        int bg = light ? (focused ? Ui.ON_FOCUS : Ui.ON) : (focused ? Ui.ITEM_FOCUS : Ui.ITEM);
        Draw.round(c, r, rad, bg);
        switch (kind) {
            case SOUND:
            case POWER:
                drawChoice(c, w, h);
                break;
            case SCREEN:
                drawSwitch(c, w, h, Icons.LAPTOP, 2);
                break;
            case ADBLOCK:
                drawSwitch(c, w, h, on ? Icons.SHIELD_ON : Icons.SHIELD, 1);
                break;
            case WIFI:
            case PHONE:
                drawTile(c, w, h, light);
                break;
            case UPDATE:
                drawUpdate(c, w, h, light);
                break;
            default:
                drawDetails(c, w, h);
        }
        Draw.ring(c, r, rad, ui.f(7), ui.f(3), ui.f(20), false, f);
        c.restoreToCount(save);
        if (focus.running() || grow.running() || knob.running() || chevron.running()) {
            postInvalidateOnAnimation();
        }
    }

    /** Sound output or power: an icon and a title, then a row of pills. */
    private void drawChoice(Canvas c, float w, float h) {
        float x = ui.f(20), top = ui.f(16);
        float is = ui.f(28);
        Icons.draw(c, kind == SOUND ? Icons.SPEAKER : Icons.POWER, x, top + (ui.f(32) - is) / 2, is, 2, Ui.ICON, false);
        title.setColor(Ui.TEXT);
        c.drawText(titleText, x + is + ui.f(14), Draw.centerBaseline(title, top + ui.f(16)), title);
        float[] cols = columns();
        float py = h - ui.f(14) - ui.f(48);
        float px = x;
        for (int i = 0; i < options.length; i++) {
            p.set(px, py, px + cols[i], py + ui.f(48));
            // The selected option: amber while the item is focused (power
            // only shows one then), light otherwise; the rest are outlined.
            boolean sel = kind == SOUND ? i == selected : focused && i == selected;
            int fill, text, border;
            if (sel && focused) {
                fill = Ui.ACCENT;
                text = Ui.ON_ACCENT;
                border = Ui.ACCENT;
            } else if (sel) {
                fill = Ui.ON;
                text = Ui.ON_TEXT;
                border = Ui.ON;
            } else {
                fill = 0;
                text = Ui.HEAD;
                border = Ui.alpha(0xFFFFFF, 0.16f);
            }
            if (fill != 0) {
                Draw.round(c, p, ui.f(24), fill);
            }
            Paint b = Draw.paint();
            b.setStyle(Paint.Style.STROKE);
            float bw = ui.f(2);
            b.setStrokeWidth(bw);
            b.setColor(border);
            p.inset(bw / 2, bw / 2);
            c.drawRoundRect(p, ui.f(24) - bw / 2, ui.f(24) - bw / 2, b);
            pill.setColor(text);
            c.drawText(Draw.fit(options[i], pill, cols[i] - ui.f(16)), p.centerX(), Draw.centerBaseline(pill, p.centerY()), pill);
            px += cols[i] + ui.f(12);
        }
    }

    /**
     * Laptop screen or ad blocking: icon, title and a note (up to lines
     * lines), the state and a switch.
     */
    private void drawSwitch(Canvas c, float w, float h, String icon, int lines) {
        float x = ui.f(20);
        float circle = ui.f(48);
        Paint pt = Draw.paint();
        pt.setColor(Ui.alpha(0xFFFFFF, 0.08f));
        c.drawCircle(x + circle / 2, h / 2, circle / 2, pt);
        float is = ui.f(26);
        Icons.draw(c, icon, x + (circle - is) / 2, h / 2 - is / 2, is, 2.2f, Ui.TEXT, false);

        // Right side: "On"/"Off" and the switch.
        float sw = ui.f(64), sh = ui.f(36);
        float right = w - ui.f(22);
        p.set(right - sw, h / 2 - sh / 2, right, h / 2 + sh / 2);
        float k = knob.get();
        Draw.round(c, p, sh / 2, blend(Ui.alpha(0xFFFFFF, 0.18f), Ui.ON, k));
        Paint kp = Draw.paint();
        kp.setColor(blend(0xFFC3CBD6, Ui.ON_TEXT, k));
        float kx = p.left + ui.f(4) + ui.f(14) + ui.f(28) * k;
        c.drawCircle(kx, h / 2, ui.f(14), kp);
        float stateW = state.measureText(stateText);
        c.drawText(stateText, p.left - ui.f(12) - stateW, Draw.centerBaseline(state, h / 2), state);

        // Middle: title and the note, wrapped.
        float tx = x + circle + ui.f(16);
        float maxW = p.left - ui.f(12) - stateW - ui.f(16) - tx;
        if (subLayout == null) {
            // One line is cut short with "…" rather than wrapped.
            String text = lines == 1 ? Draw.fit(subText, sub, maxW) : subText;
            subLayout = StaticLayout.Builder.obtain(text, 0, text.length(), sub, (int) Math.max(1, maxW))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(ui.f(24 * 1.3f) - (sub.getFontMetrics().descent - sub.getFontMetrics().ascent), 1)
                    .setIncludePad(false)
                    .setMaxLines(lines)
                    .build();
        }
        float tl = ui.f(26 * 1.25f), sl = ui.f(24 * 1.3f) * subLayout.getLineCount();
        float top = (h - (tl + ui.f(2) + sl)) / 2;
        title.setColor(Ui.TEXT);
        c.drawText(Draw.fit(titleText, title, maxW), tx, Draw.centerBaseline(title, top + tl / 2), title);
        int save = c.save();
        Paint.FontMetrics fm = sub.getFontMetrics();
        c.translate(tx, top + tl + ui.f(2) + (ui.f(24 * 1.3f) - (fm.descent - fm.ascent)) / 2);
        sub.setColor(Ui.TEXT2);
        subLayout.draw(c);
        c.restoreToCount(save);
    }

    /** Wi-Fi or phone: an icon in a circle and a mark on top, title and line at the bottom. */
    private void drawTile(Canvas c, float w, float h, boolean light) {
        int fg = light ? Ui.ON_TEXT : Ui.TEXT;
        int subColor = light ? Ui.ON_SUB : Ui.TEXT2;
        float x = ui.f(20), top = ui.f(18);
        float circle = ui.f(48);
        Paint pt = Draw.paint();
        pt.setColor(light ? Ui.alpha(0x151A21, 0.1f) : Ui.alpha(0xFFFFFF, 0.08f));
        c.drawCircle(x + circle / 2, top + circle / 2, circle / 2, pt);
        float is = ui.f(26);
        float ix = x + (circle - is) / 2, iy = top + (circle - is) / 2;
        float mark = ui.f(28);
        float mx = w - ui.f(20) - mark, my = top + (circle - mark) / 2;
        if (kind == WIFI) {
            if (cable) {
                Icons.draw(c, Icons.CABLE, ix, iy, is, 2.2f, fg, false);
            } else {
                StatusPill.drawWifi(c, ix, iy, is, 2.2f, fg, online, bars);
            }
            signal(c, mx, my, mark, fg);
        } else {
            Icons.draw(c, Icons.PHONE, ix, iy, is, 2.2f, fg, false);
            Icons.draw(c, Icons.QR, mx, my, mark, 2, fg, false);
        }
        float tl = ui.f(26 * 1.25f), sl = ui.f(24 * 1.25f);
        float bottom = h - ui.f(18);
        title.setColor(fg);
        sub.setColor(subColor);
        float maxW = w - 2 * x;
        c.drawText(Draw.fit(titleText, title, maxW), x, Draw.centerBaseline(title, bottom - sl - ui.f(2) - tl / 2), title);
        c.drawText(Draw.fit(subText, sub, maxW), x, Draw.centerBaseline(sub, bottom - sl / 2), sub);
    }

    /** Four signal bars, lit by strength. */
    private void signal(Canvas c, float x, float y, float size, int color) {
        float s = size / 24f;
        int lit = !online ? 0 : cable ? 4 : bars + 1;
        Paint pt = Draw.paint();
        float[][] rects = {{3, 15, 3.5f, 5}, {8, 11.5f, 3.5f, 8.5f}, {13, 8, 3.5f, 12}, {18, 4.5f, 3.5f, 15.5f}};
        for (int i = 0; i < 4; i++) {
            float[] q = rects[i];
            pt.setColor(Ui.alpha(color, online ? (i < lit ? 1 : 0.3f) : 0.3f));
            p.set(x + q[0] * s, y + q[1] * s, x + (q[0] + q[2]) * s, y + (q[1] + q[3]) * s);
            c.drawRoundRect(p, s, s, pt);
        }
    }

    /** "Show details" with a chevron that turns when they're shown. */
    private void drawDetails(Canvas c, float w, float h) {
        title.setColor(Ui.TEXT);
        float tw = title.measureText(titleText);
        float is = ui.f(28), gap = ui.f(12);
        float x = (w - (tw + gap + is)) / 2;
        c.drawText(titleText, x, Draw.centerBaseline(title, h / 2), title);
        int save = c.save();
        float cx = x + tw + gap + is / 2;
        c.rotate(180 * chevron.get(), cx, h / 2);
        Icons.draw(c, Icons.CHEVRON_DOWN, cx - is / 2, h / 2 - is / 2, is, 2.4f, Ui.TEXT, false);
        c.restoreToCount(save);
    }

    /** An update: icon and what to do, centered; the download fills the pill from the left. */
    private void drawUpdate(Canvas c, float w, float h, boolean light) {
        if (progress >= 0) {
            int save = c.save();
            clip.rewind();
            clip.addRoundRect(r, h / 2, h / 2, Path.Direction.CW);
            c.clipPath(clip);
            Paint fill = Draw.paint();
            fill.setColor(Ui.alpha(0xFFFFFF, 0.14f));
            c.drawRect(0, 0, w * Math.min(1, progress), h, fill);
            c.restoreToCount(save);
        }
        int fg = light ? Ui.ON_TEXT : Ui.TEXT;
        title.setColor(fg);
        float is = ui.f(28), gap = ui.f(12);
        String text = Draw.fit(titleText, title, w - is - gap - ui.f(48));
        float tw = title.measureText(text);
        float x = (w - (is + gap + tw)) / 2;
        Icons.draw(c, icon, x, h / 2 - is / 2, is, 2.4f, fg, false);
        c.drawText(text, x + is + gap, Draw.centerBaseline(title, h / 2), title);
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}

package app.ottotv.connect.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import app.ottotv.connect.R;

/**
 * Shortcuts (F1, over any app): how to use Otto TV from the couch with a
 * keyboard, a mouse or the phone, whose QR code is right here. Enter, Esc
 * or F1 close it.
 */
public class ShortcutsActivity extends Activity {
    private Ui ui;
    private FrameLayout root;
    private QrView qr;
    private boolean closing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = Ui.of(this);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.addView(new Backdrop(this, ui), new FrameLayout.LayoutParams(-1, -1));
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ui.px(Ui.W), ui.px(Ui.H));
        sp.leftMargin = ui.left;
        sp.topMargin = ui.top;
        root.addView(stage, sp);
        build(stage);
        root.setAlpha(0);
        root.animate().alpha(1).setDuration(Ui.SLOW).start();
        setContentView(root);
        Remote.get("/app/pair", this::onPair);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void place(FrameLayout parent, View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? -2 : ui.px(w), h < 0 ? -2 : ui.px(h));
        lp.leftMargin = ui.px(x);
        lp.topMargin = ui.px(y);
        parent.addView(v, lp);
    }

    private void build(FrameLayout stage) {
        ImageView otto = new ImageView(this);
        otto.setImageResource(R.drawable.home_shortcuts);
        place(stage, otto, 96, 54, 166, 136);
        float titleTop = 54 + (136 - (64 * 1.1f + 8 + 26 * 1.4f)) / 2;
        place(stage, new Label(this, ui, getString(R.string.shortcuts_title), 64, 800, Ui.TEXT, 64 * 1.1f)
                .tracking(-0.035f), 96 + 166 + 28, titleTop, -2, -2);
        place(stage, new Label(this, ui, getString(R.string.shortcuts_text), 26, 500, Ui.TEXT2, 26 * 1.4f),
                96 + 166 + 28, titleTop + 64 * 1.1f + 8, -2, -2);
        PillButton close = new PillButton(this, ui, getString(R.string.shortcuts_close), 64, 32, 16, 28)
                .chip("Esc").pivot(1);
        close.setFocus(true, false);
        close.setOnClickListener(v -> close());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, ui.px(64));
        cp.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
        cp.rightMargin = ui.px(96);
        cp.topMargin = ui.px(54 + (136 - 64) / 2f);
        stage.addView(close, cp);

        // Keyboard.
        place(stage, new Card(this, ui, Icons.KEYBOARD, getString(R.string.shortcuts_keyboard)), 96, 214, 1180, 812);
        String[][] rows = {
            {"i:" + Icons.ARROW_UP, "i:" + Icons.ARROW_DOWN, "i:" + Icons.ARROW_LEFT, "i:" + Icons.ARROW_RIGHT, "|"
                + getString(R.string.shortcuts_move)},
            {"k:Enter", "|" + getString(R.string.shortcuts_select)},
            {"w:" + getString(R.string.shortcuts_hold), "k:Enter", "|" + getString(R.string.shortcuts_options)},
            {"k:Esc", "w:" + getString(R.string.shortcuts_or), "k:Backspace", "|" + getString(R.string.shortcuts_back)},
            {"k:Windows", "w:" + getString(R.string.shortcuts_or), "k:Home", "|" + getString(R.string.shortcuts_home)},
            {"k:F1", "|" + getString(R.string.home_shortcuts)},
            {"k:F2", "|" + getString(R.string.home_quick_panel)},
            {"i:" + Icons.VOL_DOWN, "i:" + Icons.VOL_UP, "i:" + Icons.VOL_MUTE, "|" + getString(R.string.shortcuts_volume)},
            {"k:Alt", "w:+", "k:F4", "|" + getString(R.string.shortcuts_close_app), "n:" + getString(R.string.shortcuts_close_app_note)},
            {"k:Ctrl", "w:+", "k:Alt", "w:+", "k:Del", "|" + getString(R.string.shortcuts_power)},
            {"k:Fn", "w:+", "i:" + Icons.SCREEN_KEY, "w:" + getString(R.string.shortcuts_screen_key), "|"
                + getString(R.string.shortcuts_laptop_screen), "n:" + getString(R.string.shortcuts_laptop_note)},
        };
        for (int i = 0; i < rows.length; i++) {
            place(stage, new KeyRow(this, ui, rows[i]), 96 + 36, 214 + 28 + 40 + 20 + i * 60, 1180 - 72, 56);
        }

        // Mouse.
        place(stage, new Card(this, ui, Icons.MOUSE, getString(R.string.shortcuts_mouse)), 1300, 214, 524, 388);
        String[][] mouse = {
            {"1", getString(R.string.shortcuts_click), getString(R.string.shortcuts_select)},
            {"1", getString(R.string.shortcuts_hold_click), getString(R.string.shortcuts_options)},
            {"2", getString(R.string.shortcuts_right_click), getString(R.string.shortcuts_back)},
            {"3", getString(R.string.shortcuts_wheel), getString(R.string.shortcuts_scroll)},
        };
        for (int i = 0; i < mouse.length; i++) {
            place(stage, new MouseRow(this, ui, mouse[i]), 1300 + 32, 214 + 28 + 40 + 16 + i * 68, 524 - 64, 64);
        }

        // Phone.
        place(stage, new Card(this, ui, Icons.PHONE, getString(R.string.shortcuts_phone)), 1300, 626, 524, 400);
        qr = new QrView(this, ui);
        place(stage, qr, 1300 + 32, 626 + 28 + 40 + 22, 184, 184);
        float tx = 1300 + 32 + 184 + 22, tw = 524 - 64 - 184 - 22;
        Label scan = new Label(this, ui, getString(R.string.shortcuts_scan), 26, 700, Ui.TEXT, 26 * 1.35f)
                .tracking(-0.01f).wrap(tw);
        place(stage, scan, tx, 626 + 28 + 40 + 22, -2, -2);
        place(stage, new Label(this, ui, getString(R.string.shortcuts_scan_note), 24, 500, Ui.TEXT2, 24 * 1.4f).wrap(tw),
                tx, 626 + 28 + 40 + 22 + scan.designHeight() + 12, -2, -2);
    }

    private void onPair(JSONObject j) {
        if (j == null || isDestroyed()) {
            return;
        }
        JSONArray rows = j.optJSONArray("qr");
        if (rows != null) {
            String[] m = new String[rows.length()];
            for (int i = 0; i < m.length; i++) {
                m[i] = rows.optString(i);
            }
            qr.set(m);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (first) {
                    close();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true; // the only thing to focus is Close
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                && e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                && e.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
            close();
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    void close() {
        if (closing) {
            return;
        }
        closing = true;
        root.animate().alpha(0).setDuration(Ui.SLOW).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    /** Almost opaque night with a warm glow at the top left. */
    static final class Backdrop extends View {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.DITHER_FLAG);
        private Shader glow;

        Backdrop(Context c, Ui ui) {
            super(c);
            this.ui = ui;
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Ui.alpha(0x12161C, 0.98f));
            if (glow == null) {
                RadialGradient g = new RadialGradient(0, 0, ui.f(700),
                        new int[] {Ui.alpha(0xF2B25C, 0.1f), Ui.alpha(0xF2B25C, 0)}, new float[] {0, 0.7f},
                        Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(1, 520f / 700f);
                m.postTranslate(ui.left + ui.f(0.12f * Ui.W), ui.top + ui.f(0.12f * Ui.H));
                g.setLocalMatrix(m);
                glow = g;
            }
            paint.setShader(glow);
            c.drawPaint(paint);
        }
    }

    /** A section: dark rounded card with an icon and a title. */
    static final class Card extends View {
        private final Ui ui;
        private final String icon;
        private final String title;
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Card(Context c, Ui ui, String icon, String title) {
            super(c);
            this.ui = ui;
            this.icon = icon;
            this.title = title;
            ui.type(paint, 30, 700);
            paint.setLetterSpacing(-0.02f);
            paint.setColor(Ui.TEXT);
        }

        @Override
        protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            Draw.round(c, r, ui.f(32), 0xFF1C222B);
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, ui.f(1)));
            p.setColor(Ui.alpha(0xFFFFFF, 0.06f));
            c.drawRoundRect(r, ui.f(32), ui.f(32), p);
            float x = getWidth() > ui.f(600) ? ui.f(36) : ui.f(32);
            float y = ui.f(28), is = ui.f(34);
            Icons.draw(c, icon, x, y + (ui.f(40) - is) / 2, is, 2, Ui.ICON, false);
            c.drawText(title, x + is + ui.f(16), Draw.centerBaseline(paint, y + ui.f(20)), paint);
        }
    }

    /**
     * One keyboard shortcut: keys ("k:Enter"), icon keys ("i:<path>") and
     * words ("w:or") in a 340 px column, then what it does ("|...") and an
     * optional note ("n:...").
     */
    static final class KeyRow extends View {
        private final Ui ui;
        private final String[] parts;
        private final TextPaint key = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint word = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        KeyRow(Context c, Ui ui, String[] parts) {
            super(c);
            this.ui = ui;
            this.parts = parts;
            ui.type(key, 24, 700);
            key.setColor(Ui.TEXT);
            key.setTextAlign(Paint.Align.CENTER);
            ui.type(word, 24, 500);
            word.setColor(Ui.TEXT2);
            ui.type(label, 26, 600);
            label.setColor(Ui.TEXT);
            ui.type(note, 24, 500);
            note.setColor(Ui.TEXT2);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            float x = 0, gap = ui.f(8);
            String what = "", extra = "";
            for (String part : parts) {
                char kind = part.charAt(0);
                String v = part.substring(kind == '|' ? 1 : 2);
                if (kind == '|') {
                    what = v;
                } else if (kind == 'n') {
                    extra = v;
                } else if (kind == 'w') {
                    c.drawText(v, x, Draw.centerBaseline(word, h / 2), word);
                    x += word.measureText(v) + gap;
                } else {
                    float w = kind == 'k' ? Math.max(ui.f(52), key.measureText(v) + ui.f(24)) : ui.f(52);
                    cap(c, x, (h - ui.f(48)) / 2, w);
                    if (kind == 'k') {
                        c.drawText(v, x + w / 2, Draw.centerBaseline(key, h / 2), key);
                    } else {
                        float is = ui.f(24);
                        Icons.draw(c, v, x + (w - is) / 2, (h - is) / 2, is, 2.4f, Ui.TEXT, false);
                    }
                    x += w + gap;
                }
            }
            float lx = ui.f(340 + 20);
            c.drawText(what, lx, Draw.centerBaseline(label, h / 2), label);
            if (!extra.isEmpty()) {
                c.drawText(" · " + extra, lx + label.measureText(what), Draw.centerBaseline(note, h / 2), note);
            }
        }

        private void cap(Canvas c, float x, float y, float w) {
            float hh = ui.f(48), rad = ui.f(12);
            r.set(x, y + ui.f(3), x + w, y + hh + ui.f(3));
            Draw.round(c, r, rad, 0xFF0C0F14);
            r.set(x, y, x + w, y + hh);
            Draw.vertical(c, r, rad, 0xFF343C48, 0xFF29303A);
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, ui.f(1)));
            p.setColor(Ui.alpha(0xFFFFFF, 0.12f));
            r.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
            c.drawRoundRect(r, rad, rad, p);
        }
    }

    /** One mouse action: a mouse with the button that does it lit, the input and what it does. */
    static final class MouseRow extends View {
        private final Ui ui;
        private final String[] row;
        private final TextPaint input = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint action = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        MouseRow(Context c, Ui ui, String[] row) {
            super(c);
            this.ui = ui;
            this.row = row;
            ui.type(input, 24, 600);
            input.setColor(Ui.HEAD);
            ui.type(action, 26, 600);
            action.setColor(Ui.TEXT);
            action.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            float s = ui.f(1);
            float mx = 0, my = (h - ui.f(44)) / 2;
            int lit = Integer.parseInt(row[0]);
            Paint p = Draw.paint();
            p.setColor(Ui.TEXT);
            // The lit part: left button, right button or the wheel.
            if (lit == 1) {
                r.set(mx + 3.4f * s, my + 3.4f * s, mx + 26.6f * s, my + 28.2f * s);
                c.drawArc(r, 180, 90, true, p);
            } else if (lit == 2) {
                r.set(mx + 5.4f * s, my + 3.4f * s, mx + 28.6f * s, my + 28.2f * s);
                c.drawArc(r, 270, 90, true, p);
            }
            Paint st = Draw.paint();
            st.setStyle(Paint.Style.STROKE);
            st.setStrokeWidth(2.5f * s);
            st.setColor(0xFF8E99A8);
            r.set(mx + 2 * s, my + 2 * s, mx + 30 * s, my + 42 * s);
            c.drawRoundRect(r, 14 * s, 14 * s, st);
            c.drawLine(mx + 2 * s, my + 17 * s, mx + 30 * s, my + 17 * s, st);
            c.drawLine(mx + 16 * s, my + 2 * s, mx + 16 * s, my + 17 * s, st);
            r.set(mx + 13.5f * s, my + 6 * s, mx + 18.5f * s, my + 14 * s);
            p.setColor(lit == 3 ? Ui.TEXT : 0xFF1C222B);
            c.drawRoundRect(r, 2.5f * s, 2.5f * s, p);
            st.setStrokeWidth(2 * s);
            c.drawRoundRect(r, 2.5f * s, 2.5f * s, st);
            c.drawText(row[1], ui.f(32 + 14), Draw.centerBaseline(input, h / 2), input);
            c.drawText(row[2], w, Draw.centerBaseline(action, h / 2), action);
        }
    }

    /** The phone remote's QR code, on white. */
    static final class QrView extends View {
        private final Ui ui;
        private final RectF r = new RectF();
        private String[] rows;

        QrView(Context c, Ui ui) {
            super(c);
            this.ui = ui;
        }

        void set(String[] rows) {
            this.rows = rows;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            r.set(0, 0, w, getHeight());
            Draw.round(c, r, ui.f(20), 0xFFFFFFFF);
            float pad = ui.f(10);
            if (rows == null || rows.length == 0) {
                float is = ui.f(64);
                Icons.draw(c, Icons.PHONE, (w - is) / 2, (getHeight() - is) / 2, is, 1.6f, 0xFFC3CBD6, false);
                return;
            }
            // A one-module quiet zone, like the design's viewBox -1 -1 27 27.
            int n = rows.length;
            float cell = (w - 2 * pad) / (n + 2);
            Paint p = Draw.paint();
            p.setAntiAlias(false);
            p.setColor(0xFF151A21);
            for (int y = 0; y < n; y++) {
                String row = rows[y];
                for (int x = 0; x < row.length(); x++) {
                    if (row.charAt(x) == '1') {
                        float left = pad + (x + 1) * cell, top = pad + (y + 1) * cell;
                        c.drawRect(left, top, left + cell + 0.5f, top + cell + 0.5f, p);
                    }
                }
            }
        }
    }
}

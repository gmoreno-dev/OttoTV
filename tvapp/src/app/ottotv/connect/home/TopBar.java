package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.ottotv.connect.R;

/**
 * The bar at the top of the home screen: Otto TV's logo, a "No internet"
 * notice with a button while offline, the indicators and the clock.
 * 96 px from the sides, 54 to 110 from the top.
 */
final class TopBar extends FrameLayout {
    private final Ui ui;
    final StatusPill status;
    final TextView clock;
    final PillButton wifiButton;
    private final View offline;
    private final UpdateChip update;

    TopBar(Context c, Ui ui) {
        super(c);
        this.ui = ui;
        setClipChildren(false);
        setClipToPadding(false);

        LinearLayout brand = new LinearLayout(c);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.home_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.addView(logo, new LinearLayout.LayoutParams(ui.px(56), ui.px(56)));
        TextView name = ui.text("Otto TV", 30, 700, Ui.TEXT);
        name.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.leftMargin = ui.px(12);
        brand.addView(name, np);
        addView(brand, new LayoutParams(-2, ui.px(56), Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        update = new UpdateChip(c, ui);
        update.setVisibility(GONE);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-2, ui.px(52));
        up.rightMargin = ui.px(16);
        right.addView(update, up);
        status = new StatusPill(c, ui);
        status.labels(c.getString(R.string.home_phone), c.getString(R.string.home_cable));
        right.addView(status, new LinearLayout.LayoutParams(-2, ui.px(52)));
        clock = ui.text("", 32, 600, Ui.TEXT);
        clock.setLetterSpacing(-0.01f);
        clock.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.leftMargin = ui.px(22);
        right.addView(clock, cp);
        addView(right, new LayoutParams(-2, ui.px(56), Gravity.END | Gravity.CENTER_VERTICAL));

        // "No internet  [Choose a Wi-Fi network]", centered.
        Notice notice = new Notice(c, ui);
        notice.setOrientation(LinearLayout.HORIZONTAL);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(ui.px(22), 0, ui.px(6), 0);
        notice.setClipChildren(false);
        notice.setClipToPadding(false);
        OfflineIcon icon = new OfflineIcon(c, ui);
        notice.addView(icon, new LinearLayout.LayoutParams(ui.px(28), ui.px(28)));
        TextView noNet = ui.text(c.getString(R.string.home_offline), 24, 600, Ui.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = ui.px(14);
        notice.addView(noNet, tp);
        wifiButton = new PillButton(c, ui, c.getString(R.string.home_choose_wifi), 44, 20, 20, 24)
                .colors(Ui.alpha(0xFFFFFF, 0.14f), 0xFFFFFFFF, Ui.ACCENT, Ui.ON_ACCENT);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, ui.px(44));
        bp.leftMargin = ui.px(14);
        notice.addView(wifiButton, bp);
        offline = notice;
        offline.setVisibility(GONE);
        addView(offline, new LayoutParams(-2, ui.px(56), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
    }

    void showOffline(boolean show) {
        offline.setVisibility(show ? VISIBLE : GONE);
    }

    boolean offlineShown() {
        return offline.getVisibility() == VISIBLE;
    }

    /** "Update available" and its icon; "" hides it. */
    void setUpdate(String text, String icon) {
        update.set(text, icon);
        update.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    /** An update waiting (Updates): an icon in amber and a few words, in dark glass. */
    static final class UpdateChip extends View {
        private final Ui ui;
        private final android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private String label = "";
        private String icon = Icons.DOWNLOAD;

        UpdateChip(Context c, Ui ui) {
            super(c);
            this.ui = ui;
            ui.type(text, 24, 600);
            text.setColor(Ui.TEXT);
        }

        void set(String label, String icon) {
            if (label.equals(this.label) && icon.equals(this.icon)) {
                return;
            }
            this.label = label;
            this.icon = icon;
            setContentDescription(label);
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            float width = ui.f(18) + ui.f(26) + ui.f(10) + text.measureText(label) + ui.f(22);
            setMeasuredDimension(Math.round(width), ui.px(52));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            box.set(0, 0, getWidth(), h);
            Draw.round(c, box, h / 2, Ui.alpha(0x0A0D12, 0.55f));
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, ui.f(1)));
            p.setColor(Ui.alpha(0xFFFFFF, 0.1f));
            box.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, p);
            float is = ui.f(26);
            Icons.draw(c, icon, ui.f(18), h / 2 - is / 2, is, 2.4f, Ui.ACCENT, false);
            c.drawText(label, ui.f(18) + is + ui.f(10), Draw.centerBaseline(text, h / 2), text);
        }
    }

    /** The notice's rounded box: dark glass with a hairline border. */
    static final class Notice extends LinearLayout {
        private final Ui ui;
        private final RectF box = new RectF();

        Notice(Context c, Ui ui) {
            super(c);
            this.ui = ui;
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            box.set(0, 0, getWidth(), getHeight());
            Draw.round(c, box, getHeight() / 2f, Ui.alpha(0x0A0D12, 0.55f));
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, ui.f(1)));
            p.setColor(Ui.alpha(0xFFFFFF, 0.1f));
            box.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, p);
        }
    }

    /** Wi-Fi with a slash: arcs faded, dot and slash full. */
    static final class OfflineIcon extends View {
        OfflineIcon(Context c, Ui ui) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            StatusPill.drawWifi(c, 0, 0, getWidth(), 2, Ui.TEXT, false, 0);
        }
    }
}

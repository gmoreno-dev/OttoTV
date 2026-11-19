package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * The indicators at the top right: network (Wi-Fi strength, cable or none),
 * where the sound goes (TV or laptop) and whether a phone is connected.
 */
final class StatusPill extends View {
    static final int OFFLINE = 0;
    static final int WIFI = 1;
    static final int CABLE = 2;

    private final Ui ui;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Anim phoneFade = new Anim(0.45f, Ui.SLOW);

    private int net = WIFI;
    private int bars = 3;
    private String audio = "TV";
    private boolean phone;
    private String phoneLabel = "";
    private String cableLabel = "";

    StatusPill(Context c, Ui ui) {
        super(c);
        this.ui = ui;
        ui.type(text, 24, 600);
        text.setColor(Ui.ICON);
    }

    void labels(String phoneLabel, String cableLabel) {
        this.phoneLabel = phoneLabel;
        this.cableLabel = cableLabel;
    }

    /** bars: 1 to 3 Wi-Fi arcs lit. audio: "TV", "Notebook" or null to hide it. */
    void set(int net, int bars, String audio, boolean phone, boolean animate) {
        boolean resize = net != this.net || phone != this.phone
                || (audio == null ? this.audio != null : !audio.equals(this.audio));
        boolean changed = resize || bars != this.bars;
        this.net = net;
        this.bars = bars;
        this.audio = audio;
        this.phone = phone;
        changed |= phoneFade.set(phone ? 1 : 0.45f, animate);
        if (resize) {
            requestLayout();
        }
        if (changed) {
            invalidate();
        }
        setContentDescription(describe());
    }

    private String describe() {
        return (net == OFFLINE ? "offline" : net == CABLE ? cableLabel : "wifi " + bars) + ", "
                + (audio == null ? "" : audio + ", ") + (phone ? phoneLabel : "");
    }

    private float contentWidth() {
        float icon = ui.f(28), gap = ui.f(22), small = ui.f(8);
        float w = icon;
        if (net == CABLE) {
            w += small + text.measureText(cableLabel);
        }
        if (audio != null) {
            w += gap + icon + small + text.measureText(audio);
        }
        w += gap + icon;
        if (phone) {
            w += small + text.measureText(phoneLabel);
        }
        return w;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) Math.ceil(contentWidth() + ui.f(44)), ui.px(52));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        Draw.round(c, box, h / 2, Ui.alpha(0x0A0D12, 0.4f));
        float icon = ui.f(28), gap = ui.f(22), small = ui.f(8);
        float x = ui.f(22), y = (h - icon) / 2;
        float base = Draw.centerBaseline(text, h / 2);
        int col = Ui.ICON;
        if (net == CABLE) {
            Icons.draw(c, Icons.CABLE, x, y, icon, 2, col, false);
            x += icon + small;
            c.drawText(cableLabel, x, base, text);
            x += text.measureText(cableLabel);
        } else {
            wifi(c, x, y, icon, col);
            x += icon;
        }
        if (audio != null) {
            x += gap;
            Icons.draw(c, Icons.SPEAKER, x, y, icon, 2, col, false);
            x += icon + small;
            c.drawText(audio, x, base, text);
            x += text.measureText(audio);
        }
        x += gap;
        float a = phoneFade.get();
        int pc = Ui.alpha(col, a);
        Icons.draw(c, Icons.PHONE, x, y, icon, 2, pc, false);
        if (phone) {
            text.setColor(pc);
            c.drawText(phoneLabel, x + icon + small, base, text);
            text.setColor(Ui.ICON);
        }
        if (phoneFade.running()) {
            postInvalidateOnAnimation();
        }
    }

    private void wifi(Canvas c, float x, float y, float size, int col) {
        if (net == OFFLINE) {
            int dim = Ui.alpha(col, 0.45f);
            Icons.draw(c, Icons.WIFI_3, x, y, size, 2, dim, false);
            Icons.draw(c, Icons.WIFI_2, x, y, size, 2, dim, false);
            Icons.draw(c, Icons.WIFI_1, x, y, size, 2, dim, false);
            Icons.draw(c, Icons.WIFI_DOT, x, y, size, 2, col, false);
            Icons.draw(c, Icons.WIFI_SLASH, x, y, size, 2, col, false);
            return;
        }
        int dim = Ui.alpha(col, 0.3f);
        Icons.draw(c, Icons.WIFI_3, x, y, size, 2, bars >= 3 ? col : dim, false);
        Icons.draw(c, Icons.WIFI_2, x, y, size, 2, bars >= 2 ? col : dim, false);
        Icons.draw(c, Icons.WIFI_1, x, y, size, 2, col, false);
        Icons.draw(c, Icons.WIFI_DOT, x, y, size, 2, col, false);
    }

    /** The Wi-Fi icon by itself, for other places that show the network. */
    static void drawWifi(Canvas c, float x, float y, float size, float stroke, int col, boolean online, int bars) {
        if (!online) {
            int dim = Ui.alpha(col, 0.45f);
            Icons.draw(c, Icons.WARNING_WIFI, x, y, size, stroke, dim, false);
            Icons.draw(c, Icons.WIFI_DOT + " " + Icons.WIFI_SLASH, x, y, size, stroke, col, false);
            return;
        }
        int dim = Ui.alpha(col, 0.3f);
        Icons.draw(c, Icons.WIFI_3, x, y, size, stroke, bars >= 3 ? col : dim, false);
        Icons.draw(c, Icons.WIFI_2, x, y, size, stroke, bars >= 2 ? col : dim, false);
        Icons.draw(c, Icons.WIFI_1 + " " + Icons.WIFI_DOT, x, y, size, stroke, col, false);
    }
}

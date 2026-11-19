package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;

/** A screen title with a count after it on the same baseline: "All apps  25 apps". */
final class TitleRow extends View {
    private final Ui ui;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint count = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String titleText;
    private String countText = "";

    TitleRow(Context c, Ui ui, String titleText) {
        super(c);
        this.ui = ui;
        this.titleText = titleText;
        ui.type(title, 56, 800);
        title.setLetterSpacing(-0.03f);
        title.setColor(Ui.TEXT);
        ui.type(count, 28, 600);
        count.setColor(Ui.TEXT2);
        setContentDescription(titleText);
    }

    void setCount(String s) {
        if (!s.equals(countText)) {
            countText = s;
            setContentDescription(titleText + ", " + s);
            requestLayout();
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        float width = title.measureText(titleText) + ui.f(20) + count.measureText(countText);
        setMeasuredDimension((int) Math.ceil(width), ui.px(56 * 1.1f));
    }

    @Override
    protected void onDraw(Canvas c) {
        float base = Draw.centerBaseline(title, getHeight() / 2f);
        c.drawText(titleText, 0, base, title);
        c.drawText(countText, title.measureText(titleText) + ui.f(20), base, count);
    }
}

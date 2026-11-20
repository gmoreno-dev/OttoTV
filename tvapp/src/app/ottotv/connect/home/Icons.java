package app.ottotv.connect.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * The design's line icons: SVG path data on a 24x24 grid, stroked with
 * round ends. Drawn straight from the strings, so they stay sharp at any
 * scale.
 */
final class Icons {
    static final String WIFI_3 = "M2.5 9a14 14 0 0 1 19 0";
    static final String WIFI_2 = "M5.5 12.5a9.5 9.5 0 0 1 13 0";
    static final String WIFI_1 = "M8.8 16a5 5 0 0 1 6.4 0";
    static final String WIFI_DOT = "M12 19.5h.01";
    static final String WIFI_SLASH = "M4 3.5l16 17";
    static final String WIFI = WIFI_3 + " " + WIFI_2 + " " + WIFI_1 + " " + WIFI_DOT;
    static final String CABLE = "M9 3.5h6v4H9z M3 15.5h4v5H3z M10 15.5h4v5h-4z M17 15.5h4v5h-4z"
            + " M12 7.5v4 M5 11.5h14 M5 11.5v4 M19 11.5v4 M12 11.5v4";
    static final String SPEAKER = "M4 9.5v5h3.5L13 19V5L7.5 9.5H4z M16.5 9a4.5 4.5 0 0 1 0 6 M19 6.5a8 8 0 0 1 0 11";
    static final String PHONE = "M8.5 2.5h7a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2z M11 18h2";
    static final String PLAY = "M8 5.5v13l10.5-6.5z"; // filled
    static final String GRID = "M4 4h6.5v6.5H4z M13.5 4H20v6.5h-6.5z M4 13.5h6.5V20H4z M13.5 13.5H20V20h-6.5z";
    static final String PLUS = "M12 5v14 M5 12h14";
    static final String OPEN = "M7 17L17 7 M9 7h8v8";
    static final String PIN = "M9 4h6 M10 4v5.5L7 13h10l-3-3.5V4 M12 13v7";
    static final String UNPIN = PIN + " M4 4l16 16";
    static final String MOVE = "M7 8l-4 4 4 4 M17 8l4 4-4 4 M3 12h18";
    static final String INFO = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z M12 11v5.5 M12 7.5h.01";
    static final String TRASH = "M4 7h16 M9.5 7V4.5h5V7 M6 7l1 13h10l1-13";
    static final String POWER = "M12 3v8 M6.3 6.8a8 8 0 1 0 11.4 0";
    static final String LAPTOP = "M4 5h16v11H4z M2 19.5h20";
    static final String LAPTOP_OFF = LAPTOP + " M3 3l18 18";
    static final String SHIELD = "M12 3l7.5 3v5.5c0 4.6-3.2 8.2-7.5 9.5c-4.3-1.3-7.5-4.9-7.5-9.5V6z";
    static final String SHIELD_ON = SHIELD + " M8.8 12l2.2 2.2 4.2-4.4";
    static final String SHIELD_OFF = SHIELD + " M3 3l18 18";
    static final String CLOSE_APP = "M4 4h16v16H4z M9 9l6 6 M15 9l-6 6";
    static final String BLUETOOTH = "M7 7l10 10-5 4V3l5 4L7 17";
    static final String BAG = "M5.5 8h13l-1.2 12.5H6.7z M9 8V6.5a3 3 0 0 1 6 0V8";
    static final String BOX = "M3.5 7.5L12 3l8.5 4.5v9L12 21l-8.5-4.5z M3.5 7.5L12 12l8.5-4.5 M12 12v9";
    static final String QR = "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2 M18 14h2v2 M14 18v2h2 M18 18h2v2h-2";
    static final String CHEVRON_DOWN = "M6 9l6 6 6-6";
    static final String DOWNLOAD = "M12 4v11 M7 10.5l5 5 5-5 M5 20h14";
    static final String RESTART = "M20 12a8 8 0 1 1-2.34-5.66 M20 4v5h-5";
    static final String CHEVRON_LEFT = "M15 6l-6 6 6 6";
    static final String CHEVRON_RIGHT = "M9 6l6 6-6 6";
    static final String KEYBOARD = "M4 6.5h16a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"
            + " M6 10h.01 M10 10h.01 M14 10h.01 M18 10h.01 M8 14h8";
    static final String MOUSE = "M12 3a6 6 0 0 1 6 6v6a6 6 0 0 1-12 0V9a6 6 0 0 1 6-6z M12 7v3";
    static final String ARROW_UP = "M12 19V5 M6 11l6-6 6 6";
    static final String ARROW_DOWN = "M12 5v14 M6 13l6 6 6-6";
    static final String ARROW_LEFT = "M19 12H5 M11 6l-6 6 6 6";
    static final String ARROW_RIGHT = "M5 12h14 M13 6l6 6-6 6";
    static final String VOL = "M3 9.5v5h3.5L12 19V5L6.5 9.5H3z";
    static final String VOL_DOWN = VOL + " M16 12h5";
    static final String VOL_UP = VOL + " M16 12h5 M18.5 9.5v5";
    static final String VOL_MUTE = VOL + " M16 9.5l5 5 M21 9.5l-5 5";
    static final String SCREEN_KEY = "M3.5 5h17v11h-17z M9 20h6 M12 16v4";
    static final String WARNING_WIFI = WIFI_3 + " " + WIFI_2 + " " + WIFI_1;

    private static final Map<String, Path> CACHE = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Matrix MATRIX = new android.graphics.Matrix();
    private static final Path SCALED = new Path();

    private Icons() {
    }

    static Path path(String d) {
        Path p = CACHE.get(d);
        if (p == null) {
            p = new Path();
            Svg.parse(d, new PathSink(p));
            CACHE.put(d, p);
        }
        return p;
    }

    /**
     * Draws an icon with its 24x24 grid scaled to size x size at (x, y).
     * stroke is in the grid's own units, like the SVG's stroke-width.
     */
    static void draw(Canvas c, String d, float x, float y, float size, float stroke, int color, boolean fill) {
        float s = size / 24f;
        MATRIX.setScale(s, s);
        MATRIX.postTranslate(x, y);
        path(d).transform(MATRIX, SCALED);
        PAINT.reset();
        PAINT.setAntiAlias(true);
        PAINT.setColor(color);
        if (fill) {
            PAINT.setStyle(Paint.Style.FILL);
        } else {
            PAINT.setStyle(Paint.Style.STROKE);
            PAINT.setStrokeWidth(stroke * s);
            PAINT.setStrokeCap(Paint.Cap.ROUND);
            PAINT.setStrokeJoin(Paint.Join.ROUND);
        }
        c.drawPath(SCALED, PAINT);
    }

    /** Svg's output, straight into an Android path. */
    static final class PathSink implements Svg.Sink {
        private final Path p;

        PathSink(Path p) {
            this.p = p;
        }

        @Override
        public void moveTo(float x, float y) {
            p.moveTo(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            p.lineTo(x, y);
        }

        @Override
        public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) {
            p.cubicTo(x1, y1, x2, y2, x, y);
        }

        @Override
        public void quadTo(float x1, float y1, float x, float y) {
            p.quadTo(x1, y1, x, y);
        }

        @Override
        public void close() {
            p.close();
        }
    }

    /** One icon as a view, for layouts. */
    static final class IconView extends View {
        private String d;
        private float stroke;
        private int color;
        private boolean fill;

        IconView(Context c, String d, float stroke, int color) {
            super(c);
            this.d = d;
            this.stroke = stroke;
            this.color = color;
        }

        IconView filled() {
            fill = true;
            return this;
        }

        void set(String d, int color) {
            if (!d.equals(this.d) || color != this.color) {
                this.d = d;
                this.color = color;
                invalidate();
            }
        }

        void setColor(int color) {
            if (color != this.color) {
                this.color = color;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight());
            Icons.draw(c, d, (getWidth() - size) / 2, (getHeight() - size) / 2, size, stroke, color, fill);
        }
    }
}

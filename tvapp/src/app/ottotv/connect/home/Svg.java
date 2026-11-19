package app.ottotv.connect.home;

/**
 * Reads SVG path data ("M4 9.5v5h3.5L13 19V5z") into drawing calls, so the
 * design's icons are drawn from the same strings the design uses, sharp at
 * any size. Absolute and relative commands, smooth curves and arcs (turned
 * into cubic curves, as Android paths have no SVG arcs).
 *
 * Plain Java: tests run it on the JVM (tvapp/test/SvgTest.java).
 */
final class Svg {
    /** Receives the path, all in absolute coordinates. */
    interface Sink {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void cubicTo(float x1, float y1, float x2, float y2, float x, float y);

        void quadTo(float x1, float y1, float x, float y);

        void close();
    }

    private final String d;
    private int i;

    private Svg(String d) {
        this.d = d;
    }

    static void parse(String d, Sink out) {
        new Svg(d).run(out);
    }

    private void run(Sink out) {
        float x = 0, y = 0;         // current point
        float sx = 0, sy = 0;       // subpath start
        float cx = 0, cy = 0;       // last control point, for S and T
        char last = ' ';
        char cmd = ' ';
        while (true) {
            skipSeparators();
            if (i >= d.length()) {
                return;
            }
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == ' ') {
                throw new IllegalArgumentException("path data must start with a command: " + d);
            } else if (cmd == 'M') {
                cmd = 'L'; // more pairs after a moveto are linetos
            } else if (cmd == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + number();
                    y = oy + number();
                    sx = x;
                    sy = y;
                    out.moveTo(x, y);
                    break;
                case 'L':
                    x = ox + number();
                    y = oy + number();
                    out.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + number();
                    out.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + number();
                    out.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + number(), y1 = oy + number();
                    float x2 = ox + number(), y2 = oy + number();
                    x = ox + number();
                    y = oy + number();
                    out.cubicTo(x1, y1, x2, y2, x, y);
                    cx = x2;
                    cy = y2;
                    break;
                }
                case 'S': {
                    boolean smooth = "CcSs".indexOf(last) >= 0;
                    float x1 = smooth ? 2 * x - cx : x, y1 = smooth ? 2 * y - cy : y;
                    float x2 = ox + number(), y2 = oy + number();
                    x = ox + number();
                    y = oy + number();
                    out.cubicTo(x1, y1, x2, y2, x, y);
                    cx = x2;
                    cy = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + number(), y1 = oy + number();
                    x = ox + number();
                    y = oy + number();
                    out.quadTo(x1, y1, x, y);
                    cx = x1;
                    cy = y1;
                    break;
                }
                case 'T': {
                    boolean smooth = "QqTt".indexOf(last) >= 0;
                    float x1 = smooth ? 2 * x - cx : x, y1 = smooth ? 2 * y - cy : y;
                    x = ox + number();
                    y = oy + number();
                    out.quadTo(x1, y1, x, y);
                    cx = x1;
                    cy = y1;
                    break;
                }
                case 'A': {
                    float rx = number(), ry = number(), rot = number();
                    boolean large = flag(), sweep = flag();
                    float ex = ox + number(), ey = oy + number();
                    arc(out, x, y, rx, ry, rot, large, sweep, ex, ey);
                    x = ex;
                    y = ey;
                    break;
                }
                case 'Z':
                    out.close();
                    x = sx;
                    y = sy;
                    break;
                default:
                    throw new IllegalArgumentException("unknown path command '" + cmd + "' in " + d);
            }
            last = cmd;
        }
    }

    private void skipSeparators() {
        while (i < d.length()) {
            char c = d.charAt(i);
            if (c != ' ' && c != ',' && c != '\n' && c != '\t' && c != '\r') {
                return;
            }
            i++;
        }
    }

    private boolean flag() {
        skipSeparators();
        if (i >= d.length()) {
            throw new IllegalArgumentException("arc flag missing in " + d);
        }
        char c = d.charAt(i++);
        if (c != '0' && c != '1') {
            throw new IllegalArgumentException("bad arc flag '" + c + "' in " + d);
        }
        return c == '1';
    }

    private float number() {
        skipSeparators();
        int start = i;
        if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
            i++;
        }
        boolean dot = false;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && i > start) {
                i++;
                if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
                    i++;
                }
            } else {
                break;
            }
        }
        if (start == i) {
            throw new IllegalArgumentException("number expected at " + start + " in " + d);
        }
        return Float.parseFloat(d.substring(start, i));
    }

    /** SVG arc (endpoint form) as cubic curves, at most a quarter turn each. */
    static void arc(Sink out, float x0, float y0, float rx, float ry, float angle,
            boolean large, boolean sweep, float x, float y) {
        if (x0 == x && y0 == y) {
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            out.lineTo(x, y);
            return;
        }
        double phi = Math.toRadians(angle);
        double cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double x1 = cos * dx + sin * dy;
        double y1 = -sin * dx + cos * dy;
        double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        double r1 = rx, r2 = ry;
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            r1 *= s;
            r2 *= s;
        }
        double num = r1 * r1 * r2 * r2 - r1 * r1 * y1 * y1 - r2 * r2 * x1 * x1;
        double den = r1 * r1 * y1 * y1 + r2 * r2 * x1 * x1;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cx1 = coef * (r1 * y1 / r2);
        double cy1 = coef * -(r2 * x1 / r1);
        double cx = cos * cx1 - sin * cy1 + (x0 + x) / 2.0;
        double cy = sin * cx1 + cos * cy1 + (y0 + y) / 2.0;
        double t1 = Math.atan2((y1 - cy1) / r2, (x1 - cx1) / r1);
        double t2 = Math.atan2((-y1 - cy1) / r2, (-x1 - cx1) / r1);
        double dt = t2 - t1;
        if (sweep && dt < 0) {
            dt += 2 * Math.PI;
        } else if (!sweep && dt > 0) {
            dt -= 2 * Math.PI;
        }
        int n = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-9);
        double step = dt / n;
        double alpha = 4.0 / 3.0 * Math.tan(step / 4);
        double t = t1;
        for (int k = 0; k < n; k++) {
            double a = t, b = t + step;
            double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
            // Points on the unit ellipse, then scaled, rotated and moved.
            double p1x = ca - alpha * sa, p1y = sa + alpha * ca;
            double p2x = cb + alpha * sb, p2y = sb - alpha * cb;
            out.cubicTo(
                    (float) (cx + cos * r1 * p1x - sin * r2 * p1y), (float) (cy + sin * r1 * p1x + cos * r2 * p1y),
                    (float) (cx + cos * r1 * p2x - sin * r2 * p2y), (float) (cy + sin * r1 * p2x + cos * r2 * p2y),
                    k == n - 1 ? x : (float) (cx + cos * r1 * cb - sin * r2 * sb),
                    k == n - 1 ? y : (float) (cy + sin * r1 * cb + cos * r2 * sb));
            t = b;
        }
    }
}

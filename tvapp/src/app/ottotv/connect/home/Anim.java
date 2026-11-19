package app.ottotv.connect.home;

import android.os.SystemClock;

/**
 * One animated number: eases out towards its target over a fixed time.
 * Views keep a few of these and redraw while any is moving, which keeps the
 * design's rule of animating only transforms and opacity, 150 to 200 ms.
 */
final class Anim {
    private float from;
    private float to;
    private long start;
    private final int duration;

    Anim(float value, int duration) {
        this.from = value;
        this.to = value;
        this.duration = duration;
    }

    /** Moves towards target; animate=false jumps there. Returns true if it changed. */
    boolean set(float target, boolean animate) {
        if (target == to && (animate || !running())) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        from = animate ? value(now) : target;
        to = target;
        start = now;
        return true;
    }

    float get() {
        return value(SystemClock.uptimeMillis());
    }

    float target() {
        return to;
    }

    boolean running() {
        return SystemClock.uptimeMillis() - start < duration && from != to;
    }

    private float value(long now) {
        long t = now - start;
        if (t >= duration || from == to) {
            return to;
        }
        float x = t / (float) duration;
        // Ease-out, close to CSS ease-out.
        float e = 1 - (1 - x) * (1 - x);
        return from + (to - from) * e;
    }
}

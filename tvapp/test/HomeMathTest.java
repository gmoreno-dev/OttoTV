package app.ottotv.connect.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs on a desktop JVM (tests/run.sh): the home screen's path reader
 * (Svg) and app colors (Tone) are plain Java.
 */
public final class HomeMathTest {
    private static int failures;

    public static void main(String[] args) {
        svg();
        tone();
        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ok");
    }

    // ---- Svg ---------------------------------------------------------------------

    /** Records the drawing calls as text, endpoints rounded to 0.01. */
    static final class Rec implements Svg.Sink {
        final List<String> ops = new ArrayList<>();
        float x;
        float y;

        private static String n(float v) {
            float r = Math.round(v * 100) / 100f;
            return r == (int) r ? String.valueOf((int) r) : String.valueOf(r);
        }

        @Override
        public void moveTo(float x, float y) {
            ops.add("M" + n(x) + "," + n(y));
            this.x = x;
            this.y = y;
        }

        @Override
        public void lineTo(float x, float y) {
            ops.add("L" + n(x) + "," + n(y));
            this.x = x;
            this.y = y;
        }

        @Override
        public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) {
            ops.add("C" + n(x) + "," + n(y));
            this.x = x;
            this.y = y;
        }

        @Override
        public void quadTo(float x1, float y1, float x, float y) {
            ops.add("Q" + n(x) + "," + n(y));
            this.x = x;
            this.y = y;
        }

        @Override
        public void close() {
            ops.add("Z");
        }
    }

    static List<String> parse(String d) {
        Rec r = new Rec();
        Svg.parse(d, r);
        return r.ops;
    }

    static void svg() {
        // The speaker: absolute and relative lines, H and V.
        eq("lines", Arrays.asList("M4,9.5", "L4,14.5", "L7.5,14.5", "L13,19", "L13,5", "L7.5,9.5", "L4,9.5", "Z"),
                parse("M4 9.5v5h3.5L13 19V5L7.5 9.5H4z"));
        // Numbers run together the way minified SVG writes them.
        eq("packed numbers", Arrays.asList("M0.5,0.5", "L-0.5,-0.5", "L1000,0.25"), parse("M.5.5l-1-1L1e3 .25"));
        // More pairs after M are lines.
        eq("implicit lineto", Arrays.asList("M1,2", "L3,4", "L5,6"), parse("M1 2 3 4 5 6"));
        eq("implicit relative lineto", Arrays.asList("M1,1", "L2,2", "L3,3"), parse("m1 1 1 1 1 1"));
        // A circle from two half arcs ends where it started.
        Rec circle = new Rec();
        Svg.parse("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", circle);
        check("circle back to start", Math.abs(circle.x - 21) < 0.01 && Math.abs(circle.y - 12) < 0.01);
        check("half arc is two quarter curves", count(circle.ops, "C") == 4);
        List<String> half = parse("M3 12a9 9 0 0 1 18 0");
        eq("arc endpoint", "C21,12", half.get(half.size() - 1));
        // Wi-Fi arc: endpoints exact.
        List<String> wifi = parse("M2.5 9a14 14 0 0 1 19 0");
        eq("wifi arc endpoint", "C21.5,9", wifi.get(wifi.size() - 1));
        // Radii too small get scaled up instead of failing.
        List<String> tiny = parse("M0 0a1 1 0 0 1 10 0");
        eq("tiny radius arc endpoint", "C10,0", tiny.get(tiny.size() - 1));
        // Smooth curves and quadratics.
        eq("smooth cubic", Arrays.asList("M0,0", "C3,0", "C6,0"), parse("M0 0c1 1 2 1 3 0s2-1 3 0"));
        eq("quadratic and smooth", Arrays.asList("M0,0", "Q2,0", "Q4,0"), parse("M0 0q1 1 2 0t2 0"));
        // Every icon the launcher uses parses.
        for (String d : new String[] {Icons.WIFI, Icons.CABLE, Icons.SPEAKER, Icons.PHONE, Icons.PLAY, Icons.GRID,
            Icons.PLUS, Icons.OPEN, Icons.UNPIN, Icons.MOVE, Icons.INFO, Icons.TRASH, Icons.POWER, Icons.LAPTOP_OFF,
            Icons.CLOSE_APP, Icons.BLUETOOTH, Icons.BAG, Icons.BOX, Icons.QR, Icons.CHEVRON_DOWN, Icons.KEYBOARD,
            Icons.MOUSE, Icons.ARROW_UP, Icons.VOL_MUTE, Icons.VOL_UP, Icons.SCREEN_KEY}) {
            try {
                check("icon parses: " + d.substring(0, Math.min(20, d.length())), !parse(d).isEmpty());
            } catch (RuntimeException e) {
                check("icon parses: " + d + " (" + e.getMessage() + ")", false);
            }
        }
        boolean threw = false;
        try {
            parse("4 4 L5 5");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("data must start with a command", threw);
    }

    // ---- Tone ----------------------------------------------------------------------

    static double lum(int rgb) {
        double[] c = {((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    static void tone() {
        // The design's own numbers: the screen tint lands on luminance 0.10.
        for (int main : new int[] {0xFF0000, 0x29B6F6, 0xFFD21F, 0x39FF14, 0xF4F4F2}) {
            int t = Tone.tone(main, Tone.SCREEN, 0.62);
            check(String.format("tint of %06x has luminance 0.10 (%.3f)", main, lum(t)), Math.abs(lum(t) - 0.10) < 0.004);
        }
        // White turns into a bluish graphite, not gray.
        int white = Tone.tone(0xF4F4F2, Tone.SCREEN, 0.62);
        check("white becomes bluish graphite", (white & 255) > ((white >> 16) & 255));
        // Yellow leans towards gold: red stays ahead of green.
        int yellow = Tone.tone(0xFFD21F, Tone.SCREEN, 0.62);
        check("yellow leans to gold", ((yellow >> 16) & 255) > ((yellow >> 8) & 255));
        // Card backgrounds are much darker.
        check("card tone is dark", lum(Tone.tone(0x39FF14, Tone.CARD, 0.55)) < 0.03);
        check("focused card is lighter", lum(Tone.tone(0x39FF14, Tone.CARD_FOCUSED, 0.55))
                > lum(Tone.tone(0x39FF14, Tone.CARD, 0.55)));

        // Main colors.
        check("empty image is gray", Tone.main(new int[0]) == 0xFF808080);
        check("transparent pixels don't count", Tone.main(image(0x00FF0000, 90, 0xFF00FF00, 10)) == 0xFF00FF00);
        // A colorful background wins.
        eq("red banner", 0xFFFF0000, Tone.main(image(0xFFFF0000, 70, 0xFFFFFFFF, 30)));
        // White background with a small logo: white (the design's Farol: graphite).
        eq("white banner, small logo", 0xFFFEFEFE, Tone.main(image(0xFFFEFEFE, 94, 0xFFE53935, 6)));
        // Dark background with a colorful logo: the logo (Zênite, Acervo).
        int z = Tone.main(image(0xFF121417, 85, 0xFFFF6D00, 15));
        check(String.format("orange on black picks orange (%08x)", z), z == 0xFFFF6D00);
        // A dark background with hardly any color stays dark.
        eq("dark with a speck", 0xFF121417, Tone.main(image(0xFF121417, 99, 0xFFFF6D00, 1)));
        // Navy is dark too: the light blue waves win (the design's Maré).
        int m = Tone.main(image(0xFF0B1D33, 60, 0xFF1E88E5, 25, 0xFF29B6F6, 15));
        check(String.format("navy with blue waves picks blue (%08x)", m), ((m >> 16) & 255) < 60 && (m & 255) > 200);
    }

    /** Pixels: color, percent, color, percent... over 100 pixels. */
    static int[] image(int... spec) {
        List<Integer> px = new ArrayList<>();
        for (int i = 0; i < spec.length; i += 2) {
            for (int k = 0; k < spec[i + 1]; k++) {
                px.add(spec[i]);
            }
        }
        int[] out = new int[px.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = px.get(i);
        }
        return out;
    }

    static int count(List<String> ops, String prefix) {
        int n = 0;
        for (String s : ops) {
            if (s.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    static void eq(String what, Object want, Object got) {
        if (!want.equals(got)) {
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
            failures++;
        }
    }

    static void eq(String what, int want, int got) {
        if (want != got) {
            System.out.println(String.format("FAIL %s: want %08x, got %08x", what, want, got));
            failures++;
        }
    }

    static void check(String what, boolean ok) {
        if (!ok) {
            System.out.println("FAIL " + what);
            failures++;
        }
    }
}

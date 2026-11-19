package app.ottotv.connect.home;

/**
 * Colors that come from each app's own art, by the design's rules.
 *
 * The screen behind the focused app takes the app's main color: same hue,
 * saturation capped at 62% and brightness adjusted until the relative
 * luminance is 0.10. White and gray (saturation under 12% or lightness over
 * 92%) turn into a bluish graphite, and yellow (hue 42 to 75 degrees) leans
 * 10 degrees towards gold. Cards for apps without a banner use the same rule
 * at a luminance of 0.024 (0.042 when focused).
 *
 * Plain Java: tests run it on the JVM (tvapp/test/ToneTest.java).
 */
final class Tone {
    static final double SCREEN = 0.10;
    static final double CARD = 0.024;
    static final double CARD_FOCUSED = 0.042;

    private Tone() {
    }

    /** The design's tone(): rgb is 0xRRGGBB (alpha ignored), returns 0xFFRRGGBB. */
    static int tone(int rgb, double lum, double maxSat) {
        double r = ((rgb >> 16) & 255) / 255.0;
        double g = ((rgb >> 8) & 255) / 255.0;
        double b = (rgb & 255) / 255.0;
        double mx = Math.max(r, Math.max(g, b));
        double mn = Math.min(r, Math.min(g, b));
        double d = mx - mn;
        double h = 0;
        if (d != 0) {
            if (mx == r) {
                h = ((g - b) / d) % 6;
            } else if (mx == g) {
                h = (b - r) / d + 2;
            } else {
                h = (r - g) / d + 4;
            }
            h *= 60;
            if (h < 0) {
                h += 360;
            }
        }
        double l0 = (mx + mn) / 2;
        double s = d != 0 ? d / (1 - Math.abs(2 * l0 - 1)) : 0;
        if (s < 0.12 || l0 > 0.92) {
            h = 215;
            s = 0.16;
        } else {
            s = Math.min(s, maxSat);
        }
        if (h >= 42 && h <= 75) {
            h -= 10;
        }
        double lo = 0;
        double hi = 0.85;
        double[] out = hsl(h, s, 0.4);
        for (int i = 0; i < 20; i++) {
            double mid = (lo + hi) / 2;
            out = hsl(h, s, mid);
            if (luminance(out) > lum) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return 0xFF000000 | ((int) Math.round(out[0] * 255) << 16)
                | ((int) Math.round(out[1] * 255) << 8) | (int) Math.round(out[2] * 255);
    }

    private static double[] hsl(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double[] v;
        if (h < 60) {
            v = new double[] {c, x, 0};
        } else if (h < 120) {
            v = new double[] {x, c, 0};
        } else if (h < 180) {
            v = new double[] {0, c, x};
        } else if (h < 240) {
            v = new double[] {0, x, c};
        } else if (h < 300) {
            v = new double[] {x, 0, c};
        } else {
            v = new double[] {c, 0, x};
        }
        for (int i = 0; i < 3; i++) {
            v[i] = Math.max(0, Math.min(1, v[i] + m));
        }
        return v;
    }

    private static double lin(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double luminance(double[] v) {
        return 0.2126 * lin(v[0]) + 0.7152 * lin(v[1]) + 0.0722 * lin(v[2]);
    }

    /**
     * An app's main color, from its banner or icon: the color that covers
     * the most of it, unless that's a dark background with a colorful logo
     * on it (black, navy...), where the logo's color is the one people see.
     * That's how the design's sample apps got theirs: Pipoca's red
     * background, Farol's white one, Zênite's orange stripes on black.
     */
    static int main(int[] pixels) {
        int dom = dominant(pixels);
        if (lightness(dom) >= 0.18) {
            return dom;
        }
        // Vivid pixels by hue, in 18 slices of 20 degrees.
        long[] count = new long[18], sr = new long[18], sg = new long[18], sb = new long[18];
        long opaque = 0;
        for (int p : pixels) {
            if ((p >>> 24) < 128) {
                continue;
            }
            opaque++;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            double mx = Math.max(r, Math.max(g, b)) / 255.0, mn = Math.min(r, Math.min(g, b)) / 255.0;
            double l = (mx + mn) / 2, d = mx - mn;
            double s = d == 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
            if (s < 0.3 || l < 0.2 || l > 0.85) {
                continue;
            }
            int slice = (int) (hue(r, g, b) / 20) % 18;
            count[slice]++;
            sr[slice] += r;
            sg[slice] += g;
            sb[slice] += b;
        }
        int best = -1;
        long bestCount = 0;
        for (int i = 0; i < 18; i++) {
            long c = count[(i + 17) % 18] + count[i] + count[(i + 1) % 18];
            if (c > bestCount) {
                bestCount = c;
                best = i;
            }
        }
        if (best < 0 || bestCount < 0.03 * opaque) {
            return dom;
        }
        long n = 0, r = 0, g = 0, b = 0;
        for (int k = -1; k <= 1; k++) {
            int i = (best + k + 18) % 18;
            n += count[i];
            r += sr[i];
            g += sg[i];
            b += sb[i];
        }
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    static double lightness(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return (Math.max(r, Math.max(g, b)) + Math.min(r, Math.min(g, b))) / 510.0;
    }

    private static double hue(int r, int g, int b) {
        double rf = r / 255.0, gf = g / 255.0, bf = b / 255.0;
        double mx = Math.max(rf, Math.max(gf, bf)), mn = Math.min(rf, Math.min(gf, bf)), d = mx - mn;
        if (d == 0) {
            return 0;
        }
        double h;
        if (mx == rf) {
            h = ((gf - bf) / d) % 6;
        } else if (mx == gf) {
            h = (bf - rf) / d + 2;
        } else {
            h = (rf - gf) / d + 4;
        }
        h *= 60;
        return h < 0 ? h + 360 : h;
    }

    /**
     * The color that covers the most of an image: pixels (ARGB) go into
     * 4-bit-per-channel bins, mostly transparent ones are skipped, and the
     * fullest bin's average wins. Returns 0xFF808080 for an empty or fully
     * transparent image.
     */
    static int dominant(int[] pixels) {
        int[] count = new int[4096];
        long[] sr = new long[4096], sg = new long[4096], sb = new long[4096];
        int best = -1;
        for (int p : pixels) {
            if ((p >>> 24) < 128) {
                continue;
            }
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int bin = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            count[bin]++;
            sr[bin] += r;
            sg[bin] += g;
            sb[bin] += b;
            if (best < 0 || count[bin] > count[best]) {
                best = bin;
            }
        }
        if (best < 0) {
            return 0xFF808080;
        }
        int n = count[best];
        return 0xFF000000 | (int) (sr[best] / n) << 16 | (int) (sg[best] / n) << 8 | (int) (sb[best] / n);
    }
}

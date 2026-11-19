package app.ottotv.connect.home;

import android.content.ComponentName;
import android.graphics.Bitmap;

/**
 * One tile on the home screen: an installed app with its own art, or one of
 * the launcher's own tiles ("See all", "More apps").
 */
final class AppInfo {
    static final int APP = 0;
    static final int ALL = 1;
    static final int MORE = 2;

    final int kind;
    final String pkg;
    final ComponentName component;
    final String label;
    /** The app's 16:9 TV banner, already sized for the cards; null if it has none. */
    final Bitmap banner;
    final Bitmap icon;
    /** Comes with the system: can't be uninstalled, never shows in "Continue". */
    final boolean system;
    /** The color that covers most of the banner (or of the icon). */
    final int main;
    /** The screen's tint while this app is focused (Tone.SCREEN). */
    final int tint;
    final int card;
    final int cardFocused;

    AppInfo(int kind, String pkg, ComponentName component, String label, Bitmap banner, Bitmap icon,
            boolean system, int main) {
        this.kind = kind;
        this.pkg = pkg;
        this.component = component;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.system = system;
        this.main = main;
        this.tint = Tone.tone(main, Tone.SCREEN, 0.62);
        this.card = Tone.tone(main, Tone.CARD, 0.55);
        this.cardFocused = Tone.tone(main, Tone.CARD_FOCUSED, 0.55);
    }

    static AppInfo tile(int kind, String label) {
        // The design's colors for these tiles: slate for "See all", green for "More apps".
        return new AppInfo(kind, null, null, label, null, null, true, kind == ALL ? 0x606E86 : 0x467A64);
    }

    boolean isApp() {
        return kind == APP;
    }
}

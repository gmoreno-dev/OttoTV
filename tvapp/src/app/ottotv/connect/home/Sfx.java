package app.ottotv.connect.home;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

/**
 * The system's own sounds for moving and choosing, which Android plays when
 * its widgets move the focus. Our screens move the focus themselves, so
 * they ask for them here. They follow the system's setting (Settings,
 * sound effects), like everywhere else.
 */
final class Sfx {
    private Sfx() {
    }

    /** The focus moved, with an arrow key. */
    static void move(Context c, int keyCode) {
        play(c, effect(keyCode));
    }

    /** Something was chosen (OK, a click). */
    static void click(Context c) {
        play(c, AudioManager.FX_KEY_CLICK);
    }

    static int effect(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return AudioManager.FX_FOCUS_NAVIGATION_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return AudioManager.FX_FOCUS_NAVIGATION_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return AudioManager.FX_FOCUS_NAVIGATION_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return AudioManager.FX_FOCUS_NAVIGATION_RIGHT;
            default:
                return AudioManager.FX_KEY_CLICK;
        }
    }

    private static void play(Context c, int effect) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am != null) {
                am.playSoundEffect(effect);
            }
        } catch (RuntimeException e) {
            // no sound is better than no home screen
        }
    }
}

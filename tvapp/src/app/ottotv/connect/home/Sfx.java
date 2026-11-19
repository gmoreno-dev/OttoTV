package app.ottotv.connect.home;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

/**
 * The system's own sounds for moving and choosing, which Android plays when
 * its widgets move the focus. Our screens move the focus themselves, so
 * they ask for them here. They follow the system's setting (Settings,
 * sound effects), like everywhere else.
 *
 * Android loads these sounds once, at boot. On one boot of 0.6.2 they never
 * played until a restart: most likely the loading failed because the sound
 * server restarted right then (init-hooks.sh restarts it at boot complete
 * when the TV shows up late on HDMI). So the screens ask Android to load
 * them again now and then (reload): when they are loaded already, that
 * costs nothing.
 */
final class Sfx {
    private static final long EVERY_MS = 30_000;
    private static long reloadedAt = -EVERY_MS;
    private static final java.util.concurrent.ExecutorService BG =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private Sfx() {
    }

    /** Makes sure Android has the sounds loaded (off the main thread: it waits for the loading). */
    static void reload(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - reloadedAt < EVERY_MS) {
            return;
        }
        reloadedAt = now;
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) {
            return;
        }
        BG.execute(() -> {
            try {
                am.loadSoundEffects();
            } catch (RuntimeException e) {
                android.util.Log.i("OttoSfx", "loading the sound effects: " + e);
            }
        });
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

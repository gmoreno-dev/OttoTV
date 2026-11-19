package app.tvbox.connect;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The keyboard TV Box selects when a physical keyboard is plugged in.
 *
 * It never draws anything, so keys reach the app as if there were no
 * on-screen keyboard. What it adds is Enter in text fields: it runs the
 * field's action (Next, Go, Search, Done) like the TV keyboard's "›" button,
 * because Android TV screens such as the Wi-Fi password only continue on
 * that. EnterAction has the rules, including the big Enter key arriving as
 * OK (DPAD_CENTER) on this system.
 *
 * Logs under the "TVBoxKeyboard" tag: adb logcat -s TVBoxKeyboard
 */
public class KeyboardService extends InputMethodService {
    static final String TAG = "TVBoxKeyboard";

    @Override
    public boolean onEvaluateInputViewShown() {
        return false;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInput(EditorInfo ei, boolean restarting) {
        super.onStartInput(ei, restarting);
        if (ei != null) {
            Log.i(TAG, "field " + ei.packageName + " inputType=0x" + Integer.toHexString(ei.inputType)
                    + " imeOptions=0x" + Integer.toHexString(ei.imeOptions));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (decide(keyCode, event)) {
            case EnterAction.RUN_ACTION:
                return true; // runs on key up, like a button
            case EnterAction.SEND_ENTER:
                return sendAsEnter(event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (decide(keyCode, event)) {
            case EnterAction.RUN_ACTION:
                return runAction();
            case EnterAction.SEND_ENTER:
                return sendAsEnter(event);
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private int decide(int keyCode, KeyEvent event) {
        EditorInfo ei = getCurrentInputEditorInfo();
        boolean editing = ei != null && getCurrentInputConnection() != null
                && getCurrentInputStarted();
        int what = EnterAction.decide(keyCode, event.getScanCode(), editing,
                ei == null ? 0 : ei.inputType, ei == null ? 0 : ei.imeOptions);
        if (what != EnterAction.PASS || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            Log.i(TAG, KeyEvent.keyCodeToString(keyCode) + " scan=" + event.getScanCode()
                    + " action=" + event.getAction() + " editing=" + editing
                    + (ei == null ? "" : " inputType=0x" + Integer.toHexString(ei.inputType)
                            + " imeOptions=0x" + Integer.toHexString(ei.imeOptions))
                    + " -> " + what);
        }
        return what;
    }

    private boolean runAction() {
        EditorInfo ei = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ei != null && ic != null) {
            ic.performEditorAction(EnterAction.of(ei.inputType, ei.imeOptions));
        }
        return true;
    }

    /** Forwards OK from the Enter key as a real Enter (new line in notes, chats). */
    private boolean sendAsEnter(KeyEvent ok) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return false;
        }
        ic.sendKeyEvent(new KeyEvent(ok.getDownTime(), ok.getEventTime(), ok.getAction(),
                KeyEvent.KEYCODE_ENTER, ok.getRepeatCount(), ok.getMetaState(),
                ok.getDeviceId(), ok.getScanCode(), ok.getFlags(), ok.getSource()));
        return true;
    }
}

package app.ottotv.connect;

import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

/**
 * Decides what Enter on a physical keyboard does in a text field. Plain
 * Java on purpose (only compile-time constants from Android), so it is unit
 * tested on a desktop JVM: see tvapp/test/EnterActionTest.java.
 */
final class EnterAction {
    private EnterAction() {}

    /** Let the key reach the app untouched. */
    static final int PASS = 0;
    /** Run the field's editor action (Done, Next, Go, Search...). */
    static final int RUN_ACTION = 1;
    /** Send the app a real Enter key instead (new line, app's own handling). */
    static final int SEND_ENTER = 2;

    /** Linux KEY_ENTER, the big Enter key. KEY_KPENTER (keypad) is 96. */
    static final int SCAN_ENTER = 28;

    /**
     * What to do with one key press while a field is being edited.
     *
     * LineageOS TV x86's Generic.kl maps the big Enter key (scan code 28) to
     * DPAD_CENTER, so Enter works as the remote's OK button all over the TV.
     * In a text field OK does nothing, though, which is why Enter never
     * submitted the Wi-Fi password while keypad Enter (NUMPAD_ENTER) did. So
     * inside a text field, and only there, OK coming from that Enter key is
     * treated as Enter. The remote's own OK has another scan code and is
     * left alone.
     *
     * @param editing whether a real text field is focused (not a button the
     *                system hands a placeholder connection to)
     */
    static int decide(int keyCode, int scanCode, boolean editing, int inputType, int imeOptions) {
        if (!editing || (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) {
            return PASS; // buttons and lists: OK and Enter keep clicking
        }
        boolean enter = keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean enterAsOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER && scanCode == SCAN_ENTER;
        if (!enter && !enterAsOk) {
            return PASS;
        }
        if (of(inputType, imeOptions) != EditorInfo.IME_ACTION_NONE) {
            return RUN_ACTION;
        }
        // A real Enter already does the right thing; OK has to become one.
        return enterAsOk ? SEND_ENTER : PASS;
    }

    /**
     * Returns the editor action Enter should run, or
     * {@link EditorInfo#IME_ACTION_NONE} when Enter should reach the app as a
     * plain key (new lines in multi-line fields, fields that opt out).
     *
     * It mirrors what the TV keyboard's "›" button does
     * (InputMethodService.sendDefaultEditorAction): run the field's action
     * even when the field leaves it unspecified.
     */
    static int of(int inputType, int imeOptions) {
        boolean text = (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
        if (text && (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
            return EditorInfo.IME_ACTION_NONE;
        }
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return EditorInfo.IME_ACTION_NONE;
        }
        return imeOptions & EditorInfo.IME_MASK_ACTION;
    }
}

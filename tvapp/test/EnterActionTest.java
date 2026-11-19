package app.ottotv.connect;

import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

/**
 * Runs on a desktop JVM (tests/run.sh): EnterAction only uses Android's
 * compile-time constants, so android.jar is needed to compile, not to run.
 */
public final class EnterActionTest {
    private static int failures;

    public static void main(String[] args) {
        int text = InputType.TYPE_CLASS_TEXT;
        int password = text | InputType.TYPE_TEXT_VARIATION_PASSWORD;

        // A field that leaves the action unspecified still gets it run, like
        // the TV keyboard's "›" does.
        check("password field, action unspecified", EditorInfo.IME_ACTION_UNSPECIFIED,
                EnterAction.of(password, EditorInfo.IME_ACTION_UNSPECIFIED));
        check("password field with Next", EditorInfo.IME_ACTION_NEXT,
                EnterAction.of(password, EditorInfo.IME_ACTION_NEXT));
        check("search box", EditorInfo.IME_ACTION_SEARCH,
                EnterAction.of(text, EditorInfo.IME_ACTION_SEARCH));
        check("field that asks for no action", EditorInfo.IME_ACTION_NONE,
                EnterAction.of(text, EditorInfo.IME_ACTION_NONE));
        check("multi-line field gets a new line", EditorInfo.IME_ACTION_NONE,
                EnterAction.of(text | InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND));
        check("app opted out of Enter actions", EditorInfo.IME_ACTION_NONE,
                EnterAction.of(text, EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION));

        // decide(): which key presses the keyboard takes over.
        int ok = KeyEvent.KEYCODE_DPAD_CENTER;
        int enter = KeyEvent.KEYCODE_ENTER;
        int keypadEnter = KeyEvent.KEYCODE_NUMPAD_ENTER;
        int scanEnter = EnterAction.SCAN_ENTER;
        int scanKeypadEnter = 96;
        int scanSelect = 353; // remote and phone remote OK
        int done = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        int multi = text | InputType.TYPE_TEXT_FLAG_MULTI_LINE;

        // The bug from v0.3.2: the big Enter arrives as OK and has to submit.
        check("Wi-Fi password, big Enter (arrives as OK)", EnterAction.RUN_ACTION,
                EnterAction.decide(ok, scanEnter, true, password, done));
        check("Wi-Fi password, keypad Enter", EnterAction.RUN_ACTION,
                EnterAction.decide(keypadEnter, scanKeypadEnter, true, password, done));
        check("Wi-Fi password, Enter from a keyboard mapped to ENTER", EnterAction.RUN_ACTION,
                EnterAction.decide(enter, scanEnter, true, password, done));
        check("remote's OK in a text field is left alone", EnterAction.PASS,
                EnterAction.decide(ok, scanSelect, true, password, done));
        check("big Enter in a multi-line field becomes a real Enter", EnterAction.SEND_ENTER,
                EnterAction.decide(ok, scanEnter, true, multi, EditorInfo.IME_ACTION_NONE));
        check("real Enter in a multi-line field passes through", EnterAction.PASS,
                EnterAction.decide(enter, scanEnter, true, multi, EditorInfo.IME_ACTION_NONE));
        check("big Enter on a button keeps clicking", EnterAction.PASS,
                EnterAction.decide(ok, scanEnter, true, InputType.TYPE_NULL, 0));
        check("big Enter with no field focused", EnterAction.PASS,
                EnterAction.decide(ok, scanEnter, false, password, done));
        check("other keys pass", EnterAction.PASS,
                EnterAction.decide(KeyEvent.KEYCODE_A, 30, true, password, done));

        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("EnterAction: all checks passed");
    }

    private static void check(String name, int want, int got) {
        if (want != got) {
            failures++;
            System.out.println("FAIL " + name + ": want " + want + ", got " + got);
        }
    }
}

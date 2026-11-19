package app.ottotv.connect.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.ottotv.connect.ConnectActivity;
import app.ottotv.connect.R;

/**
 * The quick panel (F2, over any app; Alt+F4 on the home screen and
 * Ctrl+Alt+Del open it on Power): where the sound goes and the laptop's own
 * screen (on laptops), Wi-Fi, connecting a phone, suspend, restart and turn
 * off, and the system's details. It never scrolls: 824 px tall on a laptop,
 * 540 on a desktop.
 *
 * otto-remote does what an app may not (switch the sound, the laptop
 * screen, restart): see Remote.
 */
public class PanelActivity extends Activity {
    static final String EXTRA_FOCUS = "focus";
    static final float LEFT = 1120;
    static final float TOP = 54;
    static final float WIDTH = 704;
    /** The status, with the Wi-Fi network's name (only the panel shows it). */
    private static final String STATUS = "/app/status?wifi=1";

    /** The panel on screen, if any: notices go into it instead of over it. */
    static PanelActivity shown;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Net net;
    private Veil veil;
    private FrameLayout stage;
    private FrameLayout box;
    private Label greeting;
    private Label clock;
    private DetailsCard details;
    private ToastView toast;
    private final List<PanelItem> items = new ArrayList<>();
    private PanelItem sound;
    private PanelItem screen;
    private PanelItem wifi;
    private PanelItem phone;
    private PanelItem power;
    private PanelItem more;

    private Remote.Status status = Remote.last;
    private boolean laptop;
    private int focus = -1;
    private int halfCol = PanelItem.WIFI;
    private boolean detailsShown;
    private boolean closing;
    private final Runnable detailsTick = this::pollDetails;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = Ui.of(this);
        net = new Net(this, (kind, bars) -> refresh(true));
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        veil = new Veil(this);
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        veil.setOnClickListener(v -> close());
        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ui.px(Ui.W), ui.px(Ui.H));
        sp.leftMargin = ui.left;
        sp.topMargin = ui.top;
        root.addView(stage, sp);
        setContentView(root);
        veil.animate().alpha(1).setDuration(180).start();
        build(status != null && !status.laptop.isEmpty(), getIntent().getStringExtra(EXTRA_FOCUS));
        Remote.get(STATUS, this::onStatus);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Ctrl+Alt+Del with the panel already open: go to Power.
        if ("power".equals(intent.getStringExtra(EXTRA_FOCUS)) && power != null) {
            focus(power);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = this;
        net.start();
        tickClock();
        Remote.get(STATUS, this::onStatus);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shown == this) {
            shown = null;
        }
        net.stop();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Gone behind something else (Home, an app): it's done.
        if (!isChangingConfigurations() && !isFinishing() && !connecting) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private boolean connecting;

    @Override
    protected void onRestart() {
        super.onRestart();
        connecting = false;
    }

    // ---- Views -----------------------------------------------------------------

    private void build(boolean laptop, String startOn) {
        this.laptop = laptop;
        stage.removeAllViews();
        items.clear();
        float h = panelHeight(laptop);

        box = new Box(this, ui);
        box.setClipChildren(false);
        box.setAlpha(0);
        box.setTranslationX(ui.f(48));
        place(stage, box, LEFT, TOP, WIDTH, h);
        // Clicks inside the panel don't close it.
        box.setOnClickListener(v -> { });

        ImageView otto = new ImageView(this);
        otto.setImageResource(R.drawable.home_hello);
        place(box, otto, 32, 28, 72, 72);
        greeting = new Label(this, ui, greeting(), 30, 700, Ui.TEXT, 36).tracking(-0.02f);
        place(box, greeting, 32 + 72 + 16, 28 + (72 - 36 - 32.4f) / 2, -2, -2);
        clock = new Label(this, ui, "", 24, 500, Ui.TEXT2, 32.4f).features("tnum");
        place(box, clock, 32 + 72 + 16, 28 + (72 - 36 - 32.4f) / 2 + 36, -2, -2);

        float y = 28 + 72 + 8 + 16;
        float inner = WIDTH - 64;
        sound = null;
        screen = null;
        if (laptop) {
            sound = item(PanelItem.SOUND, 32, y, inner);
            y += PanelItem.height(PanelItem.SOUND) + 16;
            screen = item(PanelItem.SCREEN, 32, y, inner);
            y += PanelItem.height(PanelItem.SCREEN) + 16;
        }
        float half = (inner - 16) / 2;
        wifi = item(PanelItem.WIFI, 32, y, half);
        phone = item(PanelItem.PHONE, 32 + half + 16, y, half);
        y += PanelItem.height(PanelItem.WIFI) + 16;
        power = item(PanelItem.POWER, 32, y, inner);
        y += PanelItem.height(PanelItem.POWER) + 16;
        more = item(PanelItem.DETAILS, 32, y, inner);

        details = new DetailsCard(this, ui);
        details.setAlpha(0);
        details.setTranslationX(ui.f(32));
        place(stage, details, 464, TOP + h - DetailsCard.HEIGHT, DetailsCard.WIDTH, DetailsCard.HEIGHT);

        toast = new ToastView(this, ui);
        place(stage, toast, 250, 130, ToastView.WIDTH, -2);

        refresh(false);
        PanelItem first = "power".equals(startOn) ? power : laptop ? sound : wifi;
        focus = -1;
        focus(first);
        box.animate().alpha(1).translationX(0).setDuration(Ui.SLOW).setInterpolator(new DecelerateInterpolator()).start();
        if (detailsShown) {
            showDetails(true);
        }
    }

    static float panelHeight(boolean laptop) {
        return 56 + 80 + (laptop ? 284 : 0) + 184 + 140 + 80;
    }

    private PanelItem item(int kind, float x, float y, float w) {
        PanelItem it = new PanelItem(this, ui, kind);
        place(box, it, x, y, w, PanelItem.height(kind));
        it.setOnHoverListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                focus(it);
            }
            return false;
        });
        it.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                focus(it);
                int opt = it.optionAt(e.getX());
                if (it.kind == PanelItem.SOUND && opt >= 0) {
                    setAudio(opt == 0 ? "tv" : "notebook");
                } else if (it.kind == PanelItem.POWER && opt >= 0) {
                    it.selected = opt;
                    it.update(true);
                    act(it);
                } else if (it.kind != PanelItem.POWER) {
                    act(it);
                }
            }
            return true;
        });
        items.add(it);
        return it;
    }

    private void place(FrameLayout parent, View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? -2 : ui.px(w), h < 0 ? -2 : ui.px(h));
        lp.leftMargin = ui.px(x);
        lp.topMargin = ui.px(y);
        parent.addView(v, lp);
    }

    /** Puts what the panel knows into its items. */
    private void refresh(boolean animate) {
        Remote.Status s = status != null ? status : new Remote.Status(null);
        if (sound != null) {
            sound.titleText = getString(R.string.panel_sound);
            sound.options = new String[] {getString(R.string.panel_sound_tv), getString(R.string.panel_sound_laptop)};
            sound.selected = "notebook".equals(s.audio) ? 1 : 0;
            sound.update(animate);
        }
        if (screen != null) {
            screen.titleText = getString(R.string.panel_screen);
            screen.subText = getString(R.string.panel_screen_note);
            screen.on = !"off".equals(s.laptop);
            screen.stateText = getString(screen.on ? R.string.panel_screen_on : R.string.panel_screen_off);
            screen.update(animate);
        }
        boolean online = net.online();
        wifi.on = online;
        wifi.online = online;
        wifi.cable = net.kind() == StatusPill.CABLE;
        wifi.bars = net.bars();
        wifi.titleText = getString(wifi.cable ? R.string.panel_network : R.string.panel_wifi);
        wifi.subText = !online ? getString(R.string.home_offline)
                : wifi.cable ? getString(R.string.panel_cable)
                : !s.ssid.isEmpty() ? s.ssid : getString(R.string.panel_wifi_connected);
        wifi.update(animate);
        boolean connected = s.phones > 0;
        phone.on = connected;
        phone.titleText = getString(connected ? R.string.panel_phone_connected : R.string.home_connect_phone);
        phone.subText = getString(connected ? R.string.panel_phone_another : R.string.panel_phone_none);
        phone.update(animate);
        power.titleText = getString(R.string.panel_power);
        power.options = new String[] {getString(R.string.panel_suspend), getString(R.string.panel_restart),
                getString(R.string.panel_turn_off)};
        power.update(animate);
        more.titleText = getString(detailsShown ? R.string.panel_hide_details : R.string.panel_show_details);
        more.on = detailsShown;
        more.update(animate);
    }

    private void onStatus(JSONObject j) {
        if (isDestroyed() || j == null) {
            return;
        }
        if (!j.has("ssid") && status != null && !status.ssid.isEmpty()) {
            // Answers to the panel's actions leave the network's name out.
            try {
                j.put("ssid", status.ssid);
            } catch (org.json.JSONException e) {
                // shown without it
            }
        }
        status = new Remote.Status(j);
        Remote.last = status;
        boolean nowLaptop = !status.laptop.isEmpty();
        if (nowLaptop != laptop) {
            // The first answer told us this is a laptop (or isn't): lay out again.
            int kind = focus >= 0 ? items.get(focus).kind : -1;
            build(nowLaptop, kind == PanelItem.POWER ? "power" : null);
            box.setAlpha(1);
            box.setTranslationX(0);
            box.animate().cancel();
        } else {
            refresh(true);
        }
    }

    // ---- Focus and keys ---------------------------------------------------------------

    private void focus(PanelItem it) {
        int i = items.indexOf(it);
        if (i < 0 || i == focus) {
            return;
        }
        focus = i;
        if (it.kind == PanelItem.WIFI || it.kind == PanelItem.PHONE) {
            halfCol = it.kind;
        }
        if (it.kind == PanelItem.POWER) {
            it.selected = 0;
            it.update(false);
        }
        for (PanelItem p : items) {
            p.setFocus(p == it, true);
        }
    }

    private List<List<PanelItem>> rows() {
        List<List<PanelItem>> rows = new ArrayList<>();
        if (sound != null) {
            rows.add(one(sound));
            rows.add(one(screen));
        }
        List<PanelItem> pair = new ArrayList<>();
        pair.add(wifi);
        pair.add(phone);
        rows.add(pair);
        rows.add(one(power));
        rows.add(one(more));
        return rows;
    }

    private static List<PanelItem> one(PanelItem p) {
        List<PanelItem> l = new ArrayList<>();
        l.add(p);
        return l;
    }

    private void move(int key) {
        PanelItem f = items.get(focus);
        List<List<PanelItem>> rows = rows();
        int r = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(f)) {
                r = i;
            }
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int nr = Math.max(0, Math.min(rows.size() - 1, r + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
            List<PanelItem> row = rows.get(nr);
            focus(row.size() == 2 ? (halfCol == PanelItem.PHONE ? row.get(1) : row.get(0)) : row.get(0));
            return;
        }
        boolean left = key == KeyEvent.KEYCODE_DPAD_LEFT;
        if (f.kind == PanelItem.SOUND) {
            setAudio(left ? "tv" : "notebook");
        } else if (f.kind == PanelItem.WIFI && !left) {
            focus(phone);
        } else if (f.kind == PanelItem.PHONE && left) {
            focus(wifi);
        } else if (f.kind == PanelItem.POWER) {
            f.selected = Math.max(0, Math.min(2, f.selected + (left ? -1 : 1)));
            f.update(true);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && !closing) {
                    move(code);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (first && !closing) {
                    act(items.get(focus));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_F2:
                if (first) {
                    close();
                }
                return true;
            case KeyEvent.KEYCODE_F1:
                if (first) {
                    startSafely(new Intent(this, ShortcutsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    close();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                && e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                && e.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
            close();
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    // ---- Doing things ---------------------------------------------------------------------

    private void act(PanelItem it) {
        switch (it.kind) {
            case PanelItem.SOUND:
                setAudio("notebook".equals(status == null ? "" : status.audio) ? "tv" : "notebook");
                break;
            case PanelItem.SCREEN:
                setScreen(!it.on);
                break;
            case PanelItem.WIFI:
                Intent w = new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (startSafely(w)) {
                    close();
                }
                break;
            case PanelItem.PHONE:
                // In the panel's own task: Back from it comes back here.
                connecting = startSafely(new Intent(this, ConnectActivity.class));
                break;
            case PanelItem.POWER:
                powerAction(it.selected);
                break;
            default:
                showDetails(!detailsShown);
        }
    }

    private void setAudio(String to) {
        String now = status == null ? "" : status.audio;
        if (to.equals(now)) {
            return;
        }
        // Show it at once; the switch itself takes a second.
        status = withAudio(to);
        refresh(true);
        Remote.post("/app/audio", Remote.body("to", to), (j) -> {
            if (j == null) {
                showToast(Icons.SPEAKER, getString(R.string.panel_sound_failed), null);
                Remote.get(STATUS, this::onStatus);
                return;
            }
            onStatus(j);
        });
    }

    private Remote.Status withAudio(String audio) {
        JSONObject j = new JSONObject();
        try {
            Remote.Status s = status != null ? status : new Remote.Status(null);
            j.put("phones", s.phones).put("paired", s.paired).put("audio", audio).put("laptop", s.laptop)
                    .put("ssid", s.ssid);
        } catch (org.json.JSONException e) {
            return status;
        }
        return new Remote.Status(j);
    }

    private void setScreen(boolean on) {
        Remote.post("/app/screen", Remote.body("on", on), (j) -> {
            if (j == null) {
                showToast(Icons.LAPTOP, getString(R.string.panel_screen_failed), null);
                return;
            }
            String state = j.optString("laptop");
            if (status != null) {
                try {
                    JSONObject s = new JSONObject().put("phones", status.phones).put("paired", status.paired)
                            .put("audio", status.audio).put("laptop", state).put("ssid", status.ssid);
                    status = new Remote.Status(s);
                } catch (org.json.JSONException e) {
                    // keep the old one
                }
            }
            refresh(true);
            // The state is the user's choice: otto-remote keeps the screen lit
            // on its own while the TV isn't showing the picture.
            if ("off".equals(state)) {
                showToast(Icons.LAPTOP_OFF, getString(R.string.toast_screen_off), getString(R.string.toast_screen_off_sub));
            } else {
                showToast(Icons.LAPTOP, getString(R.string.toast_screen_on), null);
            }
        });
    }

    private void powerAction(int which) {
        String[] actions = {"suspend", "reboot", "shutdown"};
        int[] titles = {R.string.panel_suspending, R.string.panel_restarting, R.string.panel_turning_off};
        showToast(Icons.POWER, getString(titles[which]), null);
        Remote.post("/app/power", Remote.body("action", actions[which]), (j) -> {
            if (j == null) {
                showToast(Icons.POWER, getString(R.string.panel_power_failed), null);
            } else if (which == 0) {
                close(); // when it wakes up, the app is there without the panel
            }
        });
    }

    private void showDetails(boolean show) {
        detailsShown = show;
        more.titleText = getString(show ? R.string.panel_hide_details : R.string.panel_show_details);
        more.on = show;
        more.update(true);
        details.animate().alpha(show ? 1 : 0).translationX(show ? 0 : ui.f(32)).setDuration(Ui.SLOW)
                .setInterpolator(new DecelerateInterpolator()).start();
        if (show) {
            fillDetails(null);
            pollDetails();
        } else {
            handler.removeCallbacks(detailsTick);
        }
    }

    private void pollDetails() {
        handler.removeCallbacks(detailsTick);
        Remote.get("/app/details", this::fillDetails);
        if (detailsShown) {
            handler.postDelayed(detailsTick, 2000);
        }
    }

    private void fillDetails(JSONObject j) {
        if (isDestroyed()) {
            return;
        }
        String unknown = "–";
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mem);
        long up = SystemClock.elapsedRealtime() / 60000;
        String temp = j == null || j.isNull("cpuTemp") ? unknown : Math.round(j.optDouble("cpuTemp")) + " °C";
        String load = j == null || j.isNull("cpuLoad") ? unknown : Math.round(j.optDouble("cpuLoad")) + "%";
        String ip = j == null ? unknown : j.optString("ip").isEmpty() ? getString(R.string.panel_no_network) : j.optString("ip");
        String gfx = j == null ? unknown
                : getString("software".equals(j.optString("graphics")) ? R.string.panel_graphics_software
                : R.string.panel_graphics_hardware);
        int adb = j == null ? 0 : j.optInt("adb");
        String adbText = j == null ? unknown : adb > 0 ? getString(R.string.panel_adb_on, adb) : getString(R.string.panel_adb_off);
        details.set(getString(R.string.panel_details), new String[] {
            getString(R.string.panel_cpu_temp), getString(R.string.panel_cpu_use), getString(R.string.panel_ram),
            getString(R.string.panel_uptime), getString(R.string.panel_ip), getString(R.string.panel_graphics),
            getString(R.string.panel_adb)}, new String[] {
            temp, load, getString(R.string.panel_ram_value, gb(mem.availMem), gb(mem.totalMem)),
            up >= 60 ? getString(R.string.panel_uptime_hours, up / 60, up % 60) : getString(R.string.panel_uptime_minutes, up),
            ip, gfx, adbText});
    }

    private static String gb(long bytes) {
        double g = bytes / (1024.0 * 1024 * 1024);
        return g >= 10 ? String.format(Locale.getDefault(), "%.0f", g) : String.format(Locale.getDefault(), "%.1f", g);
    }

    private boolean startSafely(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            showToast(Icons.INFO, getString(R.string.home_cant_open), null);
            return false;
        }
    }

    void showToast(String icon, String title, String sub) {
        toast.show(icon, title, sub);
    }

    void close() {
        if (closing) {
            return;
        }
        closing = true;
        box.animate().alpha(0).translationX(ui.f(48)).setDuration(Ui.SLOW).start();
        details.animate().alpha(0).setDuration(Ui.SLOW).start();
        veil.animate().alpha(0).setDuration(180).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return getString(h >= 5 && h < 12 ? R.string.panel_morning : h >= 12 && h < 18 ? R.string.panel_afternoon
                : R.string.panel_evening);
    }

    private void tickClock() {
        String pattern = android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
        greeting.setText(greeting());
        long now = System.currentTimeMillis();
        handler.postDelayed(this::tickClock, 60000 - now % 60000 + 50);
    }

    /** The panel's background: night blue, rounded, with a hairline. */
    static final class Box extends FrameLayout {
        private final Ui ui;
        private final RectF r = new RectF();

        Box(android.content.Context c, Ui ui) {
            super(c);
            this.ui = ui;
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            Draw.round(c, r, ui.f(40), Ui.PANEL);
            Paint p = Draw.paint();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, ui.f(1)));
            p.setColor(Ui.alpha(0xFFFFFF, 0.08f));
            c.drawRoundRect(r, ui.f(40), ui.f(40), p);
        }
    }

    /** The dim layer over whatever is behind the panel; a click on it closes the panel. */
    static final class Veil extends View {
        Veil(android.content.Context c) {
            super(c);
            setAlpha(0);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Ui.alpha(0x080A0E, 0.62f));
        }
    }
}

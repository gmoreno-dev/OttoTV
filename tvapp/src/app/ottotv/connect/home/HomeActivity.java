package app.ottotv.connect.home;

import android.app.Activity;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.ottotv.connect.ConnectActivity;
import app.ottotv.connect.R;

/**
 * Otto TV's home screen (the design's Main board):
 *
 *   Continue: the last app opened, with Open      (or Otto's welcome on a fresh system)
 *   Favorites: the user's apps, in their order    (hold Enter: unpin, move, info, uninstall)
 *   All apps: the first five and "See all"        -> all apps in a grid -> More apps
 *
 * The screen takes the focused app's color. F1 opens the shortcuts, F2 the
 * quick panel. Everything is drawn in the design's 1920x1080 units (Ui).
 *
 * Navigation follows the design's prototype: the state is a handful of
 * fields, render() puts the views in line with them.
 */
public class HomeActivity extends Activity implements AppMenu.Listener {
    static final int HOME = 0;
    static final int GRID = 1;
    static final int MORE = 2;

    static final int BANNER = 0;
    static final int HERO = 1;
    static final int FAV = 2;
    static final int APPS = 3;

    static final int PIN = 0;
    static final int UNPIN = 1;
    static final int MOVE = 2;
    static final int INFO = 3;
    static final int UNINSTALL = 4;

    static final long LONG_PRESS_MS = 550;
    static final long STATUS_EVERY_MS = 3000;
    static final String AURORA = "com.aurora.store";
    static final String FDROID = "org.fdroid.fdroid";
    static final String BROWSER = "com.duckduckgo.mobile.android";
    static final String FILES = "com.android.documentsui";
    static final int WELCOME_TINT = Tone.tone(0xF2B25C, 0.12, 0.62);

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    /** The home screen while it's on screen: notices (ToastReceiver) show in it. */
    static HomeActivity shown;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Store store;
    private Net net;

    private Backdrop backdrop;
    private TopBar top;
    private FrameLayout home;
    private LinearLayout cont;
    private Label contName;
    private PillButton open;
    private FrameLayout welcome;
    private PillButton welcomeConnect;
    private PillButton welcomeAll;
    private FrameLayout favRow;
    private Hints.Empty empty;
    private final List<CardView> favCards = new ArrayList<>();
    private final List<CardView> rowCards = new ArrayList<>();
    private View hint;
    private View moveHint;
    private FrameLayout grid;
    private TitleRow gridTitle;
    private FrameLayout gridBody;
    private final List<CardView> gridCards = new ArrayList<>();
    private FrameLayout more;
    private final StoreCard[] stores = new StoreCard[2];
    private AppMenu menu;
    private ToastView toast;

    private List<AppInfo> apps = new ArrayList<>();
    private final List<AppInfo> favApps = new ArrayList<>();
    private final List<AppInfo> row = new ArrayList<>();
    private final List<AppInfo> gridList = new ArrayList<>();
    private final AppInfo allTile = AppInfo.tile(AppInfo.ALL, "");
    private AppInfo moreTile;
    private AppInfo last;
    private boolean loaded;

    private int screen = HOME;
    private int zone = HERO;
    private int heroIdx;
    private int favIdx;
    private int appIdx;
    private int gridIdx;
    private int moreIdx;
    private boolean moving;
    private int menuSource = -1;
    private int menuIdx;
    private int menuOpt;
    private int[] menuKeys = new int[0];

    private Remote.Status status = new Remote.Status(null);
    private boolean resumed;
    /** The plain list of apps, after repeated crashes (see SafeHome). */
    private boolean safe;
    private boolean enterHeld;
    private final Runnable longPress = this::onLongPress;
    private final Runnable clockTick = this::tickClock;
    private final Runnable statusTick = this::pollStatus;
    private final PackageWatcher packages = new PackageWatcher(this);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SafeHome.install(this);
        if (SafeHome.looping(this, System.currentTimeMillis())) {
            // Crashing again and again: the apps as a plain list instead.
            safe = true;
            SafeHome.show(this, () -> {
                SafeHome.reset(this);
                recreate();
            });
            return;
        }
        ui = Ui.of(this);
        store = new Store(this);
        net = new Net(this, (kind, bars) -> {
            if (kind == StatusPill.OFFLINE && screen == HOME && zone == HERO && !menu.shown()) {
                zone = BANNER; // the design starts offline on "Choose a Wi-Fi network"
            } else if (kind != StatusPill.OFFLINE && zone == BANNER) {
                zone = HERO;
            }
            render(true);
        });
        moreTile = AppInfo.tile(AppInfo.MORE, getString(R.string.home_more_apps));
        build();
        if (!net.online()) {
            zone = BANNER;
        }
        render(false);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        loadApps();
    }

    @Override
    protected void onDestroy() {
        if (!safe) {
            unregisterReceiver(packages);
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (safe) {
            return;
        }
        resumed = true;
        shown = this;
        net.start();
        tickClock();
        pollStatus();
        render(true);
        loadApps();
        Updates.check(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (safe) {
            return;
        }
        resumed = false;
        if (shown == this) {
            shown = null;
        }
        net.stop();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(statusTick);
        cancelLongPress();
        render(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (safe) {
            return;
        }
        // Home pressed (here or in an app): back to the top of the home screen.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            goHome();
        }
    }

    // ---- Views ---------------------------------------------------------------

    private void build() {
        FrameLayout root = new FrameLayout(this);
        // No background under the backdrop, which covers the whole screen:
        // every full-screen fill is one more the GPU repeats on each frame.
        root.setClipChildren(false);
        backdrop = new Backdrop(this, ui);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ui.px(Ui.W), ui.px(Ui.H));
        sp.leftMargin = ui.left;
        sp.topMargin = ui.top;
        root.addView(stage, sp);

        home = layer(stage);
        buildHome();
        grid = layer(stage);
        buildGrid();
        more = layer(stage);
        buildMore();

        top = new TopBar(this, ui);
        place(stage, top, 96, 54, Ui.W - 192, 56);
        top.wifiButton.setOnClickListener(v -> openWifi());
        hover(top.wifiButton, () -> focusHome(BANNER, 0));

        menu = new AppMenu(this, ui, this);
        stage.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        toast = new ToastView(this, ui);
        place(stage, toast, 1204, 130, ToastView.WIDTH, -2);

        setContentView(root);
        getWindow().setBackgroundDrawable(null);
    }

    private FrameLayout layer(FrameLayout stage) {
        FrameLayout l = new FrameLayout(this);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        stage.addView(l, new FrameLayout.LayoutParams(-1, -1));
        return l;
    }

    /** Adds v at (x, y) in design px; w or h of -2 means its own size. */
    private void place(ViewGroup parent, View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? -2 : ui.px(w), h < 0 ? -2 : ui.px(h));
        lp.leftMargin = ui.px(x);
        lp.topMargin = ui.px(y);
        parent.addView(v, lp);
    }

    private void buildHome() {
        // Continue: the last app, big, and Open.
        cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setClipChildren(false);
        cont.addView(new Label(this, ui, getString(R.string.home_continue), 26, 600, Ui.HEAD, 32));
        contName = new Label(this, ui, "", 88, 800, 0xFFFFFFFF, 92).tracking(-0.035f).maxWidth(1728);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = ui.px(4);
        cont.addView(contName, np);
        open = new PillButton(this, ui, getString(R.string.home_open), 64, 30, 38, 28)
                .icon(Icons.PLAY, true, 0, 28);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, ui.px(64));
        op.topMargin = ui.px(26);
        cont.addView(open, op);
        place(home, cont, 96, 150, -2, 218);
        open.setOnClickListener(v -> {
            focusHome(HERO, 0);
            select();
        });
        hover(open, () -> focusHome(HERO, 0));

        // Welcome, on a fresh system: Otto says hi.
        welcome = new FrameLayout(this);
        welcome.setClipChildren(false);
        Label hi = new Label(this, ui, getString(R.string.home_welcome_title), 64, 800, 0xFFFFFFFF, 64 * 1.08f)
                .tracking(-0.035f);
        place(welcome, hi, 96, 136, -2, -2);
        Label text = new Label(this, ui, getString(R.string.home_welcome_text), 28, 500, Ui.SOFT, 28 * 1.4f)
                .wrap(1040);
        float textTop = 136 + 64 * 1.08f + 12;
        place(welcome, text, 96, textTop, -2, -2);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        welcomeConnect = new PillButton(this, ui, getString(R.string.home_connect_phone), 64, 28, 34, 28)
                .icon(Icons.PHONE, false, 2.2f, 28);
        welcomeAll = new PillButton(this, ui, getString(R.string.home_see_all_apps), 64, 28, 34, 28)
                .icon(Icons.GRID, false, 2.2f, 28);
        buttons.addView(welcomeConnect, new LinearLayout.LayoutParams(-2, ui.px(64)));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, ui.px(64));
        ap.leftMargin = ui.px(22);
        buttons.addView(welcomeAll, ap);
        place(welcome, buttons, 96, textTop + text.designHeight() + 22, -2, 64);
        welcomeConnect.setOnClickListener(v -> {
            focusHome(HERO, 0);
            select();
        });
        welcomeAll.setOnClickListener(v -> {
            focusHome(HERO, 1);
            select();
        });
        hover(welcomeConnect, () -> focusHome(HERO, 0));
        hover(welcomeAll, () -> focusHome(HERO, 1));
        ImageView otto = new ImageView(this);
        otto.setImageResource(R.drawable.home_welcome);
        otto.setScaleType(ImageView.ScaleType.FIT_CENTER);
        place(welcome, otto, Ui.W - 150 - 290, 104, 290, 290);
        place(home, welcome, 0, 0, Ui.W, Ui.H);

        place(home, new Label(this, ui, getString(R.string.home_favorites), 26, 700, Ui.HEAD, 34), 96, 404, -2, -2);
        favRow = new FrameLayout(this);
        favRow.setClipChildren(false);
        place(home, favRow, 96, 452, 400 * 64, 207);
        Bitmap peek = BitmapFactory.decodeResource(getResources(), R.drawable.home_empty);
        empty = new Hints.Empty(this, ui, peek, getString(R.string.home_favorites_empty));
        place(home, empty, 96, 452, 1728, 207);

        place(home, new Label(this, ui, getString(R.string.home_all_apps), 26, 700, Ui.HEAD, 34), 96, 728, -2, -2);
        for (int j = 0; j < 6; j++) {
            CardView c = new CardView(this, ui, CardView.ROW);
            final int idx = j;
            place(home, c, 96 + j * 292, 776, 268, 151);
            wire(c, APPS, idx);
            rowCards.add(c);
        }

        LinearLayout hints = new LinearLayout(this);
        Hints.Key f1 = new Hints.Key(this, ui, "F1", getString(R.string.home_shortcuts));
        Hints.Key f2 = new Hints.Key(this, ui, "F2", getString(R.string.home_quick_panel));
        hints.addView(f1, new LinearLayout.LayoutParams(-2, ui.px(36)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, ui.px(36));
        hp.leftMargin = ui.px(28);
        hints.addView(f2, hp);
        f1.setOnClickListener(v -> openShortcuts());
        f2.setOnClickListener(v -> openPanel(null));
        hint = centered(home, hints, 990, 36);
        moveHint = centered(home, new Hints.Move(this, ui, getString(R.string.home_move_hint)), 976, 50);
    }

    private View centered(FrameLayout parent, View v, float y, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, ui.px(h));
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
        lp.topMargin = ui.px(y);
        parent.addView(v, lp);
        return v;
    }

    private void buildGrid() {
        gridTitle = new TitleRow(this, ui, getString(R.string.home_all_apps));
        place(grid, gridTitle, 96, 146, -2, -2);
        FrameLayout viewport = new FrameLayout(this);
        // Rows scroll under the title: the design's overflow: hidden.
        viewport.setClipBounds(new android.graphics.Rect(0, 0, ui.px(Ui.W - 152), ui.px(Ui.H - 236)));
        place(grid, viewport, 76, 236, Ui.W - 152, Ui.H - 236);
        gridBody = new FrameLayout(this);
        gridBody.setClipChildren(false);
        viewport.addView(gridBody, new FrameLayout.LayoutParams(-1, ui.px(207 * 200)));
    }

    private void buildMore() {
        place(more, new Label(this, ui, getString(R.string.home_more_apps), 56, 800, Ui.TEXT, 56 * 1.1f)
                .tracking(-0.03f), 96, 146, -2, -2);
        place(more, new Label(this, ui, getString(R.string.home_more_text), 28, 500, Ui.TEXT2, 34), 96, 222, -2, -2);
        for (int i = 0; i < 2; i++) {
            StoreCard s = new StoreCard(this, ui, getString(R.string.home_open));
            place(more, s, 96 + i * (StoreCard.WIDTH + 24), 300, StoreCard.WIDTH, StoreCard.HEIGHT);
            final int idx = i;
            s.open.setOnClickListener(v -> {
                moreIdx = idx;
                select();
            });
            hover(s, () -> {
                moreIdx = idx;
                render(true);
            });
            stores[i] = s;
        }
        LinearLayout note = new LinearLayout(this);
        note.setGravity(android.view.Gravity.CENTER_VERTICAL);
        note.addView(new Icons.IconView(this, Icons.INFO, 2, Ui.TEXT2), new LinearLayout.LayoutParams(ui.px(30), ui.px(30)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = ui.px(14);
        note.addView(new Label(this, ui, getString(R.string.home_more_note), 26, 500, Ui.TEXT2, 34), lp);
        place(more, note, 96, 832, -2, 34);
    }

    /** Mouse and touch: hover focuses, click selects, holding opens the menu. */
    private void wire(CardView c, int source, int idx) {
        hover(c, () -> hoverItem(source, idx));
        c.setOnClickListener(v -> clickItem(source, idx));
        c.setOnLongClickListener(v -> {
            openMenuFrom(source, idx);
            return true;
        });
    }

    private void hover(View v, Runnable r) {
        v.setOnHoverListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER && !menu.shown()) {
                r.run();
            }
            return false;
        });
    }

    // ---- Data ----------------------------------------------------------------

    void loadApps() {
        final Context app = getApplicationContext();
        final Ui u = ui;
        LOADER.execute(() -> {
            List<AppInfo> list = Apps.load(app, u);
            handler.post(() -> onApps(list));
        });
    }

    private void onApps(List<AppInfo> list) {
        if (isDestroyed()) {
            return;
        }
        apps = list;
        loaded = true;
        if (!store.seeded()) {
            // A fresh system starts with settings, files and the browser
            // (installed a little after the first boot: shown when it is).
            List<String> seed = new ArrayList<>();
            String settings = settingsPackage();
            if (settings != null) {
                seed.add(settings);
            }
            seed.add(FILES);
            seed.add(BROWSER);
            store.setFavorites(seed);
        }
        readUsage();
        rebuild();
        render(false);
    }

    private String settingsPackage() {
        ResolveInfo ri = getPackageManager().resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    /**
     * Apps opened outside the home screen count for "Continue" too: Android's
     * usage events say which came to the front (when init-hooks.sh has let
     * us read them).
     */
    private void readUsage() {
        try {
            UsageStatsManager usm = getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            long from = Math.max(store.usageReadUntil(), now - 7L * 24 * 3600 * 1000);
            UsageEvents events = usm.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            List<String> seen = new ArrayList<>();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    seen.add(e.getPackageName());
                }
            }
            for (String pkg : seen) {
                AppInfo a = Apps.find(apps, pkg);
                if (a != null && !a.system) {
                    store.opened(pkg);
                }
            }
            store.setUsageReadUntil(now);
        } catch (RuntimeException e) {
            // Not allowed to read usage: our own launches still count.
        }
    }

    /** Lists and cards from the apps, the favorites and the recent ones. */
    private void rebuild() {
        last = null;
        for (String pkg : store.recent()) {
            AppInfo a = Apps.find(apps, pkg);
            if (a != null && !a.system) {
                last = a;
                break;
            }
        }
        favApps.clear();
        for (String pkg : store.favorites()) {
            AppInfo a = Apps.find(apps, pkg);
            if (a != null && !favApps.contains(a)) {
                favApps.add(a);
            }
        }
        row.clear();
        for (int i = 0; i < Math.min(5, apps.size()); i++) {
            row.add(apps.get(i));
        }
        row.add(allTile);
        gridList.clear();
        gridList.addAll(apps);
        gridList.add(moreTile);

        while (favCards.size() < favApps.size()) {
            CardView c = new CardView(this, ui, CardView.FAVORITE);
            int i = favCards.size();
            place(favRow, c, i * 400, 0, 368, 207);
            wire(c, FAV, i);
            favCards.add(c);
        }
        for (int i = 0; i < favCards.size(); i++) {
            CardView c = favCards.get(i);
            c.setVisibility(i < favApps.size() ? View.VISIBLE : View.GONE);
            if (i < favApps.size()) {
                c.bind(favApps.get(i), null);
            }
        }
        String all = getString(R.string.home_see_all, apps.size());
        for (int j = 0; j < rowCards.size(); j++) {
            CardView c = rowCards.get(j);
            c.setVisibility(j < row.size() ? View.VISIBLE : View.GONE);
            if (j < row.size()) {
                AppInfo a = row.get(j);
                c.bind(a, a == allTile ? all : null);
            }
        }
        while (gridCards.size() < gridList.size()) {
            CardView c = new CardView(this, ui, CardView.ROW);
            int k = gridCards.size();
            place(gridBody, c, 20 + (k % 6) * 292, 26 + (k / 6) * 207, 268, 151);
            wire(c, GRID, k);
            gridCards.add(c);
        }
        for (int k = 0; k < gridCards.size(); k++) {
            CardView c = gridCards.get(k);
            c.setVisibility(k < gridList.size() ? View.VISIBLE : View.GONE);
            if (k < gridList.size()) {
                c.bind(gridList.get(k), null);
            }
        }
        gridTitle.setCount(getResources().getQuantityString(R.plurals.home_app_count, apps.size(), apps.size()));
        AppInfo aurora = Apps.find(apps, AURORA), fdroid = Apps.find(apps, FDROID);
        stores[0].set("Aurora Store", getString(R.string.home_aurora_text), aurora == null ? null : aurora.icon,
                Icons.BAG, aurora == null ? Tone.tone(0x2A8A84, Tone.SCREEN, 0.62) : aurora.tint);
        stores[1].set("F-Droid", getString(R.string.home_fdroid_text), fdroid == null ? null : fdroid.icon,
                Icons.BOX, fdroid == null ? Tone.tone(0x3E6EB4, Tone.SCREEN, 0.62) : fdroid.tint);
        clampFocus();
    }

    private void clampFocus() {
        favIdx = Math.max(0, Math.min(favIdx, favApps.size() - 1));
        appIdx = Math.max(0, Math.min(appIdx, row.size() - 1));
        gridIdx = Math.max(0, Math.min(gridIdx, gridList.size() - 1));
        if (zone == FAV && favApps.isEmpty()) {
            zone = APPS;
        }
        if (moving && favApps.size() < 2) {
            moving = false;
        }
    }

    // ---- Rendering -------------------------------------------------------------

    private boolean welcomeShown() {
        return last == null;
    }

    private float favShift(int i) {
        return Math.min(0, 1824 - (96 + i * 400 + 368));
    }

    private float gridScroll(int idx) {
        return -Math.max(0, idx / 6 - 2) * 207f;
    }

    void render(boolean animate) {
        boolean menuOn = menu.shown();
        // Behind the quick panel (or anything else), no focus shows here.
        boolean free = !menuOn && resumed;
        boolean focusable = free || menuOn;
        boolean isHome = screen == HOME, isGrid = screen == GRID, isMore = screen == MORE;
        home.setVisibility(isHome ? View.VISIBLE : View.GONE);
        grid.setVisibility(isGrid ? View.VISIBLE : View.GONE);
        more.setVisibility(isMore ? View.VISIBLE : View.GONE);

        top.showOffline(isHome && !net.online());
        top.wifiButton.setFocus(isHome && zone == BANNER && free, animate);
        top.status.set(net.kind(), net.bars(), audioLabel(), status.phones > 0, animate);

        // Continue, or the welcome on a fresh system.
        boolean welcome = welcomeShown();
        cont.setVisibility(welcome ? View.GONE : View.VISIBLE);
        this.welcome.setVisibility(welcome ? View.VISIBLE : View.GONE);
        if (last != null) {
            contName.setText(last.label);
        }
        boolean heroF = isHome && zone == HERO && free;
        open.setFocus(heroF && !welcome, animate);
        welcomeConnect.setFocus(heroF && welcome && heroIdx == 0, animate);
        welcomeAll.setFocus(heroF && welcome && heroIdx == 1, animate);

        // Favorites.
        boolean none = favApps.isEmpty();
        empty.setVisibility(none && loaded ? View.VISIBLE : View.GONE);
        favRow.setVisibility(none ? View.GONE : View.VISIBLE);
        for (int i = 0; i < favApps.size(); i++) {
            boolean mv = isHome && moving && favIdx == i;
            boolean f = !moving && isHome && zone == FAV && favIdx == i && focusable;
            favCards.get(i).state(f, f || mv, mv, moving && !mv, mv && i > 0, mv && i < favApps.size() - 1, animate);
        }
        slide(favRow, ui.f(favShift(favIdx)), animate, true);

        for (int j = 0; j < row.size(); j++) {
            boolean f = isHome && !moving && zone == APPS && appIdx == j && focusable;
            rowCards.get(j).state(f, f, false, false, false, false, animate);
        }
        hint.setVisibility(isHome && !moving ? View.VISIBLE : View.GONE);
        moveHint.setVisibility(isHome && moving ? View.VISIBLE : View.GONE);

        for (int k = 0; k < gridList.size(); k++) {
            boolean f = isGrid && gridIdx == k && focusable;
            gridCards.get(k).state(f, f, false, false, false, false, animate);
        }
        slide(gridBody, ui.f(gridScroll(gridIdx)), animate, false);

        for (int i = 0; i < 2; i++) {
            stores[i].setFocus(isMore && moreIdx == i && free, animate);
        }

        // The light behind: the focused app's color.
        int tint = welcome ? WELCOME_TINT : last.tint;
        boolean soft = welcome;
        AppInfo focused = null;
        if (isHome && zone == FAV && favIdx < favApps.size()) {
            focused = favApps.get(favIdx);
        } else if (isHome && zone == APPS && appIdx < row.size()) {
            focused = row.get(appIdx);
        } else if (isGrid && gridIdx < gridList.size()) {
            focused = gridList.get(gridIdx);
        }
        if (focused != null) {
            tint = focused.tint;
            soft = false;
        } else if (isMore) {
            AppInfo s = Apps.find(apps, moreIdx == 0 ? AURORA : FDROID);
            tint = s != null ? s.tint : Tone.tone(moreIdx == 0 ? 0x2A8A84 : 0x3E6EB4, Tone.SCREEN, 0.62);
            soft = false;
        }
        backdrop.setTint(tint, soft, animate);
    }

    private String audioLabel() {
        if ("tv".equals(status.audio)) {
            return getString(R.string.home_audio_tv);
        }
        if ("notebook".equals(status.audio)) {
            return getString(R.string.home_audio_laptop);
        }
        return status.reachable ? null : getString(R.string.home_audio_tv);
    }

    private void slide(View v, float to, boolean animate, boolean x) {
        float now = x ? v.getTranslationX() : v.getTranslationY();
        if (Math.abs(now - to) < 0.5f) {
            return;
        }
        v.animate().cancel();
        if (!animate) {
            if (x) {
                v.setTranslationX(to);
            } else {
                v.setTranslationY(to);
            }
            return;
        }
        if (x) {
            v.animate().translationX(to).setDuration(Ui.SLOW).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            v.animate().translationY(to).setDuration(Ui.SLOW).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // ---- Keys --------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (safe) {
            return super.dispatchKeyEvent(e);
        }
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    String before = focusState();
                    move(code);
                    if (!before.equals(focusState())) {
                        Sfx.move(this, code); // like Android's own widgets: only when the focus moved
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (first) {
                    if (canLongPress()) {
                        enterHeld = true;
                        handler.postDelayed(longPress, LONG_PRESS_MS);
                    } else {
                        select();
                    }
                } else if (e.getAction() == KeyEvent.ACTION_UP && enterHeld) {
                    cancelLongPress();
                    select();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (first) {
                    back();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (first && canLongPress()) {
                    onLongPress();
                }
                return true;
            case KeyEvent.KEYCODE_F1:
                if (first) {
                    openShortcuts();
                }
                return true;
            case KeyEvent.KEYCODE_F2:
                if (first) {
                    openPanel(null);
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void cancelLongPress() {
        enterHeld = false;
        handler.removeCallbacks(longPress);
    }

    private void onLongPress() {
        enterHeld = false;
        handler.removeCallbacks(longPress);
        Sfx.click(this);
        if (screen == GRID) {
            openMenuFrom(GRID, gridIdx);
        } else if (zone == FAV) {
            openMenuFrom(FAV, favIdx);
        } else if (zone == APPS) {
            openMenuFrom(APPS, appIdx);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (safe) {
            return super.dispatchGenericMotionEvent(e);
        }
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                    && e.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                back(); // right click goes back
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_SCROLL && !menu.shown()) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0) {
                    boolean downwards = v < 0;
                    if (screen == GRID) {
                        move(downwards ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
                    } else if (screen == HOME && (zone == FAV || zone == APPS)) {
                        move(downwards ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                    }
                    return true;
                }
            }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (safe) {
            return super.dispatchTouchEvent(e);
        }
        // The secondary button's own down/up must not click what's under it.
        if ((e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    // ---- Navigation (the design's prototype logic) --------------------------------

    private boolean canLongPress() {
        if (menu.shown() || moving) {
            return false;
        }
        if (screen == HOME && zone == FAV) {
            return !favApps.isEmpty();
        }
        if (screen == HOME && zone == APPS) {
            return appIdx < row.size() && row.get(appIdx).isApp();
        }
        if (screen == GRID) {
            return gridIdx < gridList.size() && gridList.get(gridIdx).isApp();
        }
        return false;
    }

    private float favCenter(int i, int shiftIdx) {
        return 96 + i * 400 + 184 + favShift(shiftIdx);
    }

    private float appCenter(int j) {
        return 96 + j * 292 + 134;
    }

    private int nearestApp(float x) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int j = 0; j < row.size(); j++) {
            float d = Math.abs(appCenter(j) - x);
            if (d < bd) {
                bd = d;
                best = j;
            }
        }
        return best;
    }

    private int nearestFav(float x) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < favApps.size(); i++) {
            float d = Math.abs(favCenter(i, i) - x);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    void move(int key) {
        boolean up = key == KeyEvent.KEYCODE_DPAD_UP, downK = key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = key == KeyEvent.KEYCODE_DPAD_LEFT, right = key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (menu.shown()) {
            if (up) {
                menuOpt = Math.max(0, menuOpt - 1);
            } else if (downK) {
                menuOpt = Math.min(menuKeys.length - 1, menuOpt + 1);
            }
            menu.focus(menuOpt);
            return;
        }
        if (moving) {
            if (!left && !right) {
                return;
            }
            int j = left ? favIdx - 1 : favIdx + 1;
            if (j < 0 || j >= favApps.size()) {
                return;
            }
            Collections.swap(favApps, favIdx, j);
            // The cards stay put; their apps trade places.
            favCards.get(favIdx).bind(favApps.get(favIdx), null);
            favCards.get(j).bind(favApps.get(j), null);
            favIdx = j;
            render(true);
            return;
        }
        if (screen == GRID) {
            int n = gridList.size(), g = gridIdx;
            if (left && g % 6 > 0) {
                g -= 1;
            }
            if (right && g % 6 < 5 && g + 1 < n) {
                g += 1;
            }
            if (up && g - 6 >= 0) {
                g -= 6;
            }
            if (downK && g / 6 < (n - 1) / 6) {
                g = Math.min(n - 1, g + 6);
            }
            gridIdx = g;
            render(true);
            return;
        }
        if (screen == MORE) {
            if (left) {
                moreIdx = 0;
            } else if (right) {
                moreIdx = 1;
            }
            render(true);
            return;
        }
        int count = favApps.size();
        boolean welcome = welcomeShown();
        if (zone == BANNER) {
            if (downK) {
                zone = HERO;
            }
        } else if (zone == HERO) {
            if (up && !net.online()) {
                zone = BANNER;
            }
            if (welcome && left) {
                heroIdx = 0;
            }
            if (welcome && right) {
                heroIdx = 1;
            }
            if (downK) {
                if (count > 0) {
                    zone = FAV;
                    favIdx = Math.min(favIdx, count - 1);
                } else {
                    zone = APPS;
                }
            }
        } else if (zone == FAV) {
            if (left) {
                favIdx = Math.max(0, favIdx - 1);
            }
            if (right) {
                favIdx = Math.min(count - 1, favIdx + 1);
            }
            if (up) {
                zone = HERO;
            }
            if (downK) {
                zone = APPS;
                appIdx = nearestApp(favCenter(favIdx, favIdx));
            }
        } else if (zone == APPS) {
            if (left) {
                appIdx = Math.max(0, appIdx - 1);
            }
            if (right) {
                appIdx = Math.min(row.size() - 1, appIdx + 1);
            }
            if (up) {
                if (count > 0) {
                    zone = FAV;
                    favIdx = nearestFav(appCenter(appIdx));
                } else {
                    zone = HERO;
                }
            }
        }
        render(true);
    }

    void select() {
        Sfx.click(this);
        if (menu.shown()) {
            menuAction(menuOpt);
            return;
        }
        if (moving) {
            finishMove();
            return;
        }
        if (screen == GRID) {
            AppInfo a = gridList.get(gridIdx);
            if (a.kind == AppInfo.MORE) {
                screen = MORE;
                moreIdx = 0;
                render(true);
            } else {
                launch(a);
            }
            return;
        }
        if (screen == MORE) {
            AppInfo a = Apps.find(apps, moreIdx == 0 ? AURORA : FDROID);
            if (a != null) {
                launch(a);
            }
            return;
        }
        if (zone == BANNER) {
            openWifi();
        } else if (zone == HERO) {
            if (welcomeShown()) {
                if (heroIdx == 0) {
                    startSafely(new Intent(this, ConnectActivity.class));
                } else {
                    showGrid(0);
                }
            } else {
                launch(last);
            }
        } else if (zone == FAV) {
            if (favIdx < favApps.size()) {
                launch(favApps.get(favIdx));
            }
        } else if (zone == APPS) {
            AppInfo a = row.get(appIdx);
            if (a.kind == AppInfo.ALL) {
                showGrid(0);
            } else {
                launch(a);
            }
        }
    }

    private void showGrid(int idx) {
        screen = GRID;
        gridIdx = idx;
        gridBody.setTranslationY(ui.f(gridScroll(idx)));
        render(true);
    }

    void back() {
        cancelLongPress();
        if (menu.shown()) {
            closeMenu();
            return;
        }
        if (moving) {
            finishMove();
            return;
        }
        if (screen == MORE) {
            showGrid(gridList.size() - 1);
            return;
        }
        if (screen == GRID) {
            screen = HOME;
            zone = APPS;
            appIdx = row.size() - 1;
            render(true);
            return;
        }
        if (zone != HERO && zone != BANNER) {
            zone = HERO;
            render(true);
        }
    }

    void goHome() {
        cancelLongPress();
        if (menu.shown()) {
            menu.hide(false);
        }
        if (moving) {
            finishMove();
        }
        screen = HOME;
        zone = net.online() ? HERO : BANNER;
        heroIdx = 0;
        render(true);
    }

    private void finishMove() {
        moving = false;
        List<String> order = new ArrayList<>();
        for (AppInfo a : favApps) {
            order.add(a.pkg);
        }
        // Favorites that aren't installed right now keep their place at the end.
        for (String pkg : store.favorites()) {
            if (!order.contains(pkg)) {
                order.add(pkg);
            }
        }
        store.setFavorites(order);
        render(true);
    }

    private void hoverItem(int source, int idx) {
        if (moving) {
            return;
        }
        if (source == GRID) {
            gridIdx = idx;
        } else {
            focusHome(source, idx);
            return;
        }
        render(true);
    }

    private void focusHome(int z, int idx) {
        if (moving || screen != HOME) {
            return;
        }
        zone = z;
        if (z == FAV) {
            favIdx = idx;
        } else if (z == APPS) {
            appIdx = idx;
        } else if (z == HERO) {
            heroIdx = idx;
        }
        render(true);
    }

    private void clickItem(int source, int idx) {
        if (menu.shown()) {
            return;
        }
        if (moving) {
            finishMove();
            return;
        }
        if (source == GRID) {
            gridIdx = idx;
        } else {
            zone = source;
            if (source == FAV) {
                favIdx = idx;
            } else {
                appIdx = idx;
            }
        }
        select();
    }

    // ---- App menu ------------------------------------------------------------------

    private AppInfo itemAt(int source, int idx) {
        List<AppInfo> l = source == FAV ? favApps : source == APPS ? row : gridList;
        return idx >= 0 && idx < l.size() ? l.get(idx) : null;
    }

    void openMenuFrom(int source, int idx) {
        if (menu.shown() || moving) {
            return;
        }
        AppInfo a = itemAt(source, idx);
        if (a == null || !a.isApp()) {
            return;
        }
        if (source == GRID) {
            gridIdx = idx;
        } else {
            zone = source;
            if (source == FAV) {
                favIdx = idx;
            } else {
                appIdx = idx;
            }
        }
        List<Integer> keys = new ArrayList<>();
        keys.add(favApps.contains(a) ? UNPIN : PIN);
        if (source == FAV && favApps.size() > 1) {
            keys.add(MOVE);
        }
        keys.add(INFO);
        if (!a.system) {
            keys.add(UNINSTALL);
        }
        menuKeys = new int[keys.size()];
        String[] labels = new String[keys.size()];
        String[] icons = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            menuKeys[i] = keys.get(i);
            labels[i] = menuLabel(menuKeys[i]);
            icons[i] = menuIcon(menuKeys[i]);
        }
        menuSource = source;
        menuIdx = idx;
        menuOpt = 0;

        // Next to the card, never over it.
        int n = menuKeys.length;
        float h = AppMenu.height(n);
        float x = 96 + idx * 400 + favShift(favIdx), w = 368, y = 452, ch = 207;
        if (source == APPS) {
            x = 96 + idx * 292;
            w = 268;
            y = 776;
            ch = 151;
        } else if (source == GRID) {
            x = 96 + (idx % 6) * 292;
            w = 268;
            y = 262 + (idx / 6) * 207 + gridScroll(gridIdx);
            ch = 151;
        }
        float left = x + w + 36 + AppMenu.WIDTH <= 1824 ? x + w + 36 : x - 36 - AppMenu.WIDTH;
        float topY = Math.max(54, Math.min(1026 - h, Math.round(y + ch / 2 - h / 2)));
        menu.show(a.icon, a.label, a.system ? getString(R.string.home_system_app) : null, labels, icons, 0, left, topY);
        render(true);
    }

    private String menuLabel(int key) {
        switch (key) {
            case PIN:
                return getString(R.string.home_pin);
            case UNPIN:
                return getString(R.string.home_unpin);
            case MOVE:
                return getString(R.string.home_move);
            case INFO:
                return getString(R.string.home_app_info);
            default:
                return getString(R.string.home_uninstall);
        }
    }

    private static String menuIcon(int key) {
        switch (key) {
            case PIN:
                return Icons.PIN;
            case UNPIN:
                return Icons.UNPIN;
            case MOVE:
                return Icons.MOVE;
            case INFO:
                return Icons.INFO;
            default:
                return Icons.TRASH;
        }
    }

    private void closeMenu() {
        menu.hide(true);
        menuSource = -1;
        render(true);
    }

    private void menuAction(int opt) {
        AppInfo a = itemAt(menuSource, menuIdx);
        int key = opt >= 0 && opt < menuKeys.length ? menuKeys[opt] : -1;
        int source = menuSource;
        closeMenu();
        if (a == null) {
            return;
        }
        if (key == PIN) {
            List<String> favs = store.favorites();
            favs.remove(a.pkg);
            favs.add(a.pkg);
            store.setFavorites(favs);
            rebuild();
            render(true);
            showToast(Icons.PIN, getString(R.string.home_pinned, a.label), getString(R.string.home_pinned_sub));
        } else if (key == UNPIN) {
            List<String> favs = store.favorites();
            favs.remove(a.pkg);
            store.setFavorites(favs);
            int was = favIdx;
            rebuild();
            if (source == FAV) {
                favIdx = Math.max(0, Math.min(was, favApps.size() - 1));
                if (favApps.isEmpty()) {
                    zone = APPS;
                }
            }
            render(true);
            showToast(Icons.UNPIN, getString(R.string.home_unpinned, a.label), null);
        } else if (key == MOVE) {
            moving = true;
            zone = FAV;
            favIdx = menuIdx;
            render(true);
        } else if (key == INFO) {
            startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
        } else if (key == UNINSTALL) {
            // Android asks before it removes anything.
            startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
        }
    }

    @Override
    public void onMenuHover(int item) {
        menuOpt = item;
        menu.focus(item);
    }

    @Override
    public void onMenuPick(int item) {
        menuOpt = item;
        menuAction(item);
    }

    @Override
    public void onMenuDismiss() {
        closeMenu();
    }

    // ---- Doing things ----------------------------------------------------------------

    void launch(AppInfo a) {
        if (a == null || !a.isApp()) {
            return;
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) {
            i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        }
        if (i == null) {
            String settings = settingsPackage();
            i = a.pkg.equals(settings) ? new Intent(Settings.ACTION_SETTINGS).setPackage(a.pkg)
                    : new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (startSafely(i) && !a.system) {
            store.opened(a.pkg);
            if (last != a) {
                last = a;
                // Next time home shows, Continue is this app, with Open focused.
                handler.postDelayed(() -> render(false), 500);
            }
        }
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

    private void openWifi() {
        if (!startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS))) {
            startSafely(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openShortcuts() {
        startSafely(new Intent(this, ShortcutsActivity.class));
    }

    private void openPanel(String focus) {
        Intent i = new Intent(this, PanelActivity.class);
        if (focus != null) {
            i.putExtra(PanelActivity.EXTRA_FOCUS, focus);
        }
        startSafely(i);
    }

    void showToast(String icon, String title, String sub) {
        toast.show(icon, title, sub);
    }

    // ---- Clock and status --------------------------------------------------------------

    private void tickClock() {
        handler.removeCallbacks(clockTick);
        String pattern = android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        top.clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
        long now = System.currentTimeMillis();
        if (resumed) {
            handler.postDelayed(clockTick, 60000 - now % 60000 + 50);
        }
    }

    private void pollStatus() {
        handler.removeCallbacks(statusTick);
        Remote.get("/app/status", (j) -> {
            Remote.Status s = new Remote.Status(j);
            if (j != null) {
                Remote.last = s;
            }
            boolean changed = s.phones != status.phones || !s.audio.equals(status.audio)
                    || s.reachable != status.reachable;
            status = s;
            if (changed && !isDestroyed()) {
                render(true);
            }
        });
        if (resumed) {
            handler.postDelayed(statusTick, STATUS_EVERY_MS);
        }
    }

    /** Apps installed, removed or updated: reload them. */
    static final class PackageWatcher extends BroadcastReceiver {
        private final HomeActivity home;

        PackageWatcher(HomeActivity home) {
            this.home = home;
        }

        @Override
        public void onReceive(Context c, Intent i) {
            home.loadApps();
        }
    }

    /** For tests: what is focused right now, as "screen/zone/index". */
    String focusForTest() {
        if (safe) {
            View f = getCurrentFocus();
            return "safe/" + (f instanceof android.widget.Button ? ((android.widget.Button) f).getText() : "");
        }
        return focusState();
    }

    /** Where the focus is: screen, row, card, menu option, moving. */
    private String focusState() {
        return screen + "/" + zone + "/" + (screen == GRID ? gridIdx : screen == MORE ? moreIdx
                : zone == FAV ? favIdx : zone == APPS ? appIdx : heroIdx)
                + (menu.shown() ? "/menu" + menuOpt : "") + (moving ? "/moving" : "");
    }
}

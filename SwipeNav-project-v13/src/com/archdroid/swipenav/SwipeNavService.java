package com.archdroid.swipenav;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

public class SwipeNavService extends Service {
    static final String PREFS = "swipenav", KEY_SHOW_BAR = "show_bar", KEY_VOL_HUD = "vol_hud";
    static final String KEY_POS = "pos", KEY_WIDTH = "width", KEY_HEIGHT = "height", KEY_SENS = "sens",
            KEY_OPACITY = "opacity", KEY_HAPTIC = "haptic", KEY_HITBOX = "show_hitbox",
            KEY_PANEL = "panel", KEY_PANEL_W = "panel_w", KEY_PANEL_RIGHT = "panel_right";
    static final String KEY_ACT_LEFT = "act_left", KEY_ACT_RIGHT = "act_right",
            KEY_ACT_UP = "act_up", KEY_ACT_HOLD = "act_hold";
    static final String ACTION_TOGGLE = "com.archdroid.swipenav.TOGGLE_PANEL";
    private EdgeView edge;
    private PanelView panel;
    private final BroadcastReceiver sysRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (ACTION_TOGGLE.equals(i.getAction())) {
                if (panel == null) openPanel(); else panel.requestClose();
            } else if (panel != null) {
                removePanel();   // Home pressed / system dialog -> close at once
            }
        }
    };
    private VolumeHud hud;
    private final BroadcastReceiver volRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (hud == null || panel != null || !sp.getBoolean(KEY_VOL_HUD, true)) return;
            int st = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", 3);
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            hud.show(am.getStreamVolume(st), am.getStreamMaxVolume(st));
        }
    };
    private final ContentObserver dataObs = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) { SimGuard.check(SwipeNavService.this); }
    };
    private final BroadcastReceiver ramRx = new BroadcastReceiver() {
        public void onReceive(final Context c, Intent i) {
            if (!Sigma.isMax(c)) return;
            new Thread(new Runnable() { public void run() { Sigma.refreshRam(c); } }).start();
        }
    };
    private final BroadcastReceiver simRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { SimGuard.check(c); }
    };
    private WindowManager wm;
    private GestureView view;
    private SharedPreferences sp;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences p, String k) { apply(); }
    };
    private final ContentObserver obs = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) { apply(); }
    };

    @Override public void onCreate() {
        try {   // make sure the lock screen service is running after boot
            sendBroadcast(new Intent("com.arch.locker.START").setPackage("com.arch.locker"));
        } catch (Throwable t) { }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.registerOnSharedPreferenceChangeListener(prefListener);
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor("dev_force_show_navbar"), false, obs);
        // iOS 9 style volume HUD (centered, touch-through)
        hud = new VolumeHud(this);
        float d = getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams hp = new WindowManager.LayoutParams((int) (150 * d), (int) (150 * d), 2010,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        hp.gravity = Gravity.CENTER;
        hp.setTitle("SwipeNavVolume");
        wm.addView(hud, hp);
        registerReceiver(volRx, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        IntentFilter sf = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        sf.addAction(ACTION_TOGGLE);
        registerReceiver(sysRx, sf);
        // Ultra Sigma Max: tidy memory each time the screen turns off
        registerReceiver(ramRx, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        // no SIM card -> keep mobile data off
        getContentResolver().registerContentObserver(Settings.Global.getUriFor("mobile_data"), false, dataObs);
        registerReceiver(simRx, new IntentFilter("android.intent.action.SIM_STATE_CHANGED"));
        SimGuard.check(this);
        apply();
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private WindowManager.LayoutParams buildParams() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        int sw = dm.widthPixels;
        int w = sw * clamp(sp.getInt(KEY_WIDTH, 100), 20, 100) / 100;
        int h = (int) (clamp(sp.getInt(KEY_HEIGHT, 24), 12, 80) * dm.density);
        int pos = clamp(sp.getInt(KEY_POS, 50), 0, 100);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                2010 /* TYPE_SYSTEM_ERROR */,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.x = (sw - w) * pos / 100;
        lp.setTitle("SwipeNav");
        return lp;
    }

    /** Gestures on (nav bar hidden) -> show strip. Off -> remove strip. */
    private void apply() {
        boolean on = Settings.System.getInt(getContentResolver(), "dev_force_show_navbar", 1) == 0;
        if (on && view == null) {
            view = new GestureView(this);
            wm.addView(view, buildParams());
        } else if (!on && view != null) {
            wm.removeView(view);
            view = null;
        } else if (on && view != null) {
            try { wm.updateViewLayout(view, buildParams()); } catch (Throwable t) { }
        }
        if (view != null) {
            view.setShowBar(sp.getBoolean(KEY_SHOW_BAR, true));
            view.setActions(sp.getInt(KEY_ACT_LEFT, Actions.BACK), sp.getInt(KEY_ACT_RIGHT, Actions.BACK),
                    sp.getInt(KEY_ACT_UP, Actions.HOME), sp.getInt(KEY_ACT_HOLD, Actions.RECENTS));
            view.configure(clamp(sp.getInt(KEY_SENS, 100), 50, 150) / 100f,
                    sp.getBoolean(KEY_HAPTIC, true),
                    clamp(sp.getInt(KEY_OPACITY, 100), 20, 100) / 100f,
                    sp.getBoolean(KEY_HITBOX, false));
        }
        applyEdge();
    }

    private WindowManager.LayoutParams edgeParams() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        int sw = dm.widthPixels;
        int w = sw * clamp(sp.getInt(KEY_PANEL_W, 50), 20, 100) / 100;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, (int) (10 * dm.density),
                2010,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = sp.getBoolean(KEY_PANEL_RIGHT, true) ? sw - w : 0;
        lp.setTitle("SwipeNavEdge");
        return lp;
    }

    // CyanogenMod settings behind the stock quick settings (pull-down corner, tiles, ribbon).
    private static final String[] STOCK_KEYS = {"qs_quick_pulldown", "quick_settings_tiles", "qs_quick_access"};
    private static final String[] STOCK_OFF = {"0", "", "0"};

    /** Our panel replaces the stock one: switch it off while ours is enabled, restore it afterwards. */
    private void syncStock(boolean disable) {
        ContentResolver cr = getContentResolver();
        boolean applied = sp.getBoolean("stock_off", false);
        try {
            if (disable && !applied) {
                SharedPreferences.Editor ed = sp.edit();
                for (int i = 0; i < STOCK_KEYS.length; i++) {
                    String v = Settings.System.getString(cr, STOCK_KEYS[i]);
                    ed.putBoolean("bk_has_" + i, v != null);
                    ed.putString("bk_" + i, v == null ? "" : v);
                }
                ed.commit();
                for (int i = 0; i < STOCK_KEYS.length; i++) Settings.System.putString(cr, STOCK_KEYS[i], STOCK_OFF[i]);
                sp.edit().putBoolean("stock_off", true).commit();
            } else if (!disable && applied) {
                for (int i = 0; i < STOCK_KEYS.length; i++) {
                    Settings.System.putString(cr, STOCK_KEYS[i],
                            sp.getBoolean("bk_has_" + i, false) ? sp.getString("bk_" + i, "") : null);
                }
                sp.edit().putBoolean("stock_off", false).commit();
            }
        } catch (Throwable t) { }
    }

    private void applyEdge() {
        boolean want = sp.getBoolean(KEY_PANEL, true);
        syncStock(want);
        if (want && edge == null) {
            edge = new EdgeView(this, new EdgeView.OnOpen() {
                public void open() { openPanel(); }
            });
            wm.addView(edge, edgeParams());
        } else if (!want && edge != null) {
            wm.removeView(edge);
            edge = null;
        } else if (want && edge != null) {
            try { wm.updateViewLayout(edge, edgeParams()); } catch (Throwable t) { }
        }
    }

    private void openPanel() {
        try {   // never open while the lock screen is showing
            if (Settings.System.getInt(getContentResolver(), "arch_locked", 0) == 1) return;
        } catch (Throwable t) { }
        if (panel != null) return;
        panel = new PanelView(this, new Runnable() {
            public void run() { removePanel(); }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                2010,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("SwipeNavPanel");
        try { wm.addView(panel, lp); } catch (Throwable t) { panel = null; }
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Throwable t) { }
            panel = null;
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        apply();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        unregisterReceiver(volRx);
        unregisterReceiver(sysRx);
        try { unregisterReceiver(simRx); } catch (Throwable t) { }
        try { unregisterReceiver(ramRx); } catch (Throwable t) { }
        try { getContentResolver().unregisterContentObserver(dataObs); } catch (Throwable t) { }
        removePanel();
        if (edge != null) { wm.removeView(edge); edge = null; }
        if (hud != null) { wm.removeView(hud); hud = null; }
        sp.unregisterOnSharedPreferenceChangeListener(prefListener);
        getContentResolver().unregisterContentObserver(obs);
        if (view != null) { wm.removeView(view); view = null; }
    }
}

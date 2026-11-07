package com.archdroid.swipenav;

import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Control-centre style panel. Built to be cheap on a Lumia 520:
 *  - every layer (header, page 1, page 2) is drawn once onto a GPU layer and then only moved/faded,
 *    so opening, closing and page swipes cost almost nothing per frame;
 *  - all slow calls (Wi-Fi, Bluetooth, settings, state reads) run on a background thread;
 *  - no clipPath, no per-frame allocations, sliders only redraw their own rectangle.
 */
public class PanelView extends FrameLayout {
    static final int T_AIR = 0, T_DATA = 1, T_WIFI = 2, T_BT = 3, T_ROT = 4, T_SND = 5, T_TMO = 6,
            T_LOC = 7, T_GST = 8, T_CAM = 9, T_SET = 10, T_AUTO = 11, T_SYNC = 12, T_CLK = 13,
            T_CALC = 14, T_LOCK = 15, T_PREV = 16, T_PLAY = 17, T_NEXT = 18, T_SIGMA = 19, T_SIGMAX = 20, N = 21;
    private static final int I_SUN = 30, I_VOL = 31, I_NOTE = 32, I_PAUSE = 33;
    private static final int[] TIMEOUTS = {15000, 30000, 60000, 120000, 300000, 600000};
    private static final int[] ACCENT = new int[N];
    static {
        for (int i = 0; i < N; i++) ACCENT[i] = 0xFF0A84FF;
        ACCENT[T_AIR] = 0xFFFF9F0A;
        ACCENT[T_DATA] = 0xFF30D158;
        ACCENT[T_LOC] = 0xFF30D158;
        ACCENT[T_GST] = 0xFF5E5CE6;
        ACCENT[T_AUTO] = 0xFFFFD60A;
        ACCENT[T_SYNC] = 0xFF30D158;
        ACCENT[T_CAM] = 0xFF8E8E93;
        ACCENT[T_SET] = 0xFF8E8E93;
        ACCENT[T_CLK] = 0xFFFF9F0A;
        ACCENT[T_CALC] = 0xFFFF9F0A;
        ACCENT[T_LOCK] = 0xFF8E8E93;
        ACCENT[T_SIGMA] = 0xFFFFB300;
        ACCENT[T_SIGMAX] = 0xFFFF3B30;
    }
    private static final int[] PAGE1_ORDER = {T_LOC, T_AUTO, T_SYNC, T_TMO, T_GST, T_CAM, T_CLK, T_CALC, T_LOCK, T_SIGMA, T_SIGMAX, T_SET};
    private static final int[] CLUSTER = {T_AIR, T_DATA, T_WIFI, T_BT};
    private static final int[] ROW4 = {T_LOC, T_SIGMA, T_SIGMAX, T_SET};
    private static final int MIN_BRIGHT = 10;
    private static final int FILL = 0x33FFFFFF, FILL_DARK = 0x66000000;

    // one background thread for everything slow
    private static HandlerThread ioThread;
    private static Handler ioHandler;
    private static synchronized Handler io() {
        if (ioHandler == null) {
            ioThread = new HandlerThread("swipenav-panel-io");
            ioThread.start();
            ioHandler = new Handler(ioThread.getLooper());
        }
        return ioHandler;
    }

    /** A view that draws one part of the panel onto its own hardware layer. */
    private class Layer extends View {
        final int kind;
        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        @Override protected void onDraw(Canvas c) {
            if (kind == 0) drawChrome(c);
            else if (kind == 1) drawPage0(c);
            else drawPage1(c);
        }
    }

    private final float d = getResources().getDisplayMetrics().density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final Handler ui = new Handler();
    private final Runnable closer;
    private final AudioManager am;
    private final WifiManager wifi;
    private final BluetoothAdapter bt;
    private final View dim;
    private final FrameLayout holder;
    private final Layer chrome, page0v, page1v;

    private final RectF[][] rects = new RectF[2][N];
    private final RectF[] slider = new RectF[2];   // 0 = brightness, 1 = volume
    private final RectF cluster = new RectF(), media = new RectF(), art = new RectF(), lockBtn = new RectF();
    private final boolean[] on = new boolean[N];
    private float u, g, gx0, gy0, dotsY;
    private int soundMode, timeoutMs, battery = -1;
    private boolean playing;
    private volatile float bright, vol;
    private volatile boolean sigmaBusy;
    private boolean simAbsent;
    private String timeStr = "", dateStr = "";

    private int page;
    private boolean closing, closed, manualSet, swiped, sized;
    private int pressTile = -1, dragSlider = -1;
    private float downX, downY;
    private boolean outsideDown;
    private long readyAt;
    private final AtomicBoolean[] queued = {new AtomicBoolean(), new AtomicBoolean()};
    private final Runnable[] sliderWrite = new Runnable[2];

    private final Runnable refreshRun = new Runnable() {
        public void run() { refresh(); }
    };

    public PanelView(Context c, Runnable closer) {
        super(c);
        this.closer = closer;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        bt = BluetoothAdapter.getDefaultAdapter();
        slider[0] = new RectF();
        slider[1] = new RectF();

        dim = new View(c);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6123B29, 0xF0050B08});
        dim.setBackgroundDrawable(gd);
        dim.setAlpha(0f);
        addView(dim, new LayoutParams(-1, -1));

        holder = new FrameLayout(c);
        addView(holder, new LayoutParams(-1, -1));
        chrome = new Layer(c, 0);
        page0v = new Layer(c, 1);
        page1v = new Layer(c, 2);
        holder.addView(chrome, new LayoutParams(-1, -1));
        holder.addView(page0v, new LayoutParams(-1, -1));
        holder.addView(page1v, new LayoutParams(-1, -1));
        holder.setTranslationY(-36 * d);

        for (int k = 0; k < 2; k++) {
            final int kk = k;
            sliderWrite[k] = new Runnable() {
                public void run() { queued[kk].set(false); writeSlider(kk); }
            };
        }
        readyAt = SystemClock.uptimeMillis() + 230;
        refresh();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        dim.animate().alpha(1f).setDuration(200).start();
        holder.animate().translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    @Override protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ state

    private boolean mobileData() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            return (Boolean) cm.getClass().getMethod("getMobileDataEnabled").invoke(cm);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reads every state on the background thread, then applies it on the UI thread in one go. */
    private void refresh() {
        io().post(new Runnable() {
            public void run() {
                final Context ctx = getContext();
                final ContentResolver cr = ctx.getContentResolver();
                final boolean[] n = new boolean[N];
                try {
                    n[T_AIR] = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                    n[T_DATA] = mobileData();
                    n[T_WIFI] = wifi != null && wifi.isWifiEnabled();
                    n[T_BT] = bt != null && (bt.isEnabled() || bt.getState() == BluetoothAdapter.STATE_TURNING_ON);
                    n[T_LOC] = Settings.Secure.getInt(cr, "location_mode", 0) != 0;
                    n[T_ROT] = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
                    n[T_GST] = Settings.System.getInt(cr, "dev_force_show_navbar", 1) == 0;
                    n[T_AUTO] = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1;
                    n[T_SYNC] = ContentResolver.getMasterSyncAutomatically();
                    n[T_SIGMA] = sigmaBusy ? on[T_SIGMA] : Sigma.isOn(ctx);
                    n[T_SIGMAX] = sigmaBusy ? on[T_SIGMAX] : Sigma.isMax(ctx);
                } catch (Throwable t) { }
                final boolean sa = SimGuard.noSim(ctx);
                if (sa) n[T_DATA] = false;
                final int sm = am.getRingerMode();
                n[T_SND] = sm != AudioManager.RINGER_MODE_NORMAL;
                final int to = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000);
                final boolean pl = am.isMusicActive();
                float b = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) - MIN_BRIGHT)
                        / (float) (255 - MIN_BRIGHT);
                final float bb = b < 0 ? 0 : (b > 1 ? 1 : b);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                final float vv = max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0;
                long now = System.currentTimeMillis();
                final String ts = DateUtils.formatDateTime(ctx, now, DateUtils.FORMAT_SHOW_TIME);
                final String ds = DateUtils.formatDateTime(ctx, now, DateUtils.FORMAT_SHOW_WEEKDAY
                        | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
                int bat = -1;
                Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    if (lvl >= 0 && sc > 0) bat = lvl * 100 / sc;
                }
                final int fbat = bat;
                ui.post(new Runnable() {
                    public void run() {
                        if (closed) return;
                        boolean dragging = dragSlider >= 0;
                        System.arraycopy(n, 0, on, 0, N);
                        simAbsent = sa;
                        soundMode = sm;
                        timeoutMs = to;
                        playing = pl;
                        if (!dragging) { bright = bb; vol = vv; }
                        timeStr = ts;
                        dateStr = ds;
                        battery = fbat;
                        invalidateAll();
                    }
                });
            }
        });
    }

    private void invalidateAll() {
        chrome.invalidate();
        page0v.invalidate();
        page1v.invalidate();
    }

    private String label(int i) {
        switch (i) {
            case T_LOC: return "Location";
            case T_AUTO: return "Auto-bright";
            case T_SYNC: return "Sync";
            case T_TMO: return "Timeout";
            case T_GST: return "Gestures";
            case T_CAM: return "Camera";
            case T_CLK: return "Alarms";
            case T_CALC: return "Calculator";
            case T_LOCK: return "Lock";
            case T_SIGMA: return "Ultra Sigma";
            case T_SIGMAX: return "Sigma Max";
            default: return "Settings";
        }
    }

    private String timeoutText() {
        return timeoutMs < 60000 ? (timeoutMs / 1000) + " sec" : (timeoutMs / 60000) + " min";
    }

    // ----------------------------------------------------------------- layout

    private RectF mk(int pg, int id, float l, float t, float r, float b) {
        if (rects[pg][id] == null) rects[pg][id] = new RectF();
        rects[pg][id].set(l, t, r, b);
        return rects[pg][id];
    }

    @Override protected void onSizeChanged(int w, int hgt, int ow, int oh) {
        super.onSizeChanged(w, hgt, ow, oh);
        g = 10 * d;
        float m = 16 * d, headerH = 46 * d, dotsH = 30 * d;
        float uW = (w - 2 * m - 3 * g) / 4f;
        float uH = (hgt - headerH - dotsH - 4 * g - 12 * d) / 5f;
        u = Math.min(uW, uH);
        float gridW = 4 * u + 3 * g;
        gx0 = (w - gridW) / 2f;
        gy0 = headerH;
        dotsY = gy0 + 5 * u + 4 * g + 18 * d;
        float lb = 32 * d;
        lockBtn.set(gx0 + gridW - lb, 8 * d, gx0 + gridW, 8 * d + lb);

        cluster.set(gx0, gy0, gx0 + 2 * u + g, gy0 + 2 * u + g);
        float cw = cluster.width() / 2f, rad = cw * 0.40f;
        for (int i = 0; i < 4; i++) {
            float cx = cluster.left + (i % 2 + 0.5f) * cw, cy = cluster.top + (i / 2 + 0.5f) * cw;
            mk(0, CLUSTER[i], cx - rad, cy - rad, cx + rad, cy + rad);
        }
        float mx = gx0 + 2 * (u + g);
        media.set(mx, gy0, gx0 + gridW, gy0 + 2 * u + g);
        float aS = 0.62f * u;
        art.set(media.left + 12 * d, media.top + 12 * d, media.left + 12 * d + aS, media.top + 12 * d + aS);
        float bw = media.width() / 3f, by = media.bottom - 0.62f * u;
        mk(0, T_PREV, media.left, by, media.left + bw, by + 0.5f * u);
        mk(0, T_PLAY, media.left + bw, by, media.left + 2 * bw, by + 0.5f * u);
        mk(0, T_NEXT, media.left + 2 * bw, by, media.right, by + 0.5f * u);
        float y2 = gy0 + 2 * (u + g);
        mk(0, T_ROT, gx0, y2, gx0 + u, y2 + u);
        mk(0, T_SND, gx0 + u + g, y2, gx0 + 2 * u + g, y2 + u);
        mk(0, T_TMO, gx0, y2 + u + g, gx0 + 2 * u + g, y2 + 2 * u + g);
        for (int k = 0; k < 2; k++) {
            float l = gx0 + (2 + k) * (u + g);
            slider[k].set(l, y2, l + u, y2 + 2 * u + g);
        }
        float y4 = gy0 + 4 * (u + g);
        for (int i = 0; i < 4; i++) mk(0, ROW4[i], gx0 + i * (u + g), y4, gx0 + i * (u + g) + u, y4 + u);

        float dia = 0.78f * u;
        for (int k = 0; k < PAGE1_ORDER.length; k++) {
            float cx = gx0 + (k % 4) * (u + g) + u / 2f;
            float cy = gy0 + 0.5f * u + (k / 4) * (u + 26 * d);
            mk(1, PAGE1_ORDER[k], cx - dia / 2, cy - dia / 2, cx + dia / 2, cy + dia / 2);
        }
        page0v.setTranslationX(page == 1 ? -w : 0);
        page1v.setTranslationX(page == 1 ? 0 : w);
        sized = true;
        invalidateAll();
    }

    // ---------------------------------------------------------------- drawing

    private void drawChrome(Canvas c) {
        if (!sized) return;
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(20 * d);
        p.setColor(0xFFFFFFFF);
        float hx = gx0 + 2 * d, hy = 30 * d;
        c.drawText(timeStr, hx, hy, p);
        float tw = p.measureText(timeStr);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(12 * d);
        p.setColor(0xB3FFFFFF);
        c.drawText(dateStr, hx + tw + 8 * d, hy, p);
        if (battery >= 0) {
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(battery + "%", lockBtn.left - 10 * d, hy, p);
        }
        p.setTextAlign(Paint.Align.CENTER);
        // lock button (top right)
        p.setStyle(Paint.Style.FILL);
        p.setColor(pressTile == T_LOCK ? 0x66FFFFFF : FILL);
        c.drawCircle(lockBtn.centerX(), lockBtn.centerY(), lockBtn.width() / 2f, p);
        icon(c, T_LOCK, lockBtn.centerX(), lockBtn.centerY(), lockBtn.width() * 0.2f, 0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);
        float w = getWidth();
        for (int i = 0; i < 2; i++) {
            p.setColor(page == i ? 0xFFFFFFFF : 0x55FFFFFF);
            c.drawCircle(w / 2f + (i - 0.5f) * 16 * d, dotsY, 3.2f * d, p);
        }
    }

    private void fillRound(Canvas c, RectF r, float rad, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(r, rad, rad, p);
    }

    private void drawPage0(Canvas c) {
        if (!sized) return;
        float big = 26 * d;
        fillRound(c, cluster, big, FILL);
        for (int i = 0; i < 4; i++) circleBtn(c, CLUSTER[i], rects[0][CLUSTER[i]], false);

        fillRound(c, media, big, FILL);
        fillRound(c, art, 10 * d, FILL);
        icon(c, I_NOTE, art.centerX(), art.centerY(), 0.22f * u, 0xCCFFFFFF);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(13 * d);
        p.setColor(0xFFFFFFFF);
        c.drawText(playing ? "Playing" : "Not Playing", media.centerX(), art.bottom + 0.42f * u, p);
        for (int id = T_PREV; id <= T_NEXT; id++) {
            RectF r = rects[0][id];
            c.save();
            if (id == pressTile && page == 0) c.scale(0.88f, 0.88f, r.centerX(), r.centerY());
            icon(c, id == T_PLAY && playing ? I_PAUSE : id, r.centerX(), r.centerY(), 0.17f * u, 0xFFFFFFFF);
            c.restore();
        }

        squareBtn(c, T_ROT, true);
        squareBtn(c, T_SND, true);
        drawPill(c);
        drawSlider(c, 0);
        drawSlider(c, 1);
        for (int i = 0; i < 4; i++) squareBtn(c, ROW4[i], false);
    }

    private void drawPage1(Canvas c) {
        if (!sized) return;
        for (int i = 0; i < PAGE1_ORDER.length; i++) {
            int id = PAGE1_ORDER[i];
            RectF r = rects[1][id];
            circleBtn(c, id, r, true);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(10.5f * d);
            p.setColor(0xE6FFFFFF);
            c.drawText(label(id), r.centerX(), r.bottom + 15 * d, p);
        }
    }

    private void circleBtn(Canvas c, int id, RectF r, boolean light) {
        c.save();
        if (id == pressTile && ((light && page == 1) || (!light && page == 0)))
            c.scale(0.92f, 0.92f, r.centerX(), r.centerY());
        p.setStyle(Paint.Style.FILL);
        boolean dis = id == T_DATA && simAbsent;   // no SIM card: shown dimmed
        p.setColor(dis ? FILL_DARK : (on[id] ? ACCENT[id] : (light ? FILL : FILL_DARK)));
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, p);
        icon(c, id, r.centerX(), r.centerY(), r.width() * 0.25f, dis ? 0x55FFFFFF : 0xFFFFFFFF);
        c.restore();
    }

    private void squareBtn(Canvas c, int id, boolean lockStyle) {
        RectF r = rects[0][id];
        c.save();
        if (id == pressTile && page == 0) c.scale(0.93f, 0.93f, r.centerX(), r.centerY());
        boolean hi = on[id];
        int fillC, fg;
        if (!hi) { fillC = FILL; fg = 0xFFFFFFFF; }
        else if (lockStyle) { fillC = 0xFFFFFFFF; fg = 0xFFFF453A; }
        else { fillC = ACCENT[id]; fg = 0xFFFFFFFF; }
        fillRound(c, r, 22 * d, fillC);
        icon(c, id, r.centerX(), r.centerY(), r.width() * 0.2f, fg);
        c.restore();
    }

    private void drawPill(Canvas c) {
        RectF r = rects[0][T_TMO];
        c.save();
        if (pressTile == T_TMO && page == 0) c.scale(0.96f, 0.96f, r.centerX(), r.centerY());
        fillRound(c, r, r.height() / 2f, FILL);
        float cx = r.left + 0.5f * u, cy = r.centerY();
        p.setStyle(Paint.Style.FILL);
        p.setColor(FILL);
        c.drawCircle(cx, cy, 0.3f * u, p);
        icon(c, T_TMO, cx, cy, 0.17f * u, 0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(12.5f * d);
        p.setColor(0xFFFFFFFF);
        float tx = r.left + 0.95f * u;
        c.drawText("Timeout", tx, cy - 1 * d, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(11 * d);
        p.setColor(0xB3FFFFFF);
        c.drawText(timeoutText(), tx, cy + 13 * d, p);
        p.setTextAlign(Paint.Align.CENTER);
        c.restore();
    }

    /** Track plus a fill that has flat top and rounded bottom (no clipPath needed). */
    private void drawSlider(Canvas c, int k) {
        RectF r = slider[k];
        float rad = 22 * d;
        fillRound(c, r, rad, FILL);
        float v = k == 0 ? bright : vol;
        float fh = r.height() * v;
        if (fh > 1f) {
            float top = r.bottom - fh;
            p.setColor(0xFFF2F4F8);
            if (fh >= 2 * rad) {
                rf.set(r.left, top, r.right, r.bottom);
                c.drawRoundRect(rf, rad, rad, p);
                c.drawRect(r.left, top, r.right, top + rad + 1, p);
            } else {
                rf.set(r.left, top, r.right, r.bottom);
                c.drawRoundRect(rf, fh / 2f, fh / 2f, p);
            }
        }
        float cy = r.bottom - 24 * d;
        int fg = fh > 24 * d ? 0xFF3A3D46 : 0xFFE6E8EE;
        icon(c, k == 0 ? I_SUN : I_VOL, r.centerX(), cy, 0.17f * u, fg);
    }

    private void speaker(Canvas c, float cx, float cy, float s, int waves, boolean slash) {
        float x0 = cx - 0.2f * s;
        path.reset();
        path.moveTo(x0 - 0.8f * s, cy - 0.35f * s);
        path.lineTo(x0 - 0.35f * s, cy - 0.35f * s);
        path.lineTo(x0 + 0.25f * s, cy - 0.85f * s);
        path.lineTo(x0 + 0.25f * s, cy + 0.85f * s);
        path.lineTo(x0 - 0.35f * s, cy + 0.35f * s);
        path.lineTo(x0 - 0.8f * s, cy + 0.35f * s);
        path.close();
        c.drawPath(path, p);
        for (int k = 1; k <= waves; k++) {
            float rad = 0.5f * s * k + 0.1f * s;
            rf.set(x0 + 0.25f * s - rad, cy - rad, x0 + 0.25f * s + rad, cy + rad);
            c.drawArc(rf, -50, 100, false, p);
        }
        if (slash) c.drawLine(cx - 0.9f * s, cy - 0.9f * s, cx + 0.9f * s, cy + 0.9f * s, p);
    }

    /** Circular arrow: arc from start for sweep degrees with a filled head at its end. */
    private void arcArrow(Canvas c, float cx, float cy, float rr, float start, float sweep, float s) {
        rf.set(cx - rr, cy - rr, cx + rr, cy + rr);
        c.drawArc(rf, start, sweep, false, p);
        double a = Math.toRadians(start + sweep);
        float nx = (float) Math.cos(a), ny = (float) Math.sin(a);
        float px = cx + rr * nx, py = cy + rr * ny, tx = -ny, ty = nx;
        Paint.Style old = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(px + tx * 0.6f * s, py + ty * 0.6f * s);
        path.lineTo(px + nx * 0.5f * s, py + ny * 0.5f * s);
        path.lineTo(px - nx * 0.5f * s, py - ny * 0.5f * s);
        path.close();
        c.drawPath(path, p);
        p.setStyle(old);
    }

    private void bolt(Canvas c, float cx, float cy, float s) {
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(cx + 0.25f * s, cy - 1.05f * s);
        path.lineTo(cx - 0.65f * s, cy + 0.15f * s);
        path.lineTo(cx - 0.05f * s, cy + 0.15f * s);
        path.lineTo(cx - 0.3f * s, cy + 1.05f * s);
        path.lineTo(cx + 0.65f * s, cy - 0.2f * s);
        path.lineTo(cx + 0.05f * s, cy - 0.2f * s);
        path.close();
        c.drawPath(path, p);
    }

    /** Tiny hand-drawn icon set; everything is a stroke or a path, so there are no assets. */
    private void icon(Canvas c, int id, float cx, float cy, float s, int fg) {
        p.setColor(fg);
        p.setStrokeWidth(Math.max(1.6f * d, s * 0.19f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        switch (id) {
            case T_AIR: {
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx, cy - 1.05f * s);
                path.lineTo(cx + 0.2f * s, cy - 0.75f * s);
                path.lineTo(cx + 0.2f * s, cy - 0.2f * s);
                path.lineTo(cx + 1.05f * s, cy + 0.35f * s);
                path.lineTo(cx + 1.05f * s, cy + 0.62f * s);
                path.lineTo(cx + 0.2f * s, cy + 0.28f * s);
                path.lineTo(cx + 0.2f * s, cy + 0.72f * s);
                path.lineTo(cx + 0.55f * s, cy + 0.95f * s);
                path.lineTo(cx + 0.55f * s, cy + 1.08f * s);
                path.lineTo(cx, cy + 0.95f * s);
                path.lineTo(cx - 0.55f * s, cy + 1.08f * s);
                path.lineTo(cx - 0.55f * s, cy + 0.95f * s);
                path.lineTo(cx - 0.2f * s, cy + 0.72f * s);
                path.lineTo(cx - 0.2f * s, cy + 0.28f * s);
                path.lineTo(cx - 1.05f * s, cy + 0.62f * s);
                path.lineTo(cx - 1.05f * s, cy + 0.35f * s);
                path.lineTo(cx - 0.2f * s, cy - 0.2f * s);
                path.lineTo(cx - 0.2f * s, cy - 0.75f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case T_DATA: {
                float x1 = cx - 0.5f * s, x2 = cx + 0.5f * s;
                c.drawLine(x1, cy + 0.9f * s, x1, cy - 0.9f * s, p);
                c.drawLine(x1 - 0.4f * s, cy - 0.5f * s, x1, cy - 0.9f * s, p);
                c.drawLine(x1 + 0.4f * s, cy - 0.5f * s, x1, cy - 0.9f * s, p);
                c.drawLine(x2, cy - 0.9f * s, x2, cy + 0.9f * s, p);
                c.drawLine(x2 - 0.4f * s, cy + 0.5f * s, x2, cy + 0.9f * s, p);
                c.drawLine(x2 + 0.4f * s, cy + 0.5f * s, x2, cy + 0.9f * s, p);
                break;
            }
            case T_WIFI: {
                float oy = cy + 0.8f * s;
                for (int k = 1; k <= 3; k++) {
                    float rad = s * 0.55f * k;
                    rf.set(cx - rad, oy - rad, cx + rad, oy + rad);
                    c.drawArc(rf, 225, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, oy, 1.7f * d + s * 0.05f, p);
                break;
            }
            case T_BT: {
                float a = 0.62f * s;
                path.reset();
                path.moveTo(cx - a, cy - 0.5f * s);
                path.lineTo(cx + a, cy + 0.5f * s);
                path.lineTo(cx, cy + s);
                path.lineTo(cx, cy - s);
                path.lineTo(cx + a, cy - 0.5f * s);
                path.lineTo(cx - a, cy + 0.5f * s);
                c.drawPath(path, p);
                break;
            }
            case T_LOC: {
                float r0 = 0.72f * s, ccy = cy - 0.3f * s;
                c.drawCircle(cx, ccy, r0, p);
                c.drawLine(cx - r0 * 0.8f, ccy + r0 * 0.6f, cx, cy + 1.1f * s, p);
                c.drawLine(cx + r0 * 0.8f, ccy + r0 * 0.6f, cx, cy + 1.1f * s, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, ccy, 0.25f * s, p);
                break;
            }
            case T_ROT: {
                arcArrow(c, cx, cy, 0.95f * s, 40, 280, s);
                p.setStyle(Paint.Style.FILL);
                rf.set(cx - 0.34f * s, cy - 0.02f * s, cx + 0.34f * s, cy + 0.5f * s);
                c.drawRoundRect(rf, 0.1f * s, 0.1f * s, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.3f * d, s * 0.13f));
                rf.set(cx - 0.22f * s, cy - 0.5f * s, cx + 0.22f * s, cy + 0.1f * s);
                c.drawArc(rf, 180, 180, false, p);
                break;
            }
            case T_SYNC: {
                arcArrow(c, cx, cy, 0.85f * s, 205, 130, s);
                arcArrow(c, cx, cy, 0.85f * s, 25, 130, s);
                break;
            }
            case T_SND: {
                if (soundMode == AudioManager.RINGER_MODE_VIBRATE) {
                    rf.set(cx - 0.45f * s, cy - 0.85f * s, cx + 0.45f * s, cy + 0.85f * s);
                    c.drawRoundRect(rf, 0.2f * s, 0.2f * s, p);
                    c.drawLine(cx - 0.85f * s, cy - 0.4f * s, cx - 0.85f * s, cy + 0.4f * s, p);
                    c.drawLine(cx + 0.85f * s, cy - 0.4f * s, cx + 0.85f * s, cy + 0.4f * s, p);
                } else if (soundMode == AudioManager.RINGER_MODE_NORMAL) {
                    speaker(c, cx, cy, s, 2, false);
                } else {
                    speaker(c, cx, cy, s, 0, true);
                }
                break;
            }
            case T_TMO: {
                c.drawCircle(cx, cy, 0.95f * s, p);
                c.drawLine(cx, cy, cx, cy - 0.55f * s, p);
                c.drawLine(cx, cy, cx + 0.42f * s, cy + 0.2f * s, p);
                break;
            }
            case T_CLK: {
                c.drawCircle(cx, cy + 0.1f * s, 0.8f * s, p);
                c.drawLine(cx, cy + 0.1f * s, cx, cy - 0.35f * s, p);
                c.drawLine(cx, cy + 0.1f * s, cx + 0.35f * s, cy + 0.25f * s, p);
                c.drawLine(cx - 0.95f * s, cy - 0.75f * s, cx - 0.55f * s, cy - 1.0f * s, p);
                c.drawLine(cx + 0.95f * s, cy - 0.75f * s, cx + 0.55f * s, cy - 1.0f * s, p);
                break;
            }
            case T_GST: {
                p.setStyle(Paint.Style.FILL);
                rf.set(cx - 0.95f * s, cy + 0.45f * s, cx + 0.95f * s, cy + 0.8f * s);
                c.drawRoundRect(rf, 0.18f * s, 0.18f * s, p);
                p.setStyle(Paint.Style.STROKE);
                path.reset();
                path.moveTo(cx - 0.5f * s, cy + 0.05f * s);
                path.lineTo(cx, cy - 0.5f * s);
                path.lineTo(cx + 0.5f * s, cy + 0.05f * s);
                c.drawPath(path, p);
                break;
            }
            case T_CAM: {
                rf.set(cx - 0.4f * s, cy - 0.9f * s, cx + 0.4f * s, cy - 0.5f * s);
                c.drawRoundRect(rf, 0.12f * s, 0.12f * s, p);
                rf.set(cx - 1.0f * s, cy - 0.6f * s, cx + 1.0f * s, cy + 0.85f * s);
                c.drawRoundRect(rf, 0.25f * s, 0.25f * s, p);
                c.drawCircle(cx, cy + 0.12f * s, 0.42f * s, p);
                break;
            }
            case T_CALC: {
                rf.set(cx - 0.7f * s, cy - 0.98f * s, cx + 0.7f * s, cy + 0.98f * s);
                c.drawRoundRect(rf, 0.2f * s, 0.2f * s, p);
                c.drawLine(cx - 0.4f * s, cy - 0.5f * s, cx + 0.4f * s, cy - 0.5f * s, p);
                p.setStyle(Paint.Style.FILL);
                for (int r = 0; r < 2; r++)
                    for (int k = -1; k <= 1; k++)
                        c.drawCircle(cx + k * 0.38f * s, cy + 0.1f * s + r * 0.5f * s, 0.1f * s + 0.4f, p);
                break;
            }
            case T_LOCK: {
                rf.set(cx - 0.7f * s, cy - 0.05f * s, cx + 0.7f * s, cy + 0.95f * s);
                c.drawRoundRect(rf, 0.2f * s, 0.2f * s, p);
                rf.set(cx - 0.42f * s, cy - 1.0f * s, cx + 0.42f * s, cy - 0.0f * s);
                c.drawArc(rf, 180, 180, false, p);
                c.drawLine(cx - 0.42f * s, cy - 0.5f * s, cx - 0.42f * s, cy - 0.05f * s, p);
                c.drawLine(cx + 0.42f * s, cy - 0.5f * s, cx + 0.42f * s, cy - 0.05f * s, p);
                break;
            }
            case T_AUTO: {
                c.save();
                c.translate(-0.15f * s, -0.2f * s);
                icon(c, I_SUN, cx, cy, s * 0.8f, fg);
                c.restore();
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(1.05f * s);
                p.setColor(fg);
                c.drawText("A", cx + 0.62f * s, cy + 1.0f * s, p);
                break;
            }
            case T_SIGMA:
                bolt(c, cx, cy, s);
                break;
            case T_SIGMAX:
                bolt(c, cx - 0.55f * s, cy, s * 0.82f);
                bolt(c, cx + 0.6f * s, cy, s * 0.82f);
                break;
            case T_SET: {
                c.drawCircle(cx, cy, 0.6f * s, p);
                for (int k = 0; k < 8; k++) {
                    float a = (float) (k * Math.PI / 4);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    c.drawLine(cx + ca * 0.85f * s, cy + sa * 0.85f * s,
                            cx + ca * 1.1f * s, cy + sa * 1.1f * s, p);
                }
                break;
            }
            case I_SUN: {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 0.42f * s, p);
                p.setStyle(Paint.Style.STROKE);
                for (int k = 0; k < 8; k++) {
                    float a = (float) (k * Math.PI / 4);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    c.drawLine(cx + ca * 0.7f * s, cy + sa * 0.7f * s,
                            cx + ca * 1.0f * s, cy + sa * 1.0f * s, p);
                }
                break;
            }
            case I_VOL:
                speaker(c, cx, cy, s, 2, false);
                break;
            case I_NOTE: {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - 0.45f * s, cy + 0.6f * s, 0.4f * s, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - 0.05f * s, cy + 0.6f * s, cx - 0.05f * s, cy - 0.95f * s, p);
                c.drawLine(cx - 0.05f * s, cy - 0.95f * s, cx + 0.75f * s, cy - 0.5f * s, p);
                break;
            }
            case T_PLAY: {
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx - 0.6f * s, cy - 0.95f * s);
                path.lineTo(cx + 0.95f * s, cy);
                path.lineTo(cx - 0.6f * s, cy + 0.95f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case I_PAUSE: {
                p.setStyle(Paint.Style.FILL);
                rf.set(cx - 0.7f * s, cy - 0.9f * s, cx - 0.2f * s, cy + 0.9f * s);
                c.drawRect(rf, p);
                rf.set(cx + 0.2f * s, cy - 0.9f * s, cx + 0.7f * s, cy + 0.9f * s);
                c.drawRect(rf, p);
                break;
            }
            case T_PREV:
            case T_NEXT: {
                float sgn = id == T_PREV ? -1f : 1f;
                p.setStyle(Paint.Style.FILL);
                for (int k = 0; k < 2; k++) {
                    float off = (k - 0.5f) * 1.0f * s;
                    path.reset();
                    path.moveTo(cx + off - sgn * 0.6f * s, cy - 0.75f * s);
                    path.lineTo(cx + off + sgn * 0.5f * s, cy);
                    path.lineTo(cx + off - sgn * 0.6f * s, cy + 0.75f * s);
                    path.close();
                    c.drawPath(path, p);
                }
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ touch

    public void requestClose() {
        if (closing) return;
        closing = true;
        holder.animate().translationY(-30 * d).setDuration(150)
                .setInterpolator(new AccelerateInterpolator(1.5f)).start();
        dim.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
            public void run() {
                if (!closed) { closed = true; closer.run(); }
            }
        }).start();
    }

    private boolean inGrid(float x, float y) {
        return x >= gx0 - 6 * d && x <= gx0 + 4 * u + 3 * g + 6 * d && y >= gy0 - 6 * d && y <= dotsY + 10 * d;
    }

    private void setPage(int np) {
        if (np == page) return;
        page = np;
        float w = getWidth();
        page0v.animate().translationX(np == 1 ? -w : 0).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        page1v.animate().translationX(np == 1 ? 0 : w).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        chrome.invalidate();
        readyAt = SystemClock.uptimeMillis() + 220;
    }

    private void invalidatePage() {
        chrome.invalidate();
        if (page == 0) page0v.invalidate(); else page1v.invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (closing || SystemClock.uptimeMillis() < readyAt) return true;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y;
                pressTile = -1; dragSlider = -1; swiped = false;
                outsideDown = !inGrid(x, y);
                if (lockBtn.contains(x, y)) { outsideDown = false; pressTile = T_LOCK; chrome.invalidate(); }
                if (!outsideDown) {
                    for (int i = 0; i < N; i++) {
                        RectF r = rects[page][i];
                        if (r != null && r.contains(x, y)) pressTile = i;
                    }
                    if (page == 0) {
                        for (int k = 0; k < 2; k++) {
                            if (slider[k].contains(x, y)) { dragSlider = k; pressTile = -1; setSlider(k, y, false); }
                        }
                    }
                    if (pressTile >= 0) invalidatePage();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragSlider >= 0) {
                    setSlider(dragSlider, y, false);
                } else {
                    float dx = x - downX, dy = y - downY;
                    if (pressTile >= 0 && (Math.abs(dx) > 12 * d || Math.abs(dy) > 12 * d)) {
                        pressTile = -1;
                        invalidatePage();
                    }
                    if (!swiped && Math.abs(dx) > 44 * d && Math.abs(dx) > Math.abs(dy)) {
                        swiped = true;
                        setPage(dx < 0 ? 1 : 0);
                    } else if (!swiped && downY - y > 56 * d) {
                        requestClose();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragSlider >= 0) {
                    setSlider(dragSlider, y, true);
                } else if (pressTile >= 0 && !swiped) {
                    int t = pressTile;
                    pressTile = -1;
                    invalidatePage();
                    tap(t);
                } else if (outsideDown && !swiped && Math.abs(y - downY) < 12 * d) {
                    requestClose();
                }
                pressTile = -1; dragSlider = -1;
                break;
            case MotionEvent.ACTION_CANCEL:
                if (pressTile >= 0) invalidatePage();
                pressTile = -1; dragSlider = -1;
                break;
            default:
                break;
        }
        return true;
    }

    // ---------------------------------------------------------------- sliders

    private void setSlider(int k, float y, boolean commit) {
        RectF r = slider[k];
        float v = 1f - (y - r.top) / r.height();
        v = v < 0 ? 0 : (v > 1 ? 1 : v);
        if (k == 0) bright = v; else vol = v;
        // only the slider's own rectangle is redrawn
        page0v.invalidate((int) r.left - 1, (int) r.top - 1, (int) r.right + 1, (int) r.bottom + 1);
        if (!queued[k].getAndSet(true)) io().postDelayed(sliderWrite[k], commit ? 0 : 50);
    }

    /** Runs on the io thread; always writes the latest value. */
    private void writeSlider(int k) {
        try {
            if (k == 0) {
                ContentResolver cr = getContext().getContentResolver();
                if (!manualSet) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    on[T_AUTO] = false;
                    manualSet = true;
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
                        MIN_BRIGHT + Math.round(bright * (255 - MIN_BRIGHT)));
            } else {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int want = Math.round(vol * max);
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != want)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0);
            }
        } catch (Throwable t) { }
    }

    // ------------------------------------------------------------------- taps

    private void launch(Intent i) {
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(getContext(), "App not available", Toast.LENGTH_SHORT).show();
        }
        requestClose();
    }

    private void airplaneBroadcast(boolean en) {
        boolean ok = false;
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + en});
            ok = pr.waitFor() == 0;
        } catch (Throwable t) { }
        if (!ok) toast("Airplane mode needs root access");
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }
        });
    }

    /** Updates the picture straight away, then does the real (slow) work on the io thread. */
    private void tap(final int t) {
        final Context ctx = getContext();
        if (t == T_DATA && simAbsent) {
            toast("No SIM card");
            return;
        }
        switch (t) {
            case T_CAM:
                launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                return;
            case T_CLK:
                launch(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
                return;
            case T_CALC:
                launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
                return;
            case T_SET:
                launch(new Intent(Settings.ACTION_SETTINGS));
                return;
            case T_LOCK:
                requestClose();
                LockRows.lockNow(ctx);
                return;
            case T_PREV:
                Keys.press(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case T_PLAY:
                Keys.press(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                playing = !playing;
                break;
            case T_NEXT:
                Keys.press(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            default:
                break;
        }
        // optimistic new state, shown immediately
        final boolean nv = !on[t];
        int mode = soundMode, tmo = timeoutMs;
        switch (t) {
            case T_AIR: case T_DATA: case T_WIFI: case T_BT: case T_LOC: case T_ROT:
            case T_GST: case T_AUTO: case T_SYNC:
                on[t] = nv;
                break;
            case T_SIGMA:
                on[T_SIGMA] = nv;
                if (!nv) on[T_SIGMAX] = false;   // turning Sigma off turns Max off too
                break;
            case T_SIGMAX:
                on[T_SIGMAX] = nv;
                if (nv) on[T_SIGMA] = true;      // Max includes Sigma
                break;
            case T_SND:
                mode = soundMode == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                        : (soundMode == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
                        : AudioManager.RINGER_MODE_NORMAL);
                soundMode = mode;
                on[T_SND] = mode != AudioManager.RINGER_MODE_NORMAL;
                break;
            case T_TMO:
                tmo = TIMEOUTS[0];
                for (int i = 0; i < TIMEOUTS.length; i++) {
                    if (TIMEOUTS[i] > timeoutMs) { tmo = TIMEOUTS[i]; break; }
                }
                timeoutMs = tmo;
                break;
            default:
                break;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        page0v.invalidate();
        page1v.invalidate();

        final int fmode = mode, ftmo = tmo;
        io().post(new Runnable() {
            public void run() {
                ContentResolver cr = ctx.getContentResolver();
                try {
                    switch (t) {
                        case T_AIR:
                            try {
                                Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, nv ? 1 : 0);
                            } catch (Throwable x) { }
                            airplaneBroadcast(nv);
                            break;
                        case T_DATA: {
                            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                            cm.getClass().getMethod("setMobileDataEnabled", boolean.class).invoke(cm, nv);
                            break;
                        }
                        case T_WIFI:
                            if (wifi != null) wifi.setWifiEnabled(nv);
                            break;
                        case T_BT:
                            if (bt == null) toast("No Bluetooth on this device");
                            else if (nv) bt.enable(); else bt.disable();
                            break;
                        case T_LOC:
                            Settings.Secure.putInt(cr, "location_mode", nv ? 3 : 0);
                            break;
                        case T_ROT:
                            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, nv ? 0 : 1);
                            break;
                        case T_SND:
                            am.setRingerMode(fmode);
                            break;
                        case T_TMO:
                            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, ftmo);
                            break;
                        case T_GST:
                            Settings.System.putInt(cr, "dev_force_show_navbar", nv ? 0 : 1);
                            break;
                        case T_AUTO:
                            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, nv ? 1 : 0);
                            manualSet = !nv;
                            break;
                        case T_SYNC:
                            ContentResolver.setMasterSyncAutomatically(nv);
                            break;
                        case T_SIGMAX:
                            if (sigmaBusy) break;
                            sigmaBusy = true;
                            new Thread(new Runnable() {
                                public void run() {
                                    String msg;
                                    try {
                                        msg = nv ? Sigma.enableMax(ctx) : Sigma.disableMax(ctx);
                                    } catch (Throwable x) {
                                        msg = "Ultra Sigma Max failed";
                                    }
                                    sigmaBusy = false;
                                    toast(msg);
                                    ui.post(refreshRun);
                                }
                            }).start();
                            break;
                        case T_SIGMA:
                            if (sigmaBusy) break;
                            sigmaBusy = true;
                            new Thread(new Runnable() {
                                public void run() {
                                    String msg;
                                    try {
                                        msg = nv ? Sigma.enable(ctx) : Sigma.disable(ctx);
                                    } catch (Throwable x) {
                                        msg = "Ultra Sigma failed";
                                    }
                                    sigmaBusy = false;
                                    toast(msg);
                                    ui.post(refreshRun);
                                }
                            }).start();
                            break;
                        default:
                            break;
                    }
                } catch (Throwable ex) {
                    toast("Not allowed on this ROM");
                }
                ui.removeCallbacks(refreshRun);
                ui.postDelayed(refreshRun, 900);   // re-sync async toggles (Wi-Fi / Bluetooth / media)
            }
        });
    }
}

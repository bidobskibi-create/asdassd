package com.archdroid.swipenav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;

/** Lock screen rows for Swipe Navigation. The lock itself lives in the Lock screen app (com.arch.locker). */
public final class LockRows {
    private static final String LOCK_PKG = "com.arch.locker";
    private static final int[] OFF = {15000, 30000, 60000, 120000, 300000, 600000, 1800000};
    private static final String[] OFF_L = {"15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes"};

    private LockRows() { }

    private static String labelFor(int v) {
        for (int i = 0; i < OFF.length; i++) if (OFF[i] == v) return OFF_L[i];
        return (v / 1000) + " s";
    }

    private static TextView row(Activity a, LinearLayout parent, String title, String sub, String value,
                                View.OnClickListener click) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x22FFFFFF));
        sl.addState(new int[]{}, new ColorDrawable(0));
        r.setBackgroundDrawable(sl);
        r.setClickable(true);
        if (click != null) r.setOnClickListener(click);
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Color.WHITE);
        left.addView(t);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(0xFF8A8F9C);
        left.addView(s);
        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(a);
        v.setText(value == null ? "\u203A" : value + "  \u203A");
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFF6EE7A8);
        r.addView(v);
        parent.addView(r);
        return v;
    }

    private static boolean lockAppInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(LOCK_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void add(final Activity a, LinearLayout parent) {
        final ContentResolver cr = a.getContentResolver();
        row(a, parent, "Lock now", "Lock and turn the screen off", null, new View.OnClickListener() {
            public void onClick(View v) { lockNow(a); }
        });
        final TextView off = row(a, parent, "Screen timeout", "Screen turns off after this much inactivity",
                labelFor(Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000)), null);
        ((View) off.getParent()).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000);
                int best = 0;
                for (int i = 0; i < OFF.length; i++) if (Math.abs(OFF[i] - cur) < Math.abs(OFF[best] - cur)) best = i;
                new AlertDialog.Builder(a).setTitle("Screen timeout")
                        .setSingleChoiceItems(OFF_L, best, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dlg, int w) {
                                try {
                                    Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, OFF[w]);
                                    off.setText(OFF_L[w] + "  \u203A");
                                } catch (Throwable t) {
                                    Toast.makeText(a, "Not allowed on this ROM", Toast.LENGTH_SHORT).show();
                                }
                                dlg.dismiss();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        row(a, parent, "Lock screen settings", "PIN, lock type and lock delay", null, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    a.startActivity(new Intent().setComponent(new ComponentName(LOCK_PKG, "com.arch.locker.MainActivity")));
                } catch (Throwable t) {
                    Toast.makeText(a, "Lock screen app is not installed", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Asks the Lock screen app to lock; without it, falls back to pressing the power key. */
    public static void lockNow(final Context ctx) {
        if (lockAppInstalled(ctx)) {
            ctx.sendBroadcast(new Intent("com.arch.locker.LOCK_NOW").setPackage(LOCK_PKG));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    Class<?> c = Class.forName("android.hardware.input.InputManager");
                    Object im = c.getMethod("getInstance").invoke(null);
                    Method inj = c.getMethod("injectInputEvent", InputEvent.class, int.class);
                    long t = SystemClock.uptimeMillis();
                    inj.invoke(im, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0, 0, -1, 0,
                            KeyEvent.FLAG_FROM_SYSTEM, 0x101), 2);
                    inj.invoke(im, new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_POWER, 0, 0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM, 0x101), 2);
                } catch (Throwable e) {
                    try { Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 26"}); } catch (Throwable e2) { }
                }
            }
        }).start();
    }
}

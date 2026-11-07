package com.archdroid.swipenav;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.lang.reflect.Method;

/** Sends BACK / HOME / APP_SWITCH. Injection runs off the UI thread. */
public class Keys {
    private static Handler sHandler;
    private static Object sIm;
    private static Method sInject;

    private static void init() throws Exception {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("swipenav-keys");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        if (sInject == null) {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            sIm = c.getMethod("getInstance").invoke(null);
            sInject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
    }

    public static void press(final Context ctx, final int code) {
        try { init(); } catch (Throwable e) { fallback(ctx, code); return; }
        sHandler.post(new Runnable() {
            public void run() {
                boolean ok = true;
                try {
                    long now = SystemClock.uptimeMillis();
                    ok &= send(KeyEvent.ACTION_DOWN, code, now, now);
                    ok &= send(KeyEvent.ACTION_UP, code, now, SystemClock.uptimeMillis());
                } catch (Throwable e) { ok = false; }
                if (!ok) fallback(ctx, code);
            }
        });
    }

    private static boolean send(int action, int code, long down, long time) throws Exception {
        KeyEvent ev = new KeyEvent(down, time, action, code, 0, 0, -1, 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_SOFT_KEYBOARD, 0x101);
        // mode 2 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH (the old code's async mode is why HOME/RECENTS got dropped)
        return (Boolean) sInject.invoke(sIm, ev, 2);
    }

    /** Only HOME has a safe non-injection fallback. */
    private static void fallback(Context ctx, int code) {
        if (code == KeyEvent.KEYCODE_HOME) {
            ctx.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}

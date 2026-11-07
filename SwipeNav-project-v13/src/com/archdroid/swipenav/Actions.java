package com.archdroid.swipenav;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.view.KeyEvent;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.List;

/** What a gesture can do. The numeric ids are stored in SharedPreferences, so never renumber them. */
public class Actions {
    public static final int NONE = 0, BACK = 1, HOME = 2, RECENTS = 3, FORCE_CLOSE = 4, PANEL = 5, LOCK = 6;
    public static final String[] LABELS = {"Nothing", "Back", "Home", "Recent apps", "Force close app", "Control panel", "Lock screen"};

    /** Anything unknown (e.g. from a future version) becomes NONE instead of crashing. */
    public static int sanitize(int a) { return a < 0 || a >= LABELS.length ? NONE : a; }

    public static void run(Context ctx, int action) {
        switch (sanitize(action)) {
            case BACK:    Keys.press(ctx, KeyEvent.KEYCODE_BACK); break;
            case HOME:    Keys.press(ctx, KeyEvent.KEYCODE_HOME); break;
            case RECENTS: Keys.press(ctx, KeyEvent.KEYCODE_APP_SWITCH); break;
            case PANEL:   ctx.sendBroadcast(new Intent(SwipeNavService.ACTION_TOGGLE)); break;
            case FORCE_CLOSE: forceClose(ctx); break;
            case LOCK:    LockRows.lockNow(ctx); break;
            default: break;
        }
    }

    /** Never close the launcher, the system UI, the lock screen or ourselves. */
    private static boolean isProtected(Context ctx, String pkg) {
        if (pkg == null || pkg.equals(ctx.getPackageName()) || pkg.equals("android")
                || pkg.equals("com.android.systemui") || pkg.equals("com.android.keyguard")) return true;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) return true;
        } catch (Throwable t) { }
        return false;
    }

    private static void toast(Context ctx, String s) {
        try { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show(); } catch (Throwable t) { }
    }

    /**
     * Force-stops the app that is on screen. Needs FORCE_STOP_PACKAGES (granted because this app
     * lives in /system/priv-app). If that is refused, falls back to Home + killBackgroundProcesses.
     */
    static void forceClose(final Context ctx) {
        try {
            final ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) return;
            final String pkg = tasks.get(0).topActivity.getPackageName();
            if (isProtected(ctx, pkg)) { toast(ctx, "Nothing to close"); return; }
            String label = pkg;
            try {
                PackageManager pm = ctx.getPackageManager();
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Throwable t) { }
            boolean ok = false;
            try {
                Method m = ActivityManager.class.getMethod("forceStopPackage", String.class);
                m.invoke(am, pkg);
                ok = true;
            } catch (Throwable t) { }
            if (!ok) {
                Keys.press(ctx, KeyEvent.KEYCODE_HOME);
                new Handler().postDelayed(new Runnable() {
                    public void run() {
                        try { am.killBackgroundProcesses(pkg); } catch (Throwable t) { }
                    }
                }, 450);
            }
            toast(ctx, "Closed " + label);
        } catch (Throwable t) { }
    }
}

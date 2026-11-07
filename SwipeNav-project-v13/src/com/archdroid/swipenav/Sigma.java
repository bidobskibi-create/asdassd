package com.archdroid.swipenav;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ultra Sigma mode: a reversible speed and memory boost.
 *  - no root needed: background apps are cleared and animations run at half time
 *  - with root: CPU governor "performance", earlier low-memory killing, bigger storage read-ahead,
 *    page cache dropped. Every changed value is remembered and put back when the mode is turned off.
 * The mode switches itself off after a reboot, because the kernel values reset anyway.
 */
final class Sigma {
    private static final String PREFS = "sigma";
    private static final float ANIM = 0.5f;

    private Sigma() { }

    static boolean isOn(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean("on", false)) return false;
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (Math.abs(boot - sp.getLong("boot", 0)) > 120000) {   // rebooted since: kernel values are back to default
            sp.edit().clear().commit();
            return false;
        }
        return true;
    }

    private static long freeMb(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    // ---- animation scales (hidden IWindowManager, needs SET_ANIMATION_SCALE) ----

    private static Object wm() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "window");
        Class<?> st = Class.forName("android.view.IWindowManager$Stub");
        return st.getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    private static void saveAnimations(SharedPreferences.Editor ed) {
        try {
            Object w = wm();
            for (int i = 0; i < 3; i++) {
                float v = (Float) w.getClass().getMethod("getAnimationScale", int.class).invoke(w, i);
                ed.putFloat("anim" + i, v);
            }
        } catch (Throwable t) {
            for (int i = 0; i < 3; i++) ed.putFloat("anim" + i, 1f);
        }
    }

    private static boolean setAnimations(float a, float b, float c) {
        try {
            Object w = wm();
            float[] v = {a, b, c};
            for (int i = 0; i < 3; i++) w.getClass().getMethod("setAnimationScale", int.class, float.class).invoke(w, i, v[i]);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- background apps ----

    private static void clearBackground(Context c, boolean deep) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            Set<String> keep = new HashSet<String>();
            keep.add(c.getPackageName());
            keep.add("android");
            keep.add("com.android.systemui");
            keep.add("com.android.phone");
            keep.add("com.arch.locker");
            try {
                android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
                if (ri != null) keep.add(ri.activityInfo.packageName);
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                for (InputMethodInfo i : imm.getEnabledInputMethodList()) keep.add(i.getPackageName());
            } catch (Throwable t) { }
            boolean music = false;
            try { music = ((android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE)).isMusicActive(); } catch (Throwable t) { }
            List<ActivityManager.RunningAppProcessInfo> l = am.getRunningAppProcesses();
            if (l == null) return;
            for (ActivityManager.RunningAppProcessInfo pi : l) {
                int min = deep && !music ? ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
                        : ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
                if (pi.importance < min) continue;
                if (pi.pkgList == null) continue;
                for (String pkg : pi.pkgList) if (!keep.contains(pkg)) am.killBackgroundProcesses(pkg);
            }
        } catch (Throwable t) { }
    }

    // ---- root part ----

    private static String root(String script) throws Exception {
        Process p = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(p.getOutputStream());
        os.writeBytes(script + "\nexit\n");
        os.flush();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        p.waitFor();
        return sb.toString();
    }

    private static final String ENABLE =
            "sync\n"
            + "for c in /sys/devices/system/cpu/cpu[0-9]*; do\n"
            + "  n=${c##*/}\n"
            + "  if [ -e $c/cpufreq/scaling_governor ]; then\n"
            + "    echo \"gov_$n=$(cat $c/cpufreq/scaling_governor)\"\n"
            + "    av=$(cat $c/cpufreq/scaling_available_governors)\n"
            + "    case \" $av \" in *\" performance \"*) echo performance > $c/cpufreq/scaling_governor;; esac\n"
            + "  fi\n"
            + "done\n"
            + "echo \"mpd=$(getprop init.svc.mpdecision)\"\n"
            + "cur=$(cat /sys/module/lowmemorykiller/parameters/minfree 2>/dev/null)\n"
            + "echo \"lmk=$cur\"\n"
            + "if [ -n \"$cur\" ]; then new=\"\"; IFS=,; for v in $cur; do new=\"$new$((v*3/2)),\"; done; unset IFS;\n"
            + "  new=${new%,}; echo $new > /sys/module/lowmemorykiller/parameters/minfree; fi\n"
            + "ra=$(cat /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null)\n"
            + "echo \"ra=$ra\"\n"
            + "[ -n \"$ra\" ] && echo 1024 > /sys/block/mmcblk0/queue/read_ahead_kb\n"
            + "echo 1 > /proc/sys/vm/compact_memory 2>/dev/null\n"
            + "echo 3 > /proc/sys/vm/drop_caches\n"
            + "echo done=1\n";

    private static String safe(String v) {
        return v.replaceAll("[^A-Za-z0-9_,.\\-]", "");
    }

    private static String restoreScript(String saved) {
        StringBuilder s = new StringBuilder();
        for (String line : saved.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = safe(line.substring(0, eq)), v = safe(line.substring(eq + 1));
            if (v.length() == 0) continue;
            if (k.startsWith("gov_cpu")) {
                s.append("echo ").append(v).append(" > /sys/devices/system/cpu/").append(k.substring(4))
                        .append("/cpufreq/scaling_governor\n");
            } else if (k.equals("lmk")) {
                s.append("echo ").append(v).append(" > /sys/module/lowmemorykiller/parameters/minfree\n");
            } else if (k.equals("ra")) {
                s.append("echo ").append(v).append(" > /sys/block/mmcblk0/queue/read_ahead_kb\n");
            }
        }
        return s.toString();
    }

    // ---- public ----

    /** Returns a one-line result for a toast. Call from a background thread. */
    static String enable(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long before = freeMb(c);
        SharedPreferences.Editor ed = sp.edit();
        saveAnimations(ed);
        boolean anim = setAnimations(ANIM, ANIM, ANIM);
        clearBackground(c, false);
        String saved = "";
        boolean rooted = true;
        try {
            saved = root(ENABLE);
            if (!saved.contains("done=1")) rooted = false;
        } catch (Throwable t) {
            rooted = false;
        }
        ed.putString("saved", saved).putBoolean("on", true)
                .putLong("boot", System.currentTimeMillis() - SystemClock.elapsedRealtime()).commit();
        try { Thread.sleep(700); } catch (InterruptedException e) { }
        long gain = freeMb(c) - before;
        return "Ultra Sigma ON" + (gain > 0 ? "  +" + gain + " MB free" : "")
                + (rooted ? "" : "  (no root: CPU tweaks skipped)")
                + (anim ? "" : "  (animations unchanged)");
    }

    static String disable(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean rooted = true;
        if (sp.getBoolean("max", false)) {
            try { restoreMax(c, sp, false); } catch (Throwable t) { rooted = false; }
        }
        try {
            String saved = sp.getString("saved", "");
            String script = restoreScript(saved);
            if (saved.contains("mpd=running")) script += "start mpdecision\n";
            if (script.length() > 0) root(script);
        } catch (Throwable t) {
            rooted = false;
        }
        setAnimations(sp.getFloat("anim0", 1f), sp.getFloat("anim1", 1f), sp.getFloat("anim2", 1f));
        sp.edit().clear().commit();
        return "Ultra Sigma OFF" + (rooted ? "" : "  (could not restore CPU settings, reboot to reset)");
    }

    // ======================= Ultra Sigma Max =======================

    static boolean isMax(Context c) {
        return isOn(c) && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("max", false);
    }

    private static String val(String saved, String key) {
        for (String line : saved.split("\n")) {
            if (line.startsWith(key + "=")) return safe(line.substring(key.length() + 1));
        }
        return "";
    }

    private static final String MAX_SCRIPT =
            "echo \"swap=$(cat /proc/sys/vm/swappiness 2>/dev/null)\"\n"
            + "echo \"vfs=$(cat /proc/sys/vm/vfs_cache_pressure 2>/dev/null)\"\n"
            + "echo \"dr=$(cat /proc/sys/vm/dirty_ratio 2>/dev/null)\"\n"
            + "echo \"dbr=$(cat /proc/sys/vm/dirty_background_ratio 2>/dev/null)\"\n"
            + "echo \"mfk=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null)\"\n"
            + "echo \"efk=$(cat /proc/sys/vm/extra_free_kbytes 2>/dev/null)\"\n"
            + "echo \"pc=$(cat /proc/sys/vm/page-cluster 2>/dev/null)\"\n"
            + "echo \"ksm=$(cat /sys/kernel/mm/ksm/run 2>/dev/null)\"\n"
            + "echo 200 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null\n"
            + "echo 10 > /proc/sys/vm/dirty_ratio 2>/dev/null\n"
            + "echo 5 > /proc/sys/vm/dirty_background_ratio 2>/dev/null\n"
            + "m=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null)\n"
            + "[ -n \"$m\" ] && [ \"$m\" -lt 5120 ] && echo 5120 > /proc/sys/vm/min_free_kbytes\n"
            + "[ -e /proc/sys/vm/extra_free_kbytes ] && echo 12288 > /proc/sys/vm/extra_free_kbytes\n"
            + "if [ -e /sys/kernel/mm/ksm/run ]; then echo 100 > /sys/kernel/mm/ksm/pages_to_scan; "
            + "echo 500 > /sys/kernel/mm/ksm/sleep_millisecs; echo 1 > /sys/kernel/mm/ksm/run; fi\n"
            + "if [ -e /sys/block/zram0/disksize ] && ! grep -q zram /proc/swaps; then\n"
            + "  d=$(cat /sys/block/zram0/dev); ma=${d%%:*}; mi=${d##*:}\n"
            + "  [ -e /dev/zram0 ] || mknod /dev/zram0 b $ma $mi\n"
            + "  echo 100663296 > /sys/block/zram0/disksize\n"
            + "  (mkswap /dev/zram0 || busybox mkswap /dev/zram0) >/dev/null 2>&1\n"
            + "  if (swapon /dev/zram0 || busybox swapon /dev/zram0) 2>/dev/null; then\n"
            + "    echo zram=1; echo 100 > /proc/sys/vm/swappiness; echo 0 > /proc/sys/vm/page-cluster\n"
            + "  fi\n"
            + "fi\n"
            + "cur=\"@LMK@\"\n"
            + "if [ -n \"$cur\" ]; then new=\"\"; IFS=,; for v in $cur; do new=\"$new$((v*2)),\"; done; unset IFS;\n"
            + "  new=${new%,}; echo $new > /sys/module/lowmemorykiller/parameters/minfree; fi\n"
            + "echo 1 > /proc/sys/vm/compact_memory 2>/dev/null\n"
            + "echo 3 > /proc/sys/vm/drop_caches\n"
            + "am kill-all 2>/dev/null\n"
            + "echo done=1\n";

    /** Puts every Max kernel value back. When backToSigma is true the plain Sigma values are applied again. */
    private static void restoreMax(Context c, SharedPreferences sp, boolean backToSigma) throws Exception {
        String sm = sp.getString("savedmax", "");
        String lmk = val(sp.getString("saved", ""), "lmk");
        StringBuilder s = new StringBuilder();
        if (sm.contains("zram=1")) {
            s.append("swapoff /dev/zram0 || busybox swapoff /dev/zram0\n");
            s.append("echo 1 > /sys/block/zram0/reset\n");
        }
        String[][] map = {{"swap", "/proc/sys/vm/swappiness"}, {"vfs", "/proc/sys/vm/vfs_cache_pressure"},
                {"dr", "/proc/sys/vm/dirty_ratio"}, {"dbr", "/proc/sys/vm/dirty_background_ratio"},
                {"mfk", "/proc/sys/vm/min_free_kbytes"}, {"efk", "/proc/sys/vm/extra_free_kbytes"},
                {"pc", "/proc/sys/vm/page-cluster"}, {"ksm", "/sys/kernel/mm/ksm/run"}};
        for (String[] m : map) {
            String v = val(sm, m[0]);
            if (v.length() > 0) s.append("echo ").append(v).append(" > ").append(m[1]).append(" 2>/dev/null\n");
        }
        if (backToSigma && lmk.length() > 0) {
            s.append("new=\"\"; IFS=,; for v in ").append(lmk).append("; do new=\"$new$((v*3/2)),\"; done; unset IFS;\n");
            s.append("new=${new%,}; echo $new > /sys/module/lowmemorykiller/parameters/minfree\n");
        }
        if (s.length() > 0) root(s.toString());
        ContentResolverSync.set(sp.getBoolean("syncOrig", true));
        if (backToSigma) setAnimations(ANIM, ANIM, ANIM);
        sp.edit().remove("max").remove("savedmax").remove("syncOrig").commit();
    }

    /** Sigma plus deeper memory tuning for a 512 MB phone. Call from a background thread. */
    static String enableMax(Context c) {
        String msg = "";
        if (!isOn(c)) msg = enable(c);
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long before = freeMb(c);
        boolean rooted = true, zram = false;
        String out = "";
        try {
            String lmk = val(sp.getString("saved", ""), "lmk");
            out = root(MAX_SCRIPT.replace("@LMK@", lmk));
            if (!out.contains("done=1")) rooted = false;
            zram = out.contains("zram=1");
        } catch (Throwable t) {
            rooted = false;
        }
        boolean syncOrig = ContentResolverSync.get();
        ContentResolverSync.set(false);
        setAnimations(0.3f, 0.3f, 0.3f);
        clearBackground(c, true);
        sp.edit().putBoolean("max", true).putString("savedmax", out).putBoolean("syncOrig", syncOrig).commit();
        try { Thread.sleep(700); } catch (InterruptedException e) { }
        long gain = freeMb(c) - before;
        return "Ultra Sigma MAX ON" + (gain > 0 ? "  +" + gain + " MB more free" : "")
                + (rooted ? (zram ? "  (compressed swap on)" : "  (no zram in this kernel)") : "  (no root: memory tuning skipped)");
    }

    /** Takes only the Max extras away; plain Sigma stays on. */
    static String disableMax(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            restoreMax(c, sp, true);
            return "Ultra Sigma MAX OFF (Sigma still on)";
        } catch (Throwable t) {
            sp.edit().remove("max").commit();
            return "Ultra Sigma MAX OFF  (could not restore everything, reboot to reset)";
        }
    }

    /** Quick memory refresh used when the screen turns off while Max is on. */
    static void refreshRam(Context c) {
        try {
            clearBackground(c, true);
            root("sync\necho 1 > /proc/sys/vm/compact_memory 2>/dev/null\necho 3 > /proc/sys/vm/drop_caches\n");
        } catch (Throwable t) { }
    }

    /** Master sync switch helper (kept tiny so the logic above stays readable). */
    private static final class ContentResolverSync {
        static boolean get() {
            try { return android.content.ContentResolver.getMasterSyncAutomatically(); } catch (Throwable t) { return true; }
        }
        static void set(boolean on) {
            try { android.content.ContentResolver.setMasterSyncAutomatically(on); } catch (Throwable t) { }
        }
    }
}

package com.archdroid.swipenav;

import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;

/** Keeps mobile data switched off while there is no SIM card, so it can't "turn itself on". */
final class SimGuard {
    private SimGuard() { }

    static boolean noSim(Context c) {
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Safe to call often: does nothing when a SIM is present or data is already off. */
    static void check(final Context c) {
        if (!noSim(c)) return;
        final ContentResolver cr = c.getContentResolver();
        try {
            if (Settings.Global.getInt(cr, "mobile_data", 1) == 0) return;
        } catch (Throwable t) { }
        new Thread(new Runnable() {
            public void run() {
                try {
                    ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                    cm.getClass().getMethod("setMobileDataEnabled", boolean.class).invoke(cm, false);
                } catch (Throwable t) {
                    try { Settings.Global.putInt(cr, "mobile_data", 0); } catch (Throwable t2) { }
                }
            }
        }).start();
    }
}

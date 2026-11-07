package com.archdroid.swipenav;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        c.startService(new Intent(c, SwipeNavService.class));
    }
}

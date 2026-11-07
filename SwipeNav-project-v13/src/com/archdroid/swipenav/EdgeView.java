package com.archdroid.swipenav;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Thin invisible strip at the top edge. A downward swipe opens the control panel. */
public class EdgeView extends View {
    public interface OnOpen { void open(); }

    private final OnOpen cb;
    private final float d = getResources().getDisplayMetrics().density;
    private float downY;
    private boolean fired;

    public EdgeView(Context c, OnOpen cb) {
        super(c);
        this.cb = cb;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                fired = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!fired && e.getRawY() - downY > 28 * d) {
                    fired = true;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    cb.open();
                }
                break;
            default:
                break;
        }
        return true;
    }
}

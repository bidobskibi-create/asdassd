package com.archdroid.swipenav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** iOS 9 style volume HUD: dark rounded square, speaker icon, 16 dots at the bottom. */
public class VolumeHud extends View {
    private static final int DOTS = 16;
    private final float d = getResources().getDisplayMetrics().density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Handler h = new Handler();
    private float frac;
    private int level; // filled dots

    private final Runnable hide = new Runnable() {
        public void run() {
            animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(new Runnable() { public void run() { setVisibility(INVISIBLE); } }).start();
        }
    };

    public VolumeHud(Context c) { super(c); setVisibility(INVISIBLE); setAlpha(0f); }

    public void show(int vol, int max) {
        frac = max > 0 ? (float) vol / max : 0f;
        level = Math.round(frac * DOTS);
        invalidate();
        h.removeCallbacks(hide);
        if (getVisibility() != VISIBLE || getAlpha() < 0.99f) {
            if (getVisibility() != VISIBLE) { setScaleX(0.85f); setScaleY(0.85f); }
            setVisibility(VISIBLE);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(new OvershootInterpolator(1.6f)).withEndAction(null).start();
        }
        h.postDelayed(hide, 1500);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), ht = getHeight();
        // card
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC1C1C1E);
        r.set(0, 0, w, ht);
        c.drawRoundRect(r, 22 * d, 22 * d, p);

        // speaker icon, centered in the upper area
        float cx = w / 2f - 6 * d, cy = ht * 0.42f, s = 1.25f * d;
        p.setColor(0xFFFFFFFF);
        path.reset();
        path.moveTo(cx - 22 * s, cy - 8 * s);
        path.lineTo(cx - 12 * s, cy - 8 * s);
        path.lineTo(cx + 2 * s, cy - 20 * s);
        path.lineTo(cx + 2 * s, cy + 20 * s);
        path.lineTo(cx - 12 * s, cy + 8 * s);
        path.lineTo(cx - 22 * s, cy + 8 * s);
        path.close();
        c.drawPath(path, p);

        int waves = level == 0 ? 0 : 1 + (level - 1) * 3 / DOTS; // 0..3
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2.4f * d);
        for (int i = 0; i < 3; i++) {
            p.setColor(i < waves ? 0xFFFFFFFF : 0x44FFFFFF);
            float rad = (11 + 9 * i) * s;
            r.set(cx + 2 * s - rad + 4 * s, cy - rad, cx + 2 * s + rad + 4 * s, cy + rad);
            c.drawArc(r, -38, 76, false, p);
        }
        if (level == 0) { // muted: slash
            p.setColor(0xFFFFFFFF);
            c.drawLine(cx - 24 * s, cy - 24 * s, cx + 26 * s, cy + 24 * s, p);
        }

        // 16 dots
        p.setStyle(Paint.Style.FILL);
        float gap = 7.2f * d, rad = 1.9f * d, total = gap * (DOTS - 1);
        float x0 = (w - total) / 2f, y = ht - 16 * d;
        for (int i = 0; i < DOTS; i++) {
            p.setColor(i < level ? 0xFFFFFFFF : 0x55FFFFFF);
            c.drawCircle(x0 + i * gap, y, rad, p);
        }
    }
}

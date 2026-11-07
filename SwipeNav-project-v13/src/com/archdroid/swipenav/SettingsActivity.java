package com.archdroid.swipenav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private float d;

    private int dp(int v) { return (int) (v * d); }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1A1C22); g.setCornerRadius(dp(16));
        l.setBackgroundDrawable(g);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        l.setLayoutParams(lp);
        l.setOnTouchListener(new android.view.View.OnTouchListener() {
            public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(120).start();
                else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
                return false;
            }
        });
        return l;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Switch toggle(String title, String sub, boolean on, CompoundButton.OnCheckedChangeListener l, LinearLayout parent) {
        Switch s = new Switch(this);
        s.setText(title); s.setTextColor(Color.WHITE); s.setTextSize(16);
        s.setChecked(on); s.setOnCheckedChangeListener(l);
        parent.addView(s);
        TextView t = text(sub, 12, 0xFF8A8F9C, false);
        t.setPadding(0, dp(4), 0, 0);
        parent.addView(t);
        return s;
    }

    private void slider(final String title, final String unit, final int min, int max, int cur,
                        final String key, final SharedPreferences sp, LinearLayout parent) {
        final TextView label = text(title + ": " + cur + unit, 14, Color.WHITE, false);
        label.setPadding(0, dp(12), 0, 0);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                label.setText(title + ": " + v + unit);
                if (fromUser) sp.edit().putInt(key, v).commit();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(label);
        parent.addView(sb);
    }

    /** One editable gesture row: tap it, pick an action from a list. */
    private void gestureRow(LinearLayout parent, String icon, final String title, final String key,
                            final int def, final SharedPreferences sp) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView ic = text(icon, 18, 0xFF6EE7A8, true);
        ic.setMinWidth(dp(40));
        TextView a = text(title, 14, 0xFFC9CCD6, false);
        a.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        final TextView val = text(Actions.LABELS[Actions.sanitize(sp.getInt(key, def))] + "  \u203A", 14, Color.WHITE, true);
        row.addView(ic); row.addView(a); row.addView(val);
        row.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                int cur = Actions.sanitize(sp.getInt(key, def));
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(title)
                        .setSingleChoiceItems(Actions.LABELS, cur, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dlg, int which) {
                                sp.edit().putInt(key, which).commit();
                                val.setText(Actions.LABELS[which] + "  \u203A");
                                dlg.dismiss();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        parent.addView(row);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        startService(new Intent(this, SwipeNavService.class));
        final SharedPreferences sp = getSharedPreferences(SwipeNavService.PREFS, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        TextView title = text("Swipe navigation", 26, Color.WHITE, true);
        title.setPadding(dp(4), 0, 0, dp(4));
        TextView sub = text("Gesture navigation for your Lumia 520", 13, 0xFF8A8F9C, false);
        sub.setPadding(dp(4), 0, 0, dp(18));
        root.addView(title); root.addView(sub);

        LinearLayout c1 = card();
        toggle("Swipe gestures", "Hides the on-screen buttons. Turn off to bring them back.",
                Settings.System.getInt(getContentResolver(), "dev_force_show_navbar", 1) == 0,
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        try { Settings.System.putInt(getContentResolver(), "dev_force_show_navbar", on ? 0 : 1); }
                        catch (Throwable t) {
                            Toast.makeText(SettingsActivity.this, "Could not change setting", Toast.LENGTH_SHORT).show();
                        }
                    }
                }, c1);
        root.addView(c1);

        LinearLayout c2 = card();
        toggle("Show swipe bar", "The thin pill at the bottom edge. Gestures still work when hidden.",
                sp.getBoolean(SwipeNavService.KEY_SHOW_BAR, true),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        sp.edit().putBoolean(SwipeNavService.KEY_SHOW_BAR, on).commit();
                    }
                }, c2);
        root.addView(c2);

        LinearLayout cp = card();
        TextView hp = text("Position & size", 13, 0xFF5AB4FF, true);
        cp.addView(hp);
        slider("Position (0 = left, 100 = right)", "%", 0, 100, sp.getInt(SwipeNavService.KEY_POS, 50), SwipeNavService.KEY_POS, sp, cp);
        slider("Hitbox width", "%", 20, 100, sp.getInt(SwipeNavService.KEY_WIDTH, 100), SwipeNavService.KEY_WIDTH, sp, cp);
        slider("Hitbox height", " dp", 12, 80, sp.getInt(SwipeNavService.KEY_HEIGHT, 24), SwipeNavService.KEY_HEIGHT, sp, cp);
        toggle("Show hitbox preview", "Tints the touch area blue so you can see it while adjusting.",
                sp.getBoolean(SwipeNavService.KEY_HITBOX, false),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        sp.edit().putBoolean(SwipeNavService.KEY_HITBOX, on).commit();
                    }
                }, cp);
        root.addView(cp);

        LinearLayout cf = card();
        TextView hf = text("Feel", 13, 0xFF5AB4FF, true);
        cf.addView(hf);
        slider("Swipe sensitivity", "%", 50, 150, sp.getInt(SwipeNavService.KEY_SENS, 100), SwipeNavService.KEY_SENS, sp, cf);
        slider("Bar opacity", "%", 20, 100, sp.getInt(SwipeNavService.KEY_OPACITY, 100), SwipeNavService.KEY_OPACITY, sp, cf);
        toggle("Haptic feedback", "Short vibration when a gesture triggers.",
                sp.getBoolean(SwipeNavService.KEY_HAPTIC, true),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        sp.edit().putBoolean(SwipeNavService.KEY_HAPTIC, on).commit();
                    }
                }, cf);
        TextView reset = text("Reset position & feel to defaults", 14, 0xFF6EE7A8, true);
        reset.setPadding(0, dp(16), 0, dp(4));
        reset.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                sp.edit().remove(SwipeNavService.KEY_POS).remove(SwipeNavService.KEY_WIDTH)
                        .remove(SwipeNavService.KEY_HEIGHT).remove(SwipeNavService.KEY_SENS)
                        .remove(SwipeNavService.KEY_OPACITY).remove(SwipeNavService.KEY_HAPTIC)
                        .remove(SwipeNavService.KEY_HITBOX).remove(SwipeNavService.KEY_PANEL_W)
                        .remove(SwipeNavService.KEY_PANEL_RIGHT).commit();
                recreate();
            }
        });
        cf.addView(reset);
        root.addView(cf);

        LinearLayout cc = card();
        TextView hc = text("Control panel", 13, 0xFF5AB4FF, true);
        hc.setPadding(0, 0, 0, dp(8));
        cc.addView(hc);
        toggle("Control panel", "Swipe down from the top edge. While on, it replaces Android's default quick settings (turn it off to get those back).",
                sp.getBoolean(SwipeNavService.KEY_PANEL, true),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        sp.edit().putBoolean(SwipeNavService.KEY_PANEL, on).commit();
                    }
                }, cc);
        slider("Trigger width", "%", 20, 100, sp.getInt(SwipeNavService.KEY_PANEL_W, 50), SwipeNavService.KEY_PANEL_W, sp, cc);
        toggle("Trigger on the right side", "Off = left side. Pulling down on the other side still opens notifications.",
                sp.getBoolean(SwipeNavService.KEY_PANEL_RIGHT, true),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        sp.edit().putBoolean(SwipeNavService.KEY_PANEL_RIGHT, on).commit();
                    }
                }, cc);
        TextView openNow = text("Open control panel now", 14, 0xFF6EE7A8, true);
        openNow.setPadding(0, dp(16), 0, dp(4));
        openNow.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                sendBroadcast(new Intent(SwipeNavService.ACTION_TOGGLE));
            }
        });
        cc.addView(openNow);
        root.addView(cc);

        LinearLayout cv = card();
        toggle("iOS-style volume HUD", "Centered rounded volume indicator with a dot meter.",
                sp.getBoolean(SwipeNavService.KEY_VOL_HUD, true),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        sp.edit().putBoolean(SwipeNavService.KEY_VOL_HUD, on).commit();
                    }
                }, cv);
        root.addView(cv);

        LinearLayout cl = card();
        TextView hl = text("Lock screen", 13, 0xFF5AB4FF, true);
        hl.setPadding(0, 0, 0, dp(2));
        cl.addView(hl);
        LockRows.add(this, cl);
        root.addView(cl);

        LinearLayout c3 = card();
        TextView h = text("Gestures", 13, 0xFF5AB4FF, true);
        h.setPadding(0, 0, 0, dp(2));
        c3.addView(h);
        TextView hint = text("Tap a gesture to choose what it does. \"Force close app\" stops the app you are in.", 12, 0xFF8A8F9C, false);
        hint.setPadding(0, 0, 0, dp(6));
        c3.addView(hint);
        gestureRow(c3, "\u2190", "Swipe left", SwipeNavService.KEY_ACT_LEFT, Actions.BACK, sp);
        gestureRow(c3, "\u2192", "Swipe right", SwipeNavService.KEY_ACT_RIGHT, Actions.BACK, sp);
        gestureRow(c3, "\u2191", "Swipe up", SwipeNavService.KEY_ACT_UP, Actions.HOME, sp);
        gestureRow(c3, "\u2191\u2022", "Swipe up and hold, or far", SwipeNavService.KEY_ACT_HOLD, Actions.RECENTS, sp);
        TextView resetG = text("Reset gestures to default", 14, 0xFF6EE7A8, true);
        resetG.setPadding(0, dp(12), 0, dp(4));
        resetG.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                sp.edit().remove(SwipeNavService.KEY_ACT_LEFT).remove(SwipeNavService.KEY_ACT_RIGHT)
                        .remove(SwipeNavService.KEY_ACT_UP).remove(SwipeNavService.KEY_ACT_HOLD).commit();
                recreate();
            }
        });
        c3.addView(resetG);
        root.addView(c3);

        root.setBackgroundColor(0xFF0E0F13);
        // entrance: everything fades and slides up in a stagger
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View v = root.getChildAt(i);
            v.setAlpha(0f); v.setTranslationY(dp(28));
            v.animate().alpha(1f).translationY(0).setStartDelay(60 * i).setDuration(380)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0E0F13);
        sv.addView(root);
        setContentView(sv);
    }
}

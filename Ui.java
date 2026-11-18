package com.eightubah.mod.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.eightubah.mod.L;

import java.util.List;

/**
 * Programmatic view helpers — the Android analog of the iOS RYLabel / RYBtn /
 * RYCard / RYSw / RYSl / RYGrid. No XML layouts, same as the iOS build had no
 * XIBs. RULE: controls get short names + status lines only, no explanations.
 */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusPx); return g;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    public static LinearLayout.LayoutParams lpw() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }

    public static TextView label(Context c, String text, float sp, boolean bold, int color) {
        TextView t = new TextView(c); t.setText(text); t.setTextColor(color == 0 ? L.WHITE : color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        return t;
    }

    public interface Act { void run(); }
    public interface BoolAct { void run(boolean on); }
    public interface FloatAct { void run(float v); }

    public static Button button(Context c, String text, boolean filled, final Act onClick) {
        Button b = new Button(c); b.setAllCaps(false); b.setText(text);
        b.setTextColor(L.WHITE); b.setBackground(round(filled ? L.accent() : L.CARD2, dp(c, 10)));
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        b.setMinHeight(dp(c, 42)); b.setStateListAnimator(null);
        b.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        return b;
    }

    public static LinearLayout vstack(Context c, int spacingDp, View... views) {
        LinearLayout s = new LinearLayout(c); s.setOrientation(LinearLayout.VERTICAL);
        s.setLayoutDirection(L.layoutDir());
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams p = lpw(); if (i > 0) p.topMargin = dp(c, spacingDp); s.addView(views[i], p);
        }
        return s;
    }
    public static LinearLayout hstack(Context c, int spacingDp, View... views) {
        LinearLayout s = new LinearLayout(c); s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER_VERTICAL); s.setLayoutDirection(L.layoutDir());
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) p.setMarginStart(dp(c, spacingDp)); s.addView(views[i], p);
        }
        return s;
    }

    public static LinearLayout card(Context c, View... views) {
        LinearLayout box = new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(L.CARD, dp(c, 14))); box.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        box.setLayoutDirection(L.layoutDir());
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams p = lpw(); if (i > 0) p.topMargin = dp(c, 10); box.addView(views[i], p);
        }
        return box;
    }

    public static View switchRow(Context c, String title, boolean on, int tint, final BoolAct chg) {
        Switch sw = new Switch(c); sw.setChecked(on);
        try { sw.getThumbDrawable().setTint(L.WHITE); sw.getTrackDrawable().setTint(tint == 0 ? L.accent() : tint); } catch (Throwable t) {}
        sw.setOnCheckedChangeListener((b, v) -> { if (chg != null) chg.run(v); });
        TextView l = label(c, title, 15, true, 0);
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setLayoutDirection(L.layoutDir());
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(l, lpL);
        row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** float slider with a live value label. */
    public static View slider(Context c, String title, final String unit, final float min, final float max, float val, final int dec, boolean enabled, final FloatAct chg) {
        final TextView val0 = label(c, fmt(val, unit, dec), 13, false, L.MUTED);
        val0.setGravity(Gravity.CENTER); val0.setWidth(dp(c, 76));
        final SeekBar sb = new SeekBar(c); sb.setEnabled(enabled); sb.setMax(1000);
        sb.setProgress((int) ((val - min) / (max - min) * 1000));
        try { sb.getProgressDrawable().setTint(L.accent()); sb.getThumb().setTint(L.accent()); } catch (Throwable t) {}
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                float v = min + (max - min) * prog / 1000f; val0.setText(fmt(v, unit, dec)); if (chg != null) chg.run(v);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        TextView t = title == null ? null : label(c, title, 14, false, 0);
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setLayoutDirection(L.layoutDir());
        row.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(val0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (t == null) return row;
        return vstack(c, 6, t, row);
    }

    private static String fmt(float v, String unit, int dec) {
        String s = String.format("%." + Math.max(0, dec) + "f", v);
        return unit != null && unit.length() > 0 ? s + " " + unit : s;
    }

    public static LinearLayout grid(Context c, List<View> views, int cols) {
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setLayoutDirection(L.layoutDir());
        for (int i = 0; i < views.size(); i += cols) {
            LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setLayoutDirection(L.layoutDir());
            for (int j = 0; j < cols; j++) {
                int k = i + j; View v = k < views.size() ? views.get(k) : new View(c);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j > 0) p.setMarginStart(dp(c, 8)); row.addView(v, p);
            }
            LinearLayout.LayoutParams rp = lpw(); if (i > 0) rp.topMargin = dp(c, 8); col.addView(row, rp);
        }
        return col;
    }

    public static void toast(Context c, String s) {
        try { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable t) {}
    }
}

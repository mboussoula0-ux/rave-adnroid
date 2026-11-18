package com.eightubah.mod.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.L;
import com.eightubah.mod.Lic;
import com.eightubah.mod.XEntry;
import com.eightubah.mod.feat.Backgrounds;
import com.eightubah.mod.feat.Branding;
import com.eightubah.mod.feat.ViewScan;

import java.lang.ref.WeakReference;

/**
 * The floating button + lifecycle + periodic tick. Android analog of the iOS
 * RYCore: attaches a draggable FAB to the activity, opens the panel, and runs an
 * 800 ms tick (room detection, auto-open, branding, background sync, cat,
 * licence re-check).
 */
public final class Overlay {
    private static final Overlay I = new Overlay();
    public static Overlay get() { return I; }
    private Overlay() {}

    private WeakReference<Activity> cur = new WeakReference<>(null);
    private TextView fab;
    private Panel panel;
    private boolean wasIn;
    private int licTick, brandTick;
    private final Handler H = new Handler(Looper.getMainLooper());
    private boolean ticking;

    public Activity activity() { return cur.get(); }

    public void onActivityResumed(Activity a) {
        cur = new WeakReference<>(a);
        ensureFab(a);
        if (!ticking) { ticking = true; H.post(TICK); }
        Lic.revalidate();
    }
    public void onActivityPaused(Activity a) { /* keep fab per-activity; tick continues */ }

    private void ensureFab(Activity a) {
        try {
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            if (fab != null && fab.getParent() == decor) return;
            TextView b = new TextView(a);
            b.setText("☰"); b.setTextColor(Color.WHITE); b.setGravity(Gravity.CENTER); b.setTextSize(20);
            b.setBackground(Ui.round(L.accent(), Ui.dp(a, 26)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52));
            lp.gravity = Gravity.TOP | Gravity.START;
            decor.addView(b, lp);
            float dm = a.getResources().getDisplayMetrics().widthPixels;
            b.setTranslationX(Cfg.f("bx", dm - Ui.dp(a, 60)));
            b.setTranslationY(Cfg.f("by", a.getResources().getDisplayMetrics().heightPixels * 0.35f));
            attachDrag(b);
            fab = b;
        } catch (Throwable t) { XEntry.log("fab: %s", t); }
    }

    private void attachDrag(final TextView b) {
        b.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy, sx, sy; boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: dx = e.getRawX() - v.getTranslationX(); dy = e.getRawY() - v.getTranslationY(); sx = e.getRawX(); sy = e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setTranslationX(e.getRawX() - dx); v.setTranslationY(e.getRawY() - dy);
                        if (Math.abs(e.getRawX() - sx) > 10 || Math.abs(e.getRawY() - sy) > 10) moved = true; return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) openPanel();
                        else { Cfg.setF("bx", v.getTranslationX()); Cfg.setF("by", v.getTranslationY()); }
                        return true;
                }
                return false;
            }
        });
    }

    public void openPanel() {
        Activity a = cur.get(); if (a == null || a.isFinishing()) return;
        if (panel != null && panel.isShowing()) return;
        panel = new Panel(a); panel.show();
    }
    public void panelClosed() { panel = null; }

    private final Runnable TICK = new Runnable() {
        public void run() {
            try { doTick(); } catch (Throwable t) { /* */ }
            H.postDelayed(this, 800);
        }
    };

    private void doTick() {
        Activity a = cur.get(); if (a == null) return;
        if (fab != null) fab.setBackground(Ui.round(L.accent(), Ui.dp(a, 26)));
        boolean in = ViewScan.inRoom(a);
        if (in && !wasIn && Cfg.b("autoOpen", false) && !Cfg.locked()) openPanel();
        wasIn = in;
        if (++licTick % 90 == 0) Lic.revalidate();
        Cat.tick(a);
        Backgrounds.sync(a, in);
        brandTick++;
        if (Cfg.b("brandOn", false) && brandTick % 5 == 1) Branding.walk(a);
    }

    // ---- small heads-up toast anchored near the bottom ----
    public void hud(String s) { Activity a = cur.get(); if (a != null) Ui.toast(a, s); }
}

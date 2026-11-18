package com.eightubah.mod.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.XEntry;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Cat (oneko): a small cat runs to wherever you tap. Port of the iOS RYCat*.
 * The overlay view is added to the activity decor; taps are read by hooking
 * Activity.dispatchTouchEvent (non-intrusive, like the iOS non-consuming tap
 * recognizer).
 */
public final class Cat {
    private Cat() {}

    private static CatView cat;
    private static Activity host;
    private static float px, py, tx, ty;
    private static boolean moving, installed;
    private static long idleSince, last;
    private static final Handler H = new Handler(Looper.getMainLooper());

    public static void tick(Activity a) {
        boolean want = !Cfg.locked() && Cfg.b("cat", true);
        if (want) ensure(a); else stop();
    }

    private static void ensure(Activity a) {
        installTouch();
        if (cat != null && host == a) return;
        stop();
        host = a;
        try {
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            cat = new CatView(a);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 40));
            decor.addView(cat, lp);
            int w = decor.getWidth() > 0 ? decor.getWidth() : a.getResources().getDisplayMetrics().widthPixels;
            int h = decor.getHeight() > 0 ? decor.getHeight() : a.getResources().getDisplayMetrics().heightPixels;
            px = w - Ui.dp(a, 60); py = h * 0.72f; tx = px; ty = py; moving = false; idleSince = now(); last = 0;
            place();
            H.removeCallbacks(STEP); H.post(STEP);
        } catch (Throwable t) { XEntry.log("cat ensure: %s", t); }
    }

    public static void stop() {
        H.removeCallbacks(STEP);
        if (cat != null && cat.getParent() instanceof ViewGroup) ((ViewGroup) cat.getParent()).removeView(cat);
        cat = null; host = null;
    }

    private static void installTouch() {
        if (installed) return; installed = true;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (cat == null || p.thisObject != host) return;
                        MotionEvent e = (MotionEvent) p.args[0];
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            tx = e.getX(); ty = e.getY(); moving = true;
                        }
                    } catch (Throwable t) { /* */ }
                }
            });
        } catch (Throwable t) { XEntry.log("cat touch hook: %s", t); }
    }

    private static final Runnable STEP = new Runnable() {
        public void run() {
            if (cat == null) return;
            long n = now(); float dt = last != 0 ? Math.min((n - last) / 1000f, 0.05f) : 0.016f; last = n;
            if (moving) {
                float dx = tx - px, dy = ty - py, dd = (float) Math.sqrt(dx * dx + dy * dy);
                if (dd <= Ui.dp(host, 26)) { moving = false; idleSince = n; }
                else {
                    float st = Math.min(Ui.dp(host, 170) * dt, dd - Ui.dp(host, 25));
                    px += dx / dd * st; py += dy / dd * st;
                    if (Math.abs(dx) > 2) cat.facing = dx >= 0 ? 1 : -1; cat.mode = 0; cat.phase += dt * 14;
                }
            }
            if (!moving) {
                float idle = (n - idleSince) / 1000f; cat.mode = idle > 12 ? 2 : 1; cat.phase += dt * 3;
                cat.blink = cat.mode == 1 && (n % 3400) < 160;
            }
            ViewGroup decor = cat.getParent() instanceof ViewGroup ? (ViewGroup) cat.getParent() : null;
            if (decor != null) {
                int w = decor.getWidth(), h = decor.getHeight();
                px = Math.min(Math.max(px, Ui.dp(host, 26)), w - Ui.dp(host, 26));
                py = Math.min(Math.max(py, Ui.dp(host, 20)), h - Ui.dp(host, 20));
            }
            place(); cat.invalidate();
            H.postDelayed(this, moving ? 33 : 100);
        }
    };

    private static void place() {
        if (cat == null) return;
        cat.setTranslationX(px - cat.getLayoutParams().width / 2f);
        cat.setTranslationY(py - cat.getLayoutParams().height / 2f);
    }
    private static long now() { return System.currentTimeMillis(); }

    public static void set(boolean on) { Cfg.b("cat", on); if (host != null) tick(host); }
}

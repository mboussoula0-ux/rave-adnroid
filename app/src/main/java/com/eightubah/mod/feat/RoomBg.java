package com.eightubah.mod.feat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.L;
import com.eightubah.mod.XEntry;

import java.io.File;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Room background via Rave's OWN renderer. The rearch UI is Compose, so inserting
 * a View behind it (the old Backgrounds path) doesn't show. Instead we ride
 * {@code RaveActivity.showBackgroundBitmap(Bitmap)} — Rave's own background
 * ImageView — and swap in the user's chosen image/colour/gradient whenever Rave
 * refreshes it. Deterministic, no Compose fight.
 */
public final class RoomBg {
    private RoomBg() {}

    private static final String RAVE_ACT = "com.wemesh.android.rearch.RaveActivity";

    static boolean enabled() {
        return !Cfg.locked() && !"none".equals(Cfg.s("bgMode", "none"));
    }

    public static void install(ClassLoader cl) {
        Class<?> ra = XposedHelpers.findClassIfExists(RAVE_ACT, cl);
        if (ra == null) { XEntry.log("roombg: RaveActivity not found"); return; }
        try {
            XposedHelpers.findAndHookMethod(ra, "showBackgroundBitmap", Bitmap.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    try {
                        Bitmap in = (Bitmap) p.args[0];
                        int w = in != null ? in.getWidth() : 1080;
                        int h = in != null ? in.getHeight() : 1920;
                        Bitmap my = render(w, h);
                        if (my != null) { p.args[0] = my; XEntry.log("roombg: swapped (%dx%d)", w, h); }
                    } catch (Throwable t) { XEntry.log("roombg swap: %s", t); }
                }
            });
            XEntry.log("roombg: hooked showBackgroundBitmap");
        } catch (Throwable t) { XEntry.log("roombg hook: %s", t); }
    }

    /** Draw the user's background choice into a fresh bitmap (+ dim overlay). */
    static Bitmap render(int w, int h) {
        if (w <= 0) w = 1080;
        if (h <= 0) h = 1920;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        String m = Cfg.s("bgMode", "none"), v = Cfg.s("bgValue", "");
        if ("image".equals(m)) {
            try {
                File f = new File(Cfg.dir(null), "bg.jpg");
                Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (src != null) {
                    float s = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
                    int dw = (int) (src.getWidth() * s), dh = (int) (src.getHeight() * s);
                    Rect dst = new Rect((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                    g.drawBitmap(src, null, dst, p);
                } else g.drawColor(Color.BLACK);
            } catch (Throwable t) { g.drawColor(Color.BLACK); }
        } else if ("color".equals(m)) {
            g.drawColor(L.hex(v));
        } else if ("gradient".equals(m)) {
            String[] q = v.split(",");
            if (q.length >= 2) {
                p.setShader(new LinearGradient(0, 0, w, h, L.hex(q[0]), L.hex(q[1]), Shader.TileMode.CLAMP));
                g.drawRect(0, 0, w, h, p); p.setShader(null);
            } else g.drawColor(Color.BLACK);
        } else {
            g.drawColor(Color.BLACK);
        }
        float dim = Cfg.f("dim", 30) / 100f * 0.9f;
        if (dim > 0f) g.drawColor(Color.argb((int) (dim * 255), 0, 0, 0));
        return bmp;
    }

    /** Push our background now, by calling Rave's own method (the hook re-swaps it). */
    public static void apply(android.app.Activity a) {
        if (a == null || !enabled()) return;
        if (!a.getClass().getName().contains("RaveActivity")) return;
        try {
            int w = a.getResources().getDisplayMetrics().widthPixels;
            int h = a.getResources().getDisplayMetrics().heightPixels;
            XposedHelpers.callMethod(a, "showBackgroundBitmap", render(w, h));
        } catch (Throwable t) { /* method may need a specific state; the hook still covers refreshes */ }
    }
}

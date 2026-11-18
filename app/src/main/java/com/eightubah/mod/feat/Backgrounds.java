package com.eightubah.mod.feat;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.L;
import com.eightubah.mod.XEntry;

import java.io.File;
import java.util.WeakHashMap;

/**
 * Room background (only you see it). Port of the iOS RYBGView + syncBG: a
 * background layer is inserted behind the room, and large opaque backgrounds are
 * made see-through so it shows (video / surface / web views are left alone).
 */
public final class Backgrounds {
    private Backgrounds() {}

    private static BgView bg;
    private static ViewGroup host;
    private static final WeakHashMap<View, Drawable> orig = new WeakHashMap<>();
    private static final String[] SKIP = {"Video", "Player", "Surface", "Texture", "GL", "WebView", "Web", "Exo", "Map"};

    public static void sync(Activity a, boolean inRoom) {
        boolean want = inRoom && !"none".equals(Cfg.s("bgMode", "none"));
        if (!want) { remove(); return; }
        ViewGroup h = fullHost(a);
        if (h == null) return;
        if (bg != null && bg.getParent() != h) remove();
        if (bg == null) {
            bg = new BgView(a);
            // plain ViewGroup.LayoutParams so addView converts it for whatever container type the host is
            h.addView(bg, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            host = h;
        } else if (h.getChildAt(0) != bg) {
            try { h.removeView(bg); h.addView(bg, 0); } catch (Throwable t) {}
        }
        clearBig(h, h);
        bg.apply();
    }

    private static void remove() {
        if (bg != null && bg.getParent() instanceof ViewGroup) ((ViewGroup) bg.getParent()).removeView(bg);
        bg = null; host = null;
        for (java.util.Map.Entry<View, Drawable> e : orig.entrySet()) { View v = e.getKey(); if (v != null) v.setBackground(e.getValue()); }
        orig.clear();
    }

    private static ViewGroup fullHost(Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup root = (ViewGroup) content;
        final ViewGroup[] best = {root}; final int[] bestArea = {0};
        int sw = a.getResources().getDisplayMetrics().widthPixels, sh = a.getResources().getDisplayMetrics().heightPixels;
        ViewScan.walk(root, (v, d) -> {
            if (!(v instanceof ViewGroup)) return;
            if (v.getWidth() >= sw * 0.9 && v.getHeight() >= sh * 0.8) { int ar = v.getWidth() * v.getHeight(); if (ar >= bestArea[0]) { bestArea[0] = ar; best[0] = (ViewGroup) v; } }
        });
        return best[0];
    }

    private static boolean skip(View v) {
        String cn = v.getClass().getName();
        for (String k : SKIP) if (cn.contains(k)) return true;
        return false;
    }
    private static boolean hasBg(View v) {
        Drawable d = v.getBackground();
        return d instanceof ColorDrawable && Color.alpha(((ColorDrawable) d).getColor()) > 12;
    }
    private static void clear(View v) { if (!orig.containsKey(v)) orig.put(v, v.getBackground()); v.setBackground(new ColorDrawable(Color.TRANSPARENT)); }

    private static void clearBig(ViewGroup g, View hostV) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View s = g.getChildAt(i);
            if (s == bg || skip(s)) continue;
            if (hasBg(s) && s.getWidth() >= hostV.getWidth() * 0.85 && s.getHeight() >= hostV.getHeight() * 0.4) clear(s);
            if (s instanceof ViewGroup) clearBig((ViewGroup) s, hostV);
        }
    }

    static final class BgView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BgView(android.content.Context c) { super(c); setClickable(false); setFocusable(false); }
        void apply() {
            if (Build.VERSION.SDK_INT >= 31) {
                float blur = Cfg.f("blur", 0) / 100f * 25f;
                try { setRenderEffect(blur > 0.5f ? android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null); } catch (Throwable t) {}
            }
            invalidate();
        }
        @Override protected void onDraw(Canvas g) {
            String m = Cfg.s("bgMode", "none"), v = Cfg.s("bgValue", "");
            int w = getWidth(), h = getHeight();
            if ("image".equals(m)) {
                try {
                    File f = new File(Cfg.dir(null), "bg.jpg");
                    android.graphics.Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        float s = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
                        int dw = (int) (bmp.getWidth() * s), dh = (int) (bmp.getHeight() * s);
                        android.graphics.Rect dst = new android.graphics.Rect((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                        g.drawBitmap(bmp, null, dst, p);
                    }
                } catch (Throwable t) { g.drawColor(Color.BLACK); }
            } else if ("color".equals(m)) { g.drawColor(L.hex(v)); }
            else if ("gradient".equals(m)) {
                String[] parts = v.split(",");
                if (parts.length >= 2) { p.setShader(new LinearGradient(0, 0, w, h, L.hex(parts[0]), L.hex(parts[1]), Shader.TileMode.CLAMP)); g.drawRect(0, 0, w, h, p); p.setShader(null); }
                else g.drawColor(Color.BLACK);
            } else g.drawColor(Color.BLACK);
            float dim = Cfg.f("dim", 30) / 100f * 0.9f;
            g.drawColor(Color.argb((int) (dim * 255), 0, 0, 0));
        }
    }

    public static void set(String mode, String value) { Cfg.s("bgMode", mode); Cfg.s("bgValue", value); }
}

package com.eightubah.mod;

import android.graphics.Color;
import android.view.View;

import java.util.Locale;

/**
 * Language + colours. Android analog of the iOS RYAr / T / RYHex / RYAccent /
 * RY_BG etc. Arabic-first default, same palette.
 */
public final class L {
    private L() {}

    public static boolean ar() {
        String l = Cfg.s("lang", "ar");
        if ("ar".equals(l)) return true;
        if ("en".equals(l)) return false;
        return Locale.getDefault().getLanguage().toLowerCase(Locale.US).startsWith("ar");
    }

    /** T(ar, en) */
    public static String t(String ar, String en) { return ar() ? ar : en; }

    public static int layoutDir() { return ar() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR; }

    public static int hex(String h) {
        if (h == null) return Color.BLACK;
        String s = h.replace("#", "");
        try {
            int v = (int) Long.parseLong(s, 16);
            return 0xFF000000 | (v & 0xFFFFFF);
        } catch (Throwable t) { return Color.BLACK; }
    }

    public static String hexFrom(int c) {
        return String.format("#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c));
    }

    public static String[] accents() {
        return new String[]{"#7C5CFF", "#2DD4BF", "#F43F5E", "#F59E0B", "#3B82F6", "#22C55E"};
    }

    public static int accent() {
        int idx = Cfg.i("accent", 0);
        String[] a = accents();
        if (idx < 0 || idx >= a.length) idx = 0;
        return hex(a[idx]);
    }

    public static final int BG    = 0xFF0E1014;
    public static final int CARD  = 0xFF1A1D25;
    public static final int CARD2 = 0xFF252A35;
    public static final int MUTED = 0xFF8A90A0;
    public static final int WHITE = 0xFFFFFFFF;
}

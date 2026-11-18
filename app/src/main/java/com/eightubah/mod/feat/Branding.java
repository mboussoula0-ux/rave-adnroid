package com.eightubah.mod.feat;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.eightubah.mod.Cfg;

import java.util.regex.Pattern;

/**
 * Branding: replace the word "rave" with a custom name in on-screen text.
 * Port of the iOS RYRepl / RYBrandWalk (text path). Chat/list rows are skipped
 * so messages are never rewritten. (Logo-image swap is omitted on Android.)
 */
public final class Branding {
    private Branding() {}

    private static final Pattern RX = Pattern.compile("\\brave\\b", Pattern.CASE_INSENSITIVE);

    public static String brand() {
        String s = Cfg.s("brandName", "8ubah");
        return (s != null && s.length() > 0) ? s : "8ubah";
    }

    private static boolean inRow(View v) {
        for (View p = v; p != null; ) {
            String cn = p.getClass().getName();
            if (cn.contains("RecyclerView") || cn.contains("ListView")) return false; // the list itself
            Object par = p.getParent();
            if (par instanceof View) {
                String pn = par.getClass().getName();
                if (pn.contains("RecyclerView") || pn.contains("ListView")) return true; // direct row
                p = (View) par;
            } else break;
        }
        return false;
    }

    public static void walk(Activity a) {
        final String brand = brand();
        ViewScan.walk(ViewScan.decor(a), (v, d) -> {
            if (!(v instanceof TextView)) return;
            TextView t = (TextView) v;
            CharSequence cs = t.getText(); if (cs == null) return;
            String s = cs.toString();
            if (s.length() == 0 || s.length() > 40) return;
            if (s.toLowerCase().contains(brand.toLowerCase())) return;
            if (!RX.matcher(s).find()) return;
            if (inRow(t)) return;
            t.setText(RX.matcher(s).replaceAll(java.util.regex.Matcher.quoteReplacement(brand)));
        });
    }
}

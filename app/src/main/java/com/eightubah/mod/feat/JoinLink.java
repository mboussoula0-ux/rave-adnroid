package com.eightubah.mod.feat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.eightubah.mod.L;
import com.eightubah.mod.XEntry;
import com.eightubah.mod.ui.Overlay;

/**
 * Join a room by link. Port of the iOS join-by-link idea: hand the URL to the
 * app the same way a tapped link would. On Android that is an ACTION_VIEW intent
 * targeted at the Rave package, which routes to RaveActivity.handleIncomingIntent
 * / AppLinkManager (meshId).
 */
public final class JoinLink {
    private JoinLink() {}

    public static Uri parse(String s) {
        if (s == null) return null;
        s = s.trim(); if (s.length() == 0) return null;
        // grab the first url-ish token
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9+.\\-]*://\\S+").matcher(s);
        String url = m.find() ? m.group() : null;
        if (url == null) url = s.contains(".") && !s.contains(" ") ? "https://" + s : null;
        if (url == null) return null;
        Uri u = Uri.parse(url);
        if (u.getHost() == null) return null;
        if ("http".equals(u.getScheme())) u = u.buildUpon().scheme("https").build();
        return u;
    }

    public static void join(Activity a, String str) {
        Uri u = parse(str);
        if (u == null) { Overlay.get().hud(L.t("ما لقيت رابط", "No link found")); return; }
        Overlay.get().hud(L.t("جاري الدخول...", "Joining..."));
        boolean ok = fire(a, u, true) || fire(a, u, false);
        if (!ok) Overlay.get().hud(L.t("ما قدرت أفتح الرابط", "Could not open the link"));
    }

    private static boolean fire(Activity a, Uri u, boolean targeted) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            if (targeted) i.setPackage("com.wemesh.android");
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
            return true;
        } catch (Throwable t) { XEntry.log("join fire(%b): %s", targeted, t); return false; }
    }
}

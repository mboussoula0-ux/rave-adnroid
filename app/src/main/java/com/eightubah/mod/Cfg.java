package com.eightubah.mod;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Config / storage. Android analog of the iOS RYLoad / CF / CS (NSUserDefaults)
 * plus RYDir. Lives inside the Rave process, so it uses the host app's own
 * SharedPreferences and filesDir — the equivalent of writing under
 * Documents/8ubah on iOS.
 */
public final class Cfg {
    private Cfg() {}

    private static final String PREFS = "ry.cfg.v1";
    public static Context ctx;
    private static SharedPreferences sp;

    public static synchronized void init(Context c) {
        if (sp != null) return;
        ctx = c;
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences p() {
        if (sp == null && ctx != null) sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp;
    }

    // ---- typed accessors (null-safe before init) ----
    public static String s(String k, String def) { SharedPreferences x = p(); return x == null ? def : x.getString(k, def); }
    public static void s(String k, String v) { SharedPreferences x = p(); if (x == null) return; if (v == null) x.edit().remove(k).apply(); else x.edit().putString(k, v).apply(); }

    public static int i(String k, int def) { SharedPreferences x = p(); return x == null ? def : x.getInt(k, def); }
    public static void i(String k, int v) { SharedPreferences x = p(); if (x != null) x.edit().putInt(k, v).apply(); }

    public static float f(String k, float def) { SharedPreferences x = p(); return x == null ? def : x.getFloat(k, def); }
    public static void f(String k, float v) { SharedPreferences x = p(); if (x != null) x.edit().putFloat(k, v).apply(); }

    public static boolean b(String k, boolean def) { SharedPreferences x = p(); return x == null ? def : x.getBoolean(k, def); }
    public static void b(String k, boolean v) { SharedPreferences x = p(); if (x != null) x.edit().putBoolean(k, v).apply(); }

    public static JSONArray arr(String k) {
        try { String v = s(k, null); return v == null ? new JSONArray() : new JSONArray(v); }
        catch (Throwable t) { return new JSONArray(); }
    }
    public static void arr(String k, JSONArray a) { s(k, a == null ? null : a.toString()); }

    public static JSONObject obj(String k) {
        try { String v = s(k, null); return v == null ? null : new JSONObject(v); }
        catch (Throwable t) { return null; }
    }
    public static void obj(String k, JSONObject o) { s(k, o == null ? null : o.toString()); }

    // ---- files: <filesDir>/8ubah[/sub] ----
    public static File dir(String sub) {
        File b = new File(ctx.getFilesDir(), "8ubah");
        if (sub != null) b = new File(b, sub);
        if (!b.exists()) b.mkdirs();
        return b;
    }

    public static boolean locked() { return !b("unlocked", false); }
}

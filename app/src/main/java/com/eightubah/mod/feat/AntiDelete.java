package com.eightubah.mod.feat;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.XEntry;

import org.json.JSONArray;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Anti-delete for private messages. Rave stores DMs in a Room MessageDao and
 * deletes / unsends them (WallaceClient.deleteMessage, MessageDao.applyPayload,
 * "UNSEND="). iOS turned the SQLite DELETE into a no-op and kept the row; on
 * Android the safe analog is to CAPTURE the message text the moment a
 * delete/unsend passes through the DAO and keep it in our own saved list (shown
 * in the panel), without fighting Room's own state.
 *
 * Class names are tunable from Settings > Advanced (daoClass / wallaceClass),
 * matching the iOS "tunable class" design.
 */
public final class AntiDelete {
    private AntiDelete() {}

    public static volatile boolean on = true;
    public static volatile int blocked = 0; // count of delete/unsend events seen
    private static final List<String> list = new ArrayList<>();
    private static final Set<String> seen = new LinkedHashSet<>();

    private static final String[] METHOD_TOKENS = {"delete", "unsend", "revoke", "retract", "recall", "remove", "applypayload", "markdeleted"};
    private static final String[] TEXT_FIELDS = {"text", "body", "content", "message", "msg", "caption", "translat", "comment"};

    public static void install(ClassLoader cl) {
        on = Cfg.b("antiDelete", true);
        load();
        hookClass(cl, Cfg.s("daoClass", "com.wemesh.android.legacy.dms.db.MessageDao_Impl"));
        hookClass(cl, Cfg.s("wallaceClass", "com.wemesh.android.legacy.dms.WallaceClient"));
    }

    private static void hookClass(ClassLoader cl, String name) {
        try {
            Class<?> c = cl.loadClass(name);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String mn = m.getName().toLowerCase();
                boolean match = false; for (String t : METHOD_TOKENS) if (mn.contains(t)) { match = true; break; }
                if (!match) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try { if (!on) return; blocked++; for (Object a : p.args) capture(a, 0); } catch (Throwable t) { /* */ }
                        }
                    });
                    n++;
                } catch (Throwable t) { /* */ }
            }
            XEntry.log("anti-delete: hooked %d methods on %s", n, name);
        } catch (Throwable t) { XEntry.log("anti-delete: class %s not found (%s)", name, t); }
    }

    /** pull message text out of an argument object (fields + getters). */
    private static void capture(Object o, int depth) {
        if (o == null || depth > 2) return;
        if (o instanceof CharSequence) { add(o.toString()); return; }
        if (o instanceof String[]) { for (String s : (String[]) o) add(s); return; }
        Class<?> c = o.getClass();
        String cn = c.getName();
        if (cn.startsWith("java.") || cn.startsWith("kotlin.") || cn.startsWith("android.")) return;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                String fn = f.getName().toLowerCase();
                boolean want = false; for (String t : TEXT_FIELDS) if (fn.contains(t)) { want = true; break; }
                if (!want) continue;
                if (fn.contains("id") || fn.contains("type") || fn.contains("status")) continue;
                try { f.setAccessible(true); Object v = f.get(o); if (v instanceof CharSequence) add(v.toString()); } catch (Throwable t) { /* */ }
            }
        }
        for (String g : new String[]{"getText", "getBody", "getMessage", "getContent", "getCaption"}) {
            try { Method m = c.getMethod(g); Object v = m.invoke(o); if (v instanceof CharSequence) add(v.toString()); } catch (Throwable t) { /* */ }
        }
    }

    private static synchronized void add(String raw) {
        if (raw == null) return; String t = raw.trim();
        if (t.length() < 1 || t.length() > 2000) return;
        if (looksId(t)) return;
        if (seen.contains(t)) return;
        if (list.size() >= 3000) { String first = list.remove(0); seen.remove(first); }
        list.add(t); seen.add(t);
        save();
    }
    private static boolean looksId(String t) {
        int n = t.length();
        if (n == 36 && t.charAt(8) == '-' && t.charAt(13) == '-' && t.charAt(18) == '-' && t.charAt(23) == '-') return true;
        boolean hex = true, dig = true;
        for (int i = 0; i < n; i++) { char ch = t.charAt(i); if (Character.digit(ch, 16) < 0) hex = false; if (ch < '0' || ch > '9') dig = false; }
        return (hex && n >= 20) || (dig && n >= 6);
    }

    public static synchronized List<String> all() { return new ArrayList<>(list); }
    public static synchronized int count() { return list.size(); }
    public static synchronized void clear() { list.clear(); seen.clear(); save(); }

    private static File file() { return new File(Cfg.dir("data"), "deleted.json"); }
    private static synchronized void save() {
        try { JSONArray a = new JSONArray(); for (String s : list) a.put(s); java.io.FileWriter w = new java.io.FileWriter(file()); w.write(a.toString()); w.close(); } catch (Throwable t) { /* */ }
    }
    private static synchronized void load() {
        try {
            File f = file(); if (!f.exists()) return;
            byte[] b = new byte[(int) f.length()]; java.io.FileInputStream is = new java.io.FileInputStream(f); is.read(b); is.close();
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < a.length(); i++) { String s = a.optString(i, null); if (s != null && !seen.contains(s)) { list.add(s); seen.add(s); } }
        } catch (Throwable t) { /* */ }
    }

    public static void setOn(boolean v) { on = v; Cfg.setB("antiDelete", v); }
}

package com.eightubah.mod.feat;

import android.app.Dialog;
import android.content.Context;

import com.eightubah.mod.XEntry;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Gate: defeat Rave's integrity gate that fires after LSPatch repackaging.
 *
 * The real trigger is {@code com.wemesh.android.legacy.core.IntegrityCheck}:
 * {@code checkIfTampered} sees the resigned/repackaged APK and throws, and
 * {@code performChecks} then shows "Your app is out of date. Update it in the
 * Google Play Store." (resource {@code gatekeeper_app_outdated}) and quits.
 *
 * We don't modify Rave — from inside the process we make performChecks always
 * report success and turn the individual checks into no-ops, so the app behaves
 * as if signed and installed from Play. A text-based dialog suppressor stays as a
 * belt-and-suspenders fallback for any other surface that shows the same message.
 */
public final class Gate {
    private Gate() {}

    private static final String INTEGRITY = "com.wemesh.android.legacy.core.IntegrityCheck";

    // ---- forces performChecks()Z to return true without running the original ----
    private static final XC_MethodHook PASS = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(Boolean.TRUE); }
    };
    // ---- turns a check into a no-op (void methods: never throw, never quit) ----
    private static final XC_MethodHook NOOP = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
    };

    public static void install(ClassLoader cl, Context ctx) {
        installIntegrity(cl);
        installDialogFallback(cl, ctx);
    }

    // --------------------------------------------------------------- source fix
    private static void installIntegrity(ClassLoader cl) {
        Class<?> ic = XposedHelpers.findClassIfExists(INTEGRITY, cl);
        if (ic == null) { XEntry.log("gate: IntegrityCheck not found"); return; }
        hookAll(ic, "performChecks", PASS);          // (...)Z -> true
        hookAll(ic, "checkIfTampered", NOOP);        // don't throw on resign/repackage
        hookAll(ic, "checkIfEmulator", NOOP);
        hookAll(ic, "checkIfWorkProfile", NOOP);
        hookAll(ic, "enforceIdentity", NOOP);
        hookAll(ic, "quitWithFatalMessage", NOOP);   // never show the fatal "outdated" screen
        XEntry.log("gate: IntegrityCheck neutralized");
    }

    private static void hookAll(Class<?> c, String method, XC_MethodHook h) {
        try { XposedBridge.hookAllMethods(c, method, h); }
        catch (Throwable t) { XEntry.log("gate: hook %s failed: %s", method, t); }
    }

    // ------------------------------------------------------- dialog fallback ----
    private static final Set<Object> marked =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static String target;
    private static int gkId;

    private static void installDialogFallback(ClassLoader cl, Context ctx) {
        try {
            gkId = ctx.getResources().getIdentifier(
                    "gatekeeper_app_outdated", "string", ctx.getPackageName());
            if (gkId != 0) target = ctx.getResources().getString(gkId);
        } catch (Throwable t) { /* */ }
        hookBuilder(cl, "android.app.AlertDialog$Builder");
        hookBuilder(cl, "androidx.appcompat.app.AlertDialog$Builder");
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (marked.remove(p.thisObject)) p.setResult(null);
                }
            });
        } catch (Throwable t) { /* */ }
    }

    private static boolean isOutdated(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        if (s.length() == 0) return false;
        if (target != null && s.equalsIgnoreCase(target.trim())) return true;
        String l = s.toLowerCase();
        return l.contains("out of date") && l.contains("play store");
    }

    private static void hookBuilder(ClassLoader cl, String clazz) {
        Class<?> b = XposedHelpers.findClassIfExists(clazz, cl);
        if (b == null) return;
        try {
            XposedHelpers.findAndHookMethod(b, "setMessage", CharSequence.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (isOutdated((CharSequence) p.args[0])) marked.add(p.thisObject);
                }
            });
        } catch (Throwable t) { /* */ }
        try {
            XposedHelpers.findAndHookMethod(b, "setMessage", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (gkId != 0 && (Integer) p.args[0] == gkId) marked.add(p.thisObject);
                }
            });
        } catch (Throwable t) { /* */ }
        try {
            XposedHelpers.findAndHookMethod(b, "create", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (marked.remove(p.thisObject) && p.getResult() != null) marked.add(p.getResult());
                }
            });
        } catch (Throwable t) { /* */ }
    }
}

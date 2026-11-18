package com.eightubah.mod.feat;

import android.app.Dialog;
import android.content.Context;

import com.eightubah.mod.XEntry;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Gate: defeat Rave's "Your app is out of date. Update it in the Google Play
 * Store." gate that fires after LSPatch repackaging.
 *
 * The string {@code gatekeeper_app_outdated} is shown from two places:
 *   1) {@code com.wemesh.android.legacy.core.IntegrityCheck.performChecks} — local
 *      tamper/signature/installer check. Neutralized here.
 *   2) {@code com.wemesh.android.rearch.fragments.LoginFragment.showLoginFailedDialog}
 *      — shown when the gatekeeper login is rejected server-side. Suppressed here.
 *
 * Every hook logs under tag "8ubah" so a logcat shows exactly which path fired.
 */
public final class Gate {
    private Gate() {}

    private static final String INTEGRITY = "com.wemesh.android.legacy.core.IntegrityCheck";
    private static final String LOGIN_FRAG = "com.wemesh.android.rearch.fragments.LoginFragment";

    public static void install(ClassLoader cl, Context ctx) {
        installIntegrity(cl);
        installLogin(cl);
        installDialogFallback(cl, ctx);
        XEntry.log("gate installed");
    }

    // ---- performChecks()Z -> true, without running the original ----
    private static final XC_MethodHook PASS = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            p.setResult(Boolean.TRUE);
            XEntry.log("gate: %s.%s -> forced true",
                    p.method.getDeclaringClass().getSimpleName(), p.method.getName());
        }
    };
    // ---- void check -> no-op (never throw, never quit) ----
    private static final XC_MethodHook NOOP = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            p.setResult(null);
            XEntry.log("gate: %s.%s -> skipped",
                    p.method.getDeclaringClass().getSimpleName(), p.method.getName());
        }
    };
    // ---- suppress a UI method and log where it was called from ----
    private static final XC_MethodHook SUPPRESS = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            p.setResult(null);
            XEntry.log("gate: suppressed %s args=%s", p.method.getName(),
                    p.args == null ? "[]" : Arrays.toString(p.args));
            XposedBridge.log(new Throwable("[8ubah] gate trace: who called " + p.method.getName()));
        }
    };

    private static void installIntegrity(ClassLoader cl) {
        Class<?> ic = XposedHelpers.findClassIfExists(INTEGRITY, cl);
        if (ic == null) { XEntry.log("gate: IntegrityCheck not found"); return; }
        hookAll(ic, "performChecks", PASS);
        hookAll(ic, "checkIfTampered", NOOP);
        hookAll(ic, "checkIfEmulator", NOOP);
        hookAll(ic, "checkIfWorkProfile", NOOP);
        hookAll(ic, "enforceIdentity", NOOP);
        hookAll(ic, "quitWithFatalMessage", NOOP);
        XEntry.log("gate: IntegrityCheck neutralized");
    }

    private static void installLogin(ClassLoader cl) {
        Class<?> lf = XposedHelpers.findClassIfExists(LOGIN_FRAG, cl);
        if (lf == null) { XEntry.log("gate: LoginFragment not found"); return; }
        hookAll(lf, "showLoginFailedDialog", SUPPRESS);
        XEntry.log("gate: LoginFragment.showLoginFailedDialog suppressed");
    }

    private static void hookAll(Class<?> c, String method, XC_MethodHook h) {
        try {
            Set<?> s = XposedBridge.hookAllMethods(c, method, h);
            if (s == null || s.isEmpty()) XEntry.log("gate: no method named %s on %s", method, c.getSimpleName());
        } catch (Throwable t) { XEntry.log("gate: hook %s failed: %s", method, t); }
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
                    if (marked.remove(p.thisObject)) {
                        p.setResult(null);
                        XEntry.log("gate: suppressed outdated AlertDialog");
                    }
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

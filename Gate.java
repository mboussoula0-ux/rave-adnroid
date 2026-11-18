package com.eightubah.mod.feat;

import android.content.Context;

import com.eightubah.mod.XEntry;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Gate: remove Rave's "Your app is out of date. Update it in the Google Play
 * Store." gate that fires after LSPatch repackaging.
 *
 * The dialog is a custom {@code com.wemesh.android.rearch.dialogs.FrostedRaveDialog}
 * (not an AlertDialog), built with {@code setMessage(gatekeeper_app_outdated)} then
 * {@code createAndShow()}. We mark any builder given that message and no-op its
 * createAndShow, so the dialog never appears — whichever code path builds it.
 * We also neutralize the local {@code IntegrityCheck} for good measure.
 *
 * Installed immediately on load (not via Application.attach, which LSPatch may run
 * before the module is in place), so the hooks are always live in time.
 */
public final class Gate {
    private Gate() {}

    private static final String FROSTED = "com.wemesh.android.rearch.dialogs.FrostedRaveDialog$Builder";
    private static final String INTEGRITY = "com.wemesh.android.legacy.core.IntegrityCheck";
    private static final String LOGIN_FRAG = "com.wemesh.android.rearch.fragments.LoginFragment";

    private static final Set<Object> marked =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    private static int gkId = -1;   // resolved lazily

    public static void install(ClassLoader cl) {
        hookFrostedDialog(cl);
        hookIntegrity(cl);
        hookLogin(cl);
        XEntry.log("gate installed");
    }

    private static int gatekeeperId() {
        if (gkId == -1) {
            gkId = 0;
            try {
                // android.app.ActivityThread.currentApplication() — the app Context,
                // available anywhere without the Xposed AndroidAppHelper dependency.
                Object app = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    Context c = (Context) app;
                    gkId = c.getResources().getIdentifier(
                            "gatekeeper_app_outdated", "string", c.getPackageName());
                }
                if (gkId == 0) gkId = -1;   // not ready yet; try again next call
            } catch (Throwable t) { gkId = -1; }
        }
        return gkId;
    }

    private static boolean isOutdatedText(Object o) {
        if (!(o instanceof CharSequence)) return false;
        String l = o.toString().trim().toLowerCase();
        return l.contains("out of date") && l.contains("play store");
    }

    // --------------------------------------------- the real dialog mechanism ----
    private static void hookFrostedDialog(ClassLoader cl) {
        Class<?> b = XposedHelpers.findClassIfExists(FROSTED, cl);
        if (b == null) { XEntry.log("gate: FrostedRaveDialog$Builder not found"); return; }

        XC_MethodHook mark = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object a = p.args.length > 0 ? p.args[0] : null;
                boolean hit = isOutdatedText(a)
                        || (a instanceof Integer && (Integer) a != 0 && (Integer) a == gatekeeperId());
                if (hit) { marked.add(p.thisObject); XEntry.log("gate: marked outdated dialog builder"); }
            }
        };
        try { XposedHelpers.findAndHookMethod(b, "setMessage", int.class, mark); } catch (Throwable t) { /* */ }
        try { XposedHelpers.findAndHookMethod(b, "setMessage", CharSequence.class, mark); } catch (Throwable t) { /* */ }

        try {
            XposedHelpers.findAndHookMethod(b, "createAndShow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (marked.remove(p.thisObject)) {
                        p.setResult(null);           // never create/show the outdated dialog
                        XEntry.log("gate: suppressed outdated FrostedRaveDialog");
                    }
                }
            });
        } catch (Throwable t) { XEntry.log("gate: createAndShow hook failed: %s", t); }
    }

    // --------------------------------------------- local integrity check --------
    private static final XC_MethodHook PASS = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(Boolean.TRUE); }
    };
    private static final XC_MethodHook NOOP = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
    };

    private static void hookIntegrity(ClassLoader cl) {
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

    private static void hookLogin(ClassLoader cl) {
        Class<?> lf = XposedHelpers.findClassIfExists(LOGIN_FRAG, cl);
        if (lf == null) return;
        hookAll(lf, "showLoginFailedDialog", NOOP);
    }

    private static void hookAll(Class<?> c, String method, XC_MethodHook h) {
        try { XposedBridge.hookAllMethods(c, method, h); }
        catch (Throwable t) { XEntry.log("gate: hook %s failed: %s", method, t); }
    }
}

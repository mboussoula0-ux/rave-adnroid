package com.eightubah.mod.feat;

import android.app.Dialog;
import android.content.Context;

import com.eightubah.mod.XEntry;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Gate: suppress Rave's "Your app is out of date. Update it in the Google Play
 * Store." dialog (resource {@code gatekeeper_app_outdated}).
 *
 * Why: LSPatch repackaging changes the APK signature / install source, so Rave's
 * gatekeeper flags the (actually current) build as outdated and pops a dialog on
 * top of the otherwise-working app. We don't touch Rave's binary — we just stop
 * that one dialog from showing.
 *
 * How: when an AlertDialog is built with that message, mark the dialog instance;
 * then no-op {@link Dialog#show()} for marked dialogs. Matching is by text, so it
 * works in every locale, and is specific enough not to swallow other dialogs.
 */
public final class Gate {
    private Gate() {}

    // Dialog instances whose message is the "outdated" text. Weak so we never pin them.
    private static final Set<Object> marked =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    private static String target;   // localized "gatekeeper_app_outdated"
    private static int gkId;        // its resource id, for setMessage(int) calls

    public static void install(ClassLoader cl, Context ctx) {
        resolveTarget(ctx);
        hookBuilder(cl, "android.app.AlertDialog$Builder");
        hookBuilder(cl, "androidx.appcompat.app.AlertDialog$Builder");
        hookDialogShow(cl);
        XEntry.log("gate installed (target=%s)", target != null);
    }

    private static void resolveTarget(Context ctx) {
        try {
            gkId = ctx.getResources().getIdentifier(
                    "gatekeeper_app_outdated", "string", ctx.getPackageName());
            if (gkId != 0) target = ctx.getResources().getString(gkId);
        } catch (Throwable t) { XEntry.log("gate resolve: %s", t); }
    }

    /** The outdated text — matched exactly, with a language-agnostic fallback. */
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

        // setMessage(CharSequence): mark the builder when the text matches.
        try {
            XposedHelpers.findAndHookMethod(b, "setMessage", CharSequence.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (isOutdated((CharSequence) p.args[0])) marked.add(p.thisObject);
                }
            });
        } catch (Throwable t) { /* overload may not exist */ }

        // setMessage(int resId): compare against the gatekeeper string id.
        try {
            XposedHelpers.findAndHookMethod(b, "setMessage", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (gkId != 0 && (Integer) p.args[0] == gkId) marked.add(p.thisObject);
                }
            });
        } catch (Throwable t) { /* */ }

        // create(): carry the mark from the builder to the dialog it returns.
        try {
            XposedHelpers.findAndHookMethod(b, "create", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (marked.remove(p.thisObject) && p.getResult() != null) marked.add(p.getResult());
                }
            });
        } catch (Throwable t) { /* */ }
    }

    /** android.app.Dialog.show() — the single choke point both AlertDialog kinds pass through. */
    private static void hookDialogShow(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (marked.remove(p.thisObject)) {
                        p.setResult(null);   // swallow this one dialog, never display it
                        XEntry.log("gate: suppressed outdated dialog");
                    }
                }
            });
        } catch (Throwable t) { XEntry.log("gate show hook: %s", t); }
    }
}

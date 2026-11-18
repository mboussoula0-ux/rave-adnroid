package com.eightubah.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.eightubah.mod.audio.AudioHooks;
import com.eightubah.mod.feat.AntiDelete;
import com.eightubah.mod.ui.Overlay;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point. Android analog of the iOS __attribute__((constructor)) RYInit.
 * Loaded by LSPosed / LSPatch into the Rave (com.wemesh.android) process.
 *
 * RULE (from the iOS file, kept): NO explanatory text in the UI. Controls get
 * only short names + short live status lines. Explanations go to the owner, not
 * the app.
 */
public class XEntry implements IXposedHookLoadPackage {

    public static final String TAG = "8ubah";
    public static ClassLoader appCl;

    public static void log(String fmt, Object... a) {
        try { XposedBridge.log("[" + TAG + "] " + (a.length == 0 ? fmt : String.format(fmt, a))); }
        catch (Throwable t) { /* ignore */ }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"com.wemesh.android".equals(lp.packageName)) return;
        appCl = lp.classLoader;
        log("loaded into %s", lp.packageName);

        // Install the audio chain hooks as early as possible (framework classes,
        // version-independent). Mic capture flows through WebRtcAudioRecord ->
        // AudioRecord.read; playout through WebRtcAudioTrack -> AudioTrack.write.
        safe(new Runnable() { public void run() { AudioHooks.install(); } }, "audio hooks");

        // Anti-delete for private messages (Room MessageDao / WallaceClient).
        safe(new Runnable() { public void run() { AntiDelete.install(lp.classLoader); } }, "anti-delete");

        hookApplication(lp.classLoader);
        hookActivities(lp.classLoader);
    }

    private void hookApplication(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Context ctx = (Context) p.args[0];
                        Cfg.init(ctx.getApplicationContext());
                        Keep.onAppReady(ctx.getApplicationContext());
                    } catch (Throwable t) { log("app attach: %s", t); }
                }
            });
        } catch (Throwable t) {
            log("Application.attach hook failed: %s", t);
        }
    }

    private void hookActivities(ClassLoader cl) {
        XC_MethodHook attach = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Activity a = (Activity) p.thisObject;
                    if (Cfg.ctx == null) Cfg.init(a.getApplicationContext());
                    Overlay.get().onActivityResumed(a);
                } catch (Throwable t) { log("onResume attach: %s", t); }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", attach);
        } catch (Throwable t) { log("Activity.onResume hook failed: %s", t); }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { Overlay.get().onActivityPaused((Activity) p.thisObject); } catch (Throwable t) { /* */ }
                }
            });
        } catch (Throwable t) { /* */ }
    }

    private void safe(final Runnable r, final String what) {
        try { r.run(); } catch (Throwable t) { log("%s install failed: %s", what, t); }
    }

    /** main-thread helper used across the module. */
    public static void main(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
    public static void mainDelayed(Runnable r, long ms) {
        new Handler(Looper.getMainLooper()).postDelayed(r, ms);
    }
}

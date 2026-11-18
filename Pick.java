package com.eightubah.mod.feat;

import android.app.Activity;
import android.content.Intent;

import com.eightubah.mod.XEntry;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * File/gallery picker helper. The panel is a Dialog, not an Activity, so results
 * come back through the host Activity.onActivityResult, which we hook once and
 * route by request code. Used for background images and Stereo files.
 */
public final class Pick {
    private Pick() {}

    public static final int REQ_BG = 0x8B01;
    public static final int REQ_STEREO = 0x8B02;

    public interface Cb { void run(int resultCode, Intent data); }
    private static final Map<Integer, Cb> pending = new HashMap<>();
    private static boolean installed;

    public static void pick(Activity a, String mime, int req, Cb cb) {
        install();
        pending.put(req, cb);
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType(mime); i.addCategory(Intent.CATEGORY_OPENABLE);
            a.startActivityForResult(Intent.createChooser(i, null), req);
        } catch (Throwable t) { XEntry.log("pick: %s", t); pending.remove(req); }
    }

    private static void install() {
        if (installed) return; installed = true;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult", int.class, int.class, Intent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int req = (Integer) p.args[0];
                    Cb cb = pending.remove(req);
                    if (cb != null) { try { cb.run((Integer) p.args[1], (Intent) p.args[2]); } catch (Throwable t) { XEntry.log("pick cb: %s", t); } }
                }
            });
        } catch (Throwable t) { XEntry.log("pick install: %s", t); }
    }
}

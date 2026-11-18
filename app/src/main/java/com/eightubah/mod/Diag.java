package com.eightubah.mod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;

/**
 * Diagnostics: append audio runtime facts to a file the owner can pull off the
 * device, since MIUI hides app logs from logcat. Writes to the app's external
 * files dir: /sdcard/Android/data/com.wemesh.android/files/8ubah_diag.txt
 */
public final class Diag {
    private Diag() {}

    private static volatile File file;
    private static int lines;

    // Resolve lazily and KEEP RETRYING until a context is ready — never cache a
    // null result, or an early call (before the Application exists) disables us.
    private static synchronized File file() {
        if (file != null) return file;
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                File d = ((Context) app).getExternalFilesDir(null);
                if (d != null) { d.mkdirs(); file = new File(d, "8ubah_diag.txt"); }
            }
        } catch (Throwable t) { /* try again on the next call */ }
        return file;
    }

    /** Wipe the file at startup so each session's log is fresh. */
    public static void reset() {
        try { File f = file(); if (f != null && f.exists()) f.delete(); lines = 0; } catch (Throwable t) {}
    }

    /** Show the numbers on screen too, so they can be screenshotted without adb. */
    public static void toast(final String msg) {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (!(app instanceof Context)) return;
            final Context c = (Context) app;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try { Toast.makeText(c, "8ubah ▸ " + msg, Toast.LENGTH_LONG).show(); } catch (Throwable t) {}
                }
            });
        } catch (Throwable t) { /* */ }
    }

    public static synchronized void log(String fmt, Object... a) {
        try {
            File f = file(); if (f == null || lines > 5000) return;
            String line = a.length == 0 ? fmt : String.format(fmt, a);
            FileWriter w = new FileWriter(f, true);
            w.write((System.currentTimeMillis() % 100000) + "  " + line + "\n");
            w.close(); lines++;
        } catch (Throwable t) { /* */ }
    }
}

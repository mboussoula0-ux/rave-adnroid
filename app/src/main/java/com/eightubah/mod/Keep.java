package com.eightubah.mod;

import android.content.Context;
import android.os.PowerManager;

/**
 * Anti-AFK / keep-alive. iOS used a background-location trick to keep the
 * process alive; on Android the honest, permission-light equivalent is a
 * partial wake lock (CPU stays awake) layered on top of Rave's own foreground
 * microphone service, which already keeps the process alive during voice.
 * Always on, like the iOS build (no switch, not stored).
 */
public final class Keep {
    private Keep() {}

    private static PowerManager.WakeLock wl;
    public static boolean on = true;

    public static void onAppReady(Context ctx) {
        try {
            if (wl != null) return;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "8ubah:keep");
            wl.setReferenceCounted(false);
            if (on) acquire();
        } catch (Throwable t) { XEntry.log("keep init: %s", t); }
    }

    public static void acquire() {
        try { if (wl != null && !wl.isHeld()) wl.acquire(); } catch (Throwable t) { /* */ }
    }
    public static void release() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { /* */ }
    }

    public static String status() {
        if (!on) return L.t("متوقف", "Off");
        return (wl != null && wl.isHeld()) ? L.t("شغّال", "Running") : L.t("جاهز", "Ready");
    }
}

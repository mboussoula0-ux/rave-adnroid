package com.eightubah.mod.feat;

import android.app.Activity;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.XEntry;
import com.eightubah.mod.ui.Overlay;

import org.json.JSONArray;

/**
 * Quick messages: send saved messages once, or loop them until switched off.
 * Port of the iOS RYMsgLoop* + sendMessage. Uses the view-tree chat input.
 */
public final class QuickMessages {
    private QuickMessages() {}

    public static volatile boolean loop = false;
    public static volatile int sent = 0;
    private static int idx = 0, fails = 0, gen = 0;

    public static void send(Activity a, String msg, ViewScan.SendCb cb) { ViewScan.sendMessage(a, msg, cb); }

    public static void setLoop(boolean on) {
        loop = on; gen++; fails = 0; sent = 0;
        if (on) { idx = 0; step(gen); }
    }

    private static void step(final int myGen) {
        if (!loop || myGen != gen) return;
        final Activity a = Overlay.get().activity();
        JSONArray msgs = Cfg.arr("messages");
        if (a == null || msgs.length() == 0) { loop = false; return; }
        if (idx >= msgs.length()) idx = 0;
        final String m = msgs.optString(idx++, "");
        final double delay = Math.max(0.4, Cfg.f("msgDelay", 4.0f));
        send(a, m, code -> {
            if (!loop || myGen != gen) return;
            if (code == 0) { sent++; fails = 0; }
            else if (++fails >= 3) { loop = false; return; }
            XEntry.mainDelayed(() -> step(myGen), (long) (delay * 1000));
        });
    }
}

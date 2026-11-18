package com.eightubah.mod.feat;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.eightubah.mod.L;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Invite everyone in the list. Best-effort port of the iOS invite flow: tick the
 * invite control on every visible row, 0.4s/6 apart, scroll for more, then press
 * a "Send N" button if the app shows one. A 4-minute cooldown, like iOS.
 */
public final class Invites {
    private Invites() {}

    public static volatile boolean running, readyToSend;
    public static volatile int sent;
    public static volatile String status = "";
    private static long lastRun;
    private static final Set<String> done = new HashSet<>();
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Activity act;
    private static long endT;

    private static final String[] GOOD = {"invite", "دعو", "دعوه", "add to room", "person.badge.plus", "add_", "addfriend", "plus"};
    private static final String[] BAD = {"invited", "تمت", "تم ", "sent", "pending", "remove", "block", "report", "unfriend", "chat", "message", "cancel", "delete", "decline", "deny"};

    public static long cooldownLeft() { if (lastRun == 0) return 0; long l = 240 - (System.currentTimeMillis() / 1000 - lastRun); return l > 0 ? l : 0; }

    public static void start(Activity a) {
        if (running || cooldownLeft() > 0) return;
        act = a; done.clear(); sent = 0; running = true; readyToSend = false; endT = 0;
        status = L.t("أبحث عن القائمة…", "Looking for the list…");
        H.removeCallbacks(STEP); H.post(STEP);
    }
    public static void stop() {
        H.removeCallbacks(STEP);
        if (running) { running = false; if (sent > 0) lastRun = System.currentTimeMillis() / 1000; status = L.t("وقف. حُدّد ", "Stopped. ") + sent; }
    }

    private static final Runnable STEP = new Runnable() {
        public void run() {
            try { if (step()) H.postDelayed(this, 120); } catch (Throwable t) { running = false; }
        }
    };

    // returns true to keep running
    private static boolean step() {
        if (!running || act == null) return false;
        ViewGroup list = ViewScan.biggestList(ViewScan.decor(act));
        if (list == null) { status = L.t("ما لقيت قائمة", "No list"); return true; }
        List<View> rows = rows(list);
        for (View row : rows) {
            String key = rowKey(row);
            if (done.contains(key)) continue;
            View ctl = inviteCtl(row);
            if (ctl == null) { done.add(key); continue; }
            ctl.performClick();
            done.add(key); sent++; status = L.t("حُدّد ", "Selected ") + sent;
            H.postDelayed(STEP, (long) (400.0 / 6.0)); return false; // paced, re-entry via postDelayed
        }
        // nothing left visible: scroll or finish
        if (list.canScrollVertically(1)) { list.scrollBy(0, (int) (list.getHeight() * 0.8)); endT = 0; return true; }
        if (endT == 0) endT = System.currentTimeMillis();
        if (System.currentTimeMillis() - endT > 1200) { finish(); return false; }
        return true;
    }

    private static void finish() {
        running = false; if (sent > 0) lastRun = System.currentTimeMillis() / 1000;
        View send = sendButton();
        if (send != null) { send.performClick(); status = L.t("خلصت وانرسلت الدعوات: ", "Done, invites sent: ") + sent; }
        else status = L.t("خلصت القائمة: ", "Reached the end: ") + sent;
    }

    private static List<View> rows(ViewGroup list) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) out.add(list.getChildAt(i));
        return out;
    }
    private static String rowKey(View row) {
        final StringBuilder sb = new StringBuilder();
        ViewScan.walk(row, (v, d) -> { if (v instanceof TextView && ((TextView) v).getText() != null) sb.append(((TextView) v).getText()).append('|'); });
        return sb.length() > 0 ? sb.toString() : String.valueOf(System.identityHashCode(row));
    }
    private static String key(View v) {
        StringBuilder k = new StringBuilder();
        if (v.getContentDescription() != null) k.append(v.getContentDescription()).append('|');
        try { if (v.getId() != View.NO_ID) k.append(v.getResources().getResourceEntryName(v.getId())).append('|'); } catch (Throwable t) {}
        if (v instanceof TextView) k.append(((TextView) v).getText()).append('|');
        return k.toString().toLowerCase();
    }
    private static View inviteCtl(View row) {
        final List<View> hits = new ArrayList<>();
        ViewScan.walk(row, (v, d) -> {
            if (!v.isClickable() || !ViewScan.isVisible(v)) return;
            String k = key(v); boolean bad = false; for (String w : BAD) if (k.contains(w)) { bad = true; break; }
            if (bad) return; for (String w : GOOD) if (k.contains(w)) { hits.add(v); break; }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }
    private static View sendButton() {
        final View[] found = {null};
        ViewScan.walk(ViewScan.decor(act), (v, d) -> {
            if (found[0] != null || !v.isClickable() || !ViewScan.isVisible(v)) return;
            String k = key(v);
            boolean hit = k.contains("send") || k.contains("إرسال") || k.contains("ارسال") || k.contains("invite") || k.contains("دعو");
            if (hit) found[0] = v;
        });
        return found[0];
    }
}

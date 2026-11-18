package com.eightubah.mod.feat;

import android.app.Activity;
import android.graphics.Rect;
import android.text.Editable;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.XEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * View-tree helpers. Android analog of the iOS view-walking used by quick
 * messages, invites, backgrounds and branding: find the chat input, the send
 * button, the biggest list, etc.
 */
public final class ViewScan {
    private ViewScan() {}

    public interface Visitor { void v(View view, int depth); }
    public static void walk(View v, Visitor vis) { walk(v, 0, vis); }
    private static void walk(View v, int d, Visitor vis) {
        if (v == null || d > 40) return;
        vis.v(v, d);
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), d + 1, vis); }
    }

    public static boolean isVisible(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f || !v.isShown()) return false;
        Rect r = new Rect(); return v.getGlobalVisibleRect(r) && r.width() > 10 && r.height() > 10;
    }

    public static View decor(Activity a) { return a.getWindow().getDecorView(); }

    public static boolean inRoom(Activity a) {
        String tok = Cfg.s("roomClass", "RaveActivity");
        if (a.getClass().getName().contains(tok)) return true;
        return bottomInput(decor(a)) != null;
    }

    public static List<EditText> editables(View root) {
        final List<EditText> out = new ArrayList<>();
        walk(root, (v, d) -> {
            if (v instanceof EditText) { EditText e = (EditText) v; if (isVisible(e) && e.isEnabled() && e.isFocusableInTouchMode()) out.add(e); }
        });
        return out;
    }

    /** lowest visible editable text field (the chat bar sits at the bottom). */
    public static EditText bottomInput(View root) {
        EditText best = null; int by = Integer.MIN_VALUE; int[] loc = new int[2];
        for (EditText e : editables(root)) { e.getLocationOnScreen(loc); int y = loc[1] + e.getHeight(); if (y > by) { by = y; best = e; } }
        return best;
    }

    private static String key(View v) {
        StringBuilder k = new StringBuilder();
        if (v.getContentDescription() != null) k.append(v.getContentDescription()).append('|');
        try { if (v.getId() != View.NO_ID) k.append(v.getResources().getResourceEntryName(v.getId())).append('|'); } catch (Throwable t) {}
        if (v instanceof TextView) k.append(((TextView) v).getText()).append('|');
        return k.toString().toLowerCase();
    }

    private static final String[] GOOD_SEND = {"send", "إرسال", "ارسال", "submit"};
    private static final String[] BAD_SEND = {"sticker", "emoji", "gif", "mic", "voice", "camera", "attach", "photo", "gift", "record"};

    public static View sendButtonNear(EditText input) {
        View p = input;
        for (int i = 0; i < 6 && p.getParent() instanceof View; i++) {
            p = (View) p.getParent();
            final List<View> clickables = new ArrayList<>();
            walk(p, (v, d) -> { if (v != input && v.isClickable() && isVisible(v)) clickables.add(v); });
            for (View b : clickables) {
                String k = key(b); boolean bad = false;
                for (String w : BAD_SEND) if (k.contains(w)) { bad = true; break; }
                if (bad) continue;
                for (String w : GOOD_SEND) if (k.contains(w)) return b;
            }
            if (p.getHeight() > 1200) break;
        }
        return null;
    }

    public interface SendCb { void run(int code); } // 0 ok, 1 no input, 2 not sent

    public static void sendMessage(Activity a, final String msg, final SendCb cb) {
        final EditText in = bottomInput(decor(a));
        if (in == null) { cb.run(1); return; }
        final String prev = in.getText() == null ? "" : in.getText().toString();
        in.setText(msg);
        View btn = sendButtonNear(in);
        if (btn != null) btn.performClick(); else pressSend(in);
        XEntry.mainDelayed(() -> {
            if (!msg.equals(txt(in))) { cb.run(0); return; }
            pressSend(in);
            XEntry.mainDelayed(() -> {
                if (!msg.equals(txt(in))) cb.run(0);
                else { in.setText(prev); cb.run(2); }
            }, 500);
        }, 500);
    }
    private static String txt(EditText e) { Editable ed = e.getText(); return ed == null ? "" : ed.toString(); }
    private static void pressSend(EditText e) {
        try { e.onEditorAction(EditorInfo.IME_ACTION_SEND); } catch (Throwable t) {}
        try { e.onEditorAction(EditorInfo.IME_ACTION_DONE); } catch (Throwable t) {}
    }

    // biggest visible scrollable list (RecyclerView / ListView) for invites
    public static ViewGroup biggestList(View root) {
        final ViewGroup[] best = {null}; final int[] area = {0};
        walk(root, (v, d) -> {
            if (!(v instanceof ViewGroup)) return;
            String cn = v.getClass().getName();
            if (!(cn.contains("RecyclerView") || cn.contains("ListView"))) return;
            if (!isVisible(v)) return;
            Rect r = new Rect(); v.getGlobalVisibleRect(r); int ar = r.width() * r.height();
            if (ar > area[0]) { area[0] = ar; best[0] = (ViewGroup) v; }
        });
        return best[0];
    }
}

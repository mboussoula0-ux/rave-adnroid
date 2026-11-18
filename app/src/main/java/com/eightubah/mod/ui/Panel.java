package com.eightubah.mod.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.L;
import com.eightubah.mod.Lic;
import com.eightubah.mod.XEntry;
import com.eightubah.mod.audio.Dsp;
import com.eightubah.mod.audio.Iso;
import com.eightubah.mod.audio.MicSettings;
import com.eightubah.mod.audio.Monitor;
import com.eightubah.mod.audio.Recorder;
import com.eightubah.mod.audio.SonarImport;
import com.eightubah.mod.audio.Stereo;
import com.eightubah.mod.feat.AntiDelete;
import com.eightubah.mod.feat.Backgrounds;
import com.eightubah.mod.feat.Invites;
import com.eightubah.mod.feat.JoinLink;
import com.eightubah.mod.feat.Pick;
import com.eightubah.mod.feat.QuickMessages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The tabbed panel. Programmatic, dark, Arabic-first, honoring the iOS rule:
 * controls get short names + status lines only, no explanatory paragraphs.
 * One Dialog over the room, 10 tabs — the same set and order as the iOS build.
 */
public class Panel extends Dialog {
    private final Activity act;
    private LinearLayout stack;
    private int tab = 0;
    private boolean eqEdit;
    private final Handler H = new Handler(Looper.getMainLooper());
    private final List<Runnable> tickers = new ArrayList<>();
    private boolean alive = true;

    private static final String[] NAMES_AR = {"الخلفيات", "الدعوات", "رسائل سريعة", "الإعدادات", "دخول برابط", "مايك", "ستيريو", "عوازل", "تسجيل", "القطوة"};
    private static final String[] NAMES_EN = {"Backgrounds", "Invites", "Quick messages", "Settings", "Join link", "Mic", "Stereo", "Isolation", "Record", "Cat"};
    private static final int[] ORDER = {0, 5, 7, 8, 6, 1, 2, 9, 4, 3};

    public Panel(Activity a) { super(a); this.act = a; }
    private Context c() { return act; }
    private String nm(int i) { return L.ar() ? NAMES_AR[i] : NAMES_EN[i]; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
        }
        rebuild();
    }

    @Override public void dismiss() { alive = false; super.dismiss(); Overlay.get().panelClosed(); }

    private void rebuild() {
        Context c = c();
        LinearLayout root = new LinearLayout(c); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.round(L.BG, Ui.dp(c, 18)));
        root.setLayoutDirection(L.layoutDir());
        int p = Ui.dp(c, 16); root.setPadding(p, Ui.dp(c, 14), p, 0);

        // header
        TextView title = Ui.label(c, "8ubah", 22, true, 0);
        View spacer = new View(c);
        Button close = Ui.button(c, L.t("إغلاق", "Close"), false, this::dismiss);
        LinearLayout head = new LinearLayout(c); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL); head.setLayoutDirection(L.layoutDir());
        head.addView(title); head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f)); head.addView(close);
        root.addView(head, Ui.lpw());

        // tabs
        HorizontalScrollView tabs = new HorizontalScrollView(c); tabs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(c); tabRow.setOrientation(LinearLayout.HORIZONTAL); tabRow.setLayoutDirection(L.layoutDir());
        for (int oi : ORDER) {
            final int i = oi;
            Button tb = Ui.button(c, nm(i), i == tab, () -> { tab = i; if (tab != 7) eqEdit = false; rebuild(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(c, 8)); tabRow.addView(tb, lp);
        }
        tabs.addView(tabRow);
        LinearLayout.LayoutParams tlp = Ui.lpw(); tlp.topMargin = Ui.dp(c, 12); root.addView(tabs, tlp);

        // content
        ScrollView sv = new ScrollView(c); sv.setFillViewport(true);
        stack = new LinearLayout(c); stack.setOrientation(LinearLayout.VERTICAL); stack.setLayoutDirection(L.layoutDir());
        stack.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 40));
        sv.addView(stack);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, slp);

        setContentView(root);
        // cancel old tickers
        tickers.clear();

        if (Cfg.locked()) { renderLocked(); return; }
        switch (tab) {
            case 0: renderBG(); break;
            case 1: renderInvites(); break;
            case 2: renderMsgs(); break;
            case 4: renderJoin(); break;
            case 5: renderMic(); break;
            case 6: renderStereo(); break;
            case 7: renderEQ(); break;
            case 8: renderRec(); break;
            case 9: renderCat(); break;
            default: renderSettings();
        }
        startTickers();
    }

    private void add(View v) { LinearLayout.LayoutParams lp = Ui.lpw(); lp.topMargin = Ui.dp(c(), 12); stack.addView(v, lp); }
    private void startTickers() {
        if (tickers.isEmpty()) return;
        H.postDelayed(new Runnable() { public void run() {
            if (!alive) return; for (Runnable r : tickers) try { r.run(); } catch (Throwable t) {} H.postDelayed(this, 500);
        }}, 500);
    }

    // ---------------- helpers ----------------
    private interface Ask { void run(String s); }
    private void ask(String title, String hint, boolean secure, Ask ok) {
        Context c = c();
        final EditText e = new EditText(c); e.setHint(hint); e.setTextColor(L.WHITE);
        if (secure) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog d = new AlertDialog.Builder(c)
                .setTitle(title).setView(e)
                .setNegativeButton(L.t("إلغاء", "Cancel"), null)
                .setPositiveButton(L.t("تم", "OK"), (di, wch) -> ok.run(e.getText() == null ? "" : e.getText().toString()))
                .create();
        d.show();
    }
    private void toast(String s) { Ui.toast(c(), s); }

    // ---------------- Locked ----------------
    private void renderLocked() {
        Context c = c();
        final TextView st = Ui.label(c, "", 13, false, L.MUTED);
        Button unlock = Ui.button(c, L.t("فتح", "Unlock"), true, () ->
            ask(L.t("المفتاح", "Key"), "", false, s0 -> {
                String s = s0.trim().toUpperCase();
                if (s.length() < 3) { toast(L.t("المفتاح غلط", "Wrong key")); return; }
                st.setText(L.t("جاري التحقق…", "Checking…"));
                Lic.activate(s, (ok, reason) -> { if (ok) rebuild(); else { st.setText(""); toast(L.t("المفتاح غلط", "Wrong key")); } });
            }));
        add(Ui.card(c, Ui.label(c, L.t("اللوحة مقفلة", "Panel is locked"), 17, true, 0), unlock, st));
    }

    // ---------------- Backgrounds ----------------
    private void renderBG() {
        Context c = c();
        Button photo = Ui.button(c, L.t("من الاستوديو", "Photo library"), true, () ->
            Pick.pick(act, "image/*", Pick.REQ_BG, (rc, data) -> { if (rc == Activity.RESULT_OK && data != null && data.getData() != null) saveBgFromUri(data.getData()); }));
        Button url = Ui.button(c, L.t("رابط صورة", "Image URL"), false, () ->
            ask(L.t("رابط الصورة (https)", "Image URL (https)"), "https://", false, s -> downloadBg(s.trim())));
        add(Ui.card(c, Ui.label(c, L.t("خلفية الغرفة (تشوفها أنت بس)", "Room background (only you)"), 15, true, 0), Ui.hstack(c, 8, photo, url)));

        String[] colors = {"#000000", "#111827", "#1E1B4B", "#052E16", "#450A0A", "#3B0764", "#0C4A6E", "#78350F"};
        List<View> sw = new ArrayList<>();
        for (String h : colors) {
            Button b = new Button(c); b.setBackground(Ui.round(L.hex(h), Ui.dp(c, 10))); b.setMinHeight(Ui.dp(c, 40));
            b.setOnClickListener(v -> { Backgrounds.set("color", h); Overlay.get().hud(L.t("تم", "Done")); });
            sw.add(b);
        }
        add(Ui.card(c, Ui.label(c, L.t("لون ثابت", "Solid colour"), 15, true, 0), Ui.grid(c, sw, 4)));

        String[] grads = {"#0F2027,#2C5364", "#41295A,#2F0743", "#FF512F,#DD2476", "#00C6FF,#0072FF", "#11998E,#38EF7D", "#232526,#414345"};
        List<View> gs = new ArrayList<>();
        for (String g : grads) {
            String[] pp = g.split(","); Button b = new Button(c);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{L.hex(pp[0]), L.hex(pp[1])});
            gd.setCornerRadius(Ui.dp(c, 10)); b.setBackground(gd); b.setMinHeight(Ui.dp(c, 44));
            b.setOnClickListener(v -> { Backgrounds.set("gradient", g); Overlay.get().hud(L.t("تم", "Done")); });
            gs.add(b);
        }
        add(Ui.card(c, Ui.label(c, L.t("تدرجات", "Gradients"), 15, true, 0), Ui.grid(c, gs, 3)));

        add(Ui.card(c, Ui.slider(c, L.t("التمويه", "Blur"), null, 0, 100, Cfg.f("blur", 0), 0, true, v -> Cfg.f("blur", v))));
        add(Ui.card(c, Ui.slider(c, L.t("التعتيم", "Dim"), null, 0, 100, Cfg.f("dim", 30), 0, true, v -> Cfg.f("dim", v))));
        add(Ui.button(c, L.t("رجوع للافتراضي", "Reset"), false, () -> { Backgrounds.set("none", ""); Cfg.f("blur", 0); Cfg.f("dim", 30); rebuild(); }));
    }
    private void saveBgFromUri(Uri u) {
        try (InputStream is = c().getContentResolver().openInputStream(u)) {
            File f = new File(Cfg.dir(null), "bg.jpg"); FileOutputStream os = new FileOutputStream(f);
            byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) os.write(buf, 0, n); os.close();
            Backgrounds.set("image", "bg.jpg"); toast(L.t("تم تعيين الخلفية", "Background set"));
        } catch (Throwable t) { toast(L.t("تعذر الحفظ", "Could not save")); }
    }
    private void downloadBg(String s) {
        if (!s.startsWith("https")) { toast(L.t("لازم رابط https", "Needs an https link")); return; }
        new Thread(() -> {
            try {
                java.net.HttpURLConnection cc = (java.net.HttpURLConnection) new java.net.URL(s).openConnection(); cc.setConnectTimeout(10000);
                InputStream is = cc.getInputStream(); File f = new File(Cfg.dir(null), "bg.jpg"); FileOutputStream os = new FileOutputStream(f);
                byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) os.write(buf, 0, n); os.close(); is.close();
                XEntry.main(() -> { Backgrounds.set("image", "bg.jpg"); toast(L.t("تم تعيين الخلفية", "Background set")); });
            } catch (Throwable t) { XEntry.main(() -> toast(L.t("فشل التحميل", "Failed to load"))); }
        }).start();
    }

    // ---------------- Invites ----------------
    private void renderInvites() {
        Context c = c();
        add(Ui.card(c, Ui.label(c, L.t("دعوة كل اللي بالقائمة", "Invite everyone in the list"), 16, true, 0)));
        final TextView status = Ui.label(c, invText(), 14, false, 0);
        Button go = Ui.button(c, L.t("ابدأ الدعوات", "Start invites"), true, () -> {
            if (Invites.running) return;
            if (Invites.cooldownLeft() > 0) { toast(L.t("انتظر انتهاء المؤقت", "Wait for the cooldown")); return; }
            new AlertDialog.Builder(c).setTitle(L.t("تأكيد", "Confirm"))
                .setMessage(L.t("دعوة كل من في القائمة؟", "Invite everyone in the current list?"))
                .setNegativeButton(L.t("إلغاء", "Cancel"), null)
                .setPositiveButton(L.t("ابدأ", "Start"), (d, wch) -> Invites.start(act)).show();
        });
        Button stop = Ui.button(c, L.t("إيقاف", "Stop"), false, Invites::stop);
        add(Ui.card(c, status, Ui.hstack(c, 8, go, stop)));
        tickers.add(() -> status.setText(invText()));
    }
    private String invText() {
        if (Invites.running) return L.t("شغّال… حُدّد ", "Running… ") + Invites.sent;
        long left = Invites.cooldownLeft();
        if (left > 0) return L.t("متاح بعد ", "Available in ") + (left / 60) + ":" + String.format("%02d", left % 60);
        return Invites.status != null && Invites.status.length() > 0 ? Invites.status : L.t("جاهز", "Ready");
    }

    // ---------------- Quick messages ----------------
    private void renderMsgs() {
        Context c = c();
        final JSONArray msgs = Cfg.arr("messages");
        if (msgs.length() == 0) { for (String m : new String[]{"👋", "😂", "🔥"}) msgs.put(m); Cfg.arr("messages", msgs); }
        add(Ui.card(c, Ui.switchRow(c, L.t("إرسال كل الرسائل بتكرار", "Loop all messages"), QuickMessages.loop, 0, on -> {
            if (on && Cfg.arr("messages").length() == 0) { toast(L.t("أضف رسالة أول", "Add a message first")); rebuild(); return; }
            QuickMessages.setLoop(on);
        })));
        add(Ui.card(c, Ui.slider(c, L.t("الفاصل (ثانية)", "Delay (s)"), "s", 0.4f, 30, Math.min(30, Math.max(0.4f, Cfg.f("msgDelay", 4f))), 1, true, v -> Cfg.f("msgDelay", v))));
        for (int i = 0; i < msgs.length(); i++) {
            final int idx = i; final String m = msgs.optString(i, "");
            Button send = Ui.button(c, m, true, () -> QuickMessages.send(act, m, code -> XEntry.main(() ->
                toast(code == 0 ? L.t("تم الإرسال", "Sent") : code == 1 ? L.t("ما لقيت خانة الدردشة", "Chat input not found") : L.t("ما انرسلت", "Not sent")))));
            Button del = Ui.button(c, "✕", false, () -> { JSONArray a = Cfg.arr("messages"); JSONArray n = new JSONArray(); for (int j = 0; j < a.length(); j++) if (j != idx) n.put(a.optString(j)); Cfg.arr("messages", n); rebuild(); });
            LinearLayout row = Ui.hstack(c, 8, wrap(send, 1f), del); add(row);
        }
        add(Ui.button(c, L.t("+ إضافة رسالة", "+ Add message"), false, () -> ask(L.t("الرسالة", "Message"), "", false, s -> {
            s = s.trim(); if (s.length() == 0) return; JSONArray a = Cfg.arr("messages"); a.put(s); Cfg.arr("messages", a); rebuild();
        })));
    }
    private View wrap(View v, float weight) { LinearLayout ll = new LinearLayout(c()); ll.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)); return ll; }

    // ---------------- Join link ----------------
    private void renderJoin() {
        Context c = c();
        Button paste = Ui.button(c, L.t("لصق ودخول", "Paste & join"), true, () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence t = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0 ? cm.getPrimaryClip().getItemAt(0).getText() : null;
            if (t == null) { toast(L.t("الحافظة فاضية", "Clipboard empty")); return; }
            dismiss(); JoinLink.join(act, t.toString());
        });
        Button type = Ui.button(c, L.t("كتابة الرابط", "Type link"), false, () -> ask(L.t("رابط الروم", "Room link"), "rave.link/...", false, s -> { dismiss(); JoinLink.join(act, s); }));
        List<View> rows = new ArrayList<>();
        rows.add(Ui.label(c, L.t("دخول روم برابط", "Join a room by link"), 15, true, 0));
        rows.add(Ui.hstack(c, 8, paste, type));
        rows.add(Ui.button(c, L.t("+ اختصار", "+ Shortcut"), false, this::addShortcut));
        JSONArray list = Cfg.arr("shortcuts");
        for (int i = 0; i < list.length(); i++) {
            JSONObject d = list.optJSONObject(i); if (d == null) continue;
            final String name = d.optString("n", ""), link = d.optString("u", "");
            Button g = Ui.button(c, name, false, () -> { dismiss(); JoinLink.join(act, link); });
            Button del = Ui.button(c, L.t("حذف", "Delete"), false, () -> {
                JSONArray a = Cfg.arr("shortcuts"); JSONArray n = new JSONArray();
                for (int j = 0; j < a.length(); j++) { JSONObject x = a.optJSONObject(j); if (x != null && !(name.equals(x.optString("n")) && link.equals(x.optString("u")))) n.put(x); }
                Cfg.arr("shortcuts", n); rebuild();
            });
            rows.add(Ui.hstack(c, 8, wrap(g, 1f), del));
        }
        add(Ui.card(c, rows.toArray(new View[0])));
    }
    private void addShortcut() {
        Context c = c();
        final EditText name = new EditText(c); name.setHint(L.t("اسم", "Name")); name.setTextColor(L.WHITE);
        final EditText link = new EditText(c); link.setHint("rave.link/..."); link.setTextColor(L.WHITE);
        LinearLayout box = new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL); box.addView(name); box.addView(link);
        new AlertDialog.Builder(c).setTitle(L.t("اختصار جديد", "New shortcut")).setView(box)
            .setNegativeButton(L.t("إلغاء", "Cancel"), null)
            .setPositiveButton(L.t("تم", "OK"), (d, w) -> {
                String nm = name.getText().toString().trim(), ln = link.getText().toString().trim();
                if (nm.length() == 0 || JoinLink.parse(ln) == null) { toast(L.t("اكتب اسم ورابط صحيح", "Enter a name and a valid link")); return; }
                JSONArray a = Cfg.arr("shortcuts"); try { JSONObject o = new JSONObject(); o.put("n", nm); o.put("u", ln); a.put(o); } catch (Throwable t) {} Cfg.arr("shortcuts", a); rebuild();
            }).show();
    }

    // ---------------- Mic ----------------
    private void renderMic() {
        Context c = c();
        add(Ui.card(c, Ui.switchRow(c, L.t("سكر صوتي", "Kill mic"), Dsp.gMicMute, Color.RED, on -> { Dsp.setMute(on); })));
        add(Ui.card(c, Ui.switchRow(c, L.t("تشغيل مغيّر الصوت", "Voice changer"), Dsp.gMicOn, 0, Dsp::setMicOn)));
        List<View> presets = new ArrayList<>();
        for (int i = 0; i < Dsp.N_PRESETS; i++) {
            final int idx = i; String n = L.ar() ? Dsp.kPresets[i].ar : Dsp.kPresets[i].en;
            presets.add(Ui.button(c, n, i == Dsp.gMicPreset, () -> { Dsp.applyPreset(idx); Dsp.setMicOn(true); rebuild(); }));
        }
        add(Ui.card(c, Ui.label(c, L.t("الأصوات", "Voices"), 15, true, 0), Ui.grid(c, presets, 3)));
        add(Ui.card(c, Ui.slider(c, L.t("درجة الصوت", "Pitch"), null, -12, 12, Dsp.gMicSemi, 0, true, v -> { Dsp.gMicSemi = v; Dsp.gMicPreset = -1; Cfg.f("micSemi", v); Cfg.i("micPreset", -1); })));
        add(Ui.card(c, Ui.slider(c, L.t("الصدى", "Echo"), null, 0, 1, Dsp.gMicEcho, 2, true, v -> { Dsp.gMicEcho = v; Cfg.f("micEcho", v); })));
        add(Ui.card(c, Ui.slider(c, L.t("مستوى المايك", "Mic level"), null, 0.5f, 2, Dsp.gMicGain, 2, true, v -> { Dsp.gMicGain = v; Cfg.f("micGain", v); })));
        add(Ui.card(c, Ui.label(c, L.t("فضي", "Silver"), 15, true, 0), Ui.switchRow(c, L.t("مضخم", "Amplifier"), Dsp.gMicDist, 0, Dsp::setDist)));
        add(Ui.card(c, Ui.slider(c, L.t("الصدى (كهف مسكون)", "Echo (Haunted Cavern)"), null, 0, 100, Dsp.gRevMic, 0, true, v -> Dsp.setRevMic(Math.round(v)))));
        add(Ui.card(c, Ui.switchRow(c, L.t("اسمع صوتي", "Hear my voice"), Dsp.gMonOn, 0, on -> { if (on) Monitor.start(); else Monitor.stop(); })));
        add(Ui.card(c, Ui.label(c, L.t("إعدادات المضخم", "Amplifier settings"), 15, true, 0),
            Ui.slider(c, L.t("التضخيم", "Gain"), "dB", 0, 120, Cfg.f("distDb", 60), 0, true, v -> { Dsp.gDistGain = (float) Math.pow(10, v / 20); Cfg.f("distDb", v); }),
            Ui.slider(c, L.t("الخروج", "Output"), "dB", -30, 0, Cfg.f("distOutDb", -3), 0, true, v -> { Dsp.gDistOut = (float) Math.pow(10, v / 20); Cfg.f("distOutDb", v); }),
            Ui.slider(c, L.t("عتبة الكتم", "Gate"), "dB", -80, -30, Cfg.f("distGateDb", -55), 0, true, v -> { Dsp.gDistGate = (float) Math.pow(10, v / 20); Cfg.f("distGateDb", v); })));
        add(Ui.card(c, Ui.label(c, L.t("إزالة عوازل ريف", "Remove Rave isolation"), 15, true, 0),
            Ui.switchRow(c, L.t("معالج الصوت (صدى/ضجيج/AGC)", "Voice processing (AEC/NS/AGC)"), Iso.vp, 0, on -> { Iso.vp = on; Iso.save(); }),
            Ui.switchRow(c, L.t("وضع الجلسة", "Session mode"), Iso.sess, 0, on -> { Iso.sess = on; Iso.save(); }),
            Ui.switchRow(c, L.t("WebRTC", "WebRTC"), Iso.rtc, 0, on -> { Iso.rtc = on; Iso.save(); })));
        final TextView status = Ui.label(c, micStatus(), 13, false, L.MUTED);
        add(Ui.card(c, status));
        tickers.add(() -> status.setText(micStatus()));
    }
    private String micStatus() {
        long fr = com.eightubah.mod.audio.AudioHooks.frames;
        if (fr == 0) return L.t("لسا ما التقطت المايك. افتح المايك بالروم.", "No mic data yet. Unmute in a room.");
        if (!Dsp.fxOn()) return L.t("المايك مرصود ✓، المغيّر مطفي.", "Mic detected ✓, changer off.");
        return L.t("يشتغل ✓ إطارات: ", "Working ✓ frames: ") + fr;
    }

    // ---------------- Stereo ----------------
    private void renderStereo() {
        Context c = c();
        Button choose = Ui.button(c, L.t("ملف", "File"), false, () ->
            Pick.pick(act, "*/*", Pick.REQ_STEREO, (rc, data) -> { if (rc == Activity.RESULT_OK && data != null && data.getData() != null) loadStereo(data.getData()); }));
        Button remove = Ui.button(c, L.t("حذف", "Remove"), false, () -> { Stereo.free(); Cfg.s("stName", null); Cfg.s("stFile", null); rebuild(); });
        String nm = Cfg.s("stName", null);
        add(Ui.card(c, Ui.label(c, L.t("ملف الصوت أو الفيديو", "Audio/video file"), 16, true, 0),
            Ui.label(c, Stereo.isLoading() ? L.t("جاري التحميل…", "Loading…") : (nm != null ? nm : L.t("ما فيه ملف", "No file")), 13, false, L.MUTED),
            Ui.hstack(c, 8, choose, remove)));
        add(Ui.card(c, Ui.switchRow(c, L.t("تكرار", "Loop"), Stereo.loopOn(), 0, Stereo::setLoop),
            Ui.button(c, Stereo.isOn() ? L.t("إيقاف", "Stop") : L.t("تشغيل بالمايك", "Play through mic"), Stereo.isOn(), () -> {
                if (!Stereo.loaded()) { toast(L.t("اختر ملف أول", "Choose a file first")); return; }
                if (Stereo.isOn()) { Stereo.stop(); if (!Dsp.gMonOn) Monitor.stop(); } else Stereo.play();
                rebuild();
            })));
        add(Ui.card(c, Ui.switchRow(c, L.t("أسمع الملف أنا كمان", "Hear it myself"), Stereo.monWanted(), 0, Stereo::setMon)));
        final TextView timeLbl = Ui.label(c, mmss(Stereo.posSec()) + " / " + mmss(Stereo.duration()), 12, false, L.MUTED);
        timeLbl.setGravity(Gravity.CENTER);
        final android.widget.SeekBar scrub = new android.widget.SeekBar(c); scrub.setMax(1000);
        scrub.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            boolean drag; public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) { if (u) timeLbl.setText(mmss(p / 1000.0 * Stereo.duration()) + " / " + mmss(Stereo.duration())); }
            public void onStartTrackingTouch(android.widget.SeekBar s) { drag = true; }
            public void onStopTrackingTouch(android.widget.SeekBar s) { Stereo.seek(s.getProgress() / 1000.0 * Stereo.duration()); drag = false; }
        });
        add(Ui.card(c, scrub, timeLbl));
        add(Ui.card(c, Ui.slider(c, L.t("مستوى الصوت", "Volume"), null, 0, 2, Stereo.vol(), 2, true, Stereo::setVol)));
        add(Ui.card(c, Ui.slider(c, L.t("الصدى (كهف مسكون)", "Echo (Haunted Cavern)"), null, 0, 100, Dsp.gRevSong, 0, true, v -> Dsp.setRevSong(Math.round(v)))));
        tickers.add(() -> { double d = Stereo.duration(); if (d > 0) scrub.setProgress((int) (Stereo.posSec() / d * 1000)); timeLbl.setText(mmss(Stereo.posSec()) + " / " + mmss(d)); });
    }
    private void loadStereo(Uri u) {
        try {
            String ext = "dat"; String last = u.getLastPathSegment(); if (last != null && last.contains(".")) ext = last.substring(last.lastIndexOf('.') + 1);
            File dst = new File(Cfg.dir("data"), "stereo." + ext);
            try (InputStream is = c().getContentResolver().openInputStream(u); FileOutputStream os = new FileOutputStream(dst)) {
                byte[] b = new byte[8192]; int n; while ((n = is.read(b)) > 0) os.write(b, 0, n);
            }
            Cfg.s("stName", last != null ? last : dst.getName()); Cfg.s("stFile", dst.getName());
            toast(L.t("جاري التحميل…", "Loading…"));
            Stereo.decode(dst.getAbsolutePath(), (ok, msg) -> { toast(msg); rebuild(); });
        } catch (Throwable t) { toast(L.t("تعذر فتح الملف", "Could not open the file")); }
    }
    private String mmss(double s) { if (s < 0) s = 0; int t = (int) (s + 0.5); return (t / 60) + ":" + String.format("%02d", t % 60); }

    // ---------------- Isolation tab = Equalizer + studio + Sonar ----------------
    private void renderEQ() {
        Context c = c();
        final String cur = Cfg.s("eqName", "Flat");
        final List<JSONObject> all = MicSettings.allPresets();
        Button nameBtn = Ui.button(c, cur, false, () -> {
            String[] items = new String[all.size()];
            for (int i = 0; i < all.size(); i++) items[i] = all.get(i).optString("n");
            new AlertDialog.Builder(c).setTitle(L.t("وزنية", "Preset")).setItems(items, (d, w) -> { MicSettings.applyDict(all.get(w)); rebuild(); }).show();
        });
        Button more = Ui.button(c, "•••", false, () -> {
            List<String> opts = new ArrayList<>(); opts.add(L.t("حفظ", "Save"));
            if (!MicSettings.isBuiltin(cur)) opts.add(L.t("حذف", "Delete"));
            opts.add(L.t("رجوع للافتراضي", "Reset"));
            new AlertDialog.Builder(c).setItems(opts.toArray(new String[0]), (d, w) -> {
                String o = opts.get(w);
                if (o.equals(L.t("حفظ", "Save"))) ask(L.t("اسم الوزنية", "Preset name"), cur, false, n -> { n = n.trim(); if (n.length() == 0) n = cur; if (MicSettings.isBuiltin(n)) { toast(L.t("الاسم محجوز", "Name reserved")); return; } MicSettings.saveUser(n); rebuild(); });
                else if (o.equals(L.t("حذف", "Delete"))) { MicSettings.deleteUser(cur); MicSettings.reset(); rebuild(); }
                else { MicSettings.reset(); rebuild(); }
            }).show();
        });
        add(Ui.card(c, Ui.hstack(c, 8, wrap(nameBtn, 1f), more)));

        final EqView gv = new EqView(c); gv.editing = eqEdit;
        gv.setMinimumHeight(Ui.dp(c, 260));
        final TextView st = Ui.label(c, " ", 13, false, L.MUTED);
        gv.onLive = band -> st.setText(String.format("%.0f Hz   %+.1f dB   Q %.1f", Dsp.gEqF[band], Dsp.gEqG[band], Dsp.gEqQ[band]));
        gv.onEnd = MicSettings::saveEq;
        Button edit = Ui.button(c, eqEdit ? L.t("تم", "Done") : L.t("تعديل", "Edit"), eqEdit, () -> { eqEdit = !eqEdit; rebuild(); });
        LinearLayout graphCard = Ui.card(c, Ui.hstack(c, 8, wrap(Ui.switchRow(c, L.t("الإيكولايزر", "Equalizer"), Dsp.gEqOn, 0, on -> { Dsp.setEqOn(on); gv.invalidate(); }), 1f), edit), gv, st);
        add(graphCard);
        add(Ui.card(c, Ui.slider(c, L.t("المستوى", "Preamp"), "dB", -12, 12, Dsp.gEqPre, 1, true, v -> { Dsp.gEqPre = v; Dsp.gEqVer++; Cfg.f("eqPre", v); gv.invalidate(); })));

        add(Ui.card(c, Ui.switchRow(c, "Clearcast AI", Dsp.gNcOn, 0, on -> { Dsp.gNcOn = on; Cfg.b("ncOn", on); }),
            Ui.slider(c, null, null, 0, 1, Dsp.gNcAmt, 2, true, v -> { Dsp.gNcAmt = v; Cfg.f("ncAmt", v); })));
        add(Ui.card(c, Ui.label(c, L.t("تقليل الضوضاء", "Noise Reduction"), 15, true, 0),
            Ui.switchRow(c, L.t("الخلفية", "Background"), Dsp.gNrBgOn, 0, on -> { Dsp.gNrBgOn = on; Cfg.b("nrBgOn", on); }),
            Ui.slider(c, null, null, 0, 1, Dsp.gNrBg, 2, true, v -> { Dsp.gNrBg = v; Cfg.f("nrBg", v); }),
            Ui.switchRow(c, L.t("الارتطام", "Impact"), Dsp.gNrImpOn, 0, on -> { Dsp.gNrImpOn = on; Cfg.b("nrImpOn", on); }),
            Ui.slider(c, null, null, 0, 1, Dsp.gNrImp, 2, true, v -> { Dsp.gNrImp = v; Cfg.f("nrImp", v); })));
        add(Ui.card(c, Ui.switchRow(c, L.t("بوابة الضوضاء", "Noise Gate"), Dsp.gGateOn, 0, on -> { Dsp.gGateOn = on; Cfg.b("gateOn", on); }),
            Ui.slider(c, L.t("العتبة", "Threshold"), "dB", -80, -20, Dsp.gGateThr, 1, !Dsp.gGateAuto, v -> { Dsp.gGateThr = v; Cfg.f("gateThr", v); }),
            Ui.switchRow(c, L.t("تلقائي", "Auto"), Dsp.gGateAuto, 0, on -> { Dsp.gGateAuto = on; Cfg.b("gateAuto", on); rebuild(); })));
        add(Ui.card(c, Ui.switchRow(c, L.t("الكومبريسر", "Compressor"), Dsp.gCompOn, 0, on -> { Dsp.gCompOn = on; Cfg.b("compOn", on); }),
            Ui.slider(c, L.t("المستوى", "Level"), null, 0, 1, Dsp.gCompLvl, 2, true, v -> { Dsp.gCompLvl = v; Cfg.f("compLvl", v); })));
        add(Ui.button(c, L.t("استيراد (من الحافظة)", "Import (clipboard)"), false, () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence t = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0 ? cm.getPrimaryClip().getItemAt(0).getText() : null;
            if (t == null) { toast(L.t("الحافظة فاضية", "Clipboard empty")); return; }
            ask(L.t("اسم الوزنية", "Preset name"), L.t("اسم", "Name"), false, n -> { toast(L.t("جاري الاستيراد", "Importing")); SonarImport.importLink(t.toString(), n.trim(), (err, name) -> { if (err != null) toast(err); else { toast(name); rebuild(); } }); });
        }));
    }

    // ---------------- Record ----------------
    private void renderRec() {
        Context c = c();
        Button go = Ui.button(c, Recorder.on ? L.t("إيقاف", "Stop") : L.t("تسجيل", "Record"), Recorder.on, () -> {
            if (!Recorder.on) Recorder.start(err -> { if (err != null) toast(err); else toast(L.t("جاري التسجيل", "Recording")); rebuild(); });
            else { toast(L.t("جاري الحفظ", "Saving")); Recorder.stop((f, err) -> { if (f != null) shareFile(f); else if (err != null) toast(err); rebuild(); }); }
        });
        add(Ui.card(c, go, Ui.switchRow(c, L.t("مايكي بالتسجيل", "My mic in recording"), Recorder.micOn, 0, on -> Recorder.micOn = on)));
        final TextView st = Ui.label(c, recStatus(), 13, false, L.MUTED);
        add(Ui.card(c, st)); tickers.add(() -> st.setText(recStatus()));
        File dir = Cfg.dir("rec"); File[] fs = dir.listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            int shown = 0;
            for (File f : fs) {
                if (!f.getName().endsWith(".m4a") || shown++ >= 12) continue;
                final File ff = f;
                Button b = Ui.button(c, f.getName() + "  " + String.format("%.1f MB", f.length() / 1048576.0), false, () -> shareFile(ff));
                Button x = Ui.button(c, "✕", false, () -> { ff.delete(); rebuild(); });
                add(Ui.hstack(c, 8, wrap(b, 1f), x));
            }
        }
    }
    private String recStatus() {
        if (!Recorder.on) return L.t("جاهز (صوت الروم بعد دخول الكول)", "Ready (room audio after joining the call)");
        int sec = (int) (System.nanoTime() / 1e9 - Recorder.startWall);
        return String.format("%02d:%02d   %s %s   %s %s", sec / 60, sec % 60,
            L.t("مايك", "Mic"), Recorder.micN > 0 ? "✓" : "✗", L.t("أصواتهم", "Voices"), Recorder.playN > 0 ? "✓" : "✗");
    }
    private void shareFile(File f) {
        try {
            Uri u = Uri.fromFile(f);
            Intent i = new Intent(Intent.ACTION_SEND); i.setType("audio/mp4"); i.putExtra(Intent.EXTRA_STREAM, u); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(Intent.createChooser(i, null));
        } catch (Throwable t) { toast(L.t("تعذرت المشاركة", "Could not share")); }
    }

    // ---------------- Cat ----------------
    private void renderCat() {
        Context c = c();
        boolean on = Cfg.b("cat", true);
        add(Ui.card(c, Ui.button(c, L.t("القطوة", "Cat"), on, () -> { com.eightubah.mod.ui.Cat.set(!Cfg.b("cat", true)); rebuild(); })));
    }

    // ---------------- Settings ----------------
    private void renderSettings() {
        Context c = c();
        // language
        LinearLayout langRow = new LinearLayout(c); langRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] langs = {"auto", "ar", "en"}; String[] langLabel = {L.t("تلقائي", "Auto"), "العربية", "English"};
        String curLang = Cfg.s("lang", "ar");
        for (int i = 0; i < 3; i++) { final String lv = langs[i]; langRow.addView(Ui.button(c, langLabel[i], lv.equals(curLang), () -> { Cfg.s("lang", lv); rebuild(); }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); }
        add(Ui.card(c, Ui.label(c, L.t("لغة الواجهة", "Language"), 15, true, 0), langRow));
        // accent
        List<View> accents = new ArrayList<>(); String[] acc = L.accents();
        for (int i = 0; i < acc.length; i++) { final int idx = i; Button b = new Button(c); b.setBackground(Ui.round(L.hex(acc[i]), Ui.dp(c, 18))); b.setMinHeight(Ui.dp(c, 36)); b.setOnClickListener(v -> { Cfg.i("accent", idx); rebuild(); }); accents.add(b); }
        add(Ui.card(c, Ui.label(c, L.t("لون التمييز", "Accent colour"), 15, true, 0), Ui.grid(c, accents, 6)));
        // auto open
        add(Ui.card(c, Ui.switchRow(c, L.t("افتح اللوحة تلقائيًا عند دخول الغرفة", "Open panel on entering a room"), Cfg.b("autoOpen", false), 0, on -> Cfg.b("autoOpen", on))));
        // anti-delete
        add(Ui.card(c, Ui.switchRow(c, L.t("حفظ الرسائل المحذوفة (عندك بس)", "Keep deleted messages (local)"), AntiDelete.on, 0, AntiDelete::setOn),
            Ui.label(c, L.t("المحفوظ: ", "Saved: ") + AntiDelete.count(), 12, false, L.MUTED),
            Ui.button(c, L.t("عرض المحذوفة", "Show saved"), true, this::showDeleted),
            Ui.button(c, L.t("مسح القائمة", "Clear list"), false, () -> { AntiDelete.clear(); rebuild(); })));
        // key
        List<View> key = new ArrayList<>(); key.add(Ui.label(c, L.t("المفتاح", "Key"), 15, true, 0));
        key.add(Ui.label(c, Cfg.locked() ? L.t("الحالة: مقفول", "Status: locked") : L.t("الحالة: مفتوح", "Status: unlocked"), 13, false, L.MUTED));
        if (!Cfg.locked()) key.add(Ui.button(c, L.t("قفل / تغيير المفتاح", "Lock / change key"), true, () -> { Cfg.b("unlocked", false); Cfg.s("licHash", null); rebuild(); }));
        add(Ui.card(c, key.toArray(new View[0])));
        // advanced tunables
        add(Ui.card(c, Ui.label(c, L.t("متقدم", "Advanced"), 15, true, 0),
            Ui.button(c, L.t("اسم العلامة: ", "Brand: ") + com.eightubah.mod.feat.Branding.brand(), false, () -> ask(L.t("الاسم بدل rave", "Name instead of rave"), "8ubah", false, s -> { Cfg.s("brandName", s.length() > 0 ? s : null); Cfg.b("brandOn", s.length() > 0); rebuild(); })),
            Ui.button(c, L.t("اسم شاشة الغرفة: ", "Room class: ") + Cfg.s("roomClass", "RaveActivity"), false, () -> ask(L.t("اسم الكلاس", "Class name"), "RaveActivity", false, s -> { Cfg.s("roomClass", s.length() > 0 ? s : null); rebuild(); })),
            Ui.button(c, L.t("كلاس الحذف (DAO): ", "Delete DAO class: ") + shortName(Cfg.s("daoClass", "com.wemesh.android.legacy.dms.db.MessageDao_Impl")), false, () -> ask("DAO class", "MessageDao_Impl", false, s -> { if (s.length() > 0) Cfg.s("daoClass", s); rebuild(); }))));
    }
    private String shortName(String s) { int i = s.lastIndexOf('.'); return i >= 0 ? s.substring(i + 1) : s; }
    private void showDeleted() {
        List<String> a = AntiDelete.all(); StringBuilder m = new StringBuilder(); int shown = 0;
        for (int i = a.size() - 1; i >= 0 && shown < 40; i--, shown++) m.append("• ").append(clip(a.get(i), 160)).append('\n');
        new AlertDialog.Builder(c()).setTitle(L.t("الرسائل المحذوفة (الأحدث أول)", "Saved deleted (newest)"))
            .setMessage(m.length() > 0 ? m.toString() : L.t("ما في شي محفوظ", "Nothing saved yet"))
            .setPositiveButton(L.t("نسخ الكل", "Copy all"), (d, w) -> {
                StringBuilder all = new StringBuilder(); for (int i = a.size() - 1; i >= 0; i--) all.append(a.get(i)).append('\n');
                android.content.ClipboardManager cm = (android.content.ClipboardManager) c().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("8ubah", all.toString()));
            })
            .setNegativeButton(L.t("إغلاق", "Close"), null).show();
    }
    private String clip(String s, int n) { if (s == null) return ""; s = s.replace("\n", " "); return s.length() > n ? s.substring(0, n) : s; }
}

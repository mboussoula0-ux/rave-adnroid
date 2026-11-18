package com.eightubah.mod.audio;

import com.eightubah.mod.Cfg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads/saves the mic + EQ settings, and manages EQ presets (built-in + the
 * owner's saved ones). Port of the iOS RYMicLoad / RYEq* helpers.
 */
public final class MicSettings {
    private MicSettings() {}

    public static void load() {
        Dsp.gMicOn = Cfg.b("micOn", false);
        Dsp.gMicPreset = Cfg.i("micPreset", 0);
        Dsp.gMicSemi = Cfg.f("micSemi", 0);
        Dsp.gMicMode = Cfg.i("micMode", 0);
        Dsp.gMicEcho = Cfg.f("micEcho", 0);
        Dsp.gMicGain = Cfg.f("micGain", 1);
        Dsp.gMicDist = Cfg.b("micDist", false);
        Dsp.gMicMute = false; // never restore "on"
        Dsp.gRevMic = Cfg.i("revMic", 0);
        Dsp.gRevSong = Cfg.i("revSong", 0);
        Stereo.load();
        System.arraycopy(Dsp.kRvDef, 0, Dsp.gRv, 0, Dsp.RV_N); // echo voicing fixed
        Dsp.gDistGain = (float) Math.pow(10f, Cfg.f("distDb", 60) / 20f);
        Dsp.gDistOut = (float) Math.pow(10f, Cfg.f("distOutDb", -3) / 20f);
        Dsp.gDistGate = (float) Math.pow(10f, Cfg.f("distGateDb", -55) / 20f);
        Iso.sess = Cfg.b("isoSess", false); Iso.vp = Cfg.b("isoVP", false); Iso.rtc = Cfg.b("isoRTC", false);
        Dsp.gEqOn = Cfg.b("eqOn", false); Dsp.gEqPre = Cfg.f("eqPre", 0);
        loadEqArrays();
        Dsp.gNcOn = Cfg.b("ncOn", false); Dsp.gNcAmt = Cfg.f("ncAmt", 0.2f);
        Dsp.gNrBgOn = Cfg.b("nrBgOn", false); Dsp.gNrBg = Cfg.f("nrBg", 0);
        Dsp.gNrImpOn = Cfg.b("nrImpOn", false); Dsp.gNrImp = Cfg.f("nrImp", 0);
        Dsp.gGateOn = Cfg.b("gateOn", false); Dsp.gGateThr = Cfg.f("gateThr", -56.3f);
        Dsp.gGateAuto = Cfg.b("gateAuto", false);
        Dsp.gCompOn = Cfg.b("compOn", false); Dsp.gCompLvl = Cfg.f("compLvl", 1);
        Dsp.gEqVer++;
    }

    private static void loadEqArrays() {
        JSONArray ef = Cfg.arr("eqF"), eg = Cfg.arr("eqG"), eq = Cfg.arr("eqQ"), et = Cfg.arr("eqT");
        boolean ok = Cfg.i("eqCfgVer", 0) == 2 && ef.length() == Dsp.RYEQ_N && eg.length() == Dsp.RYEQ_N;
        boolean okq = ok && eq.length() == Dsp.RYEQ_N && et.length() == Dsp.RYEQ_N;
        for (int i = 0; i < Dsp.RYEQ_N; i++) {
            Dsp.gEqF[i] = ok ? (float) ef.optDouble(i, Dsp.kEqF[i]) : Dsp.kEqF[i];
            Dsp.gEqG[i] = ok ? (float) eg.optDouble(i, Dsp.kEqG[i]) : Dsp.kEqG[i];
            Dsp.gEqQ[i] = okq ? (float) eq.optDouble(i, 1.1) : 1.1f;
            Dsp.gEqT[i] = okq ? et.optInt(i, 0) : 0;
        }
        if (!ok) Dsp.gEqOn = false;
    }

    public static void saveEq() {
        JSONArray f = new JSONArray(), g = new JSONArray(), q = new JSONArray(), t = new JSONArray();
        try {
            for (int i = 0; i < Dsp.RYEQ_N; i++) { f.put((double) Dsp.gEqF[i]); g.put((double) Dsp.gEqG[i]); q.put((double) Dsp.gEqQ[i]); t.put(Dsp.gEqT[i]); }
        } catch (org.json.JSONException e) { return; } // NaN/Inf: keep old saved EQ
        Cfg.arr("eqF", f); Cfg.arr("eqG", g); Cfg.arr("eqQ", q); Cfg.arr("eqT", t);
        Cfg.setF("eqPre", Dsp.gEqPre); Cfg.setI("eqCfgVer", 2);
    }

    public static JSONObject eqDictFrom(String name, float[] f, float[] g, float[] q, int[] t) {
        try {
            JSONArray F = new JSONArray(), G = new JSONArray(), Q = new JSONArray(), T = new JSONArray();
            for (int i = 0; i < Dsp.RYEQ_N; i++) { F.put(f[i]); G.put(g[i]); Q.put(q[i]); T.put(t[i]); }
            JSONObject o = new JSONObject(); o.put("n", name); o.put("f", F); o.put("g", G); o.put("q", Q); o.put("t", T); return o;
        } catch (Throwable e) { return new JSONObject(); }
    }

    public static List<JSONObject> allPresets() {
        List<JSONObject> a = new ArrayList<>();
        for (Dsp.EqPreset p : Dsp.kEqPresets) a.add(eqDictFrom(p.name, p.f, p.g, p.q, p.t));
        JSONArray u = Cfg.arr("eqUser");
        for (int i = 0; i < u.length(); i++) { JSONObject d = u.optJSONObject(i); if (d != null && d.opt("n") instanceof String) a.add(d); }
        return a;
    }
    public static boolean isBuiltin(String n) { for (Dsp.EqPreset p : Dsp.kEqPresets) if (p.name.equals(n)) return true; return false; }

    public static void applyDict(JSONObject d) {
        JSONArray f = d.optJSONArray("f"), g = d.optJSONArray("g"), q = d.optJSONArray("q"), t = d.optJSONArray("t");
        if (f == null || g == null || f.length() != Dsp.RYEQ_N || g.length() != Dsp.RYEQ_N) return;
        for (int i = 0; i < Dsp.RYEQ_N; i++) {
            Dsp.gEqF[i] = (float) f.optDouble(i, Dsp.kEqF[i]); Dsp.gEqG[i] = (float) g.optDouble(i, 0);
            Dsp.gEqQ[i] = (q != null && q.length() == Dsp.RYEQ_N) ? (float) q.optDouble(i, 1.1) : 1.1f;
            Dsp.gEqT[i] = (t != null && t.length() == Dsp.RYEQ_N) ? t.optInt(i, 0) : 0;
        }
        Dsp.gEqPre = 0; Dsp.gEqOn = true; Dsp.gEqVer++;
        Cfg.setB("eqOn", true); Cfg.setS("eqName", d.optString("n", ""));
        saveEq();
    }
    public static void reset() { List<JSONObject> a = allPresets(); if (!a.isEmpty()) applyDict(a.get(0)); Dsp.gEqOn = false; Cfg.setB("eqOn", false); }

    public static void saveUser(String name) {
        if (name == null || name.length() == 0) return;
        try {
            JSONArray F = new JSONArray(), G = new JSONArray(), Q = new JSONArray(), T = new JSONArray();
            for (int i = 0; i < Dsp.RYEQ_N; i++) { F.put(Dsp.gEqF[i]); G.put(Dsp.gEqG[i]); Q.put(Dsp.gEqQ[i]); T.put(Dsp.gEqT[i]); }
            JSONArray u = new JSONArray(); JSONArray old = Cfg.arr("eqUser");
            for (int i = 0; i < old.length(); i++) { JSONObject d = old.optJSONObject(i); if (d != null && !name.equals(d.optString("n"))) u.put(d); }
            JSONObject o = new JSONObject(); o.put("n", name); o.put("f", F); o.put("g", G); o.put("q", Q); o.put("t", T);
            u.put(o); Cfg.arr("eqUser", u); Cfg.setS("eqName", name);
        } catch (Throwable e) {}
    }
    public static void deleteUser(String name) {
        JSONArray u = new JSONArray(); JSONArray old = Cfg.arr("eqUser");
        for (int i = 0; i < old.length(); i++) { JSONObject d = old.optJSONObject(i); if (d != null && !name.equals(d.optString("n"))) u.put(d); }
        Cfg.arr("eqUser", u);
        JSONArray fv = new JSONArray(); JSONArray fo = Cfg.arr("eqFav");
        for (int i = 0; i < fo.length(); i++) { String n = fo.optString(i, null); if (n != null && !n.equals(name)) fv.put(n); }
        Cfg.arr("eqFav", fv);
    }
}

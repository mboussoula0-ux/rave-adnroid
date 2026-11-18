package com.eightubah.mod.audio;

/**
 * Voice DSP. Direct port of the iOS mic-effects engine: voice changer (pitch,
 * robot, megaphone), amplifier (distortion), Haunted-Cavern reverb, 10-band
 * RBJ equaliser, studio chain (noise/gate/compressor), and the per-sample
 * orchestrator RYMicFx. Math is 1:1 with the Objective-C; only the language
 * changed.
 *
 * All parameters are static (the C globals). The UI writes them directly; the
 * audio hook thread reads them.
 */
public final class Dsp {
    private Dsp() {}

    private static final float TWO_PI = 6.2831853f;

    // ================= voice-changer params =================
    public static final class Preset { public final String ar, en; public final float semi; public final int mode; public final float echo;
        Preset(String ar, String en, float semi, int mode, float echo) { this.ar = ar; this.en = en; this.semi = semi; this.mode = mode; this.echo = echo; } }
    public static final Preset[] kPresets = {
        new Preset("عادي", "Normal", 0, 0, 0), new Preset("رجل عميق", "Deep man", -4, 0, 0), new Preset("وحش", "Monster", -9, 0, 0),
        new Preset("فتاة", "Girl", 4, 0, 0), new Preset("طفل", "Kid", 7, 0, 0), new Preset("سنجاب", "Chipmunk", 11, 0, 0),
        new Preset("روبوت", "Robot", 0, 1, 0), new Preset("ميجافون", "Megaphone", 0, 2, 0), new Preset("صدى", "Echo", 0, 0, 0.45f), new Preset("صدى عميق", "Deep echo", -4, 0, 0.4f)
    };
    public static final int N_PRESETS = kPresets.length;

    public static volatile boolean gMicOn = false;
    public static volatile int gMicMode = 0, gMicPreset = 0;
    public static volatile float gMicSemi = 0, gMicEcho = 0, gMicGain = 1;
    public static volatile boolean gMicDist = false, gMonOn = false, gMicMute = false;
    public static volatile int gRevMic = 0, gRevSong = 0; // Haunted-Cavern amount 0-100 (mic / song)

    // amplifier: +60 dB in, -3 dB out, gate at -55 dB (same defaults)
    public static volatile float gDistGain = 1000f, gDistOut = 0.7f, gDistGate = 0.0018f;

    // ================= reverb (Haunted Cavern) =================
    public static final int RV_DRY=0, RV_ELVL=1, RV_ESEND=2, RV_LLVL=3, RV_SIZE=4, RV_PRE=5, RV_DECAY=6, RV_DIFF=7,
            RV_SPIN=8, RV_WANDER=9, RV_HC=10, RV_EDAMP=11, RV_LDAMP=12, RV_LC=13, RV_LB=14, RV_BF=15, RV_N=16;
    public static final float[] kRvDef = {100, 42, 85, 92, 60, 70, 7.0f, 78, 0.6f, 50, 6000, 6500, 6500, 150, 40, 110};
    public static final float[] gRv = kRvDef.clone();

    private static final int[] kRvComb = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] kRvAp = {556, 441, 341, 225};
    private static final float[] kRvEt = {0.007f, 0.011f, 0.017f, 0.023f, 0.031f, 0.041f, 0.053f, 0.067f};
    private static final float[] kRvEg = {.7f, .6f, .5f, .45f, .4f, .35f, .3f, .25f};

    // ================= equalizer =================
    public static final int RYEQ_N = 10;
    public static final float[] kEqF = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final float[] kEqG = {0,0,0,0,0,0,0,0,0,0};
    public static volatile boolean gEqOn = false;
    public static volatile int gEqVer = 1;
    public static final float[] gEqF = kEqF.clone();
    public static final float[] gEqG = kEqG.clone();
    public static final float[] gEqQ = {1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f};
    public static final int[]   gEqT = {0,0,0,0,0,0,0,0,0,0}; // 0 peaking, 1 low shelf, 2 high shelf
    public static volatile float gEqPre = 0;

    // studio chain
    public static volatile boolean gNcOn=false, gNrBgOn=false, gNrImpOn=false, gGateOn=false, gGateAuto=false, gCompOn=false;
    public static volatile float gNcAmt=0.2f, gNrBg=0f, gNrImp=0f, gGateThr=-56.3f, gCompLvl=1f;

    public static boolean studioOn() { return gNcOn || gNrBgOn || gNrImpOn || gGateOn || gCompOn; }
    public static boolean fxOn() {
        return gMicOn || gMicDist || gRevMic > 0 || gMicMute || Stereo.isOn() || gEqOn || studioOn();
    }

    // built-in EQ presets (name, f[], g[], q[], t[])
    public static final class EqPreset { public final String name; public final float[] f,g,q; public final int[] t;
        EqPreset(String name, float[] f, float[] g, float[] q, int[] t){this.name=name;this.f=f;this.g=g;this.q=q;this.t=t;} }
    private static float[] fa(float... v){return v;} private static int[] ia(int... v){return v;}
    public static final EqPreset[] kEqPresets = {
        new EqPreset("Flat", fa(31,62,125,250,500,1000,2000,4000,8000,16000), fa(0,0,0,0,0,0,0,0,0,0), fa(1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f), ia(0,0,0,0,0,0,0,0,0,0)),
        new EqPreset("rayan", fa(30,90,300,500,1000,2000,4000,12000,13600,17400), fa(-1.5f,9.1f,-1.3f,0,-2.8f,3.0f,4.0f,12.0f,3.3f,4.0f), fa(1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f), ia(0,0,0,0,0,0,0,0,0,0)),
        new EqPreset("Studio Vocal", fa(80,160,300,500,1000,2000,3500,6000,10000,14000), fa(-6,-1.5f,-2.0f,-1.0f,0,1.5f,3.0f,1.5f,1.5f,2.0f), fa(0.7f,1.0f,1.2f,1.0f,1.0f,1.0f,1.2f,1.0f,1.0f,0.7f), ia(1,0,0,0,0,0,0,0,0,2)),
        new EqPreset("Broadcast", fa(70,120,220,400,800,1600,3000,5000,9000,14000), fa(-5,2.5f,1.5f,-2.0f,0,1.0f,2.5f,1.0f,-1.0f,1.5f), fa(0.7f,1.0f,1.0f,1.2f,1.0f,1.0f,1.1f,1.0f,1.0f,0.7f), ia(1,0,0,0,0,0,0,0,0,2)),
        new EqPreset("Crystal Clear", fa(60,120,250,500,1000,2000,3500,6000,10000,15000), fa(-6,-2.0f,-1.5f,-2.0f,0,2.0f,3.5f,2.5f,2.0f,3.0f), fa(0.7f,1.0f,1.2f,1.0f,1.0f,1.0f,1.2f,1.0f,1.0f,0.7f), ia(1,0,0,0,0,0,0,0,0,2)),
        new EqPreset("Deep Voice", fa(60,100,160,300,600,1200,2400,4800,9000,14000), fa(2.0f,4.5f,3.0f,0.5f,-1.0f,-1.0f,0.5f,1.0f,0,0), fa(0.7f,1.0f,1.0f,1.0f,1.0f,1.0f,1.0f,1.0f,1.0f,0.7f), ia(1,0,0,0,0,0,0,0,0,2)),
        new EqPreset("فضي 1", fa(31,62,125,250,500,1000,2000,4000,8000,16000), fa(1,2,2,1,-2,4,5,6,5,5), fa(1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f), ia(0,0,0,0,0,0,0,0,0,0)),
        new EqPreset("فضي 2", fa(31,62,125,250,500,1000,2000,4000,8000,16000), fa(6,10,12,12,0,-3,1,4,5,5), fa(1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f), ia(0,0,0,0,0,0,0,0,0,0)),
        new EqPreset("فضي 3", fa(31,62,125,250,500,1000,2000,4000,8000,16000), fa(-1,-2,-2,-3,-12,12,12,12,12,12), fa(1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f,1.1f), ia(0,0,0,0,0,0,0,0,0,0)),
    };

    public static final Vs songVs = new Vs(); // the song/Stereo file gets its own cavern state

    // ================= amplifier (distortion) =================
    static float dist(float x, Vs s) {
        float a = Math.abs(x); s.env = a > s.env ? a : s.env * 0.9997f;
        float thr = gDistGate, gate = s.env < thr ? s.env / thr : 1f; gate *= gate;
        float v = x * gDistGain;
        float y = 0.7f * (float) Math.tanh(v * 2.2f + 0.1f) + 0.3f * Math.max(-1f, Math.min(1f, v * 3f));
        float hp = y - s.dcx + 0.995f * s.dcy; s.dcx = y; s.dcy = hp;
        s.dlp += 0.55f * (hp - s.dlp);
        return s.dlp * gDistOut * gate;
    }

    // ================= reverb =================
    static final class RevC { int[] L=new int[8], apL=new int[4], etl=new int[8]; int pd; float[] fb=new float[8];
        float ld,ed,hc,lc,bf,apg,wdepth,spininc,dry,elv,esend,llv,lb; }

    static float pole(float hz, double sr) {
        if (hz > 0.45f * (float) sr) hz = 0.45f * (float) sr; if (hz < 1f) hz = 1f;
        return (float) Math.exp(-TWO_PI * hz / (float) sr);
    }
    static void revPrep(RevC c, double sr) {
        final float k = (float) (sr / 44100.0), sz = Math.max(0.25f, gRv[RV_SIZE] / 32f), decay = Math.max(0.1f, gRv[RV_DECAY]);
        for (int i = 0; i < 8; i++) {
            int Ln = (int) (kRvComb[i] * k * sz); if (Ln > 4000) Ln = 4000; if (Ln < 32) Ln = 32; c.L[i] = Ln;
            float fb = (float) Math.pow(10.0, -3.0 * (double) Ln / ((double) sr * decay)); c.fb[i] = fb > 0.9995f ? 0.9995f : fb;
            int e = (int) (kRvEt[i] * sz * (float) sr); c.etl[i] = e > 8190 ? 8190 : e;
        }
        for (int i = 0; i < 4; i++) { int Ln = (int) (kRvAp[i] * k); if (Ln > 1000) Ln = 1000; if (Ln < 8) Ln = 8; c.apL[i] = Ln; }
        int pd = (int) (gRv[RV_PRE] * 0.001 * sr); c.pd = pd > 16383 ? 16383 : (pd < 0 ? 0 : pd);
        c.ld = pole(gRv[RV_LDAMP], sr); c.ed = pole(gRv[RV_EDAMP], sr); c.hc = pole(gRv[RV_HC], sr); c.lc = pole(gRv[RV_LC], sr); c.bf = pole(gRv[RV_BF], sr);
        c.apg = 0.1f + 0.7f * gRv[RV_DIFF] * 0.01f; c.wdepth = gRv[RV_WANDER] * 0.01f * 24f * k; c.spininc = TWO_PI * gRv[RV_SPIN] / (float) sr;
        c.dry = gRv[RV_DRY] * 0.01f; c.elv = gRv[RV_ELVL] * 0.01f * 0.3f; c.esend = gRv[RV_ESEND] * 0.01f; c.llv = gRv[RV_LLVL] * 0.01f * 3f; c.lb = gRv[RV_LB] * 0.01f;
    }
    static float rev(float x, Vs s, RevC c) {
        s.rlc = x * (1f - c.lc) + s.rlc * c.lc; float xi = x - s.rlc;
        s.pre[s.pw & 16383] = xi; float pin = s.pre[(s.pw - c.pd) & 16383]; s.pw++;
        s.er[s.erw & 8191] = xi; float e = 0;
        for (int j = 0; j < 8; j++) e += s.er[(s.erw - c.etl[j]) & 8191] * kRvEg[j];
        s.erw++; s.elp = e * (1f - c.ed) + s.elp * c.ed; e = s.elp;
        float in = (pin + e * c.esend) * 0.015f, late = 0;
        s.lfo += c.spininc; if (s.lfo > TWO_PI) s.lfo -= TWO_PI;
        for (int i = 0; i < 8; i++) {
            float Lm = (float) c.L[i]; if (c.wdepth > 0.01f && c.spininc > 0f) Lm += c.wdepth * (float) Math.sin(s.lfo + 0.8f * (float) i);
            int Li = (int) Lm; float fr = Lm - (float) Li; int wi = s.ci[i];
            float o = s.cb[i][(wi - Li) & 4095] * (1f - fr) + s.cb[i][(wi - Li - 1) & 4095] * fr;
            s.cf[i] = o * (1f - c.ld) + s.cf[i] * c.ld;
            s.cb[i][wi & 4095] = in + s.cf[i] * c.fb[i]; s.ci[i] = wi + 1; late += o;
        }
        for (int a = 0; a < 4; a++) {
            int wi = s.ai[a]; float b = s.ab[a][(wi - c.apL[a]) & 1023], o = -late + b;
            s.ab[a][wi & 1023] = late + b * c.apg; s.ai[a] = wi + 1; late = o;
        }
        s.blp = late * (1f - c.bf) + s.blp * c.bf; late += s.blp * c.lb;
        float wet = e * c.elv + late * c.llv;
        s.olp = wet * (1f - c.hc) + s.olp * c.hc;
        return x * c.dry + s.olp;
    }
    static float revAmt(int amt) { if (amt <= 0) return 0f; if (amt > 100) amt = 100; return (float) Math.pow((double) amt / 100.0, 0.45); }

    /** Haunted-Cavern on a whole block (used by the Stereo song path). */
    public static void cavern(float[] x, int off, int n, double sr, Vs s, int amount) {
        if (amount <= 0) return;
        if (s.sr != sr) s.reset(sr);
        RevC rc = new RevC(); revPrep(rc, sr);
        float a = revAmt(amount); rc.dry = 1f; rc.elv *= a; rc.llv *= a;
        for (int i = 0; i < n; i++) { float y = rev(x[off + i], s, rc); if (y > 1f) y = 1f; else if (y < -1f) y = -1f; x[off + i] = y; }
    }

    // ================= equalizer =================
    static boolean eqCoef(int t, float f, float g, float q, double sr, double[] o) {
        if (Math.abs(g) < 0.05f || f < 15f || f > 0.45f * (float) sr) return false;
        if (q < 0.2f) q = 0.2f; if (q > 10f) q = 10f;
        double A = Math.pow(10.0, g / 40.0), w0 = 2.0 * Math.PI * f / sr, cw = Math.cos(w0), al = Math.sin(w0) / (2.0 * q), b0, b1, b2, a0, a1, a2;
        if (t == 1 || t == 2) {
            double sq = 2.0 * Math.sqrt(A) * al;
            if (t == 1) { b0 = A * ((A + 1) - (A - 1) * cw + sq); b1 = 2 * A * ((A - 1) - (A + 1) * cw); b2 = A * ((A + 1) - (A - 1) * cw - sq); a0 = (A + 1) + (A - 1) * cw + sq; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - sq; }
            else        { b0 = A * ((A + 1) + (A - 1) * cw + sq); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - sq); a0 = (A + 1) - (A - 1) * cw + sq; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - sq; }
        } else { b0 = 1 + al * A; b1 = -2 * cw; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * cw; a2 = 1 - al / A; }
        o[0] = b0 / a0; o[1] = b1 / a0; o[2] = b2 / a0; o[3] = a1 / a0; o[4] = a2 / a0; return true;
    }
    static void eqPrep(Vs s, double sr) {
        if (s.eqVer == gEqVer && s.eqSr == sr) return;
        double[] o = new double[5];
        for (int b = 0; b < RYEQ_N; b++) {
            s.eqAct[b] = false;
            if (eqCoef(gEqT[b], gEqF[b], gEqG[b], gEqQ[b], sr, o)) { for (int k = 0; k < 5; k++) s.eqc[b][k] = (float) o[k]; s.eqAct[b] = true; }
        }
        s.eqVer = gEqVer; s.eqSr = sr;
    }
    static float eqRun(float x, Vs s, float pre) {
        float y = x * pre;
        for (int b = 0; b < RYEQ_N; b++) if (s.eqAct[b]) {
            float[] c = s.eqc[b]; float o = c[0] * y + s.eqz[b][0];
            s.eqz[b][0] = c[1] * y - c[3] * o + s.eqz[b][1]; s.eqz[b][1] = c[2] * y - c[4] * o; y = o;
        }
        float a = Math.abs(y); if (a > 0.8f) y = Math.copySign(0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f), y);
        return y;
    }
    /** one band's magnitude in dB at f (for the EQ graph), sample rate 48 kHz. */
    public static double eqBandDb(int type, float fc, float g, float q, double f) {
        double[] o = new double[5]; if (!eqCoef(type, fc, g, q, 48000.0, o)) return 0;
        double b0 = o[0], b1 = o[1], b2 = o[2], a1 = o[3], a2 = o[4];
        double w = 2.0 * Math.PI * f / 48000.0, c1 = Math.cos(w), c2 = Math.cos(2 * w);
        double num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2;
        double den = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * c1 + 2 * a2 * c2;
        return den > 0 && num > 0 ? 10.0 * Math.log10(num / den) : 0;
    }

    // ================= studio chain =================
    static final class StC { float rEnv,rFast,aSlow,aG,rG,rise,fall,hp,aC,rC,gateLin; }
    static void stPrep(StC c, double sr) {
        final float r = (float) sr;
        c.rEnv = (float) Math.exp(-1f / (0.03f * r)); c.rFast = (float) Math.exp(-1f / (0.003f * r)); c.aSlow = 1f - (float) Math.exp(-1f / (0.06f * r));
        c.aG = 1f - (float) Math.exp(-1f / (0.003f * r)); c.rG = 1f - (float) Math.exp(-1f / (0.08f * r));
        c.rise = 1f / (6f * r); c.fall = 1f - (float) Math.exp(-1f / (0.02f * r)); c.hp = 1f - (float) Math.exp(-TWO_PI * 70f / r);
        c.aC = 1f - (float) Math.exp(-1f / (0.005f * r)); c.rC = 1f - (float) Math.exp(-1f / (0.12f * r)); c.gateLin = (float) Math.pow(10f, gGateThr / 20f);
    }
    static float studioIn(float x, Vs s, StC c) {
        float a = Math.abs(x);
        s.stEnv = a > s.stEnv ? a : s.stEnv * c.rEnv; final float env = s.stEnv;
        if (s.stInit < 0.5f) { s.stFloor = Math.max(env, 1e-3f); s.stInit = 1f; }
        s.stFloor += (env - s.stFloor) * (env < s.stFloor ? c.fall : c.rise);
        if (s.stFloor < 1e-5f) s.stFloor = 1e-5f;
        float g = 1f;
        if (gNcOn || gNrBgOn) {
            float amt = gNcOn ? gNcAmt : gNrBg, k = gNcOn ? 2f + 5f * amt : 1.5f + 3f * amt, p = gNcOn ? 1f + 2f * amt : 1f;
            float r = env / (s.stFloor * k); if (r < 1f) g *= (float) Math.pow(Math.max(r, 1e-3f), p);
            s.stHx += c.hp * (x - s.stHx); x -= s.stHx * (0.4f + 0.6f * amt);
        }
        if (gNrImpOn && !gNcOn) {
            s.stFast = a > s.stFast ? a : s.stFast * c.rFast; s.stSlow += (a - s.stSlow) * c.aSlow;
            float allowed = 3f + 6f * (1f - gNrImp), rr = s.stFast / (s.stSlow + 1e-5f); if (rr > allowed) g *= allowed / rr;
        }
        if (gGateOn) {
            float thr = gGateAuto ? s.stFloor * 3f : c.gateLin;
            if (env > thr) s.stOpen = 1f; else if (env < thr * 0.7f) s.stOpen = 0f;
            g *= s.stOpen > 0.5f ? 1f : 0f;
        }
        s.stGate += (g - s.stGate) * (g > s.stGate ? c.aG : c.rG);
        return x * s.stGate;
    }
    static float comp(float x, Vs s, StC c) {
        float a = Math.abs(x); s.stCe += (a - s.stCe) * (a > s.stCe ? c.aC : c.rC);
        float db = 20f * (float) Math.log10(Math.max(s.stCe, 1e-6f)), thr = -20f, ratio = 1f + 3f * gCompLvl, red = db > thr ? (db - thr) * (1f - 1f / ratio) : 0f;
        float gdb = 0.4f * (-thr) * (1f - 1f / ratio) - red;
        return x * (float) Math.pow(2.0, gdb * 0.16609640f);
    }

    // ================= the main per-sample voice block =================
    public static void voiceBlock(float[] x, int off, int n, double sr, Vs s) {
        if (s.sr != sr) { s.reset(sr); s.d1 = (float) (0.016 * sr); }
        if (gMicMute) { for (int i = 0; i < n; i++) x[off + i] = 0f; return; }
        final int W = (int) (0.032 * sr); final int revAmt = gRevMic; final boolean revOn = revAmt > 0; RevC rc = null;
        if (revOn) { rc = new RevC(); revPrep(rc, sr); float a = revAmt(revAmt); rc.dry = 1f; rc.elv *= a; rc.llv *= a; }
        final boolean mst = gMicOn;
        final float semi = mst ? gMicSemi : 0f, echo = mst ? gMicEcho : 0f, gain = mst ? gMicGain : 1f; final int mode = mst ? gMicMode : 0;
        final float dd = 1f - (float) Math.pow(2f, semi / 12f);
        final boolean pitch = Math.abs(semi) > 0.05f;
        final boolean eqOn = gEqOn; final float eqPre = (float) Math.pow(10f, gEqPre / 20f); if (eqOn) eqPrep(s, sr);
        final boolean studio = studioOn(); final boolean compOn = studio && gCompOn; StC sc = null; if (studio) { sc = new StC(); stPrep(sc, sr); }
        int ed = (int) (0.27 * sr); if (ed >= Vs.RYM_ECHO) ed = Vs.RYM_ECHO - 1;
        final float rph = (float) (2.0 * Math.PI * 70.0 / sr);
        for (int i = 0; i < n; i++) {
            float in = x[off + i] * gain;
            { float ag = in < 0 ? -in : in; s.gate += (ag - s.gate) * (ag > s.gate ? 0.03f : 0.0006f);
              float gthr = 0.006f; if (s.gate < gthr) { float r = s.gate / gthr; in *= r * r; } }
            if (studio) in = studioIn(in, s, sc); float y = in;
            if (pitch) {
                s.hist[s.w & (Vs.RYM_BUF - 1)] = in;
                float acc = 0;
                for (int k = 0; k < 2; k++) {
                    float d = (k == 0) ? s.d0 : s.d1; int di = (int) d; float fr = d - (float) di;
                    float v = s.hist[(s.w - di) & (Vs.RYM_BUF - 1)] * (1f - fr) + s.hist[(s.w - di - 1) & (Vs.RYM_BUF - 1)] * fr;
                    acc += v * (0.5f - 0.5f * (float) Math.cos(TWO_PI * (d / (float) W)));
                    d += dd; if (d >= (float) W) d -= (float) W; else if (d < 0f) d += (float) W;
                    if (k == 0) s.d0 = d; else s.d1 = d;
                }
                y = acc; s.w++;
            }
            if (mode == 1) { float m = (float) Math.sin(s.ph); s.ph += rph; if (s.ph > TWO_PI) s.ph -= TWO_PI; y = y * (0.25f + 0.75f * m) * 1.35f; }
            else if (mode == 2) { float hp = 0.95f * (s.hpy + y - s.hpx); s.hpx = y; s.hpy = hp; s.lp += 0.4f * (hp - s.lp); y = (float) Math.tanh(s.lp * 3.0f); }
            if (gMicDist) y = dist(y, s);
            if (revOn) y = rev(y, s, rc);
            if (echo > 0.01f) { float e = s.eb[(s.ew - ed) & (Vs.RYM_ECHO - 1)]; s.eb[s.ew & (Vs.RYM_ECHO - 1)] = y + e * 0.25f; s.ew++; y += e * echo * 0.55f; }
            if (eqOn) y = eqRun(y, s, eqPre);
            if (compOn) y = comp(y, s, sc);
            if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
            x[off + i] = y;
        }
    }

    // ================= the mic-chain orchestrator (RYMicFx) =================
    // t[] holds channel-0 float mic samples for one block; mutated in place.
    public static void micFx(float[] t, int off, int c, double sr, Vs s) {
        Monitor.noteRate(sr);
        if (Stereo.isOn()) {
            float[] tmp = Stereo.scratch(c);
            System.arraycopy(t, off, tmp, 0, c);          // tmp = your real voice
            voiceBlock(tmp, 0, c, sr, s);                 // (silence if muted)
            Stereo.feed(t, off, c, sr);                   // t[] = the Stereo file, full clarity
            if (gRevSong > 0) cavern(t, off, c, sr, songVs, gRevSong);
            if (Stereo.monWanted() && !gMonOn) Monitor.push(t, off, c, sr);
            for (int i = 0; i < c; i++) { float v = t[off + i] + tmp[i] * 0.55f; t[off + i] = v > 1f ? 1f : (v < -1f ? -1f : v); }
        } else if (gMicMute) {
            for (int i = 0; i < c; i++) t[off + i] = 0f;
        } else if (fxOn()) {
            voiceBlock(t, off, c, sr, s);
        }
        if (gMonOn) Monitor.feed(t, off, c, sr);
        if (Recorder.on) Recorder.mic(t, off, c, sr);
    }

    // ---- setters mirroring the iOS RYMic* helpers (persist to Cfg) ----
    public static void setMicOn(boolean on) { gMicOn = on; com.eightubah.mod.Cfg.setB("micOn", on); }
    public static void setDist(boolean on) { gMicDist = on; com.eightubah.mod.Cfg.setB("micDist", on); }
    public static void setRevMic(int v) { if (v < 0) v = 0; if (v > 100) v = 100; gRevMic = v; com.eightubah.mod.Cfg.setI("revMic", v); }
    public static void setRevSong(int v) { if (v < 0) v = 0; if (v > 100) v = 100; gRevSong = v; com.eightubah.mod.Cfg.setI("revSong", v); }
    public static void setMute(boolean on) { gMicMute = on; } // not persisted, like iOS
    public static void setEqOn(boolean on) { gEqOn = on; gEqVer++; com.eightubah.mod.Cfg.setB("eqOn", on); }
    public static void applyPreset(int idx) {
        if (idx < 0 || idx >= N_PRESETS) return; Preset p = kPresets[idx];
        gMicPreset = idx; gMicSemi = p.semi; gMicMode = p.mode; gMicEcho = p.echo;
        com.eightubah.mod.Cfg.setI("micPreset", idx); com.eightubah.mod.Cfg.setF("micSemi", p.semi);
        com.eightubah.mod.Cfg.setI("micMode", p.mode); com.eightubah.mod.Cfg.setF("micEcho", p.echo);
    }
}

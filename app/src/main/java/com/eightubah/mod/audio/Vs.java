package com.eightubah.mod.audio;

/**
 * Per-route voice-processing state. Direct port of the iOS RYVS struct.
 * One instance per capture route (AudioRecord tap, AudioTrack monitor, song).
 * Powers-of-two ring buffers are indexed with int & (SIZE-1); Java two's
 * complement makes that wrap correctly, same as the C unsigned indices.
 */
public final class Vs {
    public static final int RYM_BUF = 8192;     // pitch history (power of two)
    public static final int RYM_ECHO = 32768;   // echo line (power of two)

    public final float[] hist = new float[RYM_BUF];
    public int w;
    public float d0, d1;

    public final float[] eb = new float[RYM_ECHO];
    public int ew;
    public float ph, hpx, hpy, lp;
    public double sr;

    public float dcx, dcy, dlp, env;                 // amplifier (distortion) state
    public float gate;                               // noise-gate envelope

    public final float[][] cb = new float[8][4096];  // 8 comb filters
    public final int[] ci = new int[8];
    public final float[] cf = new float[8];
    public final float[][] ab = new float[4][1024];  // 4 all-passes
    public final int[] ai = new int[4];

    public final float[] pre = new float[16384];     // pre-delay
    public int pw;
    public final float[] er = new float[8192];       // early reflections
    public int erw;

    public float rlc, elp, olp, blp, lfo;            // reverb filters + modulation

    public int eqVer; public double eqSr;
    public final float[][] eqc = new float[Dsp.RYEQ_N][5];   // biquad coeffs b0 b1 b2 a1 a2
    public final float[][] eqz = new float[Dsp.RYEQ_N][2];   // biquad state
    public final boolean[] eqAct = new boolean[Dsp.RYEQ_N];

    public float stEnv, stFloor, stGate, stOpen, stFast, stSlow, stHx, stCe, stInit; // studio chain

    /** memset(s,0,sizeof) + set sr — matches the C re-init when the rate changes. */
    public void reset(double sampleRate) {
        java.util.Arrays.fill(hist, 0f); w = 0; d0 = 0; d1 = 0;
        java.util.Arrays.fill(eb, 0f); ew = 0; ph = hpx = hpy = lp = 0;
        dcx = dcy = dlp = env = 0; gate = 0;
        for (float[] c : cb) java.util.Arrays.fill(c, 0f);
        java.util.Arrays.fill(ci, 0); java.util.Arrays.fill(cf, 0f);
        for (float[] a : ab) java.util.Arrays.fill(a, 0f);
        java.util.Arrays.fill(ai, 0);
        java.util.Arrays.fill(pre, 0f); pw = 0;
        java.util.Arrays.fill(er, 0f); erw = 0;
        rlc = elp = olp = blp = lfo = 0;
        eqVer = 0; eqSr = 0;
        for (float[] c : eqc) java.util.Arrays.fill(c, 0f);
        for (float[] z : eqz) java.util.Arrays.fill(z, 0f);
        java.util.Arrays.fill(eqAct, false);
        stEnv = stFloor = stGate = stOpen = stFast = stSlow = stHx = stCe = stInit = 0;
        sr = sampleRate;
    }
}

package com.eightubah.mod.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.eightubah.mod.XEntry;

/**
 * Local playback ("hear my voice" + the Stereo "hear it myself"). A ring buffer
 * is filled by the capture hook and drained by an AudioTrack on its own thread.
 *
 * The AudioTrack sample rate MUST match the rate the mic data was captured at,
 * or playback runs fast/slow and aliases into harsh noise. WebRTC can change the
 * capture rate (and it isn't known the instant the engine starts), so the drain
 * thread owns the track and rebuilds it whenever the live rate changes.
 */
public final class Monitor {
    private Monitor() {}

    private static final int RING = 65536;
    private static final float[] ring = new float[RING];
    private static volatile int w = 0, r = 0;
    private static volatile double sr = 48000;

    private static volatile AudioTrack track;
    private static volatile Thread thread;
    private static volatile boolean running;

    static void noteRate(double s) { if (s > 0) sr = s; }

    static void push(float[] x, int off, int n, double s) {
        if (s > 0) sr = s;
        int wi = w;
        for (int i = 0; i < n; i++) ring[(wi + i) & (RING - 1)] = x[off + i];
        w = wi + n;
    }
    static void feed(float[] x, int off, int n, double s) { if (!Dsp.gMonOn) return; push(x, off, n, s); }
    static void resetRead() { r = w; }

    public static void start() { Dsp.gMonOn = true; r = w; ensureEngine(); }
    public static void stop() {
        Dsp.gMonOn = false;
        if (Stereo.monWanted() && Stereo.isOn()) return; // stereo monitor still needs the engine
        stopEngine();
    }

    private static boolean wantOn() { return Dsp.gMonOn || (Stereo.monWanted() && Stereo.isOn()); }

    static synchronized void ensureEngine() {
        if (running || !wantOn()) return;
        running = true;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, "8ubah.mon");
        thread.start();
    }

    private static int clampRate(double s) {
        int v = (int) (s > 0 ? s : 48000);
        if (v < 8000) v = 8000; if (v > 48000) v = 48000;
        return v;
    }

    private static AudioTrack build(int rate) {
        try {
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = rate / 5 * 2;
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, rate / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            t.play();
            return t;
        } catch (Throwable e) { XEntry.log("monitor build: %s", e); return null; }
    }

    private static void loop() {
        AudioTrack t = null;
        int rate = 0;
        short[] out = new short[1024];
        boolean primed = false;
        try {
            while (running && wantOn()) {
                int want = clampRate(sr);
                if (t == null || Math.abs(want - rate) > 50) {   // (re)build when the rate changes
                    if (t != null) { try { t.stop(); } catch (Throwable e) {} try { t.release(); } catch (Throwable e) {} }
                    rate = want; t = build(rate);
                    if (t == null) break;
                    track = t; r = w; primed = false;
                }
                int wi = w, ri = r, avail = wi - ri;
                if (!primed) { if (avail < rate / 40) { sleep(); continue; } primed = true; }   // ~25 ms prime
                if (avail > rate / 5) { ri = wi - rate / 20; avail = rate / 20; }               // behind: jump to ~50 ms
                int take = Math.min(avail, out.length);
                if (take <= 0) { sleep(); continue; }
                for (int i = 0; i < take; i++) {
                    float v = ring[(ri + i) & (RING - 1)];
                    v = v > 1f ? 1f : (v < -1f ? -1f : v);
                    out[i] = (short) (v * 32767f);
                }
                r = ri + take;
                try { t.write(out, 0, take); } catch (Throwable e) { break; }
            }
        } finally {
            if (t != null) { try { t.stop(); } catch (Throwable e) {} try { t.release(); } catch (Throwable e) {} }
            track = null; running = false;
        }
    }

    private static void sleep() { try { Thread.sleep(3); } catch (InterruptedException e) {} }

    static synchronized void stopEngine() {
        running = false;
        Thread th = thread; thread = null;
        if (th != null) { try { th.join(80); } catch (InterruptedException e) {} }
    }
}

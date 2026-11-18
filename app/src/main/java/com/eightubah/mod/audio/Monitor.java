package com.eightubah.mod.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.eightubah.mod.XEntry;

/**
 * Local playback ("hear my voice" + the Stereo "hear it myself"). Port of the
 * iOS RYMon* engine: a ring buffer fed by the capture hook, drained by an
 * AudioTrack on its own thread.
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

    static void noteRate(double s) { sr = s; } // remembered even while off

    static void push(float[] x, int off, int n, double s) {
        sr = s; int wi = w;
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

    static synchronized void ensureEngine() {
        if (running || !(Dsp.gMonOn || (Stereo.monWanted() && Stereo.isOn()))) return;
        final int rate = (int) (sr > 0 ? sr : 48000);
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
            track = t; running = true; r = w;
            t.play();
            thread = new Thread(new Runnable() { public void run() { drain(rate); } }, "8ubah.mon");
            thread.start();
        } catch (Throwable e) { XEntry.log("monitor start: %s", e); running = false; Dsp.gMonOn = false; }
    }

    private static void drain(int rate) {
        short[] out = new short[1024]; boolean primed = false;
        while (running) {
            int wi = w, ri = r, avail = wi - ri;
            if (!primed) { if (avail < 1200) { sleep(); continue; } primed = true; }
            if (avail > 9600) { ri = wi - 2400; avail = 2400; }     // fell behind: jump to ~50 ms
            int take = Math.min(avail, out.length);
            if (take <= 0) { sleep(); continue; }
            for (int i = 0; i < take; i++) {
                float v = ring[(ri + i) & (RING - 1)];
                v = v > 1f ? 1f : (v < -1f ? -1f : v);
                out[i] = (short) (v * 32767f);
            }
            r = ri + take;
            AudioTrack t = track; if (t == null) break;
            try { t.write(out, 0, take); } catch (Throwable e) { break; }
        }
    }
    private static void sleep() { try { Thread.sleep(3); } catch (InterruptedException e) {} }

    static synchronized void stopEngine() {
        running = false;
        Thread th = thread; thread = null;
        AudioTrack t = track; track = null;
        if (th != null) { try { th.join(50); } catch (InterruptedException e) {} }
        if (t != null) { try { t.stop(); } catch (Throwable e) {} try { t.release(); } catch (Throwable e) {} }
    }
}

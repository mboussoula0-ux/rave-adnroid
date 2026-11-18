package com.eightubah.mod.audio;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.XEntry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Stereo: play an imported audio/video file through the mic instead of the real
 * voice. Port of the iOS RYSt* code. Decode is done with MediaExtractor +
 * MediaCodec (any format Android can open), to mono float PCM, 4-minute cap.
 */
public final class Stereo {
    private Stereo() {}

    private static volatile float[] buf;   // mono float PCM
    private static volatile int len;
    private static volatile double pos, rate = 48000;
    private static volatile boolean on, loop, loading;
    private static volatile float vol = 1f;
    private static volatile boolean mon = true;  // "hear it myself"

    public interface Done { void run(boolean ok, String msg); }

    public static boolean isOn() { return on; }
    public static boolean monWanted() { return mon; }
    public static boolean isLoading() { return loading; }
    public static boolean loaded() { return buf != null && len > 1; }
    public static boolean loopOn() { return loop; }
    public static float vol() { return vol; }

    public static void setLoop(boolean v) { loop = v; Cfg.b("stLoop", v); }
    public static void setVol(float v) { vol = v; Cfg.f("stVol", v); }
    public static void stop() { on = false; }
    public static double duration() { return len > 1 ? (double) len / rate : 0; }
    public static double posSec() { return rate > 0 ? pos / rate : 0; }
    public static void seek(double seconds) {
        if (len <= 1) return; double p = seconds * rate; if (p < 0) p = 0; if (p > len - 1) p = len - 1; pos = p;
    }
    public static void free() { buf = null; len = 0; pos = 0; on = false; }

    public static void play() { pos = 0; Monitor.resetRead(); on = true; if (mon) Monitor.ensureEngine(); }
    public static void setMon(boolean v) {
        mon = v; Cfg.b("stMon", v);
        if (v && on) { Monitor.resetRead(); Monitor.ensureEngine(); }
        else if (!v && !Dsp.gMonOn) Monitor.stopEngine();
    }

    private static final ThreadLocal<float[]> SCRATCH = new ThreadLocal<>();
    static float[] scratch(int n) {
        float[] s = SCRATCH.get();
        if (s == null || s.length < n) { s = new float[Math.max(n, 2048)]; SCRATCH.set(s); }
        return s;
    }

    /** linear-resampled playback into the mic buffer t[] (RYStFeed). */
    static void feed(float[] t, int off, int c, double sr) {
        float[] b = buf; int L = len;
        if (b == null || L <= 1) { on = false; return; }
        double step = rate / (sr > 0 ? sr : rate);
        for (int i = 0; i < c; i++) {
            if (!on) { t[off + i] = 0f; continue; }
            long i0 = (long) pos;
            if (i0 >= L - 1) { if (loop) { pos = 0; i0 = 0; } else { on = false; t[off + i] = 0f; continue; } }
            double frac = pos - (double) i0;
            float a = b[(int) i0], bb = b[(int) Math.min(i0 + 1, L - 1)];
            t[off + i] = (a + (bb - a) * (float) frac) * vol;
            pos += step;
        }
    }

    /** Decode any file to mono float PCM off the audio thread. */
    public static void decode(final String path, final Done done) {
        loading = true;
        new Thread(new Runnable() { public void run() {
            MediaExtractor ex = null; MediaCodec dec = null;
            try {
                ex = new MediaExtractor(); ex.setDataSource(path);
                int track = -1; MediaFormat fmt = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
                }
                if (track < 0) { finish(done, false, com.eightubah.mod.L.t("ما فيه صوت بالملف", "No audio track in this file"), null, 0, 0); return; }
                ex.selectTrack(track);
                int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                dec.configure(fmt, null, null, 0); dec.start();

                long cap = (long) (srcRate * 240L); // 4 minutes at source rate, mono
                float[] out = new float[(int) Math.min(cap, Integer.MAX_VALUE - 8)];
                int n = 0; boolean truncated = false;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inEos = false;
                while (true) {
                    if (!inEos) {
                        int inIx = dec.dequeueInputBuffer(10000);
                        if (inIx >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(inIx);
                            int sz = ex.readSampleData(ib, 0);
                            if (sz < 0) { dec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                            else { dec.queueInputBuffer(inIx, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    int outIx = dec.dequeueOutputBuffer(info, 10000);
                    if (outIx >= 0) {
                        ByteBuffer ob = dec.getOutputBuffer(outIx);
                        if (info.size > 0 && ob != null) {
                            ob.order(ByteOrder.LITTLE_ENDIAN); ob.position(info.offset); ob.limit(info.offset + info.size);
                            ShortBuffer sb = ob.asShortBuffer();
                            int frames = sb.remaining() / Math.max(1, ch);
                            for (int fI = 0; fI < frames && n < out.length; fI++) {
                                float acc = 0; for (int cc = 0; cc < ch; cc++) acc += sb.get() / 32768f;
                                out[n++] = acc / ch;
                            }
                            if (n >= out.length) truncated = true;
                        }
                        dec.releaseOutputBuffer(outIx, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || n >= out.length) break;
                    } else if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    } else if (outIx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (inEos) { /* keep draining */ }
                    }
                }
                final int flen = n; final double frate = srcRate; final boolean ftrunc = truncated;
                final float[] fbuf = out;
                finish(done, flen > 0, flen > 0 ? (ftrunc ? com.eightubah.mod.L.t("جاهز (انقص لأول 4 دقايق)", "Ready (trimmed to the first 4 minutes)") : com.eightubah.mod.L.t("جاهز", "Ready"))
                        : com.eightubah.mod.L.t("تعذرت القراءة", "Could not decode"), fbuf, flen, frate);
            } catch (Throwable t) {
                XEntry.log("stereo decode: %s", t);
                finish(done, false, com.eightubah.mod.L.t("تعذرت القراءة", "Could not decode"), null, 0, 0);
            } finally {
                try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable t) {}
                try { if (ex != null) ex.release(); } catch (Throwable t) {}
            }
        }}).start();
    }

    private static void finish(final Done done, final boolean ok, final String msg, final float[] b, final int l, final double r) {
        XEntry.main(new Runnable() { public void run() {
            loading = false;
            if (ok && b != null && l > 0) { free(); buf = b; len = l; pos = 0; rate = r > 0 ? r : 48000; }
            if (done != null) done.run(ok, msg);
        }});
    }

    public static void load() {
        loop = Cfg.b("stLoop", false); vol = Cfg.f("stVol", 1f); mon = Cfg.b("stMon", true);
    }
}

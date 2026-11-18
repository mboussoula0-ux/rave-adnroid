package com.eightubah.mod.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.eightubah.mod.Cfg;
import com.eightubah.mod.L;
import com.eightubah.mod.XEntry;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * In-app audio recorder. The system screen recorder on both iOS and Android
 * tends to leave the VoIP call audio out; we already see both streams in this
 * module (your mic after effects via the AudioRecord tap, and everyone else's
 * voices via the AudioTrack tap), so we mix them to a 48 kHz mono .m4a.
 * Video: use the phone's built-in screen recorder alongside this.
 * Port of the iOS recorder's audio path (RYRec*).
 */
public final class Recorder {
    private Recorder() {}

    public static volatile boolean on = false;
    public static volatile boolean micOn = true;
    public static volatile long micN = 0, playN = 0;
    public static volatile double startWall = 0;

    private static final int N = 1 << 18;
    private static final float[] ringM = new float[N], ringP = new float[N];
    private static volatile int wM = 0, rM = 0, wP = 0, rP = 0;
    private static final Rs rsM = new Rs(), rsP = new Rs();
    private static final class Rs { double pos; float last; }

    private static MediaCodec enc; private static MediaMuxer muxer; private static int trackIdx = -1;
    private static boolean muxing; private static Thread feeder; private static File file;
    private static long doneSamples; private static double t0;

    public interface Start { void run(String err); }
    public interface Stop { void run(File f, String err); }

    private static void push(float[] ring, Rs st, int[] wRef, final float[] x, int off, int n, double sr) {
        if (n <= 0) return; if (sr < 4000) sr = 48000;
        double step = sr / 48000.0, pos = st.pos; float last = st.last; int wi = wRef[0];
        while (pos < (double) (n - 1)) {
            int i = (int) Math.floor(pos); float a = i < 0 ? last : x[off + i], b = x[off + i + 1], fr = (float) (pos - (double) i);
            ring[wi & (N - 1)] = a + (b - a) * fr; wi++; pos += step;
        }
        st.pos = pos - (double) n; st.last = x[off + n - 1]; wRef[0] = wi;
    }
    private static final int[] wMRef = {0}, wPRef = {0};

    public static void mic(float[] x, int off, int n, double sr) {
        if (!on || !micOn) return; micN += n; wMRef[0] = wM; push(ringM, rsM, wMRef, x, off, n, sr); wM = wMRef[0];
    }
    public static void voices(float[] x, int off, int n, double sr) {
        if (!on) return; playN += n; wPRef[0] = wP; push(ringP, rsP, wPRef, x, off, n, sr); wP = wPRef[0];
    }

    public static void start(Start done) {
        if (on) { done.run(null); return; }
        try {
            File dir = Cfg.dir("rec");
            file = new File(dir, "rec-" + System.currentTimeMillis() + ".m4a");
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxing = false; trackIdx = -1; doneSamples = 0;
            micN = playN = 0; rM = wM; rP = wP; rsM.pos = rsP.pos = 0;
            on = true; startWall = now(); t0 = now();
            feeder = new Thread(new Runnable() { public void run() { loop(); } }, "8ubah.rec"); feeder.start();
            done.run(null);
        } catch (Throwable t) { XEntry.log("rec start: %s", t); on = false; cleanup(); done.run(L.t("ما قدرت أبدأ التسجيل", "Could not start recording")); }
    }

    public static void stop(Stop done) {
        if (!on) { done.run(null, null); return; }
        on = false;
        Thread f = feeder; feeder = null;
        if (f != null) { try { f.join(500); } catch (InterruptedException e) {} }
        File out = file; boolean ok = false;
        try { drainEncoder(true); if (muxing) { muxer.stop(); } ok = true; } catch (Throwable t) { XEntry.log("rec stop: %s", t); }
        cleanup();
        if (ok && out != null && out.exists()) done.run(out, null);
        else done.run(null, L.t("فشل الحفظ", "Save failed"));
    }

    private static void cleanup() {
        try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable t) {} enc = null;
        try { if (muxer != null) muxer.release(); } catch (Throwable t) {} muxer = null; muxing = false; trackIdx = -1;
    }

    private static void loop() {
        short[] out = new short[4800];
        while (on) {
            long target = (long) ((now() - t0 - 0.1) * 48000.0), want = target - doneSamples;
            if (want < 480) { sleep(10); continue; }
            if (want > 4800) want = 4800;
            int rm = rM, rp = rP, wm = wM, wp = wP;
            if (wm - rm > 19200) rm = wm - 4800; if (wp - rp > 19200) rp = wp - 4800;
            for (int i = 0; i < want; i++) {
                float v = 0; if (rm != wm) { v += ringM[rm & (N - 1)]; rm++; } if (rp != wp) { v += ringP[rp & (N - 1)]; rp++; }
                v = v > 1f ? 1f : (v < -1f ? -1f : v); out[i] = (short) (v * 32767f);
            }
            rM = rm; rP = rp;
            feedEncoder(out, (int) want);
            doneSamples += want;
            drainEncoder(false);
        }
    }

    private static void feedEncoder(short[] pcm, int frames) {
        try {
            int ix = enc.dequeueInputBuffer(5000);
            if (ix < 0) return;
            ByteBuffer ib = enc.getInputBuffer(ix); ib.clear();
            ib.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) ib.putShort(pcm[i]);
            long pts = (long) (doneSamples * 1_000_000.0 / 48000.0);
            enc.queueInputBuffer(ix, 0, frames * 2, pts, 0);
        } catch (Throwable t) { /* */ }
    }

    private static void drainEncoder(boolean end) {
        if (enc == null) return;
        try {
            if (end) { int ix = enc.dequeueInputBuffer(5000); if (ix >= 0) enc.queueInputBuffer(ix, 0, 0, (long) (doneSamples * 1_000_000.0 / 48000.0), MediaCodec.BUFFER_FLAG_END_OF_STREAM); }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int ox = enc.dequeueOutputBuffer(info, end ? 5000 : 0);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxing) { trackIdx = muxer.addTrack(enc.getOutputFormat()); muxer.start(); muxing = true; }
                } else if (ox >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(ox);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxing && ob != null) { ob.position(info.offset); ob.limit(info.offset + info.size); muxer.writeSampleData(trackIdx, ob, info); }
                    enc.releaseOutputBuffer(ox, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else break;
            }
        } catch (Throwable t) { /* */ }
    }

    private static double now() { return System.nanoTime() / 1e9; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
}

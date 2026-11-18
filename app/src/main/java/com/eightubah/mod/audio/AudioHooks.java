package com.eightubah.mod.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;

import com.eightubah.mod.XEntry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Taps the Android audio framework. Rave captures the mic through
 * WebRtcAudioRecord -> AudioRecord.read(ByteBuffer) and plays the room through
 * WebRtcAudioTrack -> AudioTrack.write. Hooking those two framework methods is
 * version-independent and the exact analog of the iOS capture/playout taps:
 *   - AudioRecord.read  (afterHook): rewrite the mic PCM in place (voice changer,
 *     EQ, cavern, stereo file, monitor, recorder-mic).
 *   - AudioTrack.write  (beforeHook): copy the room voices for the recorder.
 */
public final class AudioHooks {
    private AudioHooks() {}

    private static final Vs micVs = new Vs();
    public static volatile long frames = 0;   // mic frames processed (for the status line)
    public static volatile long voiceFrames = 0;
    // per-thread read nesting depth, so a delegated read isn't processed twice
    private static final ThreadLocal<int[]> readDepth = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[1]; }
    };
    private static final ThreadLocal<float[]> TL = new ThreadLocal<>();
    private static float[] tl(int n) { float[] a = TL.get(); if (a == null || a.length < n) { a = new float[Math.max(n, 2048)]; TL.set(a); } return a; }

    private static int diagN = 0;
    private static long diagT0 = 0, diagFr = 0; private static boolean diagRate = false;

    public static void install() {
        MicSettings.load();
        com.eightubah.mod.Diag.reset();
        com.eightubah.mod.Diag.log("audio hooks installed");

        XC_MethodHook micHook = new XC_MethodHook() {
            // AudioRecord.read(bb,size) delegates to read(bb,size,mode); both are hooked,
            // so without this guard each physical read is processed TWICE (2x speed/noise).
            // Count nesting and process only at the outermost read.
            @Override protected void beforeHookedMethod(MethodHookParam p) { readDepth.get()[0]++; }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                int[] dpt = readDepth.get(); if (--dpt[0] != 0) return;
                try {
                    Object res = p.getResult(); if (!(res instanceof Integer)) return;
                    int bytes = (Integer) res; if (bytes <= 0) return;
                    if (!(Dsp.fxOn() || Dsp.gMonOn || Recorder.on)) return;
                    AudioRecord ar = (AudioRecord) p.thisObject;
                    if (ar.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) return;
                    Object buf = p.args[0];
                    if (buf instanceof ByteBuffer) processMicBB(ar, (ByteBuffer) buf, bytes);
                    else if (buf instanceof short[]) processMicShorts(ar, (short[]) buf, intArg(p, 1), bytes / 2);
                } catch (Throwable t) { XEntry.log("mic read hook: %s", t); }
            }
        };
        hook(AudioRecord.class, "read", micHook, ByteBuffer.class, int.class);
        hook(AudioRecord.class, "read", micHook, ByteBuffer.class, int.class, int.class);
        hook(AudioRecord.class, "read", micHook, short[].class, int.class, int.class);

        XC_MethodHook trackHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!Recorder.on) return;
                    AudioTrack at = (AudioTrack) p.thisObject;
                    if (at.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) return;
                    Object buf = p.args[0];
                    if (buf instanceof ByteBuffer) capVoicesBB(at, (ByteBuffer) buf, intArg(p, 1));
                    else if (buf instanceof short[]) capVoicesShorts(at, (short[]) buf, intArg(p, 1), intArg(p, 2));
                } catch (Throwable t) { /* */ }
            }
        };
        hook(AudioTrack.class, "write", trackHook, ByteBuffer.class, int.class, int.class);
        hook(AudioTrack.class, "write", trackHook, short[].class, int.class, int.class);

        installIso();
    }

    private static int intArg(XC_MethodHook.MethodHookParam p, int i) {
        try { return ((Number) p.args[i]).intValue(); } catch (Throwable t) { return 0; }
    }

    private static void processMicBB(AudioRecord ar, ByteBuffer bb, int bytes) {
        int ch = Math.max(1, ar.getChannelCount()); double sr = ar.getSampleRate();
        int frames = (bytes / 2) / ch; if (frames <= 0) return;
        if (diagN < 24) { diagN++; com.eightubah.mod.Diag.log("mic(bb) sr=%d ch=%d bytes=%d frames=%d fx=%b stereo=%b eq=%b micOn=%b echo=%.2f rev=%d",
                (int) sr, ch, bytes, frames, Dsp.fxOn(), Stereo.isOn(), Dsp.gEqOn, Dsp.gMicOn, Dsp.gMicEcho, Dsp.gRevMic);
            if (diagN == 1) com.eightubah.mod.Diag.toast("mic sr=" + (int) sr + " ch=" + ch + " frames=" + frames); }
        ByteBuffer d = bb.duplicate(); d.order(ByteOrder.LITTLE_ENDIAN); d.clear(); d.limit(bytes);
        ShortBuffer sb = d.asShortBuffer();
        float[] tmp = tl(frames);
        for (int f = 0; f < frames; f++) tmp[f] = sb.get(f * ch) / 32768f;
        Dsp.micFx(tmp, 0, frames, sr, micVs); AudioHooks.frames += frames;
        if (diagT0 == 0) diagT0 = System.currentTimeMillis(); diagFr += frames;
        if (!diagRate) { long el = System.currentTimeMillis() - diagT0; if (el >= 2500) { diagRate = true;
            com.eightubah.mod.Diag.toast("mic throughput=" + (diagFr * 1000 / Math.max(1, el)) + "/s expect=" + (int) sr); } }
        for (int f = 0; f < frames; f++) {
            float v = tmp[f] * 32767f; short q = (short) (v > 32767f ? 32767 : v < -32768f ? -32768 : v);
            for (int c = 0; c < ch; c++) sb.put(f * ch + c, q);
        }
    }

    private static void processMicShorts(AudioRecord ar, short[] s, int off, int samples) {
        int ch = Math.max(1, ar.getChannelCount()); double sr = ar.getSampleRate();
        int frames = samples / ch; if (frames <= 0) return;
        float[] tmp = tl(frames);
        for (int f = 0; f < frames; f++) tmp[f] = s[off + f * ch] / 32768f;
        Dsp.micFx(tmp, 0, frames, sr, micVs);
        for (int f = 0; f < frames; f++) {
            float v = tmp[f] * 32767f; short q = (short) (v > 32767f ? 32767 : v < -32768f ? -32768 : v);
            for (int c = 0; c < ch; c++) s[off + f * ch + c] = q;
        }
    }

    private static void capVoicesBB(AudioTrack at, ByteBuffer bb, int sizeBytes) {
        int ch = Math.max(1, at.getChannelCount()); double sr = at.getSampleRate();
        int frames = (sizeBytes / 2) / ch; if (frames <= 0) return;
        ByteBuffer d = bb.duplicate(); d.order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer sb = d.asShortBuffer(); // from current position
        float[] tmp = tl(frames);
        for (int f = 0; f < frames; f++) { float s = 0; for (int c = 0; c < ch; c++) s += sb.get(f * ch + c) / 32768f; tmp[f] = s / ch; }
        Recorder.voices(tmp, 0, frames, sr);
    }
    private static void capVoicesShorts(AudioTrack at, short[] s, int off, int sizeShorts) {
        int ch = Math.max(1, at.getChannelCount()); double sr = at.getSampleRate();
        int frames = sizeShorts / ch; if (frames <= 0) return;
        float[] tmp = tl(frames);
        for (int f = 0; f < frames; f++) { float v = 0; for (int c = 0; c < ch; c++) v += s[off + f * ch + c] / 32768f; tmp[f] = v / ch; }
        Recorder.voices(tmp, 0, frames, sr);
    }

    // ---- isolation removal: kill ALL mic voice-processing, permanently ----
    // Three layers duck/mute the mic when audio plays or the voice is loud or
    // effected: (1) the hardware AEC/NS/AGC effects, (2) the OS VOICE_COMMUNICATION
    // preprocessing WebRTC asks for, (3) WebRTC's own software APM (echo/AGC/NS via
    // MediaConstraints). We disable all three unconditionally so the mic is raw.
    private static final XC_MethodHook FALSE_ARG0 = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            if (Boolean.TRUE.equals(p.args[0])) { p.args[0] = false; Iso.vpN++; }
        }
    };
    private static final XC_MethodHook RETURN_FALSE = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(false); }
    };

    private static boolean isProcKey(Object k) {
        if (!(k instanceof String)) return false;
        String s = ((String) k).toLowerCase();
        return s.contains("echocancellation") || s.contains("noisesuppression")
                || s.contains("autogaincontrol") || s.contains("highpassfilter")
                || s.contains("typingnoisedetection") || s.contains("audiomirroring");
    }

    private static void installIso() {
        // (1) hardware effects: never enable, and report unavailable so WebRTC skips them
        String[] fx = {"android.media.audiofx.AcousticEchoCanceler",
                       "android.media.audiofx.NoiseSuppressor",
                       "android.media.audiofx.AutomaticGainControl"};
        for (String cn : fx) {
            try {
                Class<?> c = Class.forName(cn);
                try { XposedHelpers.findAndHookMethod(c, "setEnabled", boolean.class, FALSE_ARG0); } catch (Throwable t) { /* */ }
                try { XposedHelpers.findAndHookMethod(c, "isAvailable", RETURN_FALSE); } catch (Throwable t) { /* */ }
            } catch (Throwable t) { /* class may be absent */ }
        }
        // (2) WebRTC device module: no hardware AEC/NS, and capture from the raw MIC
        //     source (1) instead of VOICE_COMMUNICATION, which the OS auto-processes.
        try {
            Class<?> b = XposedHelpers.findClass("org.webrtc.audio.JavaAudioDeviceModule$Builder", XEntry.appCl);
            for (String m : new String[]{"setUseHardwareAcousticEchoCanceler", "setUseHardwareNoiseSuppressor"}) {
                try { XposedHelpers.findAndHookMethod(b, m, boolean.class, FALSE_ARG0); } catch (Throwable t) { /* */ }
            }
            try {
                XposedHelpers.findAndHookMethod(b, "setAudioSource", int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.args[0] = 1; /* MediaRecorder.AudioSource.MIC — raw, unprocessed */ Iso.vpN++;
                    }
                });
            } catch (Throwable t) { /* */ }
        } catch (Throwable t) { /* */ }
        // (3) WebRTC software APM: flip every echo/NS/AGC/highpass constraint to false
        try {
            Class<?> kv = XposedHelpers.findClass("org.webrtc.MediaConstraints$KeyValuePair", XEntry.appCl);
            XposedHelpers.findAndHookConstructor(kv, String.class, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (isProcKey(p.args[0])) { p.args[1] = "false"; Iso.rtcN++; }
                }
            });
        } catch (Throwable t) { /* */ }
    }

    private static void hook(Class<?> c, String m, XC_MethodHook cb, Object... sig) {
        try {
            Object[] a = new Object[sig.length + 1];
            System.arraycopy(sig, 0, a, 0, sig.length); a[sig.length] = cb;
            XposedHelpers.findAndHookMethod(c, m, a);
        } catch (Throwable t) { XEntry.log("hook %s.%s failed: %s", c.getSimpleName(), m, t); }
    }
}

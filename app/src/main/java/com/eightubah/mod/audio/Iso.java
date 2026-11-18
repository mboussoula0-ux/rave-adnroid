package com.eightubah.mod.audio;

import com.eightubah.mod.Cfg;

/**
 * "Remove Rave isolation" flags. On Android the effective lever is the system
 * voice-processing effects (hardware AEC / NS / AGC) that duck or mute a loud or
 * effected voice. The hooks live in AudioHooks; this just holds the flags +
 * counters, mirroring the iOS gIso* state.
 */
public final class Iso {
    private Iso() {}
    public static volatile boolean sess = false; // AudioManager mode (best-effort)
    public static volatile boolean vp = false;   // hardware AEC/NS/AGC (the main lever)
    public static volatile boolean rtc = false;  // WebRTC sw processing (best-effort)
    public static volatile long vpN = 0, rtcN = 0, sessN = 0;

    public static void save() {
        Cfg.b("isoSess", sess); Cfg.b("isoVP", vp); Cfg.b("isoRTC", rtc);
    }
}

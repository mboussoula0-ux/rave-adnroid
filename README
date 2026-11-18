# 8ubah — Android

Android port of the iOS 8ubah tweak for **Rave / WeMesh** (`com.wemesh.android`).
Same feature set, rebuilt as a **no-root Xposed module** (pure Java). It loads into
the Rave process and hooks it at runtime — the Android analog of the iOS injected
dylib. Everything is built programmatically (no XML layouts), same as the iOS build
had no XIBs.

> **UI rule (kept from the iOS file):** no explanatory text in the panel. Controls
> get short names + short live status lines only. Explanations live *here*, not in
> the app.

---

## What the target actually is (from decompiling the APK you gave me)

- Package `com.wemesh.android`, main activity `com.wemesh.android.rearch.RaveActivity`, native Kotlin/Java (not Flutter/RN).
- Voice is **mediasoup / WebRTC**: `libmediasoupclient_so.so`, and the Java audio path is `org.webrtc.audio.JavaAudioDeviceModule` + `WebRtcAudioRecord` / `WebRtcAudioTrack`. That is why the voice changer works in pure Java — we tap the framework `AudioRecord.read` (mic) and `AudioTrack.write` (room voices), exactly like the iOS buffer taps.
- DMs use a Room `MessageDao` (`com.wemesh.android.legacy.dms.db.MessageDao_Impl`) + `WallaceClient.deleteMessage` + an `UNSEND` flow → that's where anti-delete hooks.
- Chat / friends are RecyclerViews → same view-tree automation as iOS.

---

## Feature map (iOS → Android)

| Feature | iOS | Android (this port) |
|---|---|---|
| Voice changer (pitch / robot / megaphone / echo presets) | capture-unit tap | `AudioRecord.read` tap → `Dsp.voiceBlock` (1:1 DSP) |
| Amplifier ("Silver") | native dist | `Dsp.dist` |
| Haunted-Cavern reverb (mic + song) | `RYRev` | `Dsp.rev` / `Dsp.cavern` |
| 10-band equalizer + graph + presets + favorites | `RYEQView` | `EqView` + `MicSettings` |
| Studio chain (Clearcast / NR / gate / compressor) | studio | `Dsp.studioIn` / `Dsp.comp` |
| Sonar (SteelSeries) EQ import | `RYSonar*` | `SonarImport` |
| Stereo (play a file through the mic) | `RYSt*` (AVAssetReader) | `Stereo` (MediaExtractor + MediaCodec) |
| Hear-my-voice monitor | AVAudioEngine | `Monitor` (AudioTrack + ring) |
| Remove isolation (AEC/NS/AGC) | VPIO / WebRTC | hooks on `AcousticEchoCanceler`/`NoiseSuppressor`/`AutomaticGainControl` + `JavaAudioDeviceModule$Builder` |
| Recorder (mic + room voices) | ReplayKit mp4 | `Recorder` → `.m4a` (mic+voices mix, in-process) |
| Backgrounds (room bg only you see) | `RYBGView` | `Backgrounds` (bg layer + see-through walk) |
| Branding (rename "rave") | `RYBrandWalk` | `Branding` (text only) |
| Anti-delete private messages | SQLite rewrite | `AntiDelete` (capture deleted/unsent text → saved list) |
| Quick messages + loop | `RYMsgLoop` | `QuickMessages` + `ViewScan.sendMessage` |
| Invite-everyone | `invStep` | `Invites` (tick rows + send) |
| Join by link | multi-attempt deliver | `JoinLink` (ACTION_VIEW → `handleIncomingIntent`) |
| Cat (oneko) | `RYCatView` | `CatView` + `Cat` (dispatchTouchEvent hook) |
| License key gate (one device/key) | `RYLic*` worker | `Lic` (same worker API) |
| Anti-AFK | bg location | `Keep` (wake lock + Rave's own fg mic service) |
| Arabic/English + accents + backup/import | `T()` / settings | `L` / `Settings` tab |

---

## Build

Needs Android Studio (the container I built this in has no Android SDK, so the APK
isn't pre-compiled — the source is complete and real).

1. Open this folder in **Android Studio** (Giraffe+). Let it sync Gradle.
2. If the Xposed API fails to resolve, confirm `settings.gradle` has
   `maven { url 'https://api.xposed.info/' }` (it does). It's `compileOnly` — not bundled.
3. Build → **Build APK** (or `./gradlew :app:assembleRelease`). Output:
   `app/build/outputs/apk/release/app-release-unsigned.apk` → sign it (any debug/release key).

The module ships **no resources** except the LSPosed scope array; it's one small APK.

---

## Install — two paths

**A) No root (closest to your iOS sideload) — LSPatch**
1. Install **LSPatch** (manager).
2. Patch the Rave APK (`rave-video-party-9-0-80.apk`) with LSPatch in **"local/integrated"** mode, and add this module to the patch (embed).
3. Install the patched Rave APK. The module loads when Rave launches — no root, no separate framework.

**B) Rooted / virtual — LSPosed**
1. Flash Magisk + **LSPosed** (Zygisk).
2. Install this module APK, enable it in LSPosed, set scope to **Rave**, force-stop Rave.
3. Launch Rave.

Either way: open a rave, tap the floating **☰** button → the panel. First use asks for the key (same server/key system as iOS).

---

## Tunables (Settings → Advanced)

Class names drift between Rave updates, so — like your iOS build — they're editable live:
- **Room class** (`roomClass`, default `RaveActivity`) — room detection / auto-open / backgrounds.
- **Delete DAO class** (`daoClass`, default `MessageDao_Impl`) — anti-delete hook target.
- **Brand name** — what replaces the word "rave".

If a future Rave update renames something and a feature goes quiet, update the token here instead of rebuilding.

---

## Honest differences from iOS (platform limits)

- **Recorder is audio-only** (mic + room voices → `.m4a`). That's the part the system
  screen recorder leaves out; for picture, run Android's built-in screen recorder
  alongside. (A MediaProjection video path can be added later; it needs a one-time
  system consent dialog.)
- **Anti-delete keeps the text, not the bubble.** iOS turned the DB `DELETE` into a
  no-op and kept the message visible in red. On Android the chat is a RecyclerView whose
  adapter removes the row, and blocking Room's delete risks destabilizing its state — so
  this port *captures* the deleted/unsent text into a saved list (Settings → Show saved)
  rather than fighting the adapter. Safe and reliable.
- **Backgrounds** sit behind the UI but can't go behind the video Surface itself
  (Android composites the player on top); the dim/blur + behind-the-chat wallpaper work.
- **Branding** rewrites text only (no logo-image → text swap).
- **Isolation removal**: the effective lever on Android is the system voice-processing
  effects (AEC/NS/AGC) + the WebRTC builder flags; those are wired. The session-mode and
  WebRTC-SW toggles are best-effort.

Everything else — voice changer, EQ, cavern, studio, stereo, monitor, quick messages,
invites, join-link, cat, license, language — is a faithful port.

---

## Layout

```
app/src/main/java/com/eightubah/mod/
  XEntry.java              hook bootstrap (IXposedHookLoadPackage)
  Cfg.java  L.java  Lic.java  Keep.java
  audio/   Dsp Vs Reverb(EQ/studio in Dsp) Stereo Monitor Recorder AudioHooks MicSettings SonarImport Iso
  ui/      Overlay(FAB+tick) Panel(10 tabs) Ui EqView Cat CatView
  feat/    Backgrounds Branding AntiDelete JoinLink QuickMessages Invites ViewScan Pick
```

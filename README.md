# bt-mic

Android voice-notes recorder that uses the **Bluetooth microphone**
(classic SCO, LE Audio, hearing aids) instead of the phone mic, with an
on-device transcript via Offline Speech to Text (OSTT).

Implementation of the recipes in
[jooray/android-bluetooth-microphone](https://github.com/jooray/android-bluetooth-microphone)
(SKILL.md @ `b4de6e1`, MIT, © Juraj Bednár) plus hardenings from
[PR #1](https://github.com/jooray/android-bluetooth-microphone/pull/1).
MIT licensed; keep both notices when you copy from this.

## Layout
```
policy/     PURE state machines (host-JVM tested, no Android)
audio/      BtRoute, InputPicker, Primer, Recorder, CuePlayer, Verifier
engine/     RecordEngine (orchestration + one-shot fallback), AacSink
service/    MicService (FGS microphone), BootReceiver
transcribe/ Transcriber (OSTT, hardened), PcmDecoder
store/      NoteStore (internal, backup-excluded), MiniFileProvider
ui/         MainActivity (state mirror only — never touches the recorder)
tools/      gen_cues.py (pitfall 9: 2 s silence before the GO cue)
```

## Milestone gates (from the skill)
```
M1 phone-mic capture            build + manual sanity
M2 SCO route + preferred input  gate: verifier shows BT
M3 LE Audio primer + watchdog
   + one-shot fallback w/ cue   gate: no reopen loops,
                                  exact-zeros = no audio
M4 screen-off FGS               gate: adb shell input
   keyevent 26, 30 s, ffmpeg -af silencedetect=n=-120dB:d=0.5
M5 OSTT transcript (hardened)   gate: busy-retry, revoke,
   EXTRA_AUDIO_SOURCE fallback for non-OSTT providers
M6 boot/update restart          gate: "open app once" state
```

## Tests
```
gradle :app:testDebugUnitTest
```
- DevicePolicyTest — the car + headphones case, type 33
- RecorderPolicyTest — fallback fires once, never re-arms
- SourceScanTest — UI never touches the recorder; no
  INTERNET, no NotificationListener; mic FGS + no-backup
  pinned in the manifest (audit F5/F6 + SKILL.md:396)

## Build
CI: `.github/workflows/build.yml` (ubuntu + JDK 17 +
Gradle 8.9, AGP 8.7.3, compileSdk 36, minSdk 31).
The APK lands in workflow artifacts.

## Known simplifications (v0.1)
- The engine swaps `rec` from the reader thread on
  fallback; drains are synchronized, but treat M3 on real
  hardware as the gate before trusting it.
- Cues are tones only (a spoken cue would end up in the
  note on the phone mic — recipe 8).
- No UI for history/notes yet; files are in
  /data/data/app.btmic/files/notes/<ts>/.

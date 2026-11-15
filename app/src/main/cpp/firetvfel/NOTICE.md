# Fire TV Cube 3 native Dolby Vision Profile 7 FEL (firetvfel)

Native FEL and MEL playback for the Fire TV Cube 3 (Gazelle, `AFTGAZL`), through the platform Dolby Vision
MediaCodec plus the `/dev/dvfel` interface of the **Fire TV AV Magisk module**. No on-the-fly conversion to
Profile 8.1. Requires a rooted Cube with the module active; elsewhere the library is not loaded and nothing changes.

## What is here

- `dvfelplay/` -- copied **unchanged** from Wholphin FEL (`native/dvfelplay`, https://github.com/signde/Wholphin,
  tag `v1.1.1-fel`), which adapts djnice's VLC FEL work (https://github.com/djnice/vlc-fel-android) and Team
  CoreELEC code. GPL-2.0-or-later, original licence and notices retained (`dvfelplay/LICENSE`).
- `firetvfel_jni.cpp`, `annexb.h` -- JNI bridge and Annex-B helper, derived from Wholphin's `wholphinfel.cpp` and
  `annexb.h` (GPL-2.0-or-later). Changes: Nuvio package and class names, own namespace and log tag, and a
  `nativeIsFel` call so the stats overlay claims the enhancement layer only while a FEL session is open.
- `LIBDOVI-LICENSE` -- licence of quietvoid's libdovi (MIT). The static archive linked in is the one already shipped
  under `DV7/libdovi/android-armeabi-v7a`.
- Kotlin side: `app/src/main/java/com/nuvio/tv/core/player/firetvfel/`. The codec adapter is the same idea as
  Wholphin's `FireTvFel.kt` (filter each access unit, queue the base layer, then commit its timestamp), written as a
  `java.lang.reflect.Proxy` over `MediaCodecAdapter`.

## Build notes

- armeabi-v7a only (the Cube's userspace is 32-bit); other ABIs skip the target. Gradle property
  `FIRETV_FEL_NATIVE_ENABLED` (default true) switches it.
- dvfelplay needs NDK platform 26 (`pthread_setname_np`, `AImage_deleteAsync`); `build.gradle.kts` passes
  `-DANDROID_PLATFORM=android-26` when the bridge is enabled. `minSdk` stays 24.
- Not validated here: 4K60 FEL, audio sync, and builds against libdovi versions other than the shipped archive.

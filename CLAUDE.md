# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OpenDroidAuto is an Android Auto head-unit app for legacy car infotainment units (primarily Honda / Fujitsu Ten "Display Audio" units running Android 4.0, API 15). It is built on a JNI port of [aasdk](https://github.com/f1xpl/aasdk). The output APK is `HondaAppCenter_A1.apk` (package `it.smg.hu`).

## Build

Requires a JDK that Gradle 8.11 can run on (17 or 21; Android Studio's bundled JBR 25 is too new) and **NDK 17.2.4988734** exactly (set in root `build.gradle` `ext.ndkVersion`). NDK 17's clang needs `libtinfo.so.5`; on distros that only ship v6, symlink `libtinfo.so.6` as `libtinfo.so.5` into `<ndk>/toolchains/llvm/prebuilt/linux-x86_64/lib64/`.

```
./gradlew :common:assemble      # must be a separate, earlier invocation: it publishes the libcommon prefab package the other native modules import
./gradlew assembleRelease       # APK -> app/build/outputs/apk/release/HondaAppCenter_A1.apk
```

Running both tasks in one Gradle call (or switching ABI) caches a header-only prefab `common` module and the `aasdk` link fails with undefined `JNIBase::*` references; delete `aasdk/build/.cxx` and `omxvideocodec/build/.cxx` and rebuild.

The ABI defaults to `armeabi-v7a` (the car); pass `-Pabis=x86` to every Gradle command to build for the emulator.

CI (`.github/workflows/main.yml`) runs `./gradlew :common:build` then `./gradlew assembleRelease` on tag push and attaches the APK to a GitHub release. Release builds are signed with the debug key and minified (ProGuard) only in `:app`.

There are no meaningful tests (only the template `omxvideocodec/src/test/.../ExampleUnitTest.java`).

Shared versions (`compileSdk`, `minSdk`/`targetSdk` = 15, `versionCode`/`versionName`, ABIs, NDK) live in root `build.gradle` `ext`; bump the version there. Java source level is 1.8.

## Debugging

`Settings.advanced.hondaIntegrationEnabled()` must be false when debugging off-car, otherwise startup fails (it binds to Fujitsu Ten system services via the AIDL in `app/src/main/aidl/com/fujitsu_ten/`). On the emulator this is done automatically on first run (`DeviceUtils.isEmulator`).

### Running on the emulator

Use a 32-bit **x86** AVD (e.g. API 30 Google APIs x86, ~800×480 landscape to match the head unit; large resolutions have crashed the emulator once video starts). On the emulator the app turns off Honda integration, turns on WiFi mode, decodes video with `NativeVideoOutput` (MediaCodec), and connects to `10.0.2.2:5277` (the host) instead of the WiFi gateway.

1. Phone on USB with adb debugging; in Android Auto settings enable developer mode (tap Version ~10×), then ⋮ → "Start head unit server".
2. `adb -s <phone> forward tcp:5277 tcp:5277` (lost on replug).
3. Build with `-Pabis=x86`, `adb -s emulator-5554 install -r app/build/outputs/apk/debug/HondaAppCenter_A1.apk`, open the app, press START WIFI.

The phone's head unit server accepts the first TCP connection as the head unit, so don't probe port 5277 before connecting. If Android Auto crashes with `IllegalStateException: Already connected`, `adb -s <phone> shell am force-stop com.google.android.projection.gearhead` and start the server again.

`tools/honda-emulator.sh [avd] [cpu-quota] [app-heap]` starts the AVD under a systemd CPU quota (default `100%` = one host core) as a rough stand-in for the head unit, and after boot sets the per-app heap limit (default `48m`, like the car) via `adb root` + `dalvik.vm.heapgrowthlimit` + zygote restart. That's needed because the emulator ignores `hw.ramSize`/`vm.heapSize` below its minimums for API 30 images (it forces 2 GB RAM). The `Honda_Emulator` AVD is 800×480 @160 dpi with 2 cores. The quota pauses the whole emulator when exceeded, so audio stutters at `100%`; use e.g. `400%` when listening. It still runs x86 code at host speed and uses `NativeVideoOutput`, not the car's `OMXVideoOutput`, so CPU and video numbers only matter when measured on the car.

## Target hardware and resource measurement

The car is a 2015 Civic Honda Connect unit (Fujitsu Ten MY15ADA, NVIDIA `e1853` automotive Tegra 3 board), measured with `ResourceMonitor`: 2× Cortex-A9 @ 900 MHz, 982 MB RAM (~210 MB available), 48 MB app heap (memory class 48), 800×480 @ 160 dpi, Android 4.0.4 (API 15). Video is decoded by the hardware `OMX.Nvidia.h264.decode`, which runs in mediaserver, so its CPU shows in system CPU, not the app's. Native threads attached to Dalvik show as `Thread-NNN` in `/proc`, not by their attach name. New code must run on API 15 (no `quitSafely`, `Long.compare`, `MediaCodecList`, etc.); check with `./gradlew :app:lintDebug` and look for `NewApi` in `app/build/reports/lint-results-debug.xml` (the task itself fails on pre-existing errors).

There is no adb on the car, so measurement goes through `manager/ResourceMonitor`, enabled by the "Log CPU/memory usage" checkbox in Advanced settings (`resourceMonitor` pref). It writes `device_<ts>.txt` (specs: CPU, RAM, display, heap limit) and `stats_<ts>.csv` (every 5 s: app/system CPU, per-thread CPU, Java/native/PSS memory, free RAM, and video frames/s received and rendered, KB/s, largest frame, longest gap between frames, peak frames waiting for the decoder (`OMXSource` queue, via `OMXVideoCodec.takeStats()`), the frames-in-flight / frame-rate-limit settings and arrival-to-screen time, counted by `projection/VideoStats`; touch rate, event age, hand-off, send-to-phone time and response time from a touch on a still screen to the first new frame, counted by `projection/InputStats` and native `service/LinkStats` (with ping round trips), exposed through `Runtime.takeLinkStats()`; main-thread stalls, CPU cores online and clock, audio bytes dropped and the busiest processes system-wide) to `<log dir>/stats/` on the USB stick, or `/sdcard/ODA/stats/` when the log dir doesn't exist (emulator: `adb pull /sdcard/ODA/stats/`). Summarize with `tools/stats_summary.py <stats.csv>...` (video is grouped by frames-in-flight value).

"Video frames in flight" (Video settings, `video_max_unacked`, default 1) is sent to the phone as `max_unacked` in `VideoService::onAVChannelSetupRequest`: with 1 the phone waits for each frame's ack before sending the next, so each frame costs a full round trip. The phone sends ~55-60 frames/s although 30 fps is requested (measured on the car too), and ignores the requested rate; on the car the round trip is not the limit (1 in flight still gets ~60 frames/s). "Limit video frame rate" (`video_fps_limit`, default 0 = off) paces the acks instead (`VideoService::sendPendingAcks`, a `steady_timer` on the service strand, since the car's wall clock jumps with GPS time), so the phone can't send faster than the limit. CPU % is relative to one core; thread names are truncated to 15 chars by the kernel; `sys_cpu_pct` is empty on Android 8+ (no `/proc/stat` access).

## Module layout

- **`:common`** (`it.smg.libs.common`) — native `libcommon.so` (logging, JNI base helpers, backtrace) exported as a prefab package, plus the Java `Log` facade used everywhere.
- **`:aasdk`** (`it.smg.libs.aasdk`) — the Android Auto protocol. Native `aasdk-jni` (C++14, Boost.Asio, OpenSSL, libusb, protobuf) plus thin Java wrappers.
- **`:omxvideocodec`** — native H.264 decoder via OpenMAX/libstagefright, for pre-Jelly Bean devices.
- **`:app`** — the head-unit app: UI, settings, connection management, and Java implementations of the aasdk projection interfaces.
- **`ext-libs/`** — prebuilt Android system libraries (`<abi>/system/hrv/*.so`), AOSP headers, and `framework/ada-ext-api.jar` (compileOnly, head-unit vendor API). Native modules link directly against these.

## Architecture

### Java ↔ native bridging pattern (aasdk)

Each Java class in `aasdk/src/main/java/it/smg/libs/aasdk/**` has a native counterpart in `aasdk/src/main/cpp/libaasdk-jni/jni/it_smg_libs_aasdk_<package>_<Class>.{h,cpp}`. The Java object stores the native pointer in a `@Keep long handle_` returned by `nativeSetup(...)`, and frees it in `delete()` → `nativeDelete()`. Methods invoked *from* C++ (callbacks such as `onError`) must be annotated `@Keep` so ProGuard doesn't strip them. The pure C++ protocol code (`channel/`, `messenger/`, `transport/`, `usb/`, `tcp/`, `service/`) is the ported aasdk.

`it.smg.libs.aasdk.Runtime` loads `c++_shared`, `common`, `aasdk-jni` and runs the Boost.Asio io_service worker threads (count from `Settings.advanced.threads()`).

### Native build specifics

- Native code uses **ndk-build** (`Android.mk` / `Application.mk`), not CMake. Every new `.cpp` must be added explicitly to the relevant `Android.mk` source list.
- Third-party libs (boost, libusb, openssl, protobuf 3.17.3) are prebuilt under `aasdk/src/main/cpp/libs/`.
- Protobuf messages: `.proto` sources are in `aasdk/proto/`; the generated `*.pb.cc/.pb.h` are checked in under `libaasdk-jni/proto/` and listed in `PROTO_SRC_FILES` in `libaasdk-jni/Android.mk`. When adding/changing a proto, regenerate with a matching protoc (3.17.3) and update that list.

### Connection flow (app)

1. `MainActivity`/`PlayerActivity` start `service/ODAService` in USB mode (`USBManager` detects an AOAP device → `LibUsbDevice`) or WiFi mode (`WIFIManager` → `TCPEndpoint`).
2. `AndroidAutoEntityFactory` uses `AndroidAutoEntityBuilder` to create the AA services (media/speech/system audio, audio input, video, sensor, bluetooth, input, optional navigation/media status) and wires each to an app-side implementation from `projection/DeviceFactory`.
3. `ODAService` implements `IAndroidAutoEntityEventHandler` and receives quit/error events; UI updates go through `LocalBroadcastManager` and `ui/notification`.

Video output is chosen at runtime in `DeviceFactory.createVideoOutput`: `FakeVideoOutput` on x86 (emulator), `OMXVideoOutput` below API 16, `NativeVideoOutput` otherwise.

### Settings

`config/Settings` is a singleton over default `SharedPreferences`, split into nested groups (`car`, `keymap`, `video`, `connectivity`, `audio`, `advanced`), each matching a fragment in `ui/settings/`. `Settings.car` implements aasdk's `ICarConfiguration` and is passed into the native entity. Honda-specific behaviour (`manager/HondaConnectManager`, audio routing in `projection/*Audio*`) is gated on `advanced.hondaIntegrationEnabled()`.

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
3. Build with `-Pabis=x86`, `adb -s emulator-5554 install -r app/build/outputs/apk/debug/HondaAppCenter_A1.apk`, open the app, tap the Android Auto tile.

The phone's head unit server accepts the first TCP connection as the head unit, so don't probe port 5277 before connecting. If Android Auto crashes with `IllegalStateException: Already connected`, `adb -s <phone> shell am force-stop com.google.android.projection.gearhead` and start the server again.

`tools/honda-emulator.sh [avd] [cpu-quota] [app-heap]` starts the AVD under a systemd CPU quota (default `100%` = one host core) as a rough stand-in for the head unit, and after boot sets the per-app heap limit (default `48m`, like the car) via `adb root` + `dalvik.vm.heapgrowthlimit` + zygote restart. That's needed because the emulator ignores `hw.ramSize`/`vm.heapSize` below its minimums for API 30 images (it forces 2 GB RAM). The `Honda_Emulator` AVD is 800×480 @160 dpi with 2 cores. The quota pauses the whole emulator when exceeded, so audio stutters at `100%`; use e.g. `400%` when listening. It still runs x86 code at host speed and uses `NativeVideoOutput`, not the car's `OMXVideoOutput`, so CPU and video numbers only matter when measured on the car.

## Target hardware and resource measurement

The car is a 2015 Civic Honda Connect unit (Fujitsu Ten MY15ADA, NVIDIA `e1853` automotive Tegra 3 board), measured with `ResourceMonitor`: 2× Cortex-A9 @ 900 MHz, 982 MB RAM (~210 MB available), 48 MB app heap (memory class 48), 800×480 @ 160 dpi, Android 4.0.4 (API 15). Video is decoded by the hardware `OMX.Nvidia.h264.decode`, which runs in mediaserver, so its CPU shows in system CPU, not the app's. By default it holds ~14 frames (~230 ms at 60 fps) and only releases the last frames of an animation when newer frames arrive (seen on the car as screens frozen mid-transition until the next touch), even though the phone's SPS already says `max_num_reorder_frames=0`, `max_dec_frame_buffering=1`. `LowLatencyOMX` wraps `IOMX` for `OMXCodec::Create` and, right after the decoder node is allocated, sets the NVIDIA extensions `OMX.Nvidia.index.param.uselowbuffer` (16-byte struct: size, version, port 0, enable) and `OMX.Nvidia.index.param.h264disabledpb` (12-byte struct: size, version, disable), as the car's own `ACodec::setupCodecForVideoTelephony` does, plus `OMX.Nvidia.index.param.vdecfullframedata` (12 bytes: size, version, full frames = true; the car's `OMXCodec::configureCodec` only ever sets it false, for MPEG-2, when the format has key `'mpge'`) (found by disassembling the car's `libstagefright.so` in `ext-libs`); "Low-latency video decoding" (`video_decoder_low_latency`, default on) switches it. The wrapper's vtable must match the car's `IOMX` (164 bytes). Native threads attached to Dalvik show as `Thread-NNN` in `/proc`, not by their attach name. New code must run on API 15 (no `quitSafely`, `Long.compare`, `MediaCodecList`, etc.); check with `./gradlew :app:lintDebug` and look for `NewApi` in `app/build/reports/lint-results-debug.xml` (the task itself fails on pre-existing errors).

Even with those three parameters the decoder held 6-7.5 frames at any frame rate (measured with the per-stage columns below: arrival-to-screen was almost entirely "inside decoder"), i.e. ~100 ms at 60 fps and seconds at the 2-6 fps the phone sends with its screen off. The cause is not the decoder: `OMXCodec` (ICS) reads its next input from our `OMXSource::read()` inside the decoder's `EMPTY_BUFFER_DONE` callback, and the decoder's callbacks reach `OMXCodec` one at a time (one-way binder calls to one `IOMXObserver`), so while `read()` waits for the phone's next frame, the `FILL_BUFFER_DONE` of an already decoded frame queues behind it. "Show frames as soon as decoded (experimental)" (`video_decoder_release_early`, default off) has `LowLatencyOMX::allocateNode` give the NVIDIA node an `EarlyReleaseObserver` that parks `EMPTY_BUFFER_DONE` in `OMXSource` (`parkEmptyDone`) while no frame is queued and passes everything else through; a forwarder thread in `OMXSource` hands parked messages to `OMXCodec` when frames arrive (each forwarded message claims one queued frame, so `read()` never waits there) and all of them on `stop()`. Forwarding never happens on the thread that queues frames: during its initial input submit `OMXCodec` waits in `read()` holding its lock. With it on the car measured 1.0 frames held and 1-2 ms arrival-to-screen.

There is no adb on the car, so measurement goes through `manager/ResourceMonitor`, enabled by the "Log CPU/memory usage" checkbox in Advanced settings (`resourceMonitor` pref). It writes `device_<ts>.txt` (specs: CPU, RAM, display, heap limit) and `stats_<ts>.csv` (every 5 s: app/system CPU, per-thread CPU, Java/native/PSS memory, free RAM, and video frames/s received and rendered, KB/s, largest frame, longest gap between frames, peak frames waiting for the decoder (`OMXSource` queue, via `OMXVideoCodec.takeStats()`) and arrival-to-screen time split into waiting for the decoder, inside the decoder, hand-off to the screen and frames held by the decoder (`OMXSource::takeStages`, OMX decoder only), counted by `projection/VideoStats`; touch rate, event age, hand-off, send-to-phone time and response time from a touch on a still screen to the first new frame, counted by `projection/InputStats` and native `service/LinkStats` (with ping round trips), exposed through `Runtime.takeLinkStats()`; main-thread stalls, CPU cores online and clock, audio bytes dropped and the busiest processes system-wide) to `<log dir>/stats/` on the USB stick, or `/sdcard/ODA/stats/` when the log dir doesn't exist (emulator: `adb pull /sdcard/ODA/stats/`). Summarize with `tools/stats_summary.py <stats.csv>...`.

Frame rate: aasdk's `VideoFPS` enum has its two values swapped as phones read them (1, named `_30`, means 60 fps; 2, named `_60`, means 30 fps; measured on the car), and the Java fps used to be compared with the enum so the setting never reached the phone; `JVideoOutput::getVideoFPS` now maps 60 → 1 and 30 → 2, and `VideoService` logs the real request. The protocol has no rate above 60 (and the screen is 60 Hz). The car's USB port runs at 480 Mbit/s (`USBManager.linkSpeed`, logged on "device opened"). The bundled OpenSSL 1.1.1n is built without ARM assembly (plain C AES-GCM); rebuilding it with asm is an open CPU win.

The phone sends ~55-60 frames/s while the screen changes, although 30 fps is requested, and the car decodes and renders all of them; with the phone's screen off (and the phone barely charging on the car's port) it drops to ~30 and down to 2-6, whatever fps is requested. Tried on the car without benefit (and removed): more than 1 unacked video frame (`max_unacked` in `VideoService::onAVChannelSetupRequest`; the round trip isn't the limit) and pacing the video acks to limit the frame rate (felt slower). CPU % is relative to one core; thread names are truncated to 15 chars by the kernel; `sys_cpu_pct` is empty on Android 8+ (no `/proc/stat` access).

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

USB: `USBManager.checkDevice` runs on its own "ODA USB" thread (AOAP control transfers block, 2 s timeouts) and ignores devices no longer in `getDeviceList()` (the reset after a session queues attach intents for vanished devices; asking permission for one put the system USB dialog on the car's screen). The AOAP identity strings are sent with version, URI and serial all at index 0, as upstream did; the spec-correct indices made the phone stop answering the version request. After a session starts, the phone's USB serial is saved (`Settings.connectivity.rememberDevice`); when a known phone's accessory attaches, `USBManager` starts `PlayerActivity` itself (`MainActivity` only shows the start popup for unknown phones). `aoapDevice()` drops a device that is no longer in `UsbManager.getDeviceList()` (detach broadcasts get missed), and `ODAService.isSessionActive()` is the session state the widgets and the home page show. `widget/ConnectionWidget` (2×2) and `ConnectionWidgetSmall` (1×1, same view ids) are redrawn through `ConnectionWidget.refresh()` on attach/detach and session start/stop.

Stopping: errors and quit requests arrive on the protocol's IOService worker threads, so `ODAService.stop()` only queues `stopNow()` on its "ODA stop" thread; `JRuntime::stopIOServiceWorkers` joins every worker before the per-session `JRuntime` is deleted (it used to detach them and restart the io_service, leaking ~5 threads per session into freed memory). `AudioCodec.stop()` waits at most 0.5 s for its playback thread, then stops the `AudioTrack` under a `write()` the car's audio routing blocked (seen when going to the car's home screen); each playback thread has its own track and running flag. The ping watchdog (`service/Pinger`) allows 4 unanswered pings (~20 s) and logs each one. `LibUsbEndpoint` guards its transfer list with a mutex (cancelling on the stop thread raced completions on the strand: SEGV). Focus calls take `ODAService.entityLock_`, which the entity delete holds.

Back / exit: Back in `PlayerActivity` goes to `MainActivity` and leaves the session running (`PlayerActivity` has its own `taskAffinity`, otherwise the singleTask `MainActivity` coming to front destroys it); the tile returns to it. Long-press Back is a reset: `ODAService.quit()` sends a `ShutdownRequest`, waits up to 1 s for the answer, then stops, and the phone's USB reset makes the known-phone auto-start reconnect. Exit on the home page sends `ODAService.ACTION_QUIT` and `USBManager.pauseAutoStart(10 s)` so that re-attach doesn't restart it. `PlayerActivity` keeps its `STOP_ACTION` receiver while in the background and finishes when the session ended meanwhile.

The car force-stops `it.smg.hu` whenever another source app starts (music, phone, voice button, audio off); not handled. Its steering menu service can throw `IllegalStateException` (left mid-broadcast by an app crash) until the car restarts, so `HondaConnectManager` catches `RuntimeException` on every vendor call. `ODALog` kills `logcat` processes left by earlier runs (they outlive a killed app and kept writing).

Video output is chosen at runtime in `DeviceFactory.createVideoOutput`: `FakeVideoOutput` on x86 (emulator), `OMXVideoOutput` below API 16, `NativeVideoOutput` otherwise.

### Settings

`config/Settings` is a singleton over default `SharedPreferences`, split into nested groups (`car`, `keymap`, `video`, `connectivity`, `audio`, `advanced`). The settings screen has five tabs that don't map one to one: Connection (`ConnectivityFragment`: remembered phones, Bluetooth, the Wi-Fi debug switch from `advanced`), Display (`VideoFragment`), Car (`CarFragment`: driving options plus the Honda keys from `advanced`), Steering keys (`KeymapFragment`) and Developer (`AdvancedFragment`: logging, head unit identity from `car`, threads). `Settings.car` implements aasdk's `ICarConfiguration` and is passed into the native entity. Honda-specific behaviour (`manager/HondaConnectManager`, audio routing in `projection/*Audio*`) is gated on `advanced.hondaIntegrationEnabled()`. Microphone: with "Use the car's microphone" on, `HondaConnectManager.startMicSession` puts the car in voice mode (`IEcNcService.startVR`), after which the car's EcNc service holds the only audio input and our `AudioRecord` fails ("other input already started"); the service has no call that hands over its audio. `stopMicSession` always calls `endVr` after a start attempt (a failed `startVR` can leave media muted). `AudioInput` logs the source and the peak level each second.

### UI

The home page (`ui/main/HomeFragment`) is tiles over the system wallpaper (drawn by `MainActivity` from `WallpaperManager`, because the activity must stay translucent for the invisible USB launch; the car's wallpaper is 1600 px wide, ~3 MB of heap). Text uses Barlow Semi Condensed through `ui/Fonts.apply` (API 15 has no XML fonts; widgets use the system font). Colours, tile and settings-row styles are tokens in `res/values/colors.xml` and `style.xml` (`SettingsRow`, `SettingsToggle`, `SettingsSwitch` for rows with a hint, `SettingsField`/`SettingsChoice` in a fixed 200dp value column); keep new settings in that pattern. On API 15 a `CheckBox` with a `null` background gets no width for its button, so switches are drawn as `drawableRight`.

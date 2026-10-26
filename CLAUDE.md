# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OpenDroidAuto is an Android Auto head-unit app for legacy car infotainment units (primarily Honda / Fujitsu Ten "Display Audio" units running Android 4.0, API 15). It is built on a JNI port of [aasdk](https://github.com/f1xpl/aasdk). The output APK is `HondaAppCenter_A1.apk` (package `it.smg.hu`).

## Build

Requires JDK 17 and **NDK 17.2.4988734** exactly (set in root `build.gradle` `ext.ndkVersion`). Only the `armeabi-v7a` ABI is built.

```
./gradlew :common:assemble      # must be built first: it publishes the libcommon prefab package the other native modules import
./gradlew assembleRelease       # APK -> app/build/outputs/apk/release/HondaAppCenter_A1.apk
```

CI (`.github/workflows/main.yml`) runs `./gradlew :common:build` then `./gradlew assembleRelease` on tag push and attaches the APK to a GitHub release. Release builds are signed with the debug key and minified (ProGuard) only in `:app`.

There are no meaningful tests (only the template `omxvideocodec/src/test/.../ExampleUnitTest.java`).

Shared versions (`compileSdk`, `minSdk`/`targetSdk` = 15, `versionCode`/`versionName`, ABIs, NDK) live in root `build.gradle` `ext`; bump the version there. Java source level is 1.8.

## Debugging

`Settings.advanced.hondaIntegrationEnabled()` must be false when debugging off-car, otherwise startup fails (it binds to Fujitsu Ten system services via the AIDL in `app/src/main/aidl/com/fujitsu_ten/`).

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

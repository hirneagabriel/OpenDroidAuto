# OpenDroidAuto

Android Auto for older Honda head units that never got it. OpenDroidAuto runs as an app on the Honda Connect / Fujitsu Ten "Display Audio" units (Android 4.0, e.g. the 2015 Civic) and turns them into an Android Auto head unit for your phone, over USB.

It is built on [aasdk](https://github.com/f1xpl/aasdk), ported to the head unit through JNI, and plays video on the unit's own NVIDIA hardware decoder.

## Features

- **Plug in and drive.** A phone that has connected once is remembered and starts Android Auto by itself the next time it is plugged in. No popups after the first time.
- **Home screen widget** in the style of the car's own tiles, in two sizes (2×2 and 1×1): grey without a phone, blue when one is ready or Android Auto is running. Tap it to open Android Auto.
- **Low-latency video.** With *Show frames as soon as decoded* on, each frame is on screen 1–2 ms after it arrives from the phone, instead of waiting behind ~7 newer frames in the decoder.
- **Integration with the car:** steering wheel buttons, the car's audio routing, day/night mode, media and navigation popups while you use the car's own screens.
- **Settings in the car's look:** Connection, Display, Car (incl. Honda integration), Steering keys and Developer.
- **Built-in resource monitor** that logs CPU, memory, video and touch timing to the USB stick, for tuning on the real car.

## Compatibility

Tested on a 2015 Honda Civic with Honda Connect (Fujitsu Ten MY15ADA, NVIDIA Tegra 3, Android 4.0.4, 800×480). Other Honda Connect / Display Audio units of the same generation should work, but are untested.

Your phone needs the Android Auto app. Wireless Android Auto is not supported directly; a wireless adapter that plugs into the car's USB port and presents itself as a wired phone may work, but is untested.

## Installing

1. Download `HondaAppCenter_A1.apk` from the [releases](../../releases), or build it (see below).
2. Install it on the head unit the way you install other apps on it (for Honda Connect, from a USB stick).
3. Open **OpenDroidAuto** and, if you like, add the **Android Auto** widget to the car's home screen.

## Using it

1. Plug the phone in with a USB data cable.
2. The first time, Android asks whether to open OpenDroidAuto for this USB device. Tick **"Use by default for this USB device"** and confirm. You'll see this twice the first time, because the phone shows up as a new device after it switches to Android Auto mode.
3. Tap the *Phone connected* popup (or the Android Auto tile). From then on, this phone starts Android Auto as soon as it is plugged in.

The home page and the widget show the state at a glance: grey means no phone, blue means a phone is ready or Android Auto is running.

### Settings worth knowing

| Where | Setting | Why |
|---|---|---|
| Display | **Show frames as soon as decoded (experimental)** | Removes most of the video delay. Turn it off if the picture ever freezes. |
| Display | Resolution, frame rate, density | What the phone renders. Changes apply on the next connection. |
| Connection | **Remembered phones** | Forget all phones, e.g. before handing over the car. |
| Car | **Honda integration** | Car audio, steering buttons and services. Turn it off when running the app outside the car. |
| Steering keys | Learn | Tap *Learn*, then press the button on the wheel, to assign an action to it. |
| Developer | Log CPU and memory use, write the log to a file | Diagnostics, written to the USB stick (see below). |

## Troubleshooting

- **Android Auto feels slow or lags.** Turn on *Show frames as soon as decoded*. With its screen off, the phone sends fewer frames per second, so motion gets less smooth, but with this setting on, the picture no longer lags behind.
- **The phone drops to 30 frames per second with its screen off.** That's the phone saving power. Check that Android Auto is not battery-restricted on the phone (Apps → Android Auto → Battery → Unrestricted) and that the phone actually charges on the car's USB port.
- **The phone asks for USB permission every time.** Tick "Use by default for this USB device" in Android's prompt (both times on the first connection).
- **Random disconnects.** Turn on *Write the log to a file* in Developer settings and bring the log: it says whether the cable or USB dropped (`USB_TRANSFER`), the phone stopped answering (`ping`) or the phone ended the session (`Shutdown request`).

## Building

Requirements:

- A JDK that Gradle 8.11 runs on (17 or 21; Android Studio's bundled JBR 25 is too new).
- **Android NDK 17.2.4988734** exactly (set in the root `build.gradle`). On Linux distributions that only ship `libtinfo.so.6`, symlink it as `libtinfo.so.5` into `<ndk>/toolchains/llvm/prebuilt/linux-x86_64/lib64/`.

Build in two separate Gradle runs; the first publishes the `common` native library the others link against:

```
./gradlew :common:assemble
./gradlew assembleRelease
```

The APK is `app/build/outputs/apk/release/HondaAppCenter_A1.apk`. Release builds are signed with the debug key.

If you build both in one Gradle run, or switch ABI, the `aasdk` link can fail with undefined `JNIBase::*` references: delete `aasdk/build/.cxx` and `omxvideocodec/build/.cxx` and build again.

The ABI defaults to `armeabi-v7a` (the car). Add `-Pabis=x86` to every Gradle command to build for the emulator.

New code has to run on Android 4.0 (API 15). Check with `./gradlew :app:lintDebug` and look for `NewApi` in `app/build/reports/lint-results-debug.xml`.

Pushing a tag builds the release APK in CI and attaches it to a GitHub release.

## Developing without the car

Use a 32-bit x86 emulator (e.g. API 30, 800×480). On the emulator the app turns off Honda integration and connects over Wi-Fi to the phone's Android Auto *head unit server*:

1. On the phone, enable Android Auto developer mode (tap its version number ~10 times), then ⋮ → **Start head unit server**.
2. `adb -s <phone> forward tcp:5277 tcp:5277`
3. Build with `-Pabis=x86`, install on the emulator, open the app and tap the Android Auto tile.

`tools/honda-emulator.sh` starts an emulator with the head unit's CPU and memory limits as a rough stand-in. It still runs x86 code at host speed and can't use the car's video decoder, so performance numbers only mean something when measured in the car.

## Measuring on the car

With **Developer → Log CPU and memory use** on, the app writes `device_*.txt` and `stats_*.csv` to `<log folder>/stats/` on the USB stick every 5 seconds: CPU per process and thread, memory, video frame rate and timing (including where frames wait in the decoder), touch response, ping, main-thread stalls and dropped audio. Summarize one or more files with:

```
tools/stats_summary.py stats_*.csv
```

## Project layout

| Module | What it is |
|---|---|
| `app` | The head unit app: UI, settings, connection management, widgets, Honda integration. |
| `aasdk` | The Android Auto protocol (aasdk port, C++ with Boost.Asio, OpenSSL, libusb, protobuf) and its Java wrappers. |
| `omxvideocodec` | H.264 decoding on the head unit's OMX hardware decoder, with the low-latency changes. |
| `common` | Shared native logging and JNI helpers. |
| `ext-libs` | Prebuilt Android system libraries and headers from the head unit, linked by the native modules. |

[`CLAUDE.md`](CLAUDE.md) has detailed notes on the architecture, the head unit's hardware and measurements.

## Credits

- Originally created by **MaKi983**, with contributions from Babeltuhnis, RoiLago and Gabriel H.
- Android Auto protocol: [aasdk](https://github.com/f1xpl/aasdk) by f1xpl.
- UI font: [Barlow Semi Condensed](https://github.com/jpt/barlow) (SIL Open Font License, in `app/src/main/assets/fonts`).

## License

GPL-3.0, see [LICENSE](LICENSE).

#!/usr/bin/env bash
# Start the Honda-like AVD with its whole CPU usage capped, to roughly approach the
# Tegra 3 head unit (still much faster per core; use the car's stats for real numbers).
#
# usage: tools/honda-emulator.sh [avd-name] [cpu-quota] [app-heap]
#   cpu-quota is a systemd CPUQuota for all emulator threads together, e.g. 100% = one host core
#   app-heap is the per-app Java heap limit (dalvik.vm.heapgrowthlimit), default 64m
#
# The emulator ignores hw.ramSize/vm.heapSize below its own minimums for recent images, so
# the heap limit is set after boot through root (needs a Google APIs image, not Play Store).
set -euo pipefail

AVD="${1:-Honda_Emulator}"
QUOTA="${2:-100%}"
HEAP="${3:-64m}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"

systemd-run --user --scope -p CPUQuota="$QUOTA" \
    "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot -no-boot-anim &
EMULATOR_PID=$!

# wait for the new emulator (ignoring any phone that is also attached)
until SERIAL=$("$ADB" devices | awk '/^emulator-/ {print $1; exit}') && [ -n "$SERIAL" ] \
        && [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done

echo "booted $SERIAL, setting app heap limit to $HEAP"
"$ADB" -s "$SERIAL" root >/dev/null
sleep 2
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" shell setprop dalvik.vm.heapgrowthlimit "$HEAP"
# apps get the new limit only from a restarted zygote
"$ADB" -s "$SERIAL" shell setprop ctl.restart zygote
echo "done; the home screen comes back in a few seconds"

wait "$EMULATOR_PID"

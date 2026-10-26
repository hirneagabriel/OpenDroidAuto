#!/usr/bin/env bash
# Start the Honda-like AVD with its whole CPU usage capped, to roughly approach the
# Tegra 3 head unit (still much faster per core; use the car's stats for real numbers).
#
# usage: tools/honda-emulator.sh [avd-name] [cpu-quota]
#   cpu-quota is a systemd CPUQuota for all emulator threads together, e.g. 100% = one host core
set -euo pipefail

AVD="${1:-Honda_Emulator}"
QUOTA="${2:-100%}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

exec systemd-run --user --scope -p CPUQuota="$QUOTA" \
    "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot -no-boot-anim

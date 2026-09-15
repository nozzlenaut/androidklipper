#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for abi in arm64-v8a armeabi-v7a; do
  unzip -p "$APK" "lib/$abi/libandroidklipper_pty.so" > "$TMP/pty-$abi.so"
  unzip -p "$APK" "lib/$abi/libklipper_c_helper.so" > "$TMP/chelper-$abi.so"
  [[ -s "$TMP/pty-$abi.so" ]]
  [[ -s "$TMP/chelper-$abi.so" ]]
  readelf -Ws "$TMP/chelper-$abi.so" | grep -q ' get_monotonic$'
  readelf -Ws "$TMP/chelper-$abi.so" | grep -q ' serialqueue_alloc$'
done

unzip -p "$APK" assets/chaquopy/app.imy > "$TMP/app.imy"
unzip -Z1 "$TMP/app.imy" | grep -q '^hostprobe.pyc$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_runner.pyc$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_vendor/klippy/klippy.py$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_vendor/klippy/serialhdl.py$'

for abi in arm64-v8a armeabi-v7a; do
  unzip -p "$APK" "assets/chaquopy/stdlib-$abi.imy" > "$TMP/stdlib-$abi.imy"
  for module in fcntl termios select; do
    unzip -Z1 "$TMP/stdlib-$abi.imy" | grep -Eq "^${module}\\.cpython-311.*\\.so$"
  done
done

unzip -p "$APK" assets/chaquopy/stdlib-common.imy > "$TMP/stdlib-common.imy"
unzip -Z1 "$TMP/stdlib-common.imy" | grep -q '^pty.pyc$'

APKSIGNER="${ANDROID_SDK_ROOT:-}/build-tools/35.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --print-certs "$APK" | grep -E 'Signer #1 certificate SHA-256 digest|Signer #1 certificate DN'
fi

echo "APK packaging invariants: PASS"

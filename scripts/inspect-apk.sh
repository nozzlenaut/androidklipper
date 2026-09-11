#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Native bridge and Klipper helper must exist for both supported ABIs.
for abi in arm64-v8a armeabi-v7a; do
  unzip -p "$APK" "lib/$abi/libandroidklipper_pty.so" > "$TMP/pty-$abi.so"
  unzip -p "$APK" "lib/$abi/libklipper_c_helper.so" > "$TMP/chelper-$abi.so"
  [[ -s "$TMP/pty-$abi.so" ]]
  [[ -s "$TMP/chelper-$abi.so" ]]

  # Check a representative spread of helper exports used by Klippy.
  for symbol in \
    get_monotonic \
    serialqueue_alloc \
    stepcompress_alloc \
    steppersyncmgr_alloc \
    trapq_alloc \
    itersolve_generate_steps \
    input_shaper_alloc; do
    readelf -Ws "$TMP/chelper-$abi.so" | grep -q " $symbol$"
  done
done

# Chaquopy app payload must contain our probes and the pinned, patched Klipper.
unzip -p "$APK" assets/chaquopy/app.imy > "$TMP/app.imy"
unzip -Z1 "$TMP/app.imy" | grep -q '^hostprobe.pyc$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_runner.pyc$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_vendor/klippy/klippy.py$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_vendor/klippy/serialhdl.py$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_vendor/klippy/chelper/__init__.py$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_vendor/KLIPPER_COMMIT$'
unzip -Z1 "$TMP/app.imy" | grep -q '^klipper_vendor/klippy/.version$'

unzip -p "$TMP/app.imy" klipper_vendor/KLIPPER_COMMIT \
  | grep -qx '2d7717e3b62ea2fe3401b27f54f8681f80451c69'
[[ -n "$(unzip -p "$TMP/app.imy" klipper_vendor/klippy/.version)" ]]
unzip -p "$TMP/app.imy" klipper_vendor/klippy/chelper/__init__.py \
  | grep -q 'ANDROID_KLIPPER_CHELPER'

# Required Python packages must be present in the APK for both pure-Python and
# ABI-specific extension pieces.
unzip -p "$APK" assets/chaquopy/requirements-common.imy > "$TMP/requirements-common.imy"
for path in \
  serial/__init__.pyc \
  jinja2/__init__.pyc \
  markupsafe/__init__.pyc \
  cffi/__init__.pyc \
  greenlet/__init__.pyc; do
  unzip -Z1 "$TMP/requirements-common.imy" | grep -q "^$path$"
done

for abi in arm64-v8a armeabi-v7a; do
  unzip -p "$APK" "assets/chaquopy/requirements-$abi.imy" > "$TMP/requirements-$abi.imy"
  unzip -Z1 "$TMP/requirements-$abi.imy" | grep -q '^_cffi_backend\.sofor abi in arm64-v8a armeabi-v7a; do
  unzip -p "$APK" "assets/chaquopy/stdlib-$abi.imy" > "$TMP/stdlib-$abi.imy"
  for module in fcntl termios select; do
    unzip -Z1 "$TMP/stdlib-$abi.imy" | grep -Eq "^${module}\\.cpython-311.*\\.so$"
  done
done

unzip -p "$APK" assets/chaquopy/stdlib-common.imy > "$TMP/stdlib-common.imy"
unzip -Z1 "$TMP/stdlib-common.imy" | grep -q '^pty.pyc$'

echo "APK packaging invariants: PASS"

  unzip -Z1 "$TMP/requirements-$abi.imy" | grep -q '^greenlet/_greenlet\.sofor abi in arm64-v8a armeabi-v7a; do
  unzip -p "$APK" "assets/chaquopy/stdlib-$abi.imy" > "$TMP/stdlib-$abi.imy"
  for module in fcntl termios select; do
    unzip -Z1 "$TMP/stdlib-$abi.imy" | grep -Eq "^${module}\\.cpython-311.*\\.so$"
  done
done

unzip -p "$APK" assets/chaquopy/stdlib-common.imy > "$TMP/stdlib-common.imy"
unzip -Z1 "$TMP/stdlib-common.imy" | grep -q '^pty.pyc$'

echo "APK packaging invariants: PASS"

done

# Klipper relies on these POSIX Python modules on Android.
for abi in arm64-v8a armeabi-v7a; do
  unzip -p "$APK" "assets/chaquopy/stdlib-$abi.imy" > "$TMP/stdlib-$abi.imy"
  for module in fcntl termios select; do
    unzip -Z1 "$TMP/stdlib-$abi.imy" | grep -Eq "^${module}\\.cpython-311.*\\.so$"
  done
done

unzip -p "$APK" assets/chaquopy/stdlib-common.imy > "$TMP/stdlib-common.imy"
unzip -Z1 "$TMP/stdlib-common.imy" | grep -q '^pty.pyc$'

echo "APK packaging invariants: PASS"

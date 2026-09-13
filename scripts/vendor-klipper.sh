#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/vendor/klipper"
PY_VENDOR="$ROOT/app/src/main/python/klipper_vendor"
KLIPPER_COMMIT="2fb3d54e2f8086fb310936c9136dbb05dc753ed5"

rm -rf "$VENDOR" "$PY_VENDOR"
mkdir -p "$(dirname "$VENDOR")" "$PY_VENDOR"

git clone --filter=blob:none --no-checkout https://github.com/Klipper3d/klipper.git "$VENDOR"
git -C "$VENDOR" checkout "$KLIPPER_COMMIT"

cp -a "$VENDOR/klippy" "$PY_VENDOR/"
touch "$PY_VENDOR/__init__.py"
printf '%s\n' "$KLIPPER_COMMIT" > "$PY_VENDOR/KLIPPER_COMMIT"

python3 "$ROOT/scripts/patch-klipper.py" \
  "$PY_VENDOR/klippy/chelper/__init__.py" \
  "$PY_VENDOR/klippy/serialhdl.py" \
  "$PY_VENDOR/klippy/mcu.py" \
  "$PY_VENDOR/klippy/util.py"

echo "Vendored Klipper $KLIPPER_COMMIT"

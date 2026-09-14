#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/vendor/klipper"
PY_VENDOR="$ROOT/app/src/main/python/klipper_vendor"
KLIPPER_COMMIT="2d7717e3b62ea2fe3401b27f54f8681f80451c69"

rm -rf "$VENDOR" "$PY_VENDOR"
mkdir -p "$(dirname "$VENDOR")" "$PY_VENDOR"

git clone --filter=blob:none --no-checkout https://github.com/Klipper3d/klipper.git "$VENDOR"
git -C "$VENDOR" checkout "$KLIPPER_COMMIT"

cp -a "$VENDOR/klippy" "$PY_VENDOR/"
touch "$PY_VENDOR/__init__.py"
printf '%s\n' "$KLIPPER_COMMIT" > "$PY_VENDOR/KLIPPER_COMMIT"

python3 "$ROOT/scripts/patch-klipper.py" \
  "$PY_VENDOR/klippy/chelper/__init__.py" \
  "$PY_VENDOR/klippy/serialhdl.py"

echo "Vendored Klipper $KLIPPER_COMMIT"

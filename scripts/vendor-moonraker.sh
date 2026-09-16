#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/vendor/moonraker"
PY_ROOT="$ROOT/app/src/main/python"
PY_VENDOR="$PY_ROOT/moonraker"
MOONRAKER_COMMIT="9008485843740c93e0154ccbdac1fc2b02b03aaa"

rm -rf "$VENDOR" "$PY_VENDOR"
mkdir -p "$(dirname "$VENDOR")"

git clone --filter=blob:none --no-checkout https://github.com/Arksine/moonraker.git "$VENDOR"
git -C "$VENDOR" checkout "$MOONRAKER_COMMIT"

cp -a "$VENDOR/moonraker" "$PY_VENDOR"
printf "__version__ = 'v0.10.0-20-g9008485-android'\n" > "$PY_VENDOR/__version__.py"

python3 "$ROOT/scripts/patch-moonraker.py" \
  "$PY_VENDOR/server.py" \
  "$PY_VENDOR/components/application.py" \
  "$PY_VENDOR/components/machine.py" \
  "$PY_VENDOR/components/proc_stats.py"

echo "Vendored Moonraker $MOONRAKER_COMMIT"

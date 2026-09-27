#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/vendor/mainsail"
ASSETS="$ROOT/app/src/main/assets/mainsail"
MAINSAIL_COMMIT="5fb9e77fb9f4e60cf0725d9dc7f57cf7b84bbd70"
PYTHON_BIN="${PYTHON:-python3}"

rm -rf "$VENDOR"
mkdir -p "$(dirname "$VENDOR")"
git clone --filter=blob:none --no-checkout https://github.com/mainsail-crew/mainsail.git "$VENDOR"
git -C "$VENDOR" checkout "$MAINSAIL_COMMIT"
"$PYTHON_BIN" "$ROOT/scripts/patch-mainsail.py" \
  "$VENDOR/src/components/charts/TempChart.vue" \
  "$VENDOR/src/store/printer/tempHistory/actions.ts" \
  "$VENDOR/src/store/printer/tempHistory/getters.ts"

pushd "$VENDOR" >/dev/null
npm ci --no-audit --no-fund
npx vite build
popd >/dev/null

rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cp -a "$VENDOR/dist/." "$ASSETS/"
printf '%s' 'v2.19.0-androidklipper-battery1' > "$ASSETS/.version"

echo "Vendored Mainsail v2.19.0 with Android battery telemetry"
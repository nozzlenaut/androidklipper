#!/usr/bin/env bash
# Bundle a pinned Happy Hare release in the Android APK. Do not run its Linux installer.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="${HAPPY_HARE_SOURCE:-$ROOT/vendor/happy-hare}"
COMMIT="70aa90fb9aa18787de445235bd26a0665168c441"
PY_ROOT="$ROOT/app/src/main/python"

if [[ -z "${HAPPY_HARE_SOURCE:-}" ]]; then
  rm -rf "$SOURCE"
  mkdir -p "$(dirname "$SOURCE")"
  git clone -q --filter=blob:none --no-checkout https://github.com/moggieuk/Happy-Hare.git "$SOURCE"
  git -C "$SOURCE" checkout -q "$COMMIT"
fi
actual="$(git -C "$SOURCE" rev-parse HEAD)"
[[ "$actual" == "$COMMIT" ]] || { echo "Happy Hare checkout must be $COMMIT, got $actual" >&2; exit 1; }
[[ -f "$PY_ROOT/extras/stepper.py" || -f "$PY_ROOT/extras/manual_stepper.py" ]] || {
  echo "Vendor Klipper BEFORE Happy Hare: $PY_ROOT/extras is missing" >&2; exit 1;
}
[[ -d "$PY_ROOT/moonraker/components" ]] || {
  echo "Vendor Moonraker BEFORE Happy Hare: components directory missing" >&2; exit 1;
}

# AndroidKlipper mirrors Klipper extras at the Python source root, because
# Klippy resolves extension modules as the top-level 'extras' package.
cp -a "$SOURCE/extras/mmu" "$PY_ROOT/extras/"
for file in "$SOURCE"/extras/mmu_*.py; do
  cp "$file" "$PY_ROOT/extras/"
done
# Keep the vendored internal Klipper tree in sync as well; no symlinks in APKs.
cp -a "$SOURCE/extras/mmu" "$PY_ROOT/klipper_vendor/klippy/extras/"
for file in "$SOURCE"/extras/mmu_*.py; do
  cp "$file" "$PY_ROOT/klipper_vendor/klippy/extras/"
done
cp "$SOURCE/components/mmu_server.py" "$PY_ROOT/moonraker/components/mmu_server.py"
mkdir -p "$PY_ROOT/happy_hare_license"
cp "$SOURCE/LICENSE" "$PY_ROOT/happy_hare_license/LICENSE.txt"
printf '%s\n' "$COMMIT" > "$PY_ROOT/happy_hare_license/SOURCE_COMMIT.txt"

# A source-level sanity check; the actual Android import/package check runs later.
python3 -m compileall -q "$PY_ROOT/extras/mmu" "$PY_ROOT"/extras/mmu_*.py "$PY_ROOT/moonraker/components/mmu_server.py"
echo "Bundled Happy Hare $COMMIT (Klipper modules + Moonraker component); opt-in until configured"

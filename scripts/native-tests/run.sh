#!/usr/bin/env bash
# Builds app/src/main/cpp/pty_bridge.cpp for the host and runs PtyNativeTest against it.
# Needs g++ and a JDK that ships jni.h. Usage: scripts/native-tests/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAVA_HOME_DIR="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
g++ -std=c++17 -Wall -Wextra -Werror -shared -fPIC \
    -I"$JAVA_HOME_DIR/include" -I"$JAVA_HOME_DIR/include/linux" \
    -o "$OUT/libandroidklipper_pty.so" "$ROOT/app/src/main/cpp/pty_bridge.cpp"
javac -d "$OUT" "$HERE/dev/nozzlenaut/androidklipper/pty/PtyBridge.java" "$HERE/PtyNativeTest.java"
java -Djava.library.path="$OUT" -cp "$OUT" PtyNativeTest

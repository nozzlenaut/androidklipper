#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
required = [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/cpp/pty_bridge.cpp",
    "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt",
    "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbSerialSession.kt",
    "app/src/main/python/hostprobe.py",
    "scripts/vendor-klipper.sh",
]
for rel in required:
    assert (root / rel).exists(), f"missing {rel}"

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
assert "android.hardware.usb.host" in manifest
assert "foregroundServiceType=\"connectedDevice\"" in manifest

build = (root / "app/build.gradle.kts").read_text()
assert 'version = "3.11"' in build
assert 'armeabi-v7a' in build and 'arm64-v8a' in build
assert 'usb-serial-for-android:3.11.0' in build

service = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt").read_text()
assert "START_NOT_STICKY" in service
assert "HostStatusStore.save" in service
assert ".filter(UsbDeviceScanner::isLikelyKlipper)" in service
assert 'probe_klipper_import_sweep' in service

session = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbSerialSession.kt").read_text()
assert "AtomicBoolean(false)" in session
assert "Do not toggle DTR/RTS" in session
assert "PTY write incomplete" in session

pty = (root / "app/src/main/cpp/pty_bridge.cpp").read_text()
assert "O_NONBLOCK" in pty
assert "EAGAIN" in pty and "EINTR" in pty

cmake = (root / "app/src/main/cpp/CMakeLists.txt").read_text()
expected = [
    "pyhelper.c", "serialqueue.c", "stepcompress.c", "steppersync.c",
    "itersolve.c", "trapq.c", "pollreactor.c", "msgblock.c", "trdispatch.c",
    "kin_cartesian.c", "kin_corexy.c", "kin_corexz.c", "kin_delta.c",
    "kin_deltesian.c", "kin_polar.c", "kin_rotary_delta.c", "kin_winch.c",
    "kin_extruder.c", "kin_shaper.c", "kin_idex.c", "kin_generic.c"
]
for name in expected:
    assert name in cmake, f"c_helper source missing from CMake: {name}"

print("project invariants: PASS")

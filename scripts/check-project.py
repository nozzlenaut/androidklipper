#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
required = [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/cpp/pty_bridge.cpp",
    "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt",
    "app/src/main/java/dev/nozzlenaut/androidklipper/LocalStatusServer.kt",
    "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbSerialSession.kt",
    "app/src/main/python/hostprobe.py",
    "scripts/patch-klipper.py",
    "scripts/vendor-klipper.sh",
]
for rel in required:
    assert (root / rel).exists(), f"missing {rel}"

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
assert "android.hardware.usb.host" in manifest
assert "foregroundServiceType=\"connectedDevice\"" in manifest
assert "android.hardware.usb.action.USB_DEVICE_ATTACHED" in manifest
assert "@xml/device_filter" in manifest
assert 'android:launchMode="singleTop"' in manifest

device_filter = (root / "app/src/main/res/xml/device_filter.xml").read_text()
assert 'vendor-id="7504"' in device_filter
assert 'product-id="24910"' in device_filter

build = (root / "app/build.gradle.kts").read_text()
assert 'GITHUB_RUN_NUMBER' in build
assert 'version = "3.11"' in build
assert 'armeabi-v7a' in build and 'arm64-v8a' in build
assert 'usb-serial-for-android:3.11.0' in build

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

usb_session = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbSerialSession.kt").read_text()
assert "port.dtr = true" not in usb_session
assert "port.rts = true" not in usb_session

pty_bridge = (root / "app/src/main/cpp/pty_bridge.cpp").read_text()
assert "cfmakeraw" in pty_bridge
assert "tcsetattr" in pty_bridge
assert "POLLHUP" in pty_bridge
assert "errno == EIO" in pty_bridge

permission_receiver = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbPermissionReceiver.kt").read_text()
assert "KlipperHostService.EXTRA_FULL_SMOKE" in permission_receiver
assert "KlipperHostService.EXTRA_REAL_CONFIG" in permission_receiver
assert "fun requestNext(" in permission_receiver
assert "supported.firstOrNull { !manager.hasPermission(it) }" in permission_receiver
assert "manager.requestPermission(device, pending)" in permission_receiver

main_activity = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/MainActivity.kt").read_text()
assert "Run full Klippy no-pin smoke test" in main_activity
assert "requestUsbPermissionsAndStart(fullSmoke = true)" in main_activity
assert "Import NUC config + run real Voron startup test" in main_activity
assert "realConfig = true" in main_activity
assert "UsbManager.ACTION_USB_DEVICE_ATTACHED" in main_activity
assert "override fun onNewIntent" in main_activity
assert "UsbPermissionReceiver.requestNext" in main_activity
assert "devices.forEachIndexed" not in main_activity

host_service = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt").read_text()
assert "EXTRA_FULL_SMOKE" in host_service
assert "EXTRA_REAL_CONFIG" in host_service
assert 'smokePtyPaths.joinToString("|")' in host_service
assert "stablePtyMap" in host_service
assert "probe_full_klippy" in host_service
assert "probe_real_config_from_moonraker" in host_service
assert "http://192.168.1.83:7125" in host_service

hostprobe = (root / "app/src/main/python/hostprobe.py").read_text()
assert "def probe_pipe_open(path):" in hostprobe
assert "os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)" in hostprobe
assert "serial.Serial(" not in hostprobe
assert "serial_reader.connect_pipe(path)" in hostprobe
assert "serial_reader.connect_uart(path" not in hostprobe
assert "import extras.error_mcu" in hostprobe
assert "import kinematics.none" in hostprobe
assert "def probe_full_klippy(pty_paths, c_helper_path, work_dir):" in hostprobe
assert "def probe_real_config_from_moonraker(" in hostprobe
assert "3F001E001450535556323420" in hostprobe
assert "3033393834057C77" in hostprobe
assert "504450610844C31C" in hostprobe
assert "temperature_sensor NUC" in hostprobe
assert "K-ShakeTune" in hostprobe
assert "restart_method" in hostprobe
assert "Android PTYs are treated as Klipper pipe transports" in hostprobe
assert "~/printer_data/gcodes" in hostprobe
assert "~/printer_data/config/variables" in hostprobe
assert '"kinematics: none"' in hostprobe
assert '"max_velocity: 1"' in hostprobe
assert '"max_accel: 1"' in hostprobe
assert '"step_pin:"' not in hostprobe
assert '"heater_pin:"' not in hostprobe
assert '"fan_pin:"' not in hostprobe

patcher = (root / "scripts/patch-klipper.py").read_text()
assert "serialport.startswith('/dev/pts/')" in patcher
assert "return self.connect_pipe(serialport)" in patcher
assert 'self._serialport.startswith("/dev/pts/")' in patcher

vendor = (root / "scripts/vendor-klipper.sh").read_text()
assert '"$PY_VENDOR/klippy/serialhdl.py"' in vendor
assert '"$PY_VENDOR/klippy/klippy.py"' in vendor
assert 'cp -a "$VENDOR/klippy/extras" "$PY_ROOT/"' in vendor
assert 'cp -a "$VENDOR/klippy/kinematics" "$PY_ROOT/"' in vendor

status_server = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/LocalStatusServer.kt").read_text()
assert "fun start(port: Int = 7715)" in status_server
assert "7125" not in status_server

rewriter = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/config/ConfigRewriter.kt").read_text()
assert "ptyBaud" not in rewriter
assert 'out += "baud:' not in rewriter

print("project invariants: PASS")

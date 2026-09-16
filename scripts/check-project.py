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
    "scripts/patch-moonraker.py",
    "scripts/vendor-moonraker.sh",
    "app/src/main/python/persistent_host.py",
    "app/src/main/python/moonraker_runner.py",
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
assert 'getSharedPreferences("usb_permission_mode"' in permission_receiver
assert "prefs.edit().clear().apply()" in permission_receiver

main_activity = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/MainActivity.kt").read_text()
assert "Run full Klippy no-pin smoke test" in main_activity
assert "requestUsbPermissionsAndStart(fullSmoke = true)" in main_activity
assert "Import NUC config + start Klipper/Moonraker" in main_activity
assert "realConfig = true" in main_activity
assert "UsbManager.ACTION_USB_DEVICE_ATTACHED" in main_activity
assert "override fun onNewIntent" in main_activity
assert "UsbPermissionReceiver.requestNext" in main_activity
assert 'getSharedPreferences("usb_permission_mode"' in main_activity
assert "devices.forEachIndexed" not in main_activity

host_service = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt").read_text()
assert "EXTRA_FULL_SMOKE" in host_service
assert "EXTRA_REAL_CONFIG" in host_service
assert 'smokePtyPaths.joinToString("|")' in host_service
assert "stablePtyMap" in host_service
assert "probe_full_klippy" in host_service
assert "http://192.168.1.83:7125" in host_service
assert "START_NOT_STICKY" in host_service
assert "return START_STICKY" not in host_service
assert "Only arm a PTY for full/real Klippy after the MCU has" in host_service
assert "toString().also" in host_service

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
assert '"$PY_VENDOR/klippy/extras/statistics.py"' in vendor
assert '"$PY_ROOT/extras/statistics.py"' in vendor
assert '"$PY_VENDOR/klippy/gcode.py"' in vendor
assert 'cp -a "$VENDOR/klippy/extras" "$PY_ROOT/"' in vendor
assert 'cp -a "$VENDOR/klippy/kinematics" "$PY_ROOT/"' in vendor

status_server = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/LocalStatusServer.kt").read_text()
assert "fun start(port: Int = 7715)" in status_server
assert "7125" not in status_server

rewriter = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/config/ConfigRewriter.kt").read_text()
assert "ptyBaud" not in rewriter
assert 'out += "baud:' not in rewriter

moon_vendor = (root / "scripts/vendor-moonraker.sh").read_text()
assert "9008485843740c93e0154ccbdac1fc2b02b03aaa" in moon_vendor
assert 'cp -a "$VENDOR/moonraker" "$PY_VENDOR"' in moon_vendor
assert "patch-moonraker.py" in moon_vendor

moon_patch = (root / "scripts/patch-moonraker.py").read_text()
assert "'database', 'file_manager'" in moon_patch
components_new_block = moon_patch.split('components_new = """', 1)[1].split('"""', 1)[0]
assert "'dbus_manager'" not in components_new_block
assert "'authorization'" in components_new_block
assert "android_request_stop" in moon_patch
assert "Signal handlers unavailable in embedded Android runtime" in moon_patch

persistent = (root / "app/src/main/python/persistent_host.py").read_text()
assert '"apiserver": api_socket' in persistent
assert '"gcode_fd": None' in persistent
assert '"printer_data"' in persistent
assert '"comms", "klippy.sock"' in persistent
assert "def stop():" in persistent
assert "def run(base_url, stable_mapping, c_helper_path, work_dir):" in persistent

moon_runner = (root / "app/src/main/python/moonraker_runner.py").read_text()
assert "host: 0.0.0.0" in moon_runner
assert "[authorization]" in moon_runner
assert "trusted_clients:" in moon_runner
assert "cors_domains:" in moon_runner
assert "http://my.mainsail.xyz" in moon_runner
assert "https://my.mainsail.xyz" in moon_runner
assert 'ipaddress.ip_network(ip + "/24"' in moon_runner
assert "port: 7125" in moon_runner
assert "provider: none" in moon_runner
assert "klippy_uds_address: {klippy_socket}" in moon_runner
assert "server.android_request_stop()" in moon_runner

assert "persistent_host" in host_service
assert "moonraker_runner" in host_service
assert "Moonraker READY: http://127.0.0.1:7125" in host_service
assert "printer_data/comms/klippy.sock" in host_service

assert 'install("tornado==6.5.2")' in build
assert 'install("distro==1.9.0")' in build
assert 'install("inotify-simple==2.0.1")' in build
assert 'implementation("org.nanohttpd:nanohttpd:2.3.1")' in build
mainsail_server = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/MainsailServer.kt").read_text()
assert 'port: Int = 8080' in mainsail_server
assert '"hostname": "$hostname"' in mainsail_server
assert '"port": 7125' in mainsail_server
assert (root / "app/src/main/assets/mainsail/index.html").exists()
assert (root / "app/src/main/assets/mainsail/sw.js").exists()
assert "prepareGcodeStorage" in host_service
assert "Environment.isExternalStorageRemovable" in host_service
assert "Os.symlink" in host_service
mainsail_activity = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/MainsailActivity.kt").read_text()
assert 'loadUrl("http://127.0.0.1:8080/")' in mainsail_activity
assert "setLayerType(View.LAYER_TYPE_HARDWARE" in mainsail_activity


assert "STREAMING_FORM_DATA_AVAILABLE" in moon_patch
assert "File uploads disabled: streaming-form-data is unavailable" in moon_patch

assert "android_get_last_error" in moon_patch
assert "_ANDROID_CURRENT_SERVER = None" in moon_patch
assert "_ANDROID_LAST_ERROR = traceback.format_exc()" in moon_patch
assert "direct Moonraker traceback" in moon_runner
assert "file_system_observer: none" in moon_runner
assert '"-l", os.path.join(p["logs_dir"], "moonraker.log")' in moon_runner

print("project invariants: PASS")

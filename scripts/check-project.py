#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
required = [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/cpp/pty_bridge.cpp",
    "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt",
    "app/src/main/java/dev/nozzlenaut/androidklipper/HostDiagnostics.kt",
    "app/src/main/java/dev/nozzlenaut/androidklipper/LocalStatusServer.kt",
    "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbSerialSession.kt",
    "app/src/main/python/hostprobe.py",
    "scripts/patch-klipper.py",
    "scripts/vendor-klipper.sh",
    "scripts/patch-moonraker.py",
    "scripts/vendor-moonraker.sh",
    "scripts/capture-runtime-checkpoint.py",
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
assert 'android.permission.WAKE_LOCK' in manifest
assert 'android:stopWithTask="false"' in manifest

device_filter = (root / "app/src/main/res/xml/device_filter.xml").read_text()
assert 'vendor-id="7504"' in device_filter
assert 'product-id="24910"' in device_filter

build = (root / "app/build.gradle.kts").read_text()
assert 'GITHUB_RUN_NUMBER' in build
assert 'version = "3.11"' in build

service = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt").read_text()
assert 'deviceDisplayName()' in service
assert 'Settings.Global.getString(contentResolver, "device_name")' in service
assert "override fun onTaskRemoved" in service
assert "servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)" in service
assert "acquireHostWakeLock()" in service
assert 'notification("Klipper host running")' in service
persistent_host = (root / "app/src/main/python/persistent_host.py").read_text()
assert '"hostname": str(device_name).strip() or "AndroidKlipper"' in persistent_host
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
assert "connection.bulkTransfer(" in usb_session
assert "serialPort.readEndpoint" in usb_session
assert "serialPort.writeEndpoint" in usb_session
assert "setReadTimeout(1000)" not in usb_session
assert "Thread.sleep(READ_RETRY_BACKOFF_MS)" in usb_session
assert "Thread.sleep(WRITE_RETRY_BACKOFF_MS)" in usb_session
assert "MAX_CONSECUTIVE_WRITE_FAILURES" not in usb_session
assert "MAX_WRITE_RETRY_WINDOW_MS" in usb_session
assert "writeRetryCount" in usb_session
assert "failSession(" in usb_session
assert "thread.join(IO_THREAD_JOIN_TIMEOUT_MS)" in usb_session
assert "Thread.sleep(5)" not in usb_session
assert "THREAD_PRIORITY_URGENT_AUDIO" not in usb_session
assert "THREAD_PRIORITY_FOREGROUND" in usb_session
assert "statsSnapshot" in usb_session
assert "buffer.copyOf(n)" not in usb_session
assert "pty.write(buffer, n)" in usb_session

pty_kotlin = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/pty/PtyBridge.kt").read_text()
assert "fun write(data: ByteArray, length: Int = data.size)" in pty_kotlin
assert "nativeWrite(masterFd, data, length)" in pty_kotlin
assert "nativeWrite(fd: Int, data: ByteArray, length: Int)" in pty_kotlin

klipper_patch = (root / "scripts/patch-klipper.py").read_text()
assert 'trsync_needle = "TRSYNC_TIMEOUT = 0.025"' in klipper_patch
assert 'trsync_replacement = "TRSYNC_TIMEOUT = 0.050"' in klipper_patch
assert "self.printer.get_start_args().get(" in klipper_patch
assert "'hostname', socket.gethostname()" in klipper_patch

pty_bridge = (root / "app/src/main/cpp/pty_bridge.cpp").read_text()
assert "cfmakeraw" in pty_bridge
assert "tcsetattr" in pty_bridge
assert "POLLHUP" in pty_bridge
assert "errno == EIO" in pty_bridge
assert "jint requested_len" in pty_bridge
assert "requested_len > array_len" in pty_bridge

permission_receiver = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbPermissionReceiver.kt").read_text()
assert "KlipperHostService.EXTRA_FULL_SMOKE" in permission_receiver
assert "KlipperHostService.EXTRA_REAL_CONFIG" in permission_receiver
assert "fun requestNext(" in permission_receiver
assert "supported.firstOrNull { !manager.hasPermission(it) }" in permission_receiver
assert "manager.requestPermission(device, pending)" in permission_receiver
assert 'getSharedPreferences("usb_permission_mode"' in permission_receiver
assert "prefs.edit().clear().apply()" in permission_receiver
assert "KEY_AUTO_START_IN_PROGRESS" in permission_receiver
assert "KEY_AUTO_KIOSK_PENDING" in permission_receiver

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
assert "Auto-start real host + Mainsail on printer USB" in main_activity
assert "handleUsbAttach()" in main_activity
assert "klipperCount < 3" in main_activity
assert "maybeOpenAutoKiosk" in main_activity

host_service = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/KlipperHostService.kt").read_text()
assert "EXTRA_FULL_SMOKE" in host_service
assert "EXTRA_REAL_CONFIG" in host_service
assert 'smokePtyPaths.joinToString("|")' in host_service
assert "stablePtyMap" in host_service
assert "probe_full_klippy" in host_service
assert "http://192.168.1.83:7125" in host_service
assert "START_NOT_STICKY" in host_service
assert "START_STICKY" in host_service
assert "persistentHostActive" in host_service
assert "HostDiagnostics.log" in host_service
assert "THREAD_PRIORITY_URGENT_AUDIO" in host_service
assert "Only arm a PTY for full/real Klippy after the MCU has" in host_service
assert "KEY_AUTO_START_USB" in host_service
assert "KEY_AUTO_KIOSK_PENDING" in host_service
assert "toString().also" in host_service
assert "sessions.forEach { runCatching { it.close() } }" in host_service
assert "Thread.sleep(USB_REOPEN_SETTLE_MS)" in host_service
assert "PowerManager.PARTIAL_WAKE_LOCK" in host_service
assert "acquireHostWakeLock()" in host_service
assert "releaseHostWakeLock()" in host_service
assert "if (!realConfig) releaseHostWakeLock()" in host_service

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
assert 'components/file_manager/file_manager.py' in moon_vendor

moon_patch = (root / "scripts/patch-moonraker.py").read_text()
assert "'database', 'file_manager'" in moon_patch
components_new_block = moon_patch.split('components_new = """', 1)[1].split('"""', 1)[0]
assert "'dbus_manager'" not in components_new_block
assert "'authorization'" in components_new_block
assert "android_request_stop" in moon_patch
assert 'build_shell_command("echo \'[]\'")' in moon_patch
assert "Signal handlers unavailable in embedded Android runtime" in moon_patch
assert "_android_extract_metadata" in moon_patch
assert "metadata_module.extract_metadata" in moon_patch
assert "app_process binary" in moon_patch

vendored_file_manager = root / "app/src/main/python/moonraker/components/file_manager/file_manager.py"
if vendored_file_manager.exists():
    fm_text = vendored_file_manager.read_text()
    md_block = fm_text.split("    async def _run_extract_metadata(", 1)[1].split(
        "    def _create_metadata_cfg(", 1
    )[0]
    assert "self._android_extract_metadata" in md_block
    assert "metadata_module.extract_metadata" in md_block
    assert "sys.executable" not in md_block

persistent = (root / "app/src/main/python/persistent_host.py").read_text()
assert '"apiserver": api_socket' in persistent
assert '"gcode_fd": None' in persistent
assert '"printer_data"' in persistent
assert '"comms", "klippy.sock"' in persistent
assert "def stop():" in persistent
assert "def run(base_url, stable_mapping, c_helper_path, work_dir, device_name):" in persistent

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
assert 'install("Pillow==11.0.0")' in build
assert 'install("inotify-simple==2.0.1")' in build
assert 'implementation("org.nanohttpd:nanohttpd:2.3.1")' in build
mainsail_server = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/MainsailServer.kt").read_text()
assert 'port: Int = 8080' in mainsail_server
assert '"hostname": "$hostname"' in mainsail_server
assert '"port": 7125' in mainsail_server
assert (root / "app/src/main/assets/mainsail/index.html").exists()
assert (root / "app/src/main/assets/mainsail/sw.js").exists()
assert "/androidklipper/battery" in mainsail_server
assert "Intent.ACTION_BATTERY_CHANGED" in mainsail_server
assert "BatteryManager.EXTRA_PRESENT" in mainsail_server
assert '"level": ${level?.toString() ?: "null"}' in mainsail_server
assert "BatteryManager.BATTERY_STATUS_CHARGING" in mainsail_server
mainsail_version = (root / "app/src/main/assets/mainsail/.version").read_text().strip()
assert mainsail_version == "v2.19.0-androidklipper-battery1"
mainsail_main_files = list((root / "app/src/main/assets/mainsail/assets").glob("index-*.js"))
assert len(mainsail_main_files) == 1
mainsail_main = mainsail_main_files[0].read_text(errors="ignore")
assert "/androidklipper/battery" in mainsail_main
assert "androidklipper-battery" in mainsail_main
assert "Android Battery" in mainsail_main
assert "Percent [%]" in mainsail_main
vendor_mainsail = (root / "scripts/vendor-mainsail.sh").read_text()
assert "5fb9e77fb9f4e60cf0725d9dc7f57cf7b84bbd70" in vendor_mainsail
mainsail_patch = (root / "scripts/patch-mainsail.py").read_text()
assert "androidklipper-battery" in mainsail_patch
assert "Percent [%]" in mainsail_patch
assert "prepareGcodeStorage" in host_service
assert "Environment.isExternalStorageRemovable" in host_service
assert "getExternalFilesDir(null)" in host_service
assert '"AndroidKlipperDrive"' in host_service
assert '"G-code drive: "' in host_service
assert "if (!dest.exists())" in host_service
assert "Os.symlink" in host_service
mainsail_activity = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/MainsailActivity.kt").read_text()
assert 'loadUrl("http://127.0.0.1:8080/")' in mainsail_activity
assert "setLayerType(View.LAYER_TYPE_HARDWARE" in mainsail_activity
assert "FLAG_KEEP_SCREEN_ON" in mainsail_activity


assert "STREAMING_FORM_DATA_AVAILABLE" in moon_patch
upload_compat = (root / "app/src/main/python/streaming_form_data/__init__.py").read_text()
upload_targets = (root / "app/src/main/python/streaming_form_data/targets.py").read_text()
assert "class StreamingFormDataParser" in upload_compat
assert "cgi.FieldStorage" in upload_compat
assert "class FileTarget" in upload_targets
assert "class SHA256Target" in upload_targets
assert "File uploads disabled: streaming-form-data is unavailable" in moon_patch

assert "android_get_last_error" in moon_patch
assert "_ANDROID_CURRENT_SERVER = None" in moon_patch
assert "_ANDROID_LAST_ERROR = traceback.format_exc()" in moon_patch
assert "direct Moonraker traceback" in moon_runner
assert "file_system_observer: none" in moon_runner
assert '"-l", os.path.join(p["logs_dir"], "moonraker.log")' in moon_runner

checkpoint_capture = (root / "scripts/capture-runtime-checkpoint.py").read_text()
assert "/printer/objects/query" in checkpoint_capture
assert "/server/history/list?limit=10" in checkpoint_capture
assert "androidklipper-host.log" in checkpoint_capture
assert "moonraker-tail.log" in checkpoint_capture

diagnostics = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/HostDiagnostics.kt").read_text()
assert "getHistoricalProcessExitReasons" in diagnostics
assert "androidklipper-host.log" in diagnostics

# Android reliability invariants. These are intentionally explicit because
# timing and lifecycle regressions can look like random MCU/USB failures.
usb_session = (root / "app/src/main/java/dev/nozzlenaut/androidklipper/usb/UsbSerialSession.kt").read_text()
assert "maxWriteTransferUs" in usb_session
assert "val packetSize = endpoint.maxPacketSize" not in usb_session
assert "WRITE_ATTEMPT_TIMEOUT_MS = 25" in usb_session
assert "THREAD_PRIORITY_URGENT_AUDIO" in host_service
assert "scheduleFirmwareRestartRecovery" in host_service
assert "persistentHostActive.set(true)" in host_service
assert "firmware restart recovery cancelled: host no longer desired" in host_service
assert "usb_rebind_required: firmware_restart" in persistent
assert "AndroidKlipper reactor timer late" in persistent
assert "final protocol compatibility checked when Klippy reaches ready" in (root / "app/src/main/python/hostprobe.py").read_text()
assert "FIRMWARE_REENUM_TIMEOUT_MS = 15_000L" in host_service
assert "source.copyOfRange(offset, length)" not in usb_session
hostprobe = (root / "app/src/main/python/hostprobe.py").read_text()
assert "probe_klipper_version" in hostprobe
assert "def get_klipper_version" in hostprobe
assert '"software_version": get_klipper_version()' in hostprobe
assert '"software_version": get_klipper_version()' in persistent
assert '"androidklipper-persistent"' not in persistent
assert '"androidklipper-smoke"' not in hostprobe
assert '"androidklipper-real-config"' not in hostprobe
assert hostprobe.count('"software_version": get_klipper_version()') >= 2
assert 'first_line == "MCU Protocol error"' in persistent

klipper_patch = (root / "scripts/patch-klipper.py").read_text()
assert 'schedule_replacement = "MIN_SCHEDULE_TIME = 0.250"' in klipper_patch
assert "BUFFER_TIME_HIGH = 2.0" in klipper_patch
assert "BUFFER_TIME_START = 0.750" in klipper_patch
assert 'reqtime_replacement = "#define MIN_REQTIME_DELTA 0.500"' in klipper_patch
assert "BGFLUSH_SG_LOW_TIME = 1.000" in klipper_patch
assert "BGFLUSH_SG_HIGH_TIME = 1.500" in klipper_patch
vendor_script = (root / "scripts/vendor-klipper.sh").read_text()
assert '"$VENDOR/klippy/chelper/serialqueue.c"' in vendor_script
assert '"$PY_VENDOR/klippy/extras/motion_queuing.py"' in vendor_script
assert '"$PY_ROOT/extras/motion_queuing.py"' in vendor_script
assert "KLIPPER_VERSION" in vendor_script

# When the vendor step has run (always true in CI), verify the generated Klipper
# tree rather than trusting the patch script alone.
vendored = root / "app/src/main/python/klipper_vendor"
if vendored.exists():
    mcu_text = (vendored / "klippy/mcu.py").read_text()
    toolhead_text = (vendored / "klippy/toolhead.py").read_text()
    assert "MIN_SCHEDULE_TIME = 0.250" in mcu_text
    assert "TRSYNC_TIMEOUT = 0.050" in mcu_text
    assert "BUFFER_TIME_HIGH = 2.0" in toolhead_text
    assert "BUFFER_TIME_START = 0.750" in toolhead_text
    motion_vendor_text = (vendored / "klippy/extras/motion_queuing.py").read_text()
    motion_root_text = (root / "app/src/main/python/extras/motion_queuing.py").read_text()
    for motion_text in (motion_vendor_text, motion_root_text):
        assert "BGFLUSH_SG_LOW_TIME = 1.000" in motion_text
        assert "BGFLUSH_SG_HIGH_TIME = 1.500" in motion_text
    vendor_serialqueue = root / "vendor/klipper/klippy/chelper/serialqueue.c"
    assert vendor_serialqueue.exists()
    assert "#define MIN_REQTIME_DELTA 0.500" in vendor_serialqueue.read_text()
    assert (vendored / "KLIPPER_COMMIT").read_text().strip() == "2d7717e3b62ea2fe3401b27f54f8681f80451c69"
    assert (vendored / "KLIPPER_VERSION").read_text().strip() == "v0.13.0-756-g2d7717e3"

print("project invariants: PASS")

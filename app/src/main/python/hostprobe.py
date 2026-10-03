def _klippy_path():
    import os
    import sys
    here = os.path.dirname(__file__)
    klippy_dir = os.path.join(here, "klipper_vendor", "klippy")
    if klippy_dir not in sys.path:
        sys.path.insert(0, klippy_dir)
    return klippy_dir


def probe_pipe_open(path):
    """Open the Android PTY as a plain byte pipe without serial ioctls."""
    import os
    fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    os.close(fd)
    return "PTY pipe open OK"


def probe_c_helper(path):
    """Load the APK-packaged Klipper c_helper and call one harmless symbol."""
    from cffi import FFI
    ffi = FFI()
    ffi.cdef("double get_monotonic(void);")
    lib = ffi.dlopen(path)
    value = float(lib.get_monotonic())
    if value <= 0:
        raise RuntimeError("c_helper get_monotonic returned an invalid value")
    return "c_helper OK"


def get_klipper_version():
    """Return the exact pinned Klipper revision bundled in this APK."""
    import os
    vendor_dir = os.path.join(os.path.dirname(__file__), "klipper_vendor")
    for filename in ("KLIPPER_VERSION", "KLIPPER_COMMIT"):
        version_path = os.path.join(vendor_dir, filename)
        try:
            with open(version_path, "r", encoding="utf-8") as version_file:
                version = version_file.read().strip()
            if version:
                return version
        except OSError:
            pass
    return "unknown"


def probe_klipper_version():
    """Human-readable pinned engine version for Android host diagnostics."""
    return (
        "Klipper engine: %s (pinned; MCU protocol compatibility is checked at connect)"
        % get_klipper_version()
    )


def probe_klipper_import():
    """Import Klipper core host modules without connecting to a printer."""
    _klippy_path()
    import klippy  # noqa: F401
    import reactor  # noqa: F401
    import serialhdl  # noqa: F401
    import mcu  # noqa: F401
    import toolhead  # noqa: F401
    import extras.error_mcu  # noqa: F401
    import kinematics.none  # noqa: F401
    return "Klipper imports OK (core + extras + kinematics)"


def check_config_includes(config_path):
    """Return missing direct Klipper includes without validating printer options.

    This mirrors Klipper's include resolution closely enough for onboarding:
    paths are relative to the file containing the include, nested includes are
    followed, and an empty wildcard is allowed just like upstream Klipper.
    The goal is only to avoid starting Klippy halfway through a multi-file
    Mainsail upload.
    """
    import configparser
    import glob
    import os

    missing = []
    visited = set()

    def scan(filename):
        path = os.path.abspath(filename)
        if path in visited:
            return
        visited.add(path)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as cfg:
                lines = cfg.read().splitlines()
        except OSError:
            missing.append(path)
            return

        for line in lines:
            # Klipper strips trailing # comments before matching section headers.
            line = line.split("#", 1)[0]
            match = configparser.RawConfigParser.SECTCRE.match(line)
            header = match and match.group("header")
            if not header or not header.startswith("include "):
                continue
            include_spec = header[8:].strip()
            include_glob = os.path.join(os.path.dirname(path), include_spec)
            matches = sorted(glob.glob(include_glob))
            if not matches and not glob.has_magic(include_glob):
                missing.append(include_glob)
                continue
            for include_path in matches:
                scan(include_path)

    scan(config_path)
    if not missing:
        return "ready"

    root = os.path.dirname(os.path.abspath(config_path))
    display = []
    for path in missing:
        try:
            rel = os.path.relpath(path, root)
        except ValueError:
            rel = path
        if rel not in display:
            display.append(rel)
    return "missing: " + " | ".join(display)


def probe_mcu_identify(path, c_helper_path, baud=115200):
    """Run only Klipper's MCU identify handshake, then disconnect.

    This loads the same SerialReader/c_helper code used by Klippy but never
    sends a printer configuration, motion, heater, or GPIO command.
    """
    import os
    os.environ["ANDROID_KLIPPER_CHELPER"] = c_helper_path
    _klippy_path()

    import reactor
    import serialhdl

    r = reactor.Reactor()
    result = {}
    serial_reader = None

    def connect(eventtime):
        nonlocal serial_reader
        try:
            serial_reader = serialhdl.SerialReader(r, mcu_name="android-probe")
            # The Kotlin USB bridge owns/configures the physical UART. Klipper
            # should treat its PTY endpoint as a byte pipe, not a hardware UART.
            serial_reader.connect_pipe(path)
            parser = serial_reader.get_msgparser()
            version, build = parser.get_version_info()
            constants = parser.get_constants()
            result["version"] = str(version)
            result["build"] = str(build)
            result["mcu"] = str(constants.get("MCU", "unknown"))
            result["clock"] = str(constants.get("CLOCK_FREQ", "unknown"))
            result["commands"] = str(len(parser.get_messages()))
        except BaseException as exc:
            result["error"] = "%s: %s" % (type(exc).__name__, exc)
        finally:
            if serial_reader is not None:
                try:
                    serial_reader.disconnect()
                except BaseException:
                    pass
            r.end()
        return r.NEVER

    r.register_callback(connect)
    try:
        r.run()
    finally:
        r.finalize()

    if "error" in result:
        raise RuntimeError(result["error"])
    return (
        "Klipper identify OK: MCU={mcu}, {commands} commands, "
        "firmware={version}, build={build} (identified; final protocol compatibility checked when Klippy reaches ready)"
    ).format(**result)


def probe_full_klippy(pty_paths, c_helper_path, work_dir):
    """Run real Klippy against all Android PTYs with a no-pin test config.

    This intentionally configures each MCU with only Klipper's bookkeeping
    commands (allocate_oids/finalize_config). No steppers, heaters, fans,
    LEDs, probes, or GPIO pins are defined. The test exits immediately when
    Klippy reaches ready, or on error/timeout.
    """
    import os
    os.environ["ANDROID_KLIPPER_CHELPER"] = c_helper_path
    _klippy_path()

    import klippy
    import reactor

    paths = [p for p in str(pty_paths).split("|") if p]
    if not paths:
        raise RuntimeError("No PTY paths supplied for full Klippy smoke test")

    os.makedirs(work_dir, exist_ok=True)
    config_path = os.path.join(work_dir, "androidklipper-smoke.cfg")
    lines = []
    for index, path in enumerate(paths):
        section = "[mcu]" if index == 0 else "[mcu smoke%d]" % index
        lines.extend([section, "serial: %s" % path, ""])
    lines.extend([
        "[printer]",
        "kinematics: none",
        "max_velocity: 1",
        "max_accel: 1",
        "",
    ])
    with open(config_path, "w", encoding="utf-8") as config_file:
        config_file.write("\n".join(lines))

    r = reactor.Reactor()
    read_fd, write_fd = os.pipe()
    start_args = {
        "config_file": config_path,
        "apiserver": None,
        "start_reason": "startup",
        "gcode_fd": read_fd,
        "software_version": get_klipper_version(),
    }
    printer = klippy.Printer(r, None, start_args)
    result = {}

    def on_ready():
        result["ready"] = True
        printer.request_exit("exit")

    def watch_state(eventtime):
        state_message, state = printer.get_state_message()
        if state in ("error", "shutdown"):
            result["error"] = state_message
            printer.request_exit("error_exit")
            return r.NEVER
        return eventtime + 0.25

    def timeout(eventtime):
        result["error"] = "Timed out waiting for Klippy ready"
        printer.request_exit("error_exit")
        return r.NEVER

    printer.register_event_handler("klippy:ready", on_ready)
    r.register_timer(watch_state, r.monotonic() + 0.25)
    r.register_timer(timeout, r.monotonic() + 30.0)

    try:
        printer.run()
        state_message, state = printer.get_state_message()
    finally:
        try:
            os.close(read_fd)
        except OSError:
            pass
        try:
            os.close(write_fd)
        except OSError:
            pass
        r.finalize()

    if not result.get("ready") or state != "ready":
        detail = result.get("error", state_message)
        raise RuntimeError("Full Klippy smoke failed: %s" % detail)
    return "Full Klippy smoke READY: %d MCUs, kinematics=none, no configured pins" % len(paths)

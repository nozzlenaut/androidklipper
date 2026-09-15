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
    return "Klipper identify OK: MCU={mcu}, {commands} commands, {version}".format(**result)


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
        "software_version": "androidklipper-smoke",
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

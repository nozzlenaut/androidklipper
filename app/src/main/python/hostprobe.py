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


def _remove_config_section(text, section_name):
    import re
    lines = text.splitlines()
    out = []
    skipping = False
    target = section_name.strip().lower()
    for line in lines:
        match = re.match(r"^\s*\[([^\]]+)\]\s*$", line)
        if match:
            current = match.group(1).strip().lower()
            skipping = current == target
            if skipping:
                continue
        if not skipping:
            out.append(line)
    return "\n".join(out) + "\n"


def _sanitize_android_config(name, text, stable_to_pty, work_dir):
    import os
    import re

    if name == "printer.cfg":
        text = re.sub(
            r"(?im)^\s*\[include\s+K-ShakeTune/\*\.cfg\]\s*$\n?",
            "",
            text,
        )
        text = _remove_config_section(text, "shaketune")
        text = _remove_config_section(text, "temperature_sensor NUC")

    def rewrite_serial(match):
        prefix, value, suffix = match.group(1), match.group(2), match.group(3)
        for stable_id, pty_path in stable_to_pty.items():
            if stable_id in value:
                return "%s%s%s" % (prefix, pty_path, suffix)
        return match.group(0)

    text = re.sub(
        r"(?im)^(\s*serial\s*:\s*)(\S+)(.*)$",
        rewrite_serial,
        text,
    )

    # Android PTYs are treated as Klipper pipe transports, not UARTs.
    # Upstream only consumes restart_method when a baud-backed UART is used,
    # so leaving it in a rewritten PTY MCU section makes config validation fail.
    text = re.sub(
        r"(?im)^\s*restart_method\s*:\s*\S+\s*$\n?",
        "",
        text,
    )

    gcodes_dir = os.path.join(work_dir, "gcodes")
    variables_path = os.path.join(work_dir, "config", "variables.cfg")
    text = re.sub(
        r"(?im)^(\s*path\s*:\s*)~/printer_data/gcodes\s*$",
        lambda m: m.group(1) + gcodes_dir,
        text,
    )
    text = re.sub(
        r"(?im)^(\s*filename\s*:\s*)~/printer_data/config/variables\.cfg\s*$",
        lambda m: m.group(1) + variables_path,
        text,
    )
    return text


def probe_real_config_from_moonraker(base_url, stable_mapping, c_helper_path, work_dir):
    """Fetch the active Voron config from Moonraker and run a real Klippy startup.

    Optional host-only K-ShakeTune and the old NUC temperature sensor are
    omitted. MCU by-id serials are rewritten to Android PTYs by stable USB ID.
    Klippy exits immediately after reaching ready.
    """
    import os
    import re
    import urllib.parse
    import urllib.request

    os.environ["ANDROID_KLIPPER_CHELPER"] = c_helper_path
    _klippy_path()

    import klippy
    import reactor

    stable_to_pty = {}
    for item in str(stable_mapping).split("|"):
        if not item or "=" not in item:
            continue
        stable_id, pty_path = item.split("=", 1)
        stable_to_pty[stable_id] = pty_path

    required_ids = {
        "3F001E001450535556323420",
        "3033393834057C77",
        "504450610844C31C",
    }
    missing_ids = required_ids.difference(stable_to_pty)
    if missing_ids:
        raise RuntimeError("Missing expected MCU mappings: %s" % sorted(missing_ids))

    config_root = os.path.join(work_dir, "real_config")
    os.makedirs(config_root, exist_ok=True)
    os.makedirs(os.path.join(config_root, "gcodes"), exist_ok=True)
    os.makedirs(os.path.join(config_root, "config"), exist_ok=True)

    fetched = {}
    skipped_includes = []

    def fetch_config(relpath):
        relpath = relpath.replace("\\", "/").lstrip("/")
        if relpath in fetched:
            return fetched[relpath]
        if any(ch in relpath for ch in "*?["):
            skipped_includes.append(relpath)
            return ""
        url = base_url.rstrip("/") + "/server/files/config/" + urllib.parse.quote(relpath, safe="/")
        with urllib.request.urlopen(url, timeout=8) as response:
            text = response.read().decode("utf-8")
        fetched[relpath] = text
        for inc in re.findall(r"(?im)^\s*\[include\s+([^\]]+)\]\s*$", text):
            inc = inc.strip()
            if inc.lower().startswith("k-shaketune/"):
                skipped_includes.append(inc)
                continue
            fetch_config(inc)
        return text

    fetch_config("printer.cfg")
    try:
        fetch_config("variables.cfg")
    except Exception:
        pass

    for relpath, source in fetched.items():
        clean = _sanitize_android_config(relpath, source, stable_to_pty, config_root)
        dest = os.path.join(config_root, relpath)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as out_file:
            out_file.write(clean)

    variables_src = os.path.join(config_root, "variables.cfg")
    variables_dest = os.path.join(config_root, "config", "variables.cfg")
    if os.path.exists(variables_src):
        with open(variables_src, "rb") as src, open(variables_dest, "wb") as dst:
            dst.write(src.read())
    elif not os.path.exists(variables_dest):
        with open(variables_dest, "w", encoding="utf-8") as out_file:
            out_file.write("[Variables]\n")

    config_path = os.path.join(config_root, "printer.cfg")
    if not os.path.exists(config_path):
        raise RuntimeError("Moonraker import did not produce printer.cfg")

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
        result["error"] = "Timed out waiting for real config Klippy ready"
        printer.request_exit("error_exit")
        return r.NEVER

    printer.register_event_handler("klippy:ready", on_ready)
    r.register_timer(watch_state, r.monotonic() + 0.25)
    r.register_timer(timeout, r.monotonic() + 45.0)

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
        raise RuntimeError("Real Voron config failed: %s" % detail)

    mapping_summary = ", ".join(sorted(stable_to_pty))
    skipped_summary = ", ".join(sorted(set(skipped_includes))) or "none"
    return (
        "REAL VORON CONFIG READY: 3 MCUs; "
        "mapped IDs=%s; skipped optional includes=%s"
        % (mapping_summary, skipped_summary)
    )

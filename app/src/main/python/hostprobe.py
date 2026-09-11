def _klippy_path():
    import os
    import sys
    here = os.path.dirname(__file__)
    klippy_dir = os.path.join(here, "klipper_vendor", "klippy")
    if klippy_dir not in sys.path:
        sys.path.insert(0, klippy_dir)
    return klippy_dir


def probe_serial(path, baud=115200):
    """Harmlessly prove embedded Python + pySerial can exclusively open a PTY."""
    import serial
    port = serial.Serial(port=None, baudrate=baud, timeout=0, exclusive=True)
    port.port = path
    try:
        port.open()
        return "pySerial OK"
    finally:
        if port.is_open:
            port.close()


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
    return "Klipper core imports OK"


def probe_klipper_import_sweep():
    """Import every bundled extras/kinematics module without configuring it.

    Upstream Klipper has an equivalent --import-test path. Running the sweep on
    the actual Android Python runtime catches missing packaged dependencies
    before we ever attempt to start a printer.
    """
    import importlib
    import os

    klippy_dir = _klippy_path()
    count = 0
    for package in ("extras", "kinematics"):
        package_dir = os.path.join(klippy_dir, package)
        for entry in os.listdir(package_dir):
            if entry.endswith(".py") and entry != "__init__.py":
                module_name = entry[:-3]
            else:
                init_file = os.path.join(package_dir, entry, "__init__.py")
                if not os.path.exists(init_file):
                    continue
                module_name = entry
            importlib.import_module(package + "." + module_name)
            count += 1
    return "Klipper import sweep OK: %d modules" % count


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

            # Do one direct PTY session rather than connect_uart(). Upstream
            # connect_uart retries for up to 90 seconds and sends an AVR
            # stk500v2 leave-programmer sequence before identifying. Neither is
            # desirable for this deliberately harmless diagnostic. The Android
            # bridge owns/configures the physical USB CDC port; this side only
            # needs a raw byte stream into Klipper's normal SerialReader.
            fd = os.open(path, os.O_RDWR | os.O_NOCTTY)
            try:
                serial_dev = os.fdopen(fd, "rb+", buffering=0)
            except BaseException:
                os.close(fd)
                raise
            if not serial_reader._start_session(serial_dev):
                raise RuntimeError("MCU identify timed out")

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

def _klippy_path():
    import os
    import sys
    here = os.path.dirname(__file__)
    klippy_dir = os.path.join(here, "klipper_vendor", "klippy")
    if klippy_dir not in sys.path:
        sys.path.insert(0, klippy_dir)
    return klippy_dir


def probe_serial(path, baud=115200):
    """Harmlessly prove embedded Python + pySerial can open an Android PTY."""
    import serial
    # Android PTYs reject pySerial's TIOCEXCL ioctl with EACCES. The native
    # bridge already owns the physical USB device, so an extra PTY lock is
    # unnecessary here.
    port = serial.Serial(port=None, baudrate=baud, timeout=0, exclusive=False)
    port.port = path
    port.open()
    port.close()
    return "pySerial OK"


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
    return "Klipper imports OK"


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

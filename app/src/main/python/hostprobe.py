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


def probe_mcu_get_config(path, c_helper_path):
    """Identify one MCU and then issue the same harmless get_config query Klippy
    sends first during startup. This does not configure, move, heat, or toggle GPIO.
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
            serial_reader = serialhdl.SerialReader(r, mcu_name="android-get-config-probe")
            serial_reader.connect_pipe(path)
            parser = serial_reader.get_msgparser()
            version, build = parser.get_version_info()
            response = serial_reader.send_with_response("get_config", "config")
            result["mcu"] = str(parser.get_constants().get("MCU", "unknown"))
            result["version"] = str(version)
            result["is_config"] = str(response.get("is_config"))
            result["crc"] = str(response.get("crc"))
            result["is_shutdown"] = str(response.get("is_shutdown"))
            result["move_count"] = str(response.get("move_count"))
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
        "identify + get_config OK: MCU={mcu}, is_config={is_config}, "
        "crc={crc}, shutdown={is_shutdown}, moves={move_count}, {version}"
    ).format(**result)

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
    import os
    import sys
    here = os.path.dirname(__file__)
    klippy_dir = os.path.join(here, "klipper_vendor", "klippy")
    if klippy_dir not in sys.path:
        sys.path.insert(0, klippy_dir)
    import klippy  # noqa: F401
    import reactor  # noqa: F401
    import serialhdl  # noqa: F401
    import mcu  # noqa: F401
    import toolhead  # noqa: F401
    return "Klipper imports OK"

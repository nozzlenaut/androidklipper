#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 5:
    raise SystemExit("usage: patch-klipper.py <chelper/__init__.py> <serialhdl.py> <mcu.py> <util.py>")

chelper_path = Path(sys.argv[1])
serialhdl_path = Path(sys.argv[2])
mcu_path = Path(sys.argv[3])
util_path = Path(sys.argv[4])

text = chelper_path.read_text()
needle = "def check_build_c_library():\n    srcdir = os.path.dirname(os.path.realpath(__file__))\n"
replacement = """def check_build_c_library():
    # AndroidKlipper packages this library with the APK. Android apps should not
    # ship a compiler or build native code at runtime.
    prebuilt = os.environ.get('ANDROID_KLIPPER_CHELPER')
    if prebuilt and os.path.exists(prebuilt):
        return prebuilt
    srcdir = os.path.dirname(os.path.realpath(__file__))
"""
if needle not in text:
    raise SystemExit("Klipper chelper patch point changed; inspect upstream before updating the pin")
chelper_path.write_text(text.replace(needle, replacement, 1))

text = serialhdl_path.read_text()
needle = """                serial_dev = serial.Serial(baudrate=baud, timeout=0,
                                           exclusive=True)
"""
replacement = """                use_exclusive = not os.environ.get('ANDROID_KLIPPER_NO_EXCLUSIVE')
                serial_dev = serial.Serial(baudrate=baud, timeout=0,
                                           exclusive=use_exclusive)
"""
if needle not in text:
    raise SystemExit("Klipper serialhdl patch point changed; inspect upstream before updating the pin")
serialhdl_path.write_text(text.replace(needle, replacement, 1))

text = mcu_path.read_text()
needle = """            if not (self._serialport.startswith("/dev/rpmsg_")
                    or self._serialport.startswith("/tmp/klipper_host_")):
"""
replacement = """            if not (self._serialport.startswith("/dev/rpmsg_")
                    or self._serialport.startswith("/tmp/klipper_host_")
                    or self._serialport.startswith("/dev/pts/")):
"""
if needle not in text:
    raise SystemExit("Klipper mcu pipe patch point changed; inspect upstream before updating the pin")
mcu_path.write_text(text.replace(needle, replacement, 1))

text = util_path.read_text()
needle = "    os.chmod(filename, 0o660)\n    os.symlink(filename, ptyname)\n"
replacement = """    try:
        os.chmod(filename, 0o660)
    except OSError:
        # Android's devpts mount may reject chmod even for a PTY created by
        # this process. The descriptor is already private to the app.
        pass
    os.symlink(filename, ptyname)
"""
if needle not in text:
    raise SystemExit("Klipper util PTY patch point changed; inspect upstream before updating the pin")
util_path.write_text(text.replace(needle, replacement, 1))

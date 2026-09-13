#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: patch-klipper.py <chelper/__init__.py> <serialhdl.py>")

chelper_path = Path(sys.argv[1])
serialhdl_path = Path(sys.argv[2])

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
replacement = """                # Android app sandboxes can open the PTY but may reject the
                # TIOCEXCL/flock operation pySerial uses for exclusive=True.
                # The PTY is private to AndroidKlipper, so exclusivity is
                # already enforced by the app's own bridge lifecycle.
                use_exclusive = not os.environ.get('ANDROID_KLIPPER_NO_EXCLUSIVE')
                serial_dev = serial.Serial(baudrate=baud, timeout=0,
                                           exclusive=use_exclusive)
"""
if needle not in text:
    raise SystemExit("Klipper serialhdl patch point changed; inspect upstream before updating the pin")
serialhdl_path.write_text(text.replace(needle, replacement, 1))

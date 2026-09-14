#!/usr/bin/env python3
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
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
path.write_text(text.replace(needle, replacement, 1))

if len(sys.argv) >= 3:
    serial_path = Path(sys.argv[2])
    serial_text = serial_path.read_text()
    serial_needle = """    def connect_uart(self, serialport, baud, rts=True):
        # Initial connection
"""
    serial_replacement = """    def connect_uart(self, serialport, baud, rts=True):
        # AndroidKlipper's Kotlin layer owns/configures the physical USB UART.
        # Its /dev/pts endpoints are byte pipes; treating them as hardware UARTs
        # triggers unsupported exclusive-lock and modem-control ioctls on Android.
        if serialport.startswith('/dev/pts/'):
            return self.connect_pipe(serialport)
        # Initial connection
"""
    if serial_needle not in serial_text:
        raise SystemExit("Klipper serialhdl patch point changed; inspect upstream before updating the pin")
    serial_path.write_text(serial_text.replace(serial_needle, serial_replacement, 1))

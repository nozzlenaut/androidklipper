"""AndroidKlipper launcher adapter for the embedded Klippy runtime."""

import os
import sys

_active_printer = None


def _klippy_path():
    here = os.path.dirname(__file__)
    return os.path.join(here, "klipper_vendor", "klippy")


def run(config_path, api_socket, log_path, input_tty, c_helper_path):
    global _active_printer

    os.environ["ANDROID_KLIPPER_CHELPER"] = c_helper_path
    os.environ["ANDROID_KLIPPER_NO_EXCLUSIVE"] = "1"

    klippy_dir = _klippy_path()
    if klippy_dir not in sys.path:
        sys.path.insert(0, klippy_dir)

    import klippy

    original_printer = klippy.Printer

    class AndroidPrinter(original_printer):
        def __init__(self, *args, **kwargs):
            global _active_printer
            super().__init__(*args, **kwargs)
            _active_printer = self

    klippy.Printer = AndroidPrinter
    argv = [
        os.path.join(klippy_dir, "klippy.py"),
        config_path,
        "-a", api_socket,
        "-l", log_path,
        "-I", input_tty,
    ]
    old_argv = sys.argv
    try:
        sys.argv = argv
        return klippy.main()
    finally:
        _active_printer = None
        klippy.Printer = original_printer
        sys.argv = old_argv


def stop():
    printer = _active_printer
    if printer is None:
        return False

    reactor = printer.get_reactor()

    def request_exit(eventtime):
        printer.request_exit("exit")
        return reactor.NEVER

    reactor.register_async_callback(request_exit)
    return True

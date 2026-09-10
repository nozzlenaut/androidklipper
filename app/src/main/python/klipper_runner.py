"""Klipper launcher adapter for the later runtime milestone.

This is intentionally not wired to the UI yet. It gives us one controlled place
for Android-specific environment setup when real Klippy execution is enabled.
"""
import os
import runpy
import sys


def run(klippy_dir, config_path, api_socket, log_path, input_tty, c_helper_path):
    os.environ["ANDROID_KLIPPER_CHELPER"] = c_helper_path
    if klippy_dir not in sys.path:
        sys.path.insert(0, klippy_dir)
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
        return runpy.run_path(argv[0], run_name="__main__")
    finally:
        sys.argv = old_argv

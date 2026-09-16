"""Persistent real Klippy runtime for AndroidKlipper.

This is separate from hostprobe.py so the proven diagnostic startup path stays
small and unchanged.  Moonraker connects to the normal Klipper webhooks Unix
socket exposed from this runtime.
"""
import gc
import os
import re
import time
import urllib.parse
import urllib.request

from hostprobe import _klippy_path, _sanitize_android_config

_REQUIRED_IDS = {
    "3F001E001450535556323420",
    "3033393834057C77",
    "504450610844C31C",
}

_current_printer = None
_current_reactor = None
_stop_requested = False
_status = "stopped"


def _set_status(value):
    global _status
    _status = str(value)


def get_status():
    return _status


def get_data_path(work_dir):
    return os.path.join(work_dir, "printer_data")


def get_klippy_socket(work_dir):
    return os.path.join(get_data_path(work_dir), "comms", "klippy.sock")


def _parse_mapping(stable_mapping):
    stable_to_pty = {}
    for item in str(stable_mapping).split("|"):
        if not item or "=" not in item:
            continue
        stable_id, pty_path = item.split("=", 1)
        stable_to_pty[stable_id] = pty_path
    missing_ids = _REQUIRED_IDS.difference(stable_to_pty)
    if missing_ids:
        raise RuntimeError("Missing expected MCU mappings: %s" % sorted(missing_ids))
    return stable_to_pty


def _import_config(base_url, stable_mapping, work_dir):
    stable_to_pty = _parse_mapping(stable_mapping)
    data_root = get_data_path(work_dir)
    config_dir = os.path.join(data_root, "config")
    for dirname in ("config", "gcodes", "logs", "comms", "database"):
        os.makedirs(os.path.join(data_root, dirname), exist_ok=True)

    fetched = {}
    skipped_includes = []

    def fetch_config(relpath):
        relpath = relpath.replace("\\", "/").lstrip("/")
        if relpath in fetched:
            return fetched[relpath]
        if any(ch in relpath for ch in "*?["):
            skipped_includes.append(relpath)
            return ""
        url = (
            base_url.rstrip("/")
            + "/server/files/config/"
            + urllib.parse.quote(relpath, safe="/")
        )
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
        clean = _sanitize_android_config(
            relpath, source, stable_to_pty, data_root
        )
        dest = os.path.join(config_dir, relpath)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as out_file:
            out_file.write(clean)

    variables_path = os.path.join(config_dir, "variables.cfg")
    if not os.path.exists(variables_path):
        with open(variables_path, "w", encoding="utf-8") as out_file:
            out_file.write("[Variables]\n")

    config_path = os.path.join(config_dir, "printer.cfg")
    if not os.path.exists(config_path):
        raise RuntimeError("Moonraker import did not produce printer.cfg")
    return config_path, data_root, skipped_includes


def stop():
    """Request a clean exit from the currently running Klippy reactor."""
    global _stop_requested
    _stop_requested = True
    printer = _current_printer
    reactor = _current_reactor
    if printer is None or reactor is None:
        return "Klippy is not running"

    def request_stop(eventtime):
        printer.request_exit("exit")
        return reactor.NEVER

    try:
        reactor.register_async_callback(request_stop)
        return "Klippy stop requested"
    except BaseException as exc:
        return "Klippy stop ERROR %s: %s" % (type(exc).__name__, exc)


def run(base_url, stable_mapping, c_helper_path, work_dir):
    """Run the real Voron config persistently and expose Klipper's API socket.

    This function intentionally blocks for the lifetime of Klippy.  Android
    runs it on a dedicated worker thread.  RESTART/FIRMWARE_RESTART requests
    use Klipper's normal outer restart loop; stop() ends the loop cleanly.
    """
    global _current_printer, _current_reactor, _stop_requested

    os.environ["ANDROID_KLIPPER_CHELPER"] = c_helper_path
    _klippy_path()
    import klippy
    import reactor

    config_path, data_root, skipped = _import_config(
        base_url, stable_mapping, work_dir
    )
    api_socket = os.path.join(data_root, "comms", "klippy.sock")
    log_path = os.path.join(data_root, "logs", "klippy.log")
    try:
        os.unlink(api_socket)
    except FileNotFoundError:
        pass

    _stop_requested = False
    start_args = {
        "config_file": config_path,
        "apiserver": api_socket,
        "start_reason": "startup",
        "gcode_fd": None,
        "software_version": "androidklipper-persistent",
        "log_file": log_path,
    }

    skipped_summary = ", ".join(sorted(set(skipped))) or "none"
    _set_status(
        "starting: config=%s; socket=%s; skipped=%s"
        % (config_path, api_socket, skipped_summary)
    )

    final_result = "exit"
    try:
        while not _stop_requested:
            gc.collect()
            main_reactor = reactor.Reactor()
            printer = klippy.Printer(main_reactor, None, start_args)
            _current_reactor = main_reactor
            _current_printer = printer

            def on_ready():
                _set_status("ready: %s" % api_socket)

            def watch_state(eventtime):
                message, state = printer.get_state_message()
                first_line = message.strip().split("\n", 1)[0]
                _set_status("%s: %s" % (state, first_line))
                return eventtime + 0.5

            printer.register_event_handler("klippy:ready", on_ready)
            main_reactor.register_timer(
                watch_state, main_reactor.monotonic() + 0.25
            )

            result = printer.run()
            final_result = result or "exit"
            try:
                main_reactor.finalize()
            finally:
                _current_printer = None
                _current_reactor = None

            if _stop_requested or result in ("exit", "error_exit", None):
                break
            time.sleep(1.0)
            start_args["start_reason"] = result
            _set_status("restarting: %s" % result)
    finally:
        _current_printer = None
        _current_reactor = None
        try:
            os.unlink(api_socket)
        except FileNotFoundError:
            pass
        _set_status("stopped: %s" % final_result)

    return "Persistent Klippy stopped: %s" % final_result

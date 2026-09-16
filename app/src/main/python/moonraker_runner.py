"""Embedded Moonraker launcher for AndroidKlipper.

Runs the pinned upstream Moonraker package against persistent_host's normal
Klipper webhooks Unix socket.  The first Android milestone is loopback-only.
"""
import os
import sys
import traceback

_status = "stopped"


def _set_status(value):
    global _status
    _status = str(value)


def get_status():
    return _status


def _paths(work_dir):
    data_root = os.path.join(work_dir, "printer_data")
    return {
        "data_root": data_root,
        "config_dir": os.path.join(data_root, "config"),
        "gcodes_dir": os.path.join(data_root, "gcodes"),
        "logs_dir": os.path.join(data_root, "logs"),
        "database_dir": os.path.join(data_root, "database"),
        "comms_dir": os.path.join(data_root, "comms"),
        "klippy_socket": os.path.join(data_root, "comms", "klippy.sock"),
        "moonraker_config": os.path.join(data_root, "config", "moonraker.conf"),
    }


def prepare_config(work_dir):
    p = _paths(work_dir)
    for key in ("config_dir", "gcodes_dir", "logs_dir", "database_dir", "comms_dir"):
        os.makedirs(p[key], exist_ok=True)

    config = """[server]
host: 127.0.0.1
port: 7125
klippy_uds_address: {klippy_socket}

[machine]
provider: none

[database]
database_path: {database_dir}

[file_manager]
config_path: {config_dir}
log_path: {logs_dir}
file_system_observer: none
""".format(**p)

    with open(p["moonraker_config"], "w", encoding="utf-8") as cfg:
        cfg.write(config)
    return p


def stop():
    try:
        from moonraker import server
        if server.android_request_stop():
            _set_status("stopping")
            return "Moonraker stop requested"
        return "Moonraker is not running"
    except BaseException as exc:
        return "Moonraker stop ERROR %s: %s" % (type(exc).__name__, exc)


def run(work_dir):
    p = prepare_config(work_dir)
    os.environ["MOONRAKER_ENABLE_UVLOOP"] = "n"

    from moonraker import server

    argv = [
        "moonraker",
        "-d", p["data_root"],
        "-c", p["moonraker_config"],
        "-n",
    ]
    old_argv = sys.argv
    _set_status("starting: http://127.0.0.1:7125")
    try:
        sys.argv = argv
        try:
            server.main(from_package=False)
        except SystemExit as exc:
            code = exc.code
            if code not in (0, None):
                _set_status("error: Moonraker exited %s" % code)
                return "Moonraker exited: %s" % code
        _set_status("stopped")
        return "Moonraker stopped"
    except BaseException as exc:
        detail = "%s: %s" % (type(exc).__name__, exc)
        _set_status("error: " + detail)
        return "Moonraker ERROR %s\n%s" % (detail, traceback.format_exc())
    finally:
        sys.argv = old_argv

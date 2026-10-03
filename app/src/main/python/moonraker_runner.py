"""Embedded Moonraker launcher for AndroidKlipper.

Runs the pinned upstream Moonraker package against persistent_host's normal
Klipper webhooks Unix socket.  The first Android milestone is loopback-only.
"""
import ipaddress
import os
import socket
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


def _lan_access():
    """Return the current Wi-Fi/LAN IP and a conservative local /24."""
    ip = "127.0.0.1"
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # UDP connect selects the active route without sending application data.
        sock.connect(("8.8.8.8", 80))
        candidate = sock.getsockname()[0]
        if ipaddress.ip_address(candidate).is_private:
            ip = candidate
    except OSError:
        pass
    finally:
        sock.close()

    if ip == "127.0.0.1":
        return ip, "127.0.0.1/32"
    return ip, str(ipaddress.ip_network(ip + "/24", strict=False))


def prepare_config(work_dir):
    p = _paths(work_dir)
    for key in ("config_dir", "gcodes_dir", "logs_dir", "database_dir", "comms_dir"):
        os.makedirs(p[key], exist_ok=True)

    lan_ip, trusted_subnet = _lan_access()
    p["lan_ip"] = lan_ip
    p["trusted_subnet"] = trusted_subnet

    config = """[server]
host: 0.0.0.0
port: 7125
klippy_uds_address: {klippy_socket}

[authorization]
trusted_clients:
  127.0.0.1
  {trusted_subnet}
cors_domains:
  http://{lan_ip}:*
  http://127.0.0.1:*
  http://localhost:*
  http://my.mainsail.xyz
  https://my.mainsail.xyz

[machine]
provider: none

[database]
database_path: {database_dir}

[file_manager]
config_path: {config_dir}
log_path: {logs_dir}
file_system_observer: none
""".format(**p)

    # The persistent config directory is user-owned after first seed.
    # Never regenerate moonraker.conf on an APK update or host restart.
    if not os.path.exists(p["moonraker_config"]):
        with open(p["moonraker_config"], "w", encoding="utf-8") as cfg:
            cfg.write(config)

    # Do not seed printer-side config files such as variables.cfg.
    # If a user's printer.cfg references one, onboarding should wait for the
    # user's real file instead of masking the missing include with a placeholder.
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
        "-l", os.path.join(p["logs_dir"], "moonraker.log"),
    ]
    old_argv = sys.argv
    _set_status("starting: http://%s:7125" % p["lan_ip"])
    try:
        sys.argv = argv
        try:
            server.main(from_package=False)
        except SystemExit as exc:
            code = exc.code
            if code not in (0, None):
                log_path = os.path.join(p["logs_dir"], "moonraker.log")
                tail = ""
                try:
                    with open(log_path, "r", encoding="utf-8", errors="replace") as log_file:
                        lines = log_file.readlines()
                    tail = "".join(lines[-80:]).strip()
                except Exception as log_exc:
                    tail = "Unable to read Moonraker log: %s: %s" % (
                        type(log_exc).__name__, log_exc
                    )
                direct_error = ""
                try:
                    direct_error = server.android_get_last_error().strip()
                except Exception:
                    pass
                detail = "Moonraker exited %s" % code
                if direct_error:
                    detail += "\n\n--- direct Moonraker traceback ---\n" + direct_error
                if tail:
                    detail += "\n\n--- moonraker.log tail ---\n" + tail
                _set_status("error: " + detail)
                return detail
        _set_status("stopped")
        return "Moonraker stopped"
    except BaseException as exc:
        detail = "%s: %s" % (type(exc).__name__, exc)
        _set_status("error: " + detail)
        return "Moonraker ERROR %s\n%s" % (detail, traceback.format_exc())
    finally:
        sys.argv = old_argv

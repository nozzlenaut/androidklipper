"""Embedded Moonraker launcher for AndroidKlipper.

Runs the pinned upstream Moonraker package against persistent_host's normal
Klipper webhooks Unix socket.  The first Android milestone is loopback-only.
"""
import ipaddress
import os
import re
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


# Conservative on purpose: the trusted network is never wider than a /24, but
# a smaller real subnet (/26, hotspot /28, ...) is honoured.
_MAX_TRUSTED_WIDTH_PREFIX = 24

MANAGED_BEGIN = "# >>> androidklipper-managed:authorization >>>"
MANAGED_END = "# <<< androidklipper-managed:authorization <<<"


def _trusted_network(ip, prefix):
    prefix = max(int(prefix), _MAX_TRUSTED_WIDTH_PREFIX)
    return str(ipaddress.ip_network("%s/%d" % (ip, prefix), strict=False))


def _from_hint(hint):
    """Parse "192.168.1.50/24" (or a bare IPv4) handed over by Android."""
    if not hint:
        return None
    try:
        iface = ipaddress.ip_interface(str(hint).strip())
    except ValueError:
        return None
    if iface.version != 4 or iface.ip.is_loopback or not iface.ip.is_private:
        return None
    prefix = iface.network.prefixlen
    # A bare address parses as /32; treat that as "prefix unknown".
    if prefix >= 32 and "/" not in str(hint):
        prefix = _MAX_TRUSTED_WIDTH_PREFIX
    return str(iface.ip), _trusted_network(str(iface.ip), prefix)


def _lan_access(lan_hint=None):
    """Return the current Wi-Fi/LAN IP and the network allowed to use Moonraker.

    Order of preference:
      1. lan_hint ("ip/prefix") from Android's NetworkInterface. Works on a
         printer LAN or phone hotspot that has no default route and no internet.
      2. the UDP-connect route trick (needs a default route)
      3. loopback only
    """
    hinted = _from_hint(lan_hint)
    if hinted:
        return hinted

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
    return ip, _trusted_network(ip, _MAX_TRUSTED_WIDTH_PREFIX)


def _authorization_block(lan_ip, trusted_subnet):
    return (
        MANAGED_BEGIN + "\n"
        "# Regenerated on every start so it follows the current Wi-Fi network.\n"
        "# To manage [authorization] yourself, delete these two marker lines and\n"
        "# everything between them, then add your own section.\n"
        "[authorization]\n"
        "trusted_clients:\n"
        "  127.0.0.1\n"
        "  {subnet}\n"
        "cors_domains:\n"
        "  http://{ip}:*\n"
        "  http://127.0.0.1:*\n"
        "  http://localhost:*\n"
        "  http://my.mainsail.xyz\n"
        "  https://my.mainsail.xyz\n"
        + MANAGED_END + "\n"
    ).format(ip=lan_ip, subnet=trusted_subnet)


_CONFIG_HEAD = """[server]
host: 0.0.0.0
port: 7125
klippy_uds_address: {klippy_socket}

"""

_CONFIG_TAIL = """
[machine]
provider: none

[database]
database_path: {database_dir}

[file_manager]
config_path: {config_dir}
log_path: {logs_dir}
file_system_observer: none
"""

# Exactly what builds before the managed block existed wrote. Used only to
# recognise an untouched generated file so it can be upgraded safely.
_LEGACY_AUTH = """[authorization]
trusted_clients:
  127.0.0.1
  {trusted_subnet}
cors_domains:
  http://{lan_ip}:*
  http://127.0.0.1:*
  http://localhost:*
  http://my.mainsail.xyz
  https://my.mainsail.xyz
"""


def _render_config(p, lan_ip, trusted_subnet):
    return (
        _CONFIG_HEAD.format(**p)
        + _authorization_block(lan_ip, trusted_subnet)
        + _CONFIG_TAIL.format(**p)
    )


def _render_legacy_config(p, lan_ip, trusted_subnet):
    return (
        _CONFIG_HEAD.format(**p)
        + _LEGACY_AUTH.format(lan_ip=lan_ip, trusted_subnet=trusted_subnet)
        + _CONFIG_TAIL.format(**p)
    )


_LEGACY_VALUES = re.compile(
    r"trusted_clients:\n  127\.0\.0\.1\n  (?P<subnet>[0-9./]+)\ncors_domains:\n"
    r"  http://(?P<ip>[0-9.]+):\*\n"
)
_MANAGED_BLOCK = re.compile(
    re.escape(MANAGED_BEGIN) + r".*?" + re.escape(MANAGED_END) + r"\n?", re.DOTALL
)


def _write_atomic(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, path)


def _trusted_entries(text):
    """trusted_clients entries of a user-owned file (best effort, for a warning only)."""
    entries = []
    in_section = False
    in_option = False
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if line.startswith("["):
            in_section = line.strip().lower() == "[authorization]"
            in_option = False
            continue
        if not in_section or not line.strip():
            continue
        if raw[:1] not in (" ", "\t"):
            in_option = line.lower().startswith("trusted_clients:")
            rest = line.split(":", 1)[1].strip() if ":" in line else ""
            if in_option and rest:
                entries.append(rest)
        elif in_option:
            entries.append(line.strip())
    return entries


def _covers(entries, ip):
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return True
    for entry in entries:
        try:
            if addr == ipaddress.ip_address(entry):
                return True
        except ValueError:
            pass
        try:
            if addr in ipaddress.ip_network(entry, strict=False):
                return True
        except ValueError:
            pass
    return False


def sync_config_file(p, lan_ip, trusted_subnet):
    """Create moonraker.conf, or keep its network-dependent part current.

    Returns (action, warning). action is one of: created, refreshed, upgraded,
    unchanged, user_owned, malformed. The file stays user-owned: only the block
    between the androidklipper-managed markers is ever rewritten, an untouched
    pre-marker generated file is upgraded in place, and any other file is left
    alone (with a warning when it would lock out the current network).
    """
    path = p["moonraker_config"]
    if not os.path.exists(path):
        _write_atomic(path, _render_config(p, lan_ip, trusted_subnet))
        return "created", None

    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()

    begin = text.count(MANAGED_BEGIN)
    end = text.count(MANAGED_END)
    if begin or end:
        if begin != 1 or end != 1 or not _MANAGED_BLOCK.search(text):
            return "malformed", (
                "moonraker.conf has unbalanced androidklipper-managed markers; "
                "leaving it untouched"
            )
        fresh = _authorization_block(lan_ip, trusted_subnet)
        updated = _MANAGED_BLOCK.sub(lambda _m: fresh, text, count=1)
        if updated == text:
            return "unchanged", None
        _write_atomic(path, updated)
        return "refreshed", None

    legacy = _LEGACY_VALUES.search(text)
    if legacy:
        old = _render_legacy_config(p, legacy.group("ip"), legacy.group("subnet"))
        if text == old:
            _write_atomic(path, _render_config(p, lan_ip, trusted_subnet))
            return "upgraded", None

    if lan_ip != "127.0.0.1" and not _covers(_trusted_entries(text), lan_ip):
        return "user_owned", (
            "moonraker.conf is user-managed and its trusted_clients does not include "
            "%s; remote Mainsail will get 401 until you add %s" % (lan_ip, trusted_subnet)
        )
    return "user_owned", None


def prepare_config(work_dir, lan_hint=None):
    p = _paths(work_dir)
    for key in ("config_dir", "gcodes_dir", "logs_dir", "database_dir", "comms_dir"):
        os.makedirs(p[key], exist_ok=True)

    lan_ip, trusted_subnet = _lan_access(lan_hint)
    p["lan_ip"] = lan_ip
    p["trusted_subnet"] = trusted_subnet

    # The persistent config directory stays user-owned: it is never regenerated
    # on an APK update or host restart. Only the marked network block is kept
    # current (see sync_config_file).
    action, warning = sync_config_file(p, lan_ip, trusted_subnet)
    p["config_action"] = action
    p["config_warning"] = warning

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


def run(work_dir, lan_hint=None):
    p = prepare_config(work_dir, lan_hint)
    if p.get("config_warning"):
        print("moonraker_runner WARNING: " + p["config_warning"], file=sys.stderr)
    print("moonraker_runner: config %s lan=%s trusted=%s"
          % (p["config_action"], p["lan_ip"], p["trusted_subnet"]), file=sys.stderr)
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

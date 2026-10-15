"""Tests for moonraker_runner's network-aware config handling (no Android, no Moonraker needed).

Run: python3 -m unittest scripts/tests/test_moonraker_config.py
"""
import configparser
import os
import pathlib
import socket
import sys
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "app" / "src" / "main" / "python"))
import moonraker_runner as mr  # noqa: E402


def parse(path):
    """Parse the way Moonraker does (plain ConfigParser, no interpolation, strict sections)."""
    cp = configparser.ConfigParser(interpolation=None)
    cp.read(path, encoding="utf-8")
    return cp


def trusted(cp):
    return [v.strip() for v in cp["authorization"]["trusted_clients"].splitlines() if v.strip()]


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = self.tmp.name
        self.p = mr._paths(self.work)
        for k in ("config_dir", "gcodes_dir", "logs_dir", "database_dir", "comms_dir"):
            os.makedirs(self.p[k], exist_ok=True)
        self.conf = self.p["moonraker_config"]

    def tearDown(self):
        self.tmp.cleanup()

    def read(self):
        return pathlib.Path(self.conf).read_text(encoding="utf-8")

    def prepare(self, hint):
        return mr.prepare_config(self.work, hint)


class FreshInstall(Base):
    def test_creates_a_parseable_config_with_markers(self):
        p = self.prepare("192.168.1.50/24")
        self.assertEqual(p["config_action"], "created")
        self.assertIn(mr.MANAGED_BEGIN, self.read())
        cp = parse(self.conf)  # strict: raises on duplicate sections
        self.assertEqual(trusted(cp), ["127.0.0.1", "192.168.1.0/24"])
        self.assertIn("http://192.168.1.50:*", cp["authorization"]["cors_domains"])
        self.assertEqual(cp["server"]["port"], "7125")
        self.assertEqual(cp["server"]["klippy_uds_address"], self.p["klippy_socket"])
        self.assertEqual(cp["file_manager"]["config_path"], self.p["config_dir"])

    def test_no_temp_file_is_left_behind(self):
        self.prepare("192.168.1.50/24")
        self.assertEqual(sorted(os.listdir(self.p["config_dir"])), ["moonraker.conf"])


class NetworkChange(Base):
    def test_managed_block_follows_the_network_and_nothing_else_changes(self):
        self.prepare("192.168.1.50/24")
        user_tail = "\n[update_manager]\nenable_auto_refresh: False\n\n# my note\n[octoprint_compat]\n"
        with open(self.conf, "a", encoding="utf-8") as f:
            f.write(user_tail)
        before = self.read()

        p = self.prepare("10.20.30.7/24")  # moved to another router
        self.assertEqual(p["config_action"], "refreshed")
        after = self.read()
        cp = parse(self.conf)
        self.assertEqual(trusted(cp), ["127.0.0.1", "10.20.30.0/24"])
        self.assertIn("http://10.20.30.7:*", cp["authorization"]["cors_domains"])
        self.assertTrue(after.endswith(user_tail))
        # Everything outside the managed block is byte-for-byte unchanged.
        strip = lambda t: mr._MANAGED_BLOCK.sub("", t)
        self.assertEqual(strip(before), strip(after))

    def test_same_network_does_not_rewrite_the_file(self):
        self.prepare("192.168.1.50/24")
        mtime = os.stat(self.conf).st_mtime_ns
        before = self.read()
        p = self.prepare("192.168.1.50/24")
        self.assertEqual(p["config_action"], "unchanged")
        self.assertEqual(self.read(), before)
        self.assertEqual(os.stat(self.conf).st_mtime_ns, mtime)

    def test_ip_change_inside_the_same_subnet_updates_cors_only(self):
        self.prepare("192.168.1.50/24")
        p = self.prepare("192.168.1.77/24")
        self.assertEqual(p["config_action"], "refreshed")
        cp = parse(self.conf)
        self.assertEqual(trusted(cp), ["127.0.0.1", "192.168.1.0/24"])
        self.assertIn("http://192.168.1.77:*", cp["authorization"]["cors_domains"])

    def test_edits_inside_the_managed_block_are_overwritten(self):
        self.prepare("192.168.1.50/24")
        text = self.read().replace("  127.0.0.1\n", "  127.0.0.1\n  8.8.8.8\n", 1)
        pathlib.Path(self.conf).write_text(text, encoding="utf-8")
        self.prepare("192.168.1.50/24")
        self.assertNotIn("8.8.8.8", self.read())


class LegacyFiles(Base):
    def legacy(self, ip, subnet):
        pathlib.Path(self.conf).write_text(mr._render_legacy_config(self.p | {}, ip, subnet), encoding="utf-8")

    def test_untouched_generated_file_is_upgraded_in_place(self):
        self.legacy("192.168.1.50", "192.168.1.0/24")
        p = self.prepare("10.0.0.9/24")
        self.assertEqual(p["config_action"], "upgraded")
        self.assertIn(mr.MANAGED_BEGIN, self.read())
        self.assertEqual(trusted(parse(self.conf)), ["127.0.0.1", "10.0.0.0/24"])

    def test_legacy_file_matches_what_the_previous_release_wrote(self):
        # Guard against the legacy template drifting from the shipped checkpoint.
        # (The checkpoint's literal text, with its two placeholders filled in.)
        checkpoint = (
            "[server]\nhost: 0.0.0.0\nport: 7125\nklippy_uds_address: {klippy_socket}\n\n"
            "[authorization]\ntrusted_clients:\n  127.0.0.1\n  192.168.1.0/24\ncors_domains:\n"
            "  http://192.168.1.50:*\n  http://127.0.0.1:*\n  http://localhost:*\n"
            "  http://my.mainsail.xyz\n  https://my.mainsail.xyz\n\n[machine]\nprovider: none\n\n"
            "[database]\ndatabase_path: {database_dir}\n\n[file_manager]\nconfig_path: {config_dir}\n"
            "log_path: {logs_dir}\nfile_system_observer: none\n"
        ).format(**self.p)
        self.assertEqual(mr._render_legacy_config(self.p, "192.168.1.50", "192.168.1.0/24"), checkpoint)

    def test_user_edited_legacy_file_is_left_alone(self):
        self.legacy("192.168.1.50", "192.168.1.0/24")
        with open(self.conf, "a", encoding="utf-8") as f:
            f.write("\n[octoprint_compat]\n")
        before = self.read()
        p = self.prepare("10.0.0.9/24")
        self.assertEqual(p["config_action"], "user_owned")
        self.assertEqual(self.read(), before)
        self.assertIn("10.0.0.9", p["config_warning"])  # tells the user why remote access fails

    def test_no_warning_when_the_user_file_still_covers_the_network(self):
        self.legacy("192.168.1.50", "192.168.1.0/24")
        with open(self.conf, "a", encoding="utf-8") as f:
            f.write("\n[octoprint_compat]\n")
        p = self.prepare("192.168.1.60/24")
        self.assertEqual(p["config_action"], "user_owned")
        self.assertIsNone(p["config_warning"])

    def test_user_who_removed_markers_and_owns_authorization(self):
        pathlib.Path(self.conf).write_text(
            "[server]\nhost: 0.0.0.0\nport: 7125\n\n[authorization]\ntrusted_clients:\n  192.168.0.0/16\n"
            "force_logins: True\n", encoding="utf-8")
        before = self.read()
        p = self.prepare("192.168.7.7/24")
        self.assertEqual((p["config_action"], p["config_warning"]), ("user_owned", None))
        self.assertEqual(self.read(), before)

    def test_unbalanced_markers_are_never_touched(self):
        self.prepare("192.168.1.50/24")
        broken = self.read().replace(mr.MANAGED_END, "# oops")
        pathlib.Path(self.conf).write_text(broken, encoding="utf-8")
        p = self.prepare("10.0.0.9/24")
        self.assertEqual(p["config_action"], "malformed")
        self.assertEqual(self.read(), broken)
        self.assertTrue(p["config_warning"])


class LanDetection(unittest.TestCase):
    def test_hint_works_without_a_default_route(self):
        # A printer LAN / phone hotspot with no internet: the UDP trick raises.
        class NoRoute:
            def __init__(self, *a, **k): pass
            def connect(self, *a): raise OSError(101, "Network is unreachable")
            def getsockname(self): raise AssertionError("unreachable")
            def close(self): pass
        with mock.patch.object(socket, "socket", NoRoute):
            self.assertEqual(mr._lan_access("192.168.43.1/24"), ("192.168.43.1", "192.168.43.0/24"))
            # Without the hint the old behaviour is loopback-only: remote Mainsail would be locked out.
            self.assertEqual(mr._lan_access(None), ("127.0.0.1", "127.0.0.1/32"))

    def test_hint_parsing_rules(self):
        self.assertEqual(mr._lan_access("192.168.4.23/24")[1], "192.168.4.0/24")
        self.assertEqual(mr._lan_access("192.168.4.23/26")[1], "192.168.4.0/26")   # smaller is honoured
        self.assertEqual(mr._lan_access("10.1.2.3/8")[1], "10.1.2.0/24")            # never wider than /24
        self.assertEqual(mr._lan_access("172.16.5.5/16")[1], "172.16.5.0/24")
        self.assertEqual(mr._lan_access("192.168.4.23")[1], "192.168.4.0/24")       # bare address

    def test_unusable_hints_fall_through(self):
        class NoRoute:
            def __init__(self, *a, **k): pass
            def connect(self, *a): raise OSError(101, "unreachable")
            def close(self): pass
        with mock.patch.object(socket, "socket", NoRoute):
            for bad in ("", None, "garbage", "8.8.8.8/24", "fe80::1/64", "127.0.0.1/8", "999.1.1.1/24"):
                self.assertEqual(mr._lan_access(bad), ("127.0.0.1", "127.0.0.1/32"), bad)


class TrustedEntryParser(unittest.TestCase):
    def test_forms(self):
        text = ("[server]\nport: 1\n[authorization]\ntrusted_clients:\n  10.0.0.0/8  # lan\n  192.168.1.5\n"
                "cors_domains:\n  http://x\n[machine]\ntrusted_clients:\n  1.1.1.1\n")
        self.assertEqual(mr._trusted_entries(text), ["10.0.0.0/8", "192.168.1.5"])
        self.assertEqual(mr._trusted_entries("[authorization]\ntrusted_clients: 10.1.1.1\n"), ["10.1.1.1"])
        self.assertTrue(mr._covers(["10.0.0.0/8"], "10.9.9.9"))
        self.assertFalse(mr._covers(["10.0.0.0/8"], "192.168.1.5"))


if __name__ == "__main__":
    unittest.main()


def _moonraker_loader():
    """Moonraker's own config loader, when the vendored tree and its imports are available."""
    try:
        from unittest import mock as _mock
        import moonraker.confighelper as ch  # vendored by scripts/vendor-moonraker.sh
        return ch, _mock
    except Exception:  # pragma: no cover - environment without vendored Moonraker deps
        return None, None


_CH, _MOCK = _moonraker_loader()


@unittest.skipUnless(_CH is not None, "vendored Moonraker (and tornado/importlib_metadata) not importable")
class AgainstRealMoonrakerParser(Base):
    """Feed generated files through Moonraker's own FileSourceWrapper/ConfigHelper."""

    def load(self):
        server = _MOCK.MagicMock()
        source = _CH.FileSourceWrapper(server)
        source.read_file(pathlib.Path(self.conf))
        helper = _CH.ConfigHelper(server, source, "server", {})
        return server, helper

    def test_fresh_and_refreshed_files_parse_with_the_expected_values(self):
        for hint, subnet in (("192.168.1.50/24", "192.168.1.0/24"), ("10.9.8.7/26", "10.9.8.0/26")):
            self.prepare(hint)
            server, helper = self.load()
            auth = helper.getsection("authorization")
            self.assertEqual(auth.getlist("trusted_clients"), ["127.0.0.1", subnet])
            self.assertIn("http://%s:*" % hint.split("/")[0], auth.getlist("cors_domains"))
            self.assertEqual(helper.getint("port"), 7125)
            server.add_warning.assert_not_called()

    def test_legacy_upgrade_result_parses(self):
        pathlib.Path(self.conf).write_text(
            mr._render_legacy_config(self.p, "192.168.1.50", "192.168.1.0/24"), encoding="utf-8")
        self.prepare("172.20.1.9/24")
        _, helper = self.load()
        self.assertEqual(helper.getsection("authorization").getlist("trusted_clients"),
                         ["127.0.0.1", "172.20.1.0/24"])

"""Native-Python smoke tests for the experimental extension importer."""
import importlib
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2] / "app/src/main/python"
sys.path.insert(0, str(ROOT))
import extension_manager as manager


def make_archive(path, missing_component=False, bad=None):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("Happy-Hare-main/extras/mmu/__init__.py", "HELLO = 42\n")
        if not missing_component:
            z.writestr("Happy-Hare-main/components/mmu_server.py", "READY = True\n")
        z.writestr("Happy-Hare-main/config/base/mmu.cfg", "[mmu]\n")
        if bad:
            z.writestr(bad, "bad")


class HappyHareStagingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.source = self.base / "source.zip"
        self.extensions = self.base / "extensions"
        make_archive(self.source)

    def test_install_exposes_expected_files(self):
        result = manager.install_archive(self.extensions, "happy_hare", self.source)
        self.assertEqual(result["files"], 3)
        self.assertTrue(result["restart_required"])
        self.assertTrue((self.extensions / "happy_hare/klipper/extras/mmu/__init__.py").exists())
        self.assertTrue((self.extensions / "happy_hare/moonraker/components/mmu_server.py").exists())
        self.assertTrue((self.extensions / "happy_hare/templates/base/mmu.cfg").exists())

    def test_unknown_extension_refused(self):
        with self.assertRaises(manager.ExtensionError):
            manager.install_archive(self.extensions, "unknown", self.source)

    def test_bad_update_does_not_replace_existing(self):
        manager.install_archive(self.extensions, "happy_hare", self.source)
        make_archive(self.source, missing_component=True)
        with self.assertRaises(manager.ExtensionError):
            manager.install_archive(self.extensions, "happy_hare", self.source)
        self.assertIn("HELLO", (self.extensions / "happy_hare/klipper/extras/mmu/__init__.py").read_text())

    def test_zip_slip_refused(self):
        make_archive(self.source, bad="Happy-Hare-main/extras/../outside.py")
        with self.assertRaises(manager.ExtensionError):
            manager.install_archive(self.extensions, "happy_hare", self.source)
        self.assertFalse((self.base / "outside.py").exists())

    def test_activation_deduplicates_path_and_imports(self):
        manager.install_archive(self.extensions, "happy_hare", self.source)
        original = sys.modules.get("extras")
        stub = types.ModuleType("extras")
        stub.__path__ = []
        sys.modules["extras"] = stub
        try:
            self.assertEqual(manager.activate(self.extensions, "extras"), ["happy_hare"])
            self.assertEqual(manager.activate(self.extensions, "extras"), ["happy_hare"])
            self.assertEqual(len(stub.__path__), 1)
            self.assertEqual(importlib.import_module("extras.mmu").HELLO, 42)
        finally:
            sys.modules.pop("extras.mmu", None)
            if original is None:
                sys.modules.pop("extras", None)
            else:
                sys.modules["extras"] = original

    def test_disabled_extension_not_activated(self):
        manager.install_archive(self.extensions, "happy_hare", self.source)
        path = self.extensions / "happy_hare/extension.json"
        meta = json.loads(path.read_text())
        meta["enabled"] = False
        path.write_text(json.dumps(meta))
        self.assertEqual(manager.activate(self.extensions, "extras"), [])


if __name__ == "__main__":
    unittest.main()

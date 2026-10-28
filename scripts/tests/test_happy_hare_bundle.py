"""Smoke checks for opt-in Happy Hare v4 modules vendored in the Moto APK."""
import importlib
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
PY_ROOT = ROOT / "app/src/main/python"


class HappyHareBundleTest(unittest.TestCase):
    def test_expected_source_files_are_vendored(self):
        for file in (
            "extras/mmu_machine.py",
            "extras/mmu_stepper.py",
            "extras/mmu_servo.py",
            "extras/mmu/mmu_controller.py",
            "extras/mmu/unit/selectors/mmu_servo_selector.py",
            "moonraker/components/mmu_server.py",
            "happy_hare_license/LICENSE.txt",
            "happy_hare_license/SOURCE_COMMIT.txt",
        ):
            with self.subTest(file=file):
                self.assertTrue((PY_ROOT / file).is_file(), file)
        self.assertEqual(
            (PY_ROOT / "happy_hare_license/SOURCE_COMMIT.txt").read_text().strip(),
            "70aa90fb9aa18787de445235bd26a0665168c441",
        )

    def test_imports_are_compatible_with_pinned_klipper_and_moonraker(self):
        sys.path.insert(0, str(PY_ROOT))
        sys.path.insert(0, str(PY_ROOT / "klipper_vendor/klippy"))
        try:
            for name in (
                "extras.mmu_machine",
                "extras.mmu_stepper",
                "extras.mmu_servo",
                "extras.mmu.mmu_controller",
                "moonraker.components.mmu_server",
            ):
                with self.subTest(module=name):
                    module = importlib.import_module(name)
                    self.assertIsNone(getattr(module, "_IMPORT_ERROR", None), name)
        finally:
            sys.path.remove(str(PY_ROOT / "klipper_vendor/klippy"))
            sys.path.remove(str(PY_ROOT))


if __name__ == "__main__":
    unittest.main()

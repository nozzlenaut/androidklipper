"""Exercise the debug-signing-key steps of .github/workflows/android.yml.

The shell text is pulled out of the workflow itself and executed in a throwaway
HOME, so the test breaks if the workflow logic regresses. Needs `keytool`.
Run: python3 -m unittest scripts/tests/test_ci_signing.py
"""
import base64
import os
import pathlib
import subprocess
import tempfile
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "android.yml"


def step(name_prefix):
    steps = yaml.safe_load(WORKFLOW.read_text())["jobs"]["build"]["steps"]
    for s in steps:
        if (s.get("name") or "").startswith(name_prefix):
            return s
    raise AssertionError("workflow step not found: " + name_prefix)


def run_step(s, home, env_extra=None):
    out = pathlib.Path(home) / "github_output"
    out.touch()
    env = {"PATH": os.environ["PATH"], "HOME": str(home), "GITHUB_OUTPUT": str(out)}
    env.update(env_extra or {})
    p = subprocess.run(["bash", "-eo", "pipefail", "-c", s["run"]],
                       env=env, capture_output=True, text=True)
    return p, out.read_text()


def make_keystore(path):
    subprocess.run(
        ["keytool", "-genkeypair", "-keystore", str(path), "-storepass", "android",
         "-alias", "androiddebugkey", "-keypass", "android", "-dname", "CN=T,O=T,C=US",
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "100"],
        check=True, capture_output=True)


def fingerprint(path):
    txt = subprocess.run(
        ["keytool", "-list", "-v", "-keystore", str(path), "-storepass", "android",
         "-alias", "androiddebugkey"], check=True, capture_output=True, text=True).stdout
    line = next(l for l in txt.splitlines() if "SHA256:" in l)
    return line.split("SHA256:")[1].strip()


class SigningSteps(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = pathlib.Path(self.tmp.name)
        (self.home / ".android").mkdir()
        self.src = self.home / "source.keystore"
        make_keystore(self.src)

    def tearDown(self):
        self.tmp.cleanup()

    def test_secret_is_decoded_byte_for_byte(self):
        b64 = base64.b64encode(self.src.read_bytes()).decode()
        p, out = run_step(step("Install debug signing key from secret"), self.home,
                          {"DEBUG_KEYSTORE_B64": b64})
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("installed=true", out)
        self.assertEqual((self.home / ".android/debug.keystore").read_bytes(),
                         self.src.read_bytes())

    def test_missing_secret_warns_and_requests_cache_fallback(self):
        p, out = run_step(step("Install debug signing key from secret"), self.home,
                          {"DEBUG_KEYSTORE_B64": ""})
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("installed=false", out)
        self.assertIn("::warning::", p.stdout)
        self.assertFalse((self.home / ".android/debug.keystore").exists())

    def test_fallback_creates_a_usable_key(self):
        p, _ = run_step(step("Create debug signing key on first run"), self.home)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertTrue(fingerprint(self.home / ".android/debug.keystore"))

    def _verify(self, expected):
        (self.home / ".android/debug.keystore").write_bytes(self.src.read_bytes())
        return run_step(step("Verify debug signing key"), self.home,
                        {"EXPECTED_SHA256": expected})[0]

    def test_pin_unset_is_allowed_with_notice(self):
        p = self._verify("")
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("::notice::", p.stdout)

    def test_pin_matches_in_any_notation(self):
        fp = fingerprint(self.src)
        for variant in (fp, fp.replace(":", ""), fp.replace(":", "").lower(), "  " + fp + " "):
            p = self._verify(variant)
            self.assertEqual(p.returncode, 0, (variant, p.stdout, p.stderr))
            self.assertIn("matches pinned", p.stdout)

    def test_pin_mismatch_fails_the_build(self):
        other = self.home / "other.keystore"
        make_keystore(other)
        p = self._verify(fingerprint(other))
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("::error::", p.stdout)


class WorkflowShape(unittest.TestCase):
    def test_cache_steps_only_run_when_secret_absent(self):
        for prefix in ("Restore debug signing key", "Create debug signing key on first run"):
            self.assertIn("installed != 'true'", step(prefix)["if"])

    def test_secret_step_precedes_verify_and_build(self):
        names = [s.get("name") or "" for s in
                 yaml.safe_load(WORKFLOW.read_text())["jobs"]["build"]["steps"]]
        i = lambda p: next(k for k, n in enumerate(names) if n.startswith(p))
        self.assertLess(i("Install debug signing key"), i("Verify debug signing key"))
        self.assertLess(i("Verify debug signing key"), i("Unit tests and debug APK"))


if __name__ == "__main__":
    unittest.main()

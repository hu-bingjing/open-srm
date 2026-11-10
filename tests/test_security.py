from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import scan_sensitive as scanner


class ScannerTests(unittest.TestCase):
    def test_provider_credential_detected_without_printing_value(self):
        sample = "ghp_" + "a" * 36  # Intentionally synthetic fixture, assembled at runtime.
        findings = scanner.inspect_text("value=" + sample)
        self.assertIn((1, "provider-credential"), findings)
        self.assertNotIn(sample, str(findings))

    def test_private_ip_detected(self):
        self.assertIn((1, "private-ip"), scanner.inspect_text("host=" + "192" + ".168.20.30"))

    def test_real_secret_assignment_detected(self):
        self.assertIn((1, "secret-assignment"), scanner.inspect_text("DB_PASSWORD=" + "synthetic-value"))

    def test_placeholder_not_flagged(self):
        self.assertEqual(scanner.inspect_text("DB_PASSWORD=CHANGE_ME"), [])

    def test_secret_file_reference_is_not_plaintext_secret(self):
        self.assertEqual(scanner.inspect_text("POSTGRES_PASSWORD_FILE: /run/secrets/db_password"), [])

    def test_local_user_path_detected(self):
        self.assertIn((1, "local-user-path"), scanner.inspect_text("C:" + "\\Users\\synthetic\\project"))

    def test_private_configuration_outside_allowlist(self):
        for name in (".env", "private/config.json", "docs/../../.env", "docs/data.pdf", "tmp/report.md"):
            with self.subTest(name=name):
                self.assertFalse(scanner.permitted(name))

    def test_standard_files_allowed(self):
        for name in (".env.example", "docs/api/openapi.json", ".github/workflows/ci.yml", ".githooks/pre-commit"):
            self.assertTrue(scanner.permitted(name))

    def test_staged_scan_uses_index_not_sanitized_worktree(self):
        # All test fixtures stay under this project's ignored tmp directory.
        (ROOT / "tmp").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "tmp") as directory:
            fixture = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(fixture)], check=True, capture_output=True)
            sample = "ghp_" + "b" * 36
            (fixture / "README.md").write_text(sample, encoding="utf-8")
            # Trust only this newly created test repository for each invocation;
            # sandbox ownership may differ. Never modify global Git settings.
            def fixture_git(*args):
                return subprocess.run(["git", "-c", "safe.directory=" + fixture.as_posix(), "-C", str(fixture), *args],
                                      check=True, capture_output=True).stdout
            fixture_git("add", "--", "README.md")
            (fixture / "README.md").write_text("Sanitized worktree", encoding="utf-8")
            with patch.object(scanner, "ROOT", fixture), patch.object(scanner, "git", fixture_git):
                _, _, findings = scanner.scan("staged")
                self.assertIn(("README.md", 1, "provider-credential"), findings)
                self.assertEqual(scanner.scan("tracked")[2], [])

    def test_binary_in_public_tree_fails_closed(self):
        with patch.object(scanner, "candidates", return_value=iter([("docs/sample.md", b"a\x00b")])):
            self.assertIn(("docs/sample.md", 0, "nonregular-or-binary-file"), scanner.scan()[2])

    def test_empty_scan_not_pass(self):
        with patch.object(scanner, "candidates", return_value=iter([])):
            self.assertTrue(scanner.scan()[2])

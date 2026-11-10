import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("init_local", ROOT / "scripts/init-local.py")
provisioning = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provisioning)


class ProvisioningTests(unittest.TestCase):
    def setUp(self):
        (ROOT / "tmp").mkdir(exist_ok=True)

    def test_random_secrets_and_no_overwrite(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "tmp") as directory:
            base = Path(directory)
            with patch.object(provisioning, "ROOT", base), patch("sys.argv", ["init-local.py"]), patch("builtins.print"):
                provisioning.main()
                keys = [(base / "private" / name).read_text() for name in ("db-password", "bootstrap-token", "idempotency-key")]
                self.assertEqual(len(set(keys)), 3)
                self.assertTrue(all(len(value) >= 32 for value in keys))
                before = (base / "private/db-password").read_bytes()
                with self.assertRaisesRegex(SystemExit, "Refusing to overwrite"):
                    provisioning.main()
                self.assertEqual((base / "private/db-password").read_bytes(), before)

    def test_invalid_tls_host_does_not_generate_credentials(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "tmp") as directory:
            base = Path(directory)
            with patch.object(provisioning, "ROOT", base), patch("sys.argv", ["init-local.py", "--tls-host", "invalid;command"]):
                with self.assertRaisesRegex(SystemExit, "Invalid TLS host"):
                    provisioning.main()
                self.assertFalse((base / "private/db-password").exists())

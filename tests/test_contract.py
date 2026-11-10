import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from check_project import check
from generate_openapi import build


class ContractTests(unittest.TestCase):
    def test_project_consistency(self):
        self.assertEqual(check(), [])

    def test_all_references_resolve(self):
        contract = build()

        def visit(node):
            if isinstance(node, dict):
                if "$ref" in node:
                    target = contract
                    for part in node["$ref"].split("/")[1:]:
                        target = target[part]
                for value in node.values():
                    visit(value)
            elif isinstance(node, list):
                for value in node:
                    visit(value)
        visit(contract)

    def test_business_mutations_require_csrf_idempotency(self):
        for path, methods in build()["paths"].items():
            for method, operation in methods.items():
                if method == "get" or path.startswith("/auth/"):
                    continue
                with self.subTest(path=path, method=method):
                    required = {p["name"] for p in operation["parameters"] if p.get("required")}
                    self.assertTrue({"X-CSRF-Token", "Idempotency-Key"} <= required)
                    if "{id}" in path:
                        self.assertIn("If-Match", required)
                    self.assertEqual(operation["security"], [{"Session": []}])

    def test_no_user_endpoint_can_create_actual_receipt_or_order(self):
        paths = build()["paths"]
        self.assertEqual(set(paths["/goods-receipts"]), {"get"})
        self.assertEqual(set(paths["/purchase-orders"]), {"get"})

    def test_states_are_independent_and_receipt_is_not_asn_state(self):
        machines = json.loads((ROOT / "specs/state-machines.json").read_text(encoding="utf-8"))
        self.assertEqual(len(machines), 6)
        self.assertNotIn("RECEIVED", machines["asn"]["transitions"])
        self.assertEqual(machines["goodsReceipt"]["initial"], "POSTED")
        self.assertEqual(machines["deliveryChange"]["transitions"]["APPROVED"], [])

    def test_seven_erp_ports_and_safe_defaults(self):
        ports = json.loads((ROOT / "specs/erp-ports.json").read_text(encoding="utf-8"))
        self.assertEqual(len(ports["ports"]), 7)
        self.assertEqual(sum(p["direction"] == "publish" for p in ports["ports"]), 2)
        self.assertEqual(ports["defaultAdapter"], "mock")
        self.assertFalse(ports["defaultWriteEnabled"])

    def test_erp_java_ports_exist_and_core_has_no_adapter_import(self):
        ports = json.loads((ROOT / "specs/erp-ports.json").read_text(encoding="utf-8"))
        java = ROOT / "backend/src/main/java/org/opensrm"
        for port in ports["ports"]:
            text = (java / "integration/port" / (port["name"] + ".java")).read_text(encoding="utf-8")
            self.assertIn("interface " + port["name"], text)
        for path in java.rglob("*.java"):
            if "adapters" not in path.relative_to(java).parts:
                self.assertNotIn("import org.opensrm.adapters.", path.read_text(encoding="utf-8"), path.relative_to(java).as_posix())

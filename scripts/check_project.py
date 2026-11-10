"""Offline consistency checks; standard OpenAPI validation is a separate check."""
import json
from pathlib import Path
import re
import sys

from generate_openapi import serialized

ROOT = Path(__file__).resolve().parents[1]


def check():
    errors = []
    for filename in ("README.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md", ".env.example", ".gitignore",
                     "docs/prd.md", "docs/architecture.md", "docs/permissions.md", "docs/data-model.md",
                     "docs/state-machines.md", "docs/api/README.md", "docs/api/openapi.json", "docs/erp-integration.md",
                     "docs/plan.md", "docs/gap-analysis.md", "docs/installation.md", "docs/security-release.md", "docs/acceptance/phase-00.md"):
        if not (ROOT / filename).is_file():
            errors.append("missing " + filename)
    if (ROOT / "docs/api/openapi.json").read_text(encoding="utf-8") != serialized():
        errors.append("OpenAPI differs from generator")
    for path in [ROOT / "README.md", *ROOT.glob("docs/**/*.md")]:
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
            if "://" in target or target.startswith("#"):
                continue
            resolved = (path.parent / target.split("#")[0]).resolve()
            if not resolved.is_relative_to(ROOT) or not resolved.exists():
                errors.append(f"broken/outside link: {path.relative_to(ROOT)} -> {target}")
    machines = json.loads((ROOT / "specs/state-machines.json").read_text(encoding="utf-8"))
    for name, machine in machines.items():
        states = machine["transitions"]
        if machine["initial"] not in states or any(target not in states for targets in states.values() for target in targets):
            errors.append("unknown state: " + name)
    ports = json.loads((ROOT / "specs/erp-ports.json").read_text(encoding="utf-8"))
    expected = {"OrganizationProvider", "SupplierProvider", "MaterialProvider", "PurchaseOrderProvider", "GoodsReceiptProvider",
                "OrderConfirmationPublisher", "AdvanceShippingNoticePublisher"}
    if {p["name"] for p in ports["ports"]} != expected or ports["defaultWriteEnabled"] or ports["defaultAdapter"] != "mock":
        errors.append("ERP port/default mismatch")
    return errors


if __name__ == "__main__":
    issues = check()
    for issue in issues:
        print(issue)
    print(f"Project consistency: {'FAIL' if issues else 'PASS'} ({len(issues)} issues)")
    sys.exit(bool(issues))

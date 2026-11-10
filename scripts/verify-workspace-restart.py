"""Verify Phase 07 notification and business persistence on a dedicated TEST stack."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import ssl
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--allow-test-restart", action="store_true", required=True)
    args = parser.parse_args()
    base = args.url.rstrip("/")
    context = ssl.create_default_context(cafile=str(ROOT / "private/tls/server.crt")) if base.startswith("https:") else None
    handlers = [urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())]
    if context:
        handlers.append(urllib.request.HTTPSHandler(context=context))
    client = urllib.request.build_opener(*handlers)

    def request(path, body=None):
        headers = {"Content-Type": "application/json"}
        if body is not None:
            headers["X-CSRF-Token"] = request("/auth/csrf")["csrfToken"]
        message = urllib.request.Request(base + "/api/v1" + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with client.open(message, timeout=10) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    credentials = json.loads((ROOT / "private/phase07-test.json").read_text(encoding="utf-8"))
    request("/auth/login", {k: credentials[k] for k in ("login", "password")})
    before = request("/auth/me")
    count = request("/users")["total"]
    compose = ["docker", "compose", "--env-file", ".env", "-f", "deploy/compose.yml"]
    if context:
        compose += ["-f", "deploy/compose.tls.yml"]
    def business_snapshot():
        tables = ("company", "plant", "purchasing_org", "company_purch_org", "supplier", "supplier_company", "organization_grant", "user_supplier", "audit_event_company", "erp_connection", "erp_plant_mapping", "material", "purchase_order", "po_revision", "po_item", "po_schedule", "sync_job", "sync_cursor", "inbox_event", "sync_issue", "confirmation", "delivery_change", "approval_event", "delivery_plan", "outbox_event", "asn", "asn_line", "receipt_inbox", "receipt_event", "receipt_allocation", "notification_delivery", "notification")
        query = ";".join("SELECT md5(COALESCE(string_agg(row_to_json(t)::text,chr(10) ORDER BY row_to_json(t)::text),'')) FROM " + table + " t" for table in tables)
        result = subprocess.run([*compose, "exec", "-T", "database", "sh", "-c", 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "$1"', "sh", query], cwd=ROOT, check=True, capture_output=True, text=True)
        return result.stdout.strip().splitlines()

    # Projection and delivery must be quiescent before comparing full row contents.
    deadline = time.monotonic() + 120
    while True:
        pending = subprocess.check_output([*compose, "exec", "-T", "database", "sh", "-c", 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT count(*) FROM notification_delivery WHERE status=\'PENDING\'"'], cwd=ROOT, text=True).strip()
        if pending == "0":
            time.sleep(2)
            break
        if time.monotonic() > deadline:
            raise RuntimeError("Notification delivery did not become quiescent")
        time.sleep(2)
    business_before = business_snapshot()
    assert len(business_before) == 32
    for service in ("api", "database"):
        subprocess.run([*compose, "restart", service], cwd=ROOT, check=True, capture_output=True)
        deadline = time.monotonic() + 120
        while True:
            try:
                after = request("/auth/me")
                assert after == before
                assert request("/users")["total"] == count
                assert request("/auth/bootstrap/status")["setupRequired"] is False
                assert business_snapshot() == business_before
                break
            except Exception:
                if time.monotonic() > deadline:
                    raise RuntimeError("Persistence verification failed after " + service + " restart") from None
                time.sleep(2)
        print("PASS: " + service + " restart preserves the authenticated session and account data")
        print("PASS: " + service + " restart preserves all 32 business/notification tables, including read state and delivery status")
    request("/auth/logout", {})


if __name__ == "__main__":
    main()

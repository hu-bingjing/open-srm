"""Capture/check existing row fingerprints on an authorized test deployment; no raw rows leave the database."""
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TABLES = ('app_user', 'user_role', 'company', 'plant', 'purchasing_org', 'company_purch_org', 'supplier', 'supplier_company', 'organization_grant', 'user_supplier', 'audit_event_company', 'erp_connection', 'erp_plant_mapping', 'material', 'purchase_order', 'po_revision', 'po_item', 'po_schedule', 'sync_job', 'sync_cursor', 'inbox_event', 'sync_issue', 'confirmation', 'delivery_change', 'approval_event', 'delivery_plan', 'outbox_event', 'asn', 'asn_line', 'receipt_inbox', 'receipt_event', 'receipt_allocation')

def snapshot(strict=False):
    compose = ['docker', 'compose', '--env-file', '.env', '-f', 'deploy/compose.yml', 'exec', '-T', 'database', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "$1"', 'sh']
    result = {}
    for table in TABLES:
        expression = "to_jsonb(t)-'next_attempt_at'" if table == 'sync_job' and not strict else 'to_jsonb(t)'
        result[table] = subprocess.check_output([*compose, 'SELECT md5((' + expression + ')::text) FROM ' + table + ' t'], cwd=ROOT, text=True).splitlines()
    return result

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--capture', action='store_true')
    p.add_argument('--strict', action='store_true', help='Include existing retry schedule fields for V7+ upgrades')
    p.add_argument('--baseline', type=Path, default=ROOT / 'private/upgrade-before.json')
    args = p.parse_args()
    path = args.baseline
    current = snapshot(args.strict)
    if args.capture:
        with path.open('x', encoding='utf-8') as output:
            json.dump(current, output)
        path.chmod(0o600)
        print('Captured original row fingerprints in', len(TABLES), 'tables; accounts:', len(current['app_user']))
    else:
        before = json.loads(path.read_text(encoding='utf-8'))
        for table, rows in before.items():
            if Counter(rows) - Counter(current[table]):
                raise SystemExit('Original row changed or missing: ' + table)
        print('PASS: original rows retained in', len(TABLES), 'tables; original accounts:', len(before['app_user']))

if __name__ == '__main__':
    main()

"""Verify checkpoint recovery on a dedicated local Mock TEST deployment. Credentials stay on this host."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import ssl
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--allow-test-restart', action='store_true', required=True)
    args = parser.parse_args()
    state = json.loads((ROOT / 'private/phase03-test.json').read_text(encoding='utf-8'))
    handlers = [urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())]
    if args.url.startswith('https:'):
        handlers.append(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(ROOT / 'private/tls/server.crt'))))
    client = urllib.request.build_opener(*handlers)

    def request(path, body=None, etag=None):
        headers = {'Content-Type': 'application/json'}
        if body is not None:
            headers.update({'X-CSRF-Token': request('/auth/csrf')['csrfToken'], 'Idempotency-Key': str(uuid.uuid4())})
        if etag is not None:
            headers['If-Match'] = '"' + str(etag) + '"'
        message = urllib.request.Request(args.url.rstrip('/') + '/api/v1' + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with client.open(message, timeout=10) as response:
            value = response.read()
            return json.loads(value) if value else None

    request('/auth/login', {'login': state['login'], 'password': state['password']})
    assert request('/erp-connections/' + state['connectionId'])['adapterType'] == 'mock'
    job = request('/sync-jobs', {'connectionId': state['connectionId'], 'entityType': 'PURCHASE_ORDER', 'scenario': 'FAIL_ONCE'})

    def wait(status):
        end = time.monotonic() + 120
        while time.monotonic() < end:
            try:
                current = request('/sync-jobs/' + job['id'])
                if current['status'] == status:
                    return current
            except Exception:
                pass
            time.sleep(1)
        raise RuntimeError('Mock checkpoint verification timed out') from None

    failed = wait('FAILED')
    assert failed['processed'] == 2 and failed['nextCursor'] and failed['errorCode'] == 'ERP_READ_UNAVAILABLE'
    before = request('/purchase-orders?companyId=' + state['companyId'] + '&size=100')
    before_revisions = {value['id']: request('/purchase-orders/' + value['id'] + '/revisions?size=100') for value in before['items']}
    compose = ['docker', 'compose', '--env-file', '.env', '-f', 'deploy/compose.yml']
    if args.url.startswith('https:'):
        compose += ['-f', 'deploy/compose.tls.yml']
    subprocess.run([*compose, 'restart', 'api'], cwd=ROOT, check=True, capture_output=True)
    restored = wait('FAILED')
    assert restored['nextCursor'] == failed['nextCursor'] and restored['processed'] == failed['processed']
    request('/sync-jobs/' + job['id'] + '/retry', {}, restored['version'])
    completed = wait('SUCCEEDED')
    assert completed['processed'] == 4 and completed['applied'] == 0 and completed['skipped'] == 4 and completed['attempts'] == 1
    assert request('/purchase-orders?companyId=' + state['companyId'] + '&size=100') == before
    assert all(request('/purchase-orders/' + key + '/revisions?size=100') == value for key, value in before_revisions.items())
    print('PASS: API restart preserves the failed snapshot, checkpoint and authenticated session')
    print('PASS: retry continues the remaining page without duplicating orders, revisions or quantities')
    request('/auth/logout', {})


if __name__ == '__main__':
    main()

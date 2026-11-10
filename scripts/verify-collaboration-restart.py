"""Resume a synthetic pending approval across an API restart on a dedicated TEST deployment."""
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
    state = json.loads((ROOT / 'private/phase04-test.json').read_text(encoding='utf-8'))
    handlers = [urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())]
    if args.url.startswith('https:'):
        handlers.append(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(ROOT / 'private/tls/server.crt'))))
    client = urllib.request.build_opener(*handlers)

    def request(path, body=None, version=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if body is not None:
            headers.update({'X-CSRF-Token': request('/auth/csrf')['csrfToken'], 'Idempotency-Key': key or str(uuid.uuid4())})
        if version is not None:
            headers['If-Match'] = '"' + str(version) + '"'
        message = urllib.request.Request(args.url.rstrip('/') + '/api/v1' + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with client.open(message, timeout=10) as response:
            value = response.read()
            return json.loads(value) if value else None

    request('/auth/login', {'login': state['login'], 'password': state['password']})
    path = '/delivery-changes/' + state['changeId']
    before = request(path)
    assert before['status'] == 'SUBMITTED'
    assert request('/purchase-orders/' + state['orderId'])['supplierCode'].startswith('WFS-')
    compose = ['docker', 'compose', '--env-file', '.env', '-f', 'deploy/compose.yml']
    if args.url.startswith('https:'):
        compose += ['-f', 'deploy/compose.tls.yml']
    subprocess.run([*compose, 'restart', 'api'], cwd=ROOT, check=True, capture_output=True)
    deadline = time.monotonic() + 120
    while True:
        try:
            assert request(path) == before
            break
        except Exception:
            if time.monotonic() >= deadline:
                raise RuntimeError('Pending approval did not survive API restart') from None
            time.sleep(1)
    payload = {'decision': 'APPROVED', 'reason': 'Virtual restart acceptance'}
    key = str(uuid.uuid4())
    first = request(path + '/decision', payload, before['version'], key)
    assert first == request(path + '/decision', payload, before['version'], key)
    approvals = request(path + '/approval-events')
    assert len(approvals) == 1 and approvals[0]['decision'] == 'APPROVED'
    confirmation = request('/confirmations/' + before['confirmationId'])
    plans = request('/delivery-plans?orderId=' + state['orderId'] + '&status=PLANNED&size=100')['items']
    plans = [plan for plan in plans if plan['confirmationId'] == confirmation['id']]
    assert len(plans) == 1 and plans[0]['quantity'] == confirmation['quantity'] and plans[0]['dueDate'] == before['proposedDate']
    print('PASS: pending approval and authenticated session survive API restart')
    print('PASS: approval replay creates one immutable decision and one equal-quantity plan')
    request('/auth/logout', {})


if __name__ == '__main__':
    main()

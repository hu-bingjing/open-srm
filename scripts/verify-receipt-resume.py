"""Resume a synthetic receipt page across an API restart on a dedicated TEST deployment."""
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
    state = json.loads((ROOT / 'private/phase05-test.json').read_text(encoding='utf-8'))
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
    def wait_job(id, status):
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            job=request('/sync-jobs/'+id)
            if job['status']==status:return job
            time.sleep(.25)
        raise RuntimeError('Receipt job did not reach expected state')
    before=request('/goods-receipts?size=100')
    job=request('/sync-jobs',{'connectionId':state['connectionId'],'entityType':'GOODS_RECEIPT','scenario':'GR_FAIL_ONCE'})
    failed=wait_job(job['id'],'FAILED')
    assert failed['processed']==2 and failed['nextCursor']
    compose=['docker','compose','--env-file','.env','-f','deploy/compose.yml']
    if args.url.startswith('https:'):compose+=['-f','deploy/compose.tls.yml']
    subprocess.run([*compose,'restart','api'],cwd=ROOT,check=True,capture_output=True)
    deadline=time.monotonic()+120
    while True:
        try:
            assert request('/sync-jobs/'+job['id'])==failed
            break
        except Exception:
            if time.monotonic()>deadline:raise RuntimeError('Receipt checkpoint/session not preserved') from None
            time.sleep(1)
    request('/sync-jobs/'+job['id']+'/retry',{},failed['version'])
    done=wait_job(job['id'],'SUCCEEDED')
    assert done['processed']==3 and done['skipped']==3 and done['applied']==0
    assert request('/goods-receipts?size=100')==before
    print('PASS: failed receipt page and authenticated session survive API restart')
    print('PASS: retry resumes saved cursor, skips duplicate receipts and preserves receipt history')
    request('/auth/logout',{})

if __name__=='__main__':
    main()

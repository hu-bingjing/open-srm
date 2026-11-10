"""Encrypted TEST backup and fresh-volume offline restore. Never overwrite an existing backup or volume."""
import argparse
import gzip
import hashlib
import http.cookiejar
import json
import ipaddress
import os
from pathlib import Path, PurePosixPath
import secrets
import shutil
import ssl
import socket
import subprocess
import tarfile
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ['docker', 'compose', '--env-file', '.env', '-f', 'deploy/compose.yml']


def run(args, *, cwd=ROOT, input=None, output=None):
    return subprocess.run(args, cwd=cwd, input=input, stdout=output or subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def sql(compose, query, cwd=ROOT):
    return run([*compose, 'exec', '-T', 'database', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "$1"', 'sh', query], cwd=cwd).decode().strip()


def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_image_export(path, images):
    """Prove all requested images and referenced layers are present, without a pull."""
    with tarfile.open(path) as archive:
        names = {member.name for member in archive.getmembers() if member.isfile()}
        manifests = json.load(archive.extractfile('manifest.json'))
        identifiers = {'sha256:' + name.removeprefix('blobs/sha256/') for name in names if name.startswith('blobs/sha256/')}
        for manifest in manifests:
            for name in [manifest['Config'], *manifest['Layers']]:
                if name not in names:
                    raise ValueError('Offline image export is missing a config or layer')
            # Older Docker uses the configuration digest as its image ID;
            # Docker 29 can report an OCI manifest digest instead.
            identifiers.add('sha256:' + Path(manifest['Config']).name.removesuffix('.json'))
        if not set(images.values()).issubset(identifiers):
            raise ValueError('Offline image export does not contain every running image')


def snapshot(compose, cwd=ROOT):
    tables = json.loads(sql(compose, "SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public'", cwd))
    result = {}
    for table in tables:
        quoted = '"' + table.replace('"', '""') + '"'
        # Only counts/digests leave Postgres; never print raw business or identity rows.
        result[table] = json.loads(sql(compose, "SELECT json_build_object('count',count(*),'digest',md5(COALESCE(string_agg(to_jsonb(t)::text,chr(10) ORDER BY to_jsonb(t)::text),''))) FROM " + quoted + ' t', cwd))
    return result


def private_directory(path, parent):
    path = path.resolve()
    if not path.is_relative_to(parent.resolve()) or path == parent.resolve():
        raise ValueError('Target must be a new project-owned child directory')
    path.mkdir(mode=0o700, parents=True, exist_ok=False)
    return path


def extract(archive, target):
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            name = PurePosixPath(member.name)
            if chr(92) in member.name or name.is_absolute() or '..' in name.parts or member.issym() or member.islnk() or not (member.isfile() or member.isdir()):
                raise ValueError('Unsafe recovery archive')
        source.extractall(target, filter='data')


def maintenance_url(base):
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('Maintenance URL must be a local deployment origin')
    local = {'127.0.0.1', '::1', *run(['hostname', '-I']).decode().split()}
    resolved = {info[4][0] for info in socket.getaddrinfo(parsed.hostname, parsed.port)}
    if not resolved or not resolved.issubset(local):
        raise ValueError('Never send deployment credentials to a remote host')
    if parsed.scheme == 'http' and any(not ipaddress.ip_address(value).is_loopback for value in resolved):
        raise ValueError('Plain HTTP maintenance is loopback-only')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def client(base, ca=None):
    cookie = http.cookiejar.CookieJar()
    handlers = [urllib.request.HTTPCookieProcessor(cookie), NoRedirect()]
    if base.startswith('https:'):
        handlers.append(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(ca))))
    opener = urllib.request.build_opener(*handlers)

    def request(path, body=None, headers=None, method=None):
        h = dict(headers or {})
        if body is not None or method not in (None, 'GET', 'HEAD', 'OPTIONS'):
            csrf_headers = {'Cookie': h['Cookie']} if 'Cookie' in h else None
            h.update({'Content-Type': 'application/json', 'X-CSRF-Token': request('/auth/csrf', headers=csrf_headers)['csrfToken']})
        r = urllib.request.Request(base.rstrip('/') + '/api/v1' + path, data=None if body is None else json.dumps(body).encode(), headers=h, method=method)
        with opener.open(r, timeout=20) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    return request, cookie


def backup(args):
    started = time.monotonic()
    maintenance_url(args.url)
    container = run([*COMPOSE, 'ps', '-q', 'api']).decode().strip()
    info = json.loads(run(['docker', 'inspect', container]))[0]
    env = dict(x.split('=', 1) for x in info['Config']['Env'] if '=' in x)
    if env.get('OPEN_SRM_ERP_WRITE_ENABLED') != 'false' or env.get('OPEN_SRM_ENV') not in ('local', 'test'):
        raise ValueError('Only an explicitly read-only local/test deployment can be backed up by this maintenance tool')
    pending = sql(COMPOSE, "SELECT (SELECT count(*) FROM sync_job WHERE status IN ('PENDING','RUNNING'))+(SELECT count(*) FROM notification_delivery WHERE status='PENDING')")
    if pending != '0':
        raise ValueError('Finish synchronization/notification work before consistent backup')
    directory = private_directory(args.directory, ROOT / 'backups')
    key = args.key_file.resolve()
    if not key.is_relative_to((ROOT / 'private').resolve()):
        raise ValueError('Recovery key must stay in project private storage')
    if not key.exists():
        with key.open('x') as output:
            output.write(secrets.token_urlsafe(48))
        key.chmod(0o600)
    request, cookies = client(args.url, ROOT / 'private/tls/server.crt')
    account_path = ROOT / 'private/phase07-test.json'
    if not account_path.exists(): account_path = ROOT / 'private/initial-admin.json'
    account = json.loads(account_path.read_text())
    request('/auth/login', {k: account[k] for k in ('login', 'password')})
    validation = {'statistics': request('/statistics'), 'me': request('/auth/me'), 'cookie': next(c.value for c in cookies if c.name == 'OPEN_SRM_SESSION')}
    validation['credentials'] = {k: account[k] for k in ('login', 'password')}
    actor = str(uuid.UUID(validation['me']['id']))
    replay = json.loads(sql(COMPOSE, "SELECT COALESCE(json_agg(json_build_object('key',request_key,'body',response_body::json)), '[]'::json) FROM (SELECT request_key,response_body FROM idempotency_record WHERE actor_id='" + actor + "'::uuid AND operation='sync.start' ORDER BY created_at DESC LIMIT 1) r"))
    if replay:
        validation['replay'] = replay[0]
    images = {}
    for service in ('api', 'web', 'database'):
        identifier = run([*COMPOSE, 'ps', '-q', service]).decode().strip()
        images[service] = json.loads(run(['docker', 'inspect', identifier]))[0]['Image']
    paused = time.monotonic()
    try:
        run([*COMPOSE, 'stop', 'api'])
        with (directory / 'database.dump').open('xb') as output:
            run([*COMPOSE, 'exec', '-T', 'database', 'sh', '-c', 'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], output=output)
        tables = snapshot(COMPOSE)
    finally:
        run([*COMPOSE, 'start', '--wait', '--wait-timeout', '180', 'api'])
    pause_seconds = time.monotonic() - paused
    # Encrypt only the selected application settings; SAP profile is deliberately excluded.
    config = directory / 'configuration.tar'
    with tarfile.open(config, 'w') as archive:
        for name in ('.env', 'private/db-password', 'private/bootstrap-token', 'private/idempotency-key', 'private/tls/server.crt', 'private/tls/server.key'):
            if (ROOT / name).exists():
                archive.add(ROOT / name, arcname=name, recursive=False)
        import io
        raw = json.dumps(validation).encode()
        member = tarfile.TarInfo('private/recovery-validation.json'); member.size = len(raw); member.mode = 0o600
        archive.addfile(member, io.BytesIO(raw))
    try:
        run(['openssl', 'enc', '-aes-256-cbc', '-salt', '-pbkdf2', '-iter', '200000', '-in', str(config), '-out', str(directory / 'configuration.enc'), '-pass', 'file:' + str(key)])
    finally:
        config.unlink(missing_ok=True)  # Only this invocation's unencrypted temporary archive.
    import scan_sensitive as scanner
    count, size, findings = scanner.scan()
    if findings: raise ValueError('Public source scan must pass before recovery packaging')
    with tarfile.open(directory / 'source.tar.gz', 'w:gz') as archive:
        for name, data in scanner.candidates('candidate'):
            if scanner.permitted(name):
                archive.add(ROOT / name, arcname=name, recursive=False)
    image_tar = directory / 'images.tar'
    run(['docker', 'image', 'save', '-o', str(image_tar), *sorted(set(images.values()))])
    verify_image_export(image_tar, images)
    with image_tar.open('rb') as src, gzip.open(directory / 'images.tar.gz', 'wb', compresslevel=1) as dst:
        shutil.copyfileobj(src, dst)
    image_tar.unlink()  # The verified compressed export is retained.
    manifest = {'format': 1, 'tables': tables, 'images': images, 'pauseSeconds': round(pause_seconds, 3), 'backupSeconds': round(time.monotonic() - started, 3), 'files': {}}
    for path in directory.iterdir():
        if path.is_file():
            path.chmod(0o600)
            manifest['files'][path.name] = checksum(path)
    manifest['backupSeconds'] = round(time.monotonic() - started, 3)
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (directory / 'manifest.json').chmod(0o600)
    print(json.dumps({'tables': len(tables), 'rows': sum(t['count'] for t in tables.values()), 'pauseSeconds': manifest['pauseSeconds'], 'backupSeconds': manifest['backupSeconds'], 'sapIncluded': False}))


def restore(args):
    started = time.monotonic()
    directory = args.directory.resolve()
    if not directory.is_relative_to((ROOT / 'backups').resolve()):
        raise ValueError('Backup must belong to this project')
    manifest = json.loads((directory / 'manifest.json').read_text())
    if manifest['format'] != 1 or set(manifest['files']) != {'database.dump', 'configuration.enc', 'source.tar.gz', 'images.tar.gz'}:
        raise ValueError('Incomplete recovery bundle')
    for name, expected in manifest['files'].items():
        path = directory / name
        if path.is_symlink() or checksum(path) != expected:
            raise ValueError('Recovery checksum mismatch: ' + name)
    verify_image_export(directory / 'images.tar.gz', manifest['images'])
    target = private_directory(args.target, ROOT / 'tmp')
    project = 'open-srm-restore-' + secrets.token_hex(4)
    state = {'project': project, 'port': args.port}
    (target / 'recovery-state.json').write_text(json.dumps(state, indent=2) + chr(10))
    if run(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]).strip():
        raise ValueError('Recovery project must not own an existing volume')
    extract(directory / 'source.tar.gz', target)
    config = target / 'configuration.tar'
    try:
        run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '200000', '-in', str(directory / 'configuration.enc'), '-out', str(config), '-pass', 'file:' + str(args.key_file.resolve())])
        extract(config, target)
    finally:
        config.unlink(missing_ok=True)
    (target / 'private').chmod(0o700)
    for path in (target / 'private').rglob('*'):
        if path.is_file(): path.chmod(0o644)  # Non-root image users; private parent stays 0700.
    with gzip.open(directory / 'images.tar.gz', 'rb') as source:
        load = subprocess.Popen(['docker', 'image', 'load'], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        shutil.copyfileobj(source, load.stdin); load.stdin.close()
        if load.wait() != 0: raise RuntimeError('Offline image import failed')
    overrides = {'services': {}, 'networks': {'backend': {'internal': True}, 'frontend': {'internal': True}, 'recovery-entry': {'internal': False}}}
    for service, identifier in manifest['images'].items():
        tag = 'open-srm-recovery-' + service + ':' + project.removeprefix('open-srm-restore-')
        run(['docker', 'tag', identifier, tag])
        overrides['services'][service] = {'image': tag, 'pull_policy': 'never'}
    overrides['services']['web']['networks'] = ['frontend', 'recovery-entry']
    overrides['services']['api']['environment'] = {'OPEN_SRM_ERP_ADAPTER': 'mock', 'OPEN_SRM_SAP_CONFIG': '', 'OPEN_SRM_ERP_WRITE_ENABLED': 'false', 'OPEN_SRM_ENV': 'test', 'OPEN_SRM_COOKIE_SECURE': 'false'}
    (target / 'recovery-compose.json').write_text(json.dumps(overrides, indent=2))
    compose = ['docker', 'compose', '-p', project, '--env-file', '.env', '-f', 'deploy/compose.yml', '-f', 'recovery-compose.json']
    # Environment values take precedence over the copied private .env, exposing only loopback.
    os.environ['OPEN_SRM_HTTP_BIND'] = '127.0.0.1'; os.environ['OPEN_SRM_HTTP_PORT'] = str(args.port)
    run([*compose, 'up', '-d', '--no-build', '--pull', 'never', '--wait', 'database'], cwd=target)
    imported = time.monotonic()
    with (directory / 'database.dump').open('rb') as source:
        result = subprocess.run([*compose, 'exec', '-T', 'database', 'sh', '-c', 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], cwd=target, stdin=source, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode: raise RuntimeError('Database import failed; original deployment was not touched')
    actual = snapshot(compose, target)
    if actual != manifest['tables']:
        raise RuntimeError('Restored row counts or content digests differ')
    import_seconds = time.monotonic() - imported
    run([*compose, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '240'], cwd=target)
    identifier = run([*compose, 'ps', '-q', 'api'], cwd=target).decode().strip()
    info = json.loads(run(['docker', 'inspect', identifier]))[0]
    for network in info['NetworkSettings']['Networks']:
        if not json.loads(run(['docker', 'network', 'inspect', network]))[0]['Internal']:
            raise RuntimeError('Recovery network must not allow ERP egress')
    request, cookies = client('http://127.0.0.1:' + str(args.port))
    validation = json.loads((target / 'private/recovery-validation.json').read_text())
    session_restored = True
    try:
        me = request('/auth/me', headers={'Cookie': 'OPEN_SRM_SESSION=' + validation['cookie']})
    except urllib.error.HTTPError as error:
        if error.code != 401 or 'credentials' not in validation: raise
        session_restored = False
        request('/auth/login', validation['credentials'])
        validation['cookie'] = next(c.value for c in cookies if c.name == 'OPEN_SRM_SESSION')
        me = request('/auth/me')
    if me != validation['me'] or request('/auth/bootstrap/status')['setupRequired']:
        raise RuntimeError('Restored identity or initialized state differs')
    auth = {'Cookie': 'OPEN_SRM_SESSION=' + validation['cookie']}
    if request('/statistics', headers=auth) != validation['statistics']:
        raise RuntimeError('Restored business statistics differ')
    replay_restored = None
    if 'replay' in validation:
        saved = validation['replay']; body = saved['body']
        jobs_before = sql(compose, 'SELECT count(*) FROM sync_job', target)
        response = request('/sync-jobs', {k: body[k] for k in ('connectionId', 'entityType', 'scenario')}, headers={**auth, 'Idempotency-Key': saved['key']}, method='POST')
        if response != body or sql(compose, 'SELECT count(*) FROM sync_job', target) != jobs_before:
            raise RuntimeError('Recovered idempotency key did not preserve the cached result')
        replay_restored = True
    (target / 'private/recovery-validation.json').write_text(json.dumps(validation) + chr(10))
    (target / 'private/recovery-validation.json').chmod(0o600)
    env = dict(x.split('=', 1) for x in info['Config']['Env'] if '=' in x)
    assert env['OPEN_SRM_ERP_WRITE_ENABLED'] == 'false' and env['OPEN_SRM_ERP_ADAPTER'] == 'mock' and not env['OPEN_SRM_SAP_CONFIG']
    result = {'project': project, 'port': args.port, 'tables': len(actual), 'rows': sum(t['count'] for t in actual.values()), 'allTableDigestsMatch': True, 'sessionRestored': session_restored, 'loginRestored': True, 'idempotencyReplayRestored': replay_restored, 'statisticsRestored': True, 'erpWritesEnabled': False, 'apiNetworksInternal': True, 'webLoopbackOnly': True, 'importSeconds': round(import_seconds, 3), 'recoverySeconds': round(time.monotonic() - started, 3)}
    (target / 'private/recovery-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('backup', 'restore'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--allow-test-maintenance', action='store_true', required=True)
    parser.add_argument('--key-file', type=Path, default=ROOT / 'private/recovery-key')
    parser.add_argument('--url', default='http://127.0.0.1:8080')
    parser.add_argument('--target', type=Path, default=ROOT / 'tmp/recovery-drill')
    parser.add_argument('--port', type=int, default=8089)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535: parser.error('Port must be unprivileged')
    (backup if args.operation == 'backup' else restore)(args)


if __name__ == '__main__':
    main()

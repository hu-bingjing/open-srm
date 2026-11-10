"""Generate private test deployment secrets. Never overwrite existing files."""
import argparse
import ipaddress
from pathlib import Path
import re
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def write_new(path, content, mode=0o600):
    with path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(content)
    path.chmod(mode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tls-host", help="IP or DNS name for a 30-day self-signed TEST certificate")
    args = parser.parse_args()
    private = ROOT / "private"
    private.mkdir(mode=0o700, exist_ok=True)
    targets = [ROOT / ".env", private / "db-password", private / "bootstrap-token", private / "idempotency-key"]
    if any(path.exists() for path in targets):
        raise SystemExit("Refusing to overwrite existing configuration or credentials")
    san = None
    if args.tls_host:
        try:
            san = "IP:" + str(ipaddress.ip_address(args.tls_host))
        except ValueError:
            if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?", args.tls_host):
                raise SystemExit("Invalid TLS host")
            san = "DNS:" + args.tls_host
        tls = private / "tls"
        tls.mkdir(mode=0o755, exist_ok=True)
        if any(tls.iterdir()):
            raise SystemExit("Refusing to overwrite existing TLS files")
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes", "-days", "30",
                        "-subj", "/CN=OpenSRM Test", "-addext", "subjectAltName=" + san,
                        "-keyout", str(tls / "server.key"), "-out", str(tls / "server.crt")], check=True, capture_output=True)
        # Nginx runs unprivileged; only this private directory is mounted into it.
        (tls / "server.key").chmod(0o644)
        (tls / "server.crt").chmod(0o644)
    write_new(private / "db-password", secrets.token_urlsafe(36))
    write_new(private / "bootstrap-token", secrets.token_urlsafe(36))
    write_new(private / "idempotency-key", secrets.token_urlsafe(36))
    # Compose local secrets use bind mounts; process users need to read files.
    # The containing private directory remains mode 0700 on the host.
    (private / "db-password").chmod(0o644)
    (private / "bootstrap-token").chmod(0o644)
    (private / "idempotency-key").chmod(0o644)
    write_new(ROOT / ".env", "OPEN_SRM_DB_USER=opensrm\nOPEN_SRM_ENV=local\nOPEN_SRM_COOKIE_SECURE=false\nOPEN_SRM_HTTP_BIND=127.0.0.1\nOPEN_SRM_HTTP_PORT=8080\nOPEN_SRM_TLS_BIND=0.0.0.0\nOPEN_SRM_TLS_PORT=443\n")
    print("Created private configuration. No secret values are printed. Bootstrap token: private/bootstrap-token")


if __name__ == "__main__":
    main()

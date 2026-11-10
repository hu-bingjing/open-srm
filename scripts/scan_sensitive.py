"""Conservative publish-allowlist scanner. Reports locations, never matched text.

Heuristics complement manual review and a dedicated full-history scanner.
--staged reads index blobs, --tracked reads tracked working-tree files.
Default mode reads only the explicit public candidate paths, not private files.
"""
import argparse
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = frozenset({"README.md", "AGENTS.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md",
                        ".gitignore", ".gitattributes", ".env.example", "requirements-dev.txt", "requirements-dev.lock.txt"})
PUBLIC_DIRS = frozenset({"docs", "specs", "scripts", "tests", ".github", ".githooks", "backend", "frontend", "deploy"})
TEXT_SUFFIXES = frozenset({".md", ".py", ".json", ".yml", ".yaml", ".txt", ".java", ".xml", ".properties", ".sql", ".ts", ".vue", ".css", ".html", ".cmd", ".conf", ".sh"})
IGNORED_PARTS = frozenset({"__pycache__", ".venv", "node_modules", "tmp", "backup", "backups", "test-results", "target", "dist", "playwright-report"})
RULES = {
    "private-key": re.compile(r"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----"),
    "provider-credential": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}|sk-[A-Za-z0-9_-]{24,})\b"),
    "credential-url": re.compile(r"\b[a-z][a-z0-9+.-]*://[^\s/@:]+:[^\s/@]+@", re.I),
    "private-ip": re.compile(r"(?<![\w.])(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})(?![\w.])"),
    "local-user-path": re.compile(r"\b[A-Z]:[\\/]+Users[\\/]+[^\s/\\]+", re.I),
    "internal-domain": re.compile(r"\bhttps?://[\w.-]+\.(?:internal|corp|local)(?=[:/\s]|$)", re.I),
    "unc-path": re.compile(r"\\\\[A-Za-z0-9][A-Za-z0-9_.-]*\\[A-Za-z0-9]"),
    "organization-name": re.compile(r"[\u4e00-\u9fffA-Za-z]{2,}(?:有限公司|股份公司)"),
}
ASSIGNMENT = re.compile(r'''(?im)^\s*["']?([\w.-]*(?:password|passwd|token|secret|api_key|access_key)[\w.-]*)["']?\s*[:=]\s*["']?([^\s"',}#]+)''')


def permitted(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        return False
    if name in ROOT_FILES or name == ".githooks/pre-commit":
        return True
    return (len(path.parts) > 1 and path.parts[0] in PUBLIC_DIRS
            and not any(part in IGNORED_PARTS for part in path.parts)
            and (path.suffix in TEXT_SUFFIXES or path.name in ("mvnw", "Dockerfile", ".dockerignore")))


def inspect_text(content):
    findings = []
    for number, line in enumerate(content.splitlines(), 1):
        for name, rule in RULES.items():
            if rule.search(line):
                findings.append((number, name))
        assignment = ASSIGNMENT.search(line)
        if assignment:
            value = assignment[2]
            key = assignment[1]
            # A checked UI error code and a mounted secret-file reference are not secret values.
            if key == "PASSWORD_TOO_LONG" or re.fullmatch(r"/run/secrets/[A-Za-z0-9_.-]+", value):
                continue
            # Explicit placeholders and source-code schema definitions only.
            if value not in ("CHANGE_ME", "false", "true", "{", "[", "None") and not value.startswith(("${", "ref(")):
                findings.append((number, "secret-assignment"))
    return findings


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def candidates(mode):
    if mode in ("staged", "tracked"):
        entries = git("ls-files", "--stage", "-z").split(b"\0")
        for entry in entries:
            if not entry:
                continue
            metadata, raw_name = entry.split(b"\t", 1)
            file_mode, _oid, stage = metadata.split()
            name = raw_name.decode("utf-8")
            if stage != b"0" or file_mode not in (b"100644", b"100755"):
                yield name, None
            elif mode == "staged":
                yield name, git("show", ":" + name)
            else:
                path = ROOT / name
                yield name, None if path.is_symlink() or not path.is_file() else path.read_bytes()
        return
    for name in sorted(ROOT_FILES):
        path = ROOT / name
        if path.exists():
            yield name, None if path.is_symlink() else path.read_bytes()
    for directory in sorted(PUBLIC_DIRS):
        base = ROOT / directory
        if base.is_symlink():
            yield directory, None
            continue
        if not base.exists():
            continue
        paths = []
        for root, directories, files in os.walk(base, followlinks=False):
            for name in directories[:]:
                child = Path(root) / name
                if name in IGNORED_PARTS:
                    directories.remove(name)
                elif child.is_symlink():
                    paths.append(child)
                    directories.remove(name)
            paths.extend(Path(root) / name for name in files)
        for path in sorted(paths):
            if any(p in IGNORED_PARTS for p in path.relative_to(ROOT).parts):
                continue
            if path.is_symlink():
                yield path.relative_to(ROOT).as_posix(), None
            elif path.is_file():
                yield path.relative_to(ROOT).as_posix(), path.read_bytes()


def scan(mode="candidate"):
    count = size = 0
    findings = []
    for name, data in candidates(mode):
        count += 1
        if not permitted(name):
            findings.append((name, 0, "outside-publish-allowlist"))
            continue
        if data is None or b"\x00" in data:
            findings.append((name, 0, "nonregular-or-binary-file"))
            continue
        size += len(data)
        if len(data) > 1024 * 1024:
            findings.append((name, 0, "oversize-review-required"))
            continue
        try:
            content = data.decode("utf-8-sig")
        except UnicodeDecodeError:
            findings.append((name, 0, "non-utf8-review-required"))
            continue
        findings.extend((name, line, rule) for line, rule in inspect_text(content))
    if count == 0:
        findings.append(("<scope>", 0, "empty-scan-scope"))
    return count, size, findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--staged", action="store_true")
    group.add_argument("--tracked", action="store_true")
    args = parser.parse_args()
    try:
        count, size, findings = scan("staged" if args.staged else "tracked" if args.tracked else "candidate")
    except (OSError, subprocess.CalledProcessError, UnicodeError, ValueError):
        print("FAIL: scanner could not read complete scope", file=sys.stderr)
        return 2
    for name, line, rule in findings:
        print(f"{name}:{line}: {rule}")
    print(f"Scanned {count} files, {size} bytes; findings={len(findings)}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())

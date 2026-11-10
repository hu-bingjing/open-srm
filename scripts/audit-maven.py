"""Audit a Maven dependency:tree JSON against OSV; never sends application source.

Generate with maven-dependency-plugin:3.8.1:tree -DoutputType=json
-DoutputFile=target/dependency-tree.json. Network failures fail closed.
"""
import json
from pathlib import Path
import sys
import urllib.request


def packages(tree):
    result = set()
    def visit(node):
        result.add((node["groupId"] + ":" + node["artifactId"], node["version"]))
        for child in node.get("children", []):
            visit(child)
    for child in tree.get("children", []):
        visit(child)
    return sorted(result)


def main():
    dependencies = packages(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
    if not dependencies:
        raise SystemExit("FAIL: dependency scope is empty")
    findings = []
    try:
        for start in range(0, len(dependencies), 100):
            batch = dependencies[start:start + 100]
            body = {"queries": [{"package": {"ecosystem": "Maven", "name": name}, "version": version} for name, version in batch]}
            request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=60) as response:
                result = json.load(response)["results"]
            if len(result) != len(batch):
                raise ValueError("incomplete vulnerability response")
            for (name, version), outcome in zip(batch, result):
                if outcome.get("next_page_token"):
                    raise ValueError("paginated vulnerability response requires further review")
                for vulnerability in outcome.get("vulns", []):
                    findings.append((name, version, vulnerability["id"]))
    except Exception as error:
        print("FAIL: vulnerability lookup incomplete (" + type(error).__name__ + ")")
        return 2
    for name, version, identifier in findings:
        print(f"{name}@{version}: {identifier}")
    print(f"Audited {len(dependencies)} resolved Maven packages; findings={len(findings)}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())

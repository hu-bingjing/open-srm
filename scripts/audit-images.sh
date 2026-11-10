#!/bin/sh
# Scan locally built runtime images; Docker access is required. Reports stay local.
set -eu
mkdir -p tmp/image-audit tmp/trivy-cache
for component in api web database; do
  docker run --rm --network host --cap-drop ALL --security-opt no-new-privileges:true \
    --user "$(id -u):$(id -g)" --group-add "$(stat -c %g /var/run/docker.sock)" \
    -v /var/run/docker.sock:/var/run/docker.sock:ro \
    -v "$(pwd)/tmp/trivy-cache:/tmp/trivy-cache" \
    -v "$(pwd)/tmp/image-audit:/reports" \
    ghcr.io/aquasecurity/trivy@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb \
    image --cache-dir /tmp/trivy-cache --image-src docker --scanners vuln,secret,license --license-full \
    --timeout 30m --no-progress --format json --output "/reports/$component.json" \
    "open-srm-$component:0.8.0-local"
done
python3 - <<'PY'
import json
from pathlib import Path
summary = []
for component in ('api', 'web', 'database'):
    report = json.loads(Path('tmp/image-audit', component + '.json').read_text())
    vulnerabilities = [v for result in report.get('Results', []) for v in result.get('Vulnerabilities', [])]
    disclosures = [s for result in report.get('Results', []) for s in result.get('Secrets', [])]
    summary.append({'component': component, 'image': report['Metadata']['ImageID'],
                    'highCritical': sum(v['Severity'] in ('HIGH', 'CRITICAL') for v in vulnerabilities),
                    'totalVulnerabilities': len(vulnerabilities), 'secrets': len(disclosures)})
Path('tmp/image-audit/summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary))
if any(row['highCritical'] or row['secrets'] for row in summary):
    raise SystemExit('Runtime image security gate failed; inspect local reports')
PY

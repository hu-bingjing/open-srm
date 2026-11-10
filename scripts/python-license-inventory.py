"""Record declared Python development-tool licenses from installed distribution metadata."""
import argparse
import importlib.metadata
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packages', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = []
    for distribution in importlib.metadata.distributions(path=[str(args.packages)]):
        metadata = distribution.metadata
        classifiers = [v.removeprefix('License :: ') for v in metadata.get_all('Classifier', []) if v.startswith('License :: ')]
        declared = metadata.get('License-Expression') or metadata.get('License') or '; '.join(classifiers)
        if declared:
            declared = next((line.strip() for line in declared.splitlines() if line.strip()), '')
        name = metadata['Name']
        rows.append({'name': name, 'version': distribution.version, 'scope': 'development/test', 'declaredLicense': declared or 'UNRESOLVED', 'classifiers': classifiers, 'source': 'https://pypi.org/project/' + name + '/' + distribution.version + '/'})
    rows.sort(key=lambda row: row['name'].lower())
    result = {'projectLicense': 'UNSELECTED', 'declarationsAreNotLegalClearance': True, 'packages': rows}
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'pythonDevelopmentPackages': len(rows), 'unresolved': sum(row['declaredLicense'] in ('UNRESOLVED', 'UNKNOWN') for row in rows)}))


if __name__ == '__main__':
    main()

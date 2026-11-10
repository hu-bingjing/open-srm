"""Inventory declared Maven/npm licenses without choosing a project license. Reports stay local."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def maven_licenses(repository, group, artifact, version, seen=None):
    seen = set(seen or ())
    coordinate = (group, artifact, version)
    if coordinate in seen or len(seen) > 12: return [], 'unresolved'
    seen.add(coordinate)
    path = repository / group.replace('.', '/') / artifact / version / (artifact + '-' + version + '.pom')
    if not path.exists(): return [], 'missing-pom'
    root = ET.fromstring(path.read_bytes())
    licenses = [{'name': item.findtext('m:name', '', NS), 'url': item.findtext('m:url', '', NS)} for item in root.findall('m:licenses/m:license', NS)]
    if licenses: return licenses, ':'.join(coordinate)
    parent = root.find('m:parent', NS)
    if parent is None: return [], 'undeclared'
    props = {'project.version': version, 'version': version}
    properties = root.find('m:properties', NS)
    if properties is not None:
        props.update({item.tag.split('}')[-1]: item.text or '' for item in properties})
    def value(name):
        text = parent.findtext('m:' + name, '', NS)
        return re.sub(r'\$\{([^}]+)\}', lambda m: props.get(m[1], m[0]), text)
    return maven_licenses(repository, value('groupId'), value('artifactId'), value('version'), seen)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--maven-tree', type=Path, required=True)
    p.add_argument('--repository', type=Path, required=True)
    p.add_argument('--output', type=Path, default=ROOT / 'tmp/license-inventory.json')
    args = p.parse_args()
    dependencies = {}
    def visit(node):
        key = (node['groupId'], node['artifactId'], node['version'])
        if key not in dependencies:
            licenses, source = maven_licenses(args.repository, *key)
            dependencies[key] = {'ecosystem': 'Maven', 'name': key[0] + ':' + key[1], 'version': key[2], 'scope': node.get('scope', 'unknown'), 'licenses': licenses, 'declaration': source}
        for child in node.get('children', []): visit(child)
    tree = json.loads(args.maven_tree.read_text())
    for child in tree.get('children', []): visit(child)
    rows = list(dependencies.values())
    lock = json.loads((ROOT / 'frontend/package-lock.json').read_text())
    for name, item in lock['packages'].items():
        if not name: continue
        identifier = name.split('node_modules/')[-1]
        declared = item.get('license')
        rows.append({'ecosystem': 'npm', 'name': identifier, 'version': item['version'], 'scope': 'build/test' if item.get('dev') else 'runtime', 'optional': item.get('optional', False), 'licenses': [{'name': declared, 'url': 'https://www.npmjs.com/package/' + identifier + '/v/' + item['version']}] if declared else [], 'declaration': 'package-lock'})
    unresolved = [row['ecosystem'] + ':' + row['name'] + '@' + row['version'] for row in rows if not row['licenses']]
    review = [row['ecosystem'] + ':' + row['name'] + '@' + row['version'] for row in rows if any(re.search(r'(?i)\b(A?GPL|LGPL|EPL|CDDL|MPL)', license['name'] or '') for license in row['licenses'])]
    result = {'projectLicense': 'UNSELECTED', 'declaredLicensesAreNotLegalClearance': True, 'dependencies': rows, 'unresolved': unresolved, 'requiresTermsReview': review}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'maven': len(dependencies), 'npm': len(rows) - len(dependencies), 'unresolved': len(unresolved), 'requiresTermsReview': len(review), 'projectLicense': 'UNSELECTED'}))
    return 0 if not unresolved else 2


if __name__ == '__main__':
    raise SystemExit(main())

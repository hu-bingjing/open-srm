"""Bounded read-only benchmark against an isolated offline recovery instance."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import statistics
import time
import urllib.request


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--url', required=True)
    p.add_argument('--requests', type=int, default=200)
    p.add_argument('--concurrency', type=int, default=10)
    args = p.parse_args()
    proof = json.loads((args.root / 'private/recovery-result.json').read_text())
    if not proof['apiNetworksInternal'] or not proof['allTableDigestsMatch'] or args.url != 'http://127.0.0.1:' + str(proof['port']):
        raise ValueError('Benchmark only the verified loopback recovery copy')
    if not 1 <= args.concurrency <= 20 or not 1 <= args.requests <= 1000:
        raise ValueError('Benchmark bounds exceeded')
    validation = json.loads((args.root / 'private/recovery-validation.json').read_text())
    results = []
    for endpoint in ('/purchase-orders?size=20', '/statistics', '/dashboard', '/notifications/unread-count'):
        def sample(_):
            start = time.perf_counter()
            request = urllib.request.Request(args.url + '/api/v1' + endpoint, headers={'Cookie': 'OPEN_SRM_SESSION=' + validation['cookie']})
            try:
                with urllib.request.urlopen(request, timeout=15) as response:
                    body = json.load(response)
                    if response.status != 200: raise ValueError('Unexpected HTTP status')
                return (time.perf_counter() - start) * 1000, True
            except Exception:
                return (time.perf_counter() - start) * 1000, False
        start = time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            observations = list(pool.map(sample, range(args.requests)))
        elapsed = time.perf_counter() - start
        latencies = sorted(x[0] for x in observations)
        results.append({'endpoint': endpoint, 'requests': args.requests, 'concurrency': args.concurrency, 'p50Ms': round(statistics.median(latencies), 2), 'p95Ms': round(latencies[int(.95 * (len(latencies) - 1))], 2), 'maxMs': round(max(latencies), 2), 'requestsPerSecond': round(len(latencies) / elapsed, 2), 'errors': sum(not x[1] for x in observations)})
    result = {'scope': 'small synthetic restored dataset; not production capacity certification', 'tables': proof['tables'], 'rows': proof['rows'], 'results': results, 'passed': all(r['errors'] == 0 and r['p95Ms'] < 1000 for r in results)}
    (args.root / 'private/benchmark-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

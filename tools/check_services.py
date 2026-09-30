#!/usr/bin/env python3
"""Read-only checks of the original Android API contracts. Never sends SOS."""
import argparse
import json
import sys
from urllib.request import urlopen


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--warning', required=True)
    p.add_argument('--vision', required=True)
    args = p.parse_args()
    checks = [
        ('Hazard snapshot', args.warning, '/api/now', 'place', dict),
        ('Forecast', args.warning, '/api/forecast', 'timeline', list),
        ('Photo service', args.vision, '/health', 'status', str),
        ('Alert history', args.vision, '/alerts', 'alerts', list),
    ]
    failures = 0
    for label, base, path, key, kind in checks:
        try:
            with urlopen(base.rstrip('/') + path, timeout=8) as response:
                data = json.load(response)
            if not isinstance(data.get(key), kind):
                raise ValueError(f'Expected {key}: {kind.__name__}')
            print(f'PASS {label}')
        except Exception as error:
            failures += 1
            print(f'FAIL {label}: {error}')
    print('Read-only contract checks only. Camera, route safety, SOS, mesh and delivery still require end-to-end tests.')
    return 1 if failures else 0

if __name__ == '__main__':
    sys.exit(main())

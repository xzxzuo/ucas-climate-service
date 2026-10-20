#!/usr/bin/env python3
"""Verify live API authentication/roles. Only --run-once triggers an inbox scan."""
import argparse
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from local_auth import reader_key, admin_key


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8087')
    parser.add_argument('--run-once', action='store_true', help='Explicitly process this round of incoming files')
    arguments = parser.parse_args()
    read_key, management_key = reader_key(), admin_key()
    checks = 0

    def request(path, expected=200, key=None, method='GET', extra_headers=None):
        nonlocal checks
        headers = dict(extra_headers or {})
        if key:
            headers['X-API-Key'] = key
        try:
            response = urlopen(Request(arguments.base.rstrip('/') + path, headers=headers, method=method), timeout=180)
        except HTTPError as exception:
            response = exception
        with response:
            body = response.read()
            assert response.code == expected, (path, response.code, expected)
            assert response.headers.get('X-Request-Id'), (path, 'missing request ID')
            checks += 2
            if 'json' in response.headers.get('Content-Type', ''):
                return json.loads(body)
            return body

    request('/')
    request('/actuator/health')
    request('/api/meta', 401)
    request('/api/meta', 401, 'intentionally-invalid-test-key')
    request('/api/meta?apiKey=not-a-real-key', 401)
    request('/api/meta', key=read_key)
    request('/api/exports/thresholds.csv', key=read_key)
    request('/api/admin/incremental/status', 403, read_key)
    request('/api/admin/incremental/status', 403, read_key, extra_headers={'X-Role': 'ADMIN'})
    request('/api/admin/incremental/run', 403, read_key, 'POST')
    state = request('/api/admin/incremental/status', key=management_key)
    request('/api/admin/incremental/jobs?limit=1', key=management_key)
    report = {'status': 'PASS', 'assertions': checks, 'automatic_scheduling_enabled': state['enabled'], 'run_once_triggered': False}
    if arguments.run_once:
        result = request('/api/admin/incremental/run', key=management_key, method='POST')
        report.update(assertions=checks, run_once_triggered=True, run=result)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

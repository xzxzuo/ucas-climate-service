#!/usr/bin/env python3
"""Local platform credentials, real HTTP verification, and restart persistence checks."""
import argparse
import csv
from datetime import datetime
import http.cookiejar
import io
import json
import os
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from local_auth import load_credentials

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / '.runtime/platform-verification.json'


class Client:
    def __init__(self, base):
        url = urllib.parse.urlsplit(base)
        if url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost', '::1') or url.username or url.password:
            raise SystemExit('This helper only sends credentials to a loopback HTTP URL.')
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = None

    def request(self, path, method='GET', data=None, headers=None, csrf=True):
        headers = dict(headers or {})
        if method != 'GET' and csrf and self.token:
            headers[self.token['headerName']] = self.token['token']
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(self.base + '/platform/api' + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=30) as response:
                self.last_headers = dict(response.headers)
                code, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            self.last_headers = dict(error.headers)
            code, raw = error.code, error.read()
        try:
            return code, json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return code, raw.decode('utf-8')

    def ok(self, path, method='GET', data=None):
        code, body = self.request(path, method, data)
        if code not in (200, 201, 202):
            raise RuntimeError(f'{method} {path}: HTTP {code}: {body}')
        return body

    def login(self, username, password):
        self.token = self.ok('/csrf')
        body = urllib.parse.urlencode({'username': username, 'password': password}).encode()
        code, _ = self.request('/login', 'POST', body, {'Content-Type': 'application/x-www-form-urlencoded'})
        if code != 200:
            raise RuntimeError('Login failed. Check the initial password, account state, or temporary lock; no credentials were printed.')
        self.token = self.ok('/csrf')

    def upload(self, dataset, content, name='sample.csv'):
        boundary = 'climate-' + uuid.uuid4().hex
        head = f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/csv\r\n\r\n'.encode()
        payload = head + content + f'\r\n--{boundary}--\r\n'.encode()
        return self.request(f'/datasets/{dataset}/files', 'POST', payload, {'Content-Type': 'multipart/form-data; boundary=' + boundary})

    def wait(self, task, timeout=240):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            job = self.ok('/tasks/' + task)
            if job['status'] not in ('QUEUED', 'RUNNING'):
                return job
            time.sleep(.5)
        raise RuntimeError(f'Task {task} did not finish within {timeout}s; inspect the task and server log.')


def admin_client(base):
    client = Client(base)
    password = os.environ.get('CLIMATE_PLATFORM_ADMIN_PASSWORD') or load_credentials().get('CLIMATE_PLATFORM_ADMIN_PASSWORD')
    if not password:
        raise SystemExit('Run python3 scripts/local_auth.py and restart the app with the new configuration.')
    client.login('admin', password)
    return client


def credentials(_):
    keys = load_credentials()
    print('Local platform login (private; do not share screenshots):')
    print('username: admin')
    print('password:', keys.get('CLIMATE_PLATFORM_ADMIN_PASSWORD', '(run local_auth.py first)'))
    print('This is the bootstrap password. Changing the env file does not reset an existing account.')


def verify(args):
    checks = []

    def check(name, condition):
        if not condition:
            raise RuntimeError('FAIL: ' + name)
        checks.append(name)
        print('PASS:', name, flush=True)

    anonymous = Client(args.base)
    check('anonymous platform API rejected', anonymous.request('/me')[0] == 401)
    retired = []
    for path in ('/api/meta', '/index.html'):
        try:
            with anonymous.opener.open(args.base + path, timeout=5) as response:
                retired.append(response.status)
        except urllib.error.HTTPError as error:
            retired.append(error.code)
            error.close()
    check('retired API and page are absent', retired == [404, 404])
    admin = admin_client(args.base)
    check('real cookie login', admin.ok('/me')['role'] == 'ADMIN')
    check('CSRF protects mutations', admin.request('/datasets', 'POST', {'name': 'not-created'}, csrf=False)[0] == 403)
    suffix = uuid.uuid4().hex[:8]
    clients = []
    names = []
    for label, role in [('alice', 'RESEARCHER'), ('bob', 'RESEARCHER'), ('viewer', 'VIEWER')]:
        name, password = f'{label}_{suffix}', secrets.token_hex(16)
        account = admin.ok('/admin/users', 'POST', {'username': name, 'displayName': f'端到端验证 {label}', 'password': password, 'role': role})
        check('account created: ' + label, 'password' not in account and account['role'] == role)
        client = Client(args.base)
        client.login(name, password)
        clients.append(client)
        names.append(name)
    alice, bob, viewer = clients
    a = alice.ok('/datasets', 'POST', {'name': '端到端验证 A ' + suffix, 'description': '明确标注的本地验证数据，不冒充科研结论。'})['id']
    b = bob.ok('/datasets', 'POST', {'name': '端到端验证 B ' + suffix})['id']
    check('private dataset hidden from another user', bob.request(f'/datasets/{a}')[0] == 404)
    check('viewer cannot create datasets', viewer.request('/datasets', 'POST', {'name': 'forbidden'})[0] == 403)
    check('researcher cannot manage accounts', alice.request('/admin/users')[0] == 403)
    sample = ROOT / 'data/sample.csv'
    if not sample.exists() or sample.stat().st_size > 8 * 1024 * 1024:
        raise RuntimeError('Expected the existing bounded data/sample.csv, not the 45 GB source.')
    content = sample.read_bytes()
    code, upload_a = alice.upload(a, content)
    check('CSV upload accepted', code == 202)
    code, duplicate = alice.upload(a, content, 'renamed.csv')
    check('same dataset content deduplicated', code == 202 and duplicate['duplicate'] and duplicate['task']['id'] == upload_a['task']['id'])
    code, upload_b = bob.upload(b, content)
    check('same file allowed in another dataset', code == 202 and not upload_b['duplicate'])
    imports = [alice.wait(upload_a['task']['id']), bob.wait(upload_b['task']['id'])]
    check('both real imports complete', all(t['status'] == 'SUCCESS' for t in imports))
    da, db = alice.ok(f'/datasets/{a}')['dataset'], bob.ok(f'/datasets/{b}')['dataset']
    check('observation counts isolated', da['raw_rows'] > 0 and da['raw_rows'] == db['raw_rows'] and da['raw_revision'] == 1 and db['raw_revision'] == 1)
    ca = alice.ok(f'/datasets/{a}/analyze', 'POST')['id']
    cb = bob.ok(f'/datasets/{b}/analyze', 'POST')['id']
    calculations = [alice.wait(ca), bob.wait(cb)]
    check('both real SQL calculations publish', all(t['status'] == 'SUCCESS' and t['result_version'] == 1 for t in calculations))
    first = alice.ok(f'/datasets/{a}/analysis?kind=THRESHOLDS')
    second = alice.ok(f'/datasets/{a}/analysis?kind=THRESHOLDS')
    check('cached results retain numerical values', first == second and len(first['items']) > 0)
    stations = alice.ok(f'/datasets/{a}/stations')
    check('platform station list is scoped and usable', len(stations) > 0 and sum(s['observations'] for s in stations) == da['raw_rows'])
    station = first['items'][0]['stationCode']
    daily_parameters = {'kind': 'DAILY', 'station': station, 'from': '1993-01-01', 'to': '2101-01-01'}
    daily = alice.ok(f'/datasets/{a}/analysis?' + urllib.parse.urlencode({**daily_parameters, 'limit': 1}))
    check('DAILY query returns a real paginated result', len(daily['items']) == 1 and daily['total'] >= 1)
    date = daily['items'][0]['date']
    kinds = [daily_parameters,
             {'kind': 'STATION_ANNUAL', 'station': station, 'year': date[:4]},
             {'kind': 'REGION_DAILY', 'date': date, 'level': 'COUNTRY', 'metric': 'HTD'},
             {'kind': 'REGION_ANNUAL', 'year': date[:4], 'level': 'COUNTRY', 'metric': 'HTD'}]
    extra_tasks, extra_artifacts = [], []
    for parameters in kinds:
        result = daily if parameters['kind'] == 'DAILY' else alice.ok(f'/datasets/{a}/analysis?' + urllib.parse.urlencode({**parameters, 'limit': 1}))
        if parameters['kind'] != 'DAILY':
            check(parameters['kind'] + ' query returns a real paginated result', len(result['items']) == 1 and result['total'] >= 1)
        export = alice.ok(f'/datasets/{a}/exports', 'POST', {**parameters, 'version': '1'})
        finished = alice.wait(export['id'])
        if finished['status'] != 'SUCCESS':
            raise RuntimeError(f"{parameters['kind']} export failed: {finished.get('error_message')}")
        artifact = finished['export']['id']
        code, text = alice.request(f'/exports/{artifact}/download')
        rows = list(csv.DictReader(io.StringIO(text)))
        check(parameters['kind'] + ' CSV uses the same filtered result', code == 200 and len(rows) == result['total'] and all(r['datasetId'] == str(a) and r['resultVersion'] == '1' for r in rows))
        extra_tasks.append(export['id'])
        extra_artifacts.append(artifact)
    alice.ok(f'/datasets/{a}/members', 'POST', {'username': names[1], 'permission': 'READ'})
    check('explicit read grant works', bob.request(f'/datasets/{a}/analysis?kind=THRESHOLDS')[0] == 200)
    check('read grant does not permit analysis', bob.request(f'/datasets/{a}/analyze', 'POST')[0] == 403)
    alice.ok(f'/datasets/{a}/members', 'POST', {'username': names[1], 'permission': 'REMOVE'})
    check('revocation checked before warm cache', bob.request(f'/datasets/{a}/analysis?kind=THRESHOLDS')[0] == 404)
    export_task = alice.ok(f'/datasets/{a}/exports', 'POST', {'kind': 'THRESHOLDS', 'version': '1'})['id']
    newer = alice.ok(f'/datasets/{a}/analyze', 'POST')['id']
    exported, recomputed = alice.wait(export_task), alice.wait(newer)
    check('export and new version succeed', exported['status'] == recomputed['status'] == 'SUCCESS' and recomputed['result_version'] == 2)
    export_id = exported['export']['id']
    code, text = alice.request(f'/exports/{export_id}/download')
    csv_rows = list(csv.DictReader(io.StringIO(text)))
    check('CSV download pins selected version', code == 200 and len(csv_rows) == len(first['items']) and all(r['datasetId'] == str(a) and r['resultVersion'] == '1' for r in csv_rows))
    check('private export cannot be downloaded by another user', bob.request(f'/exports/{export_id}/download')[0] == 404)
    before = alice.ok(f'/datasets/{a}')['dataset']
    bad = b'STATION,DATE,NAME,TEMP,PRCP,HUMIDITY,MAX,MIN,FRSHTT,country,province,city,district\n1,1993-01-01,A,20,1,2,30,10,0,,,,\n'
    code, rejected = alice.upload(a, bad, 'invalid-humidity.csv')
    check('invalid content is processed as a task', code == 202)
    failed = alice.wait(rejected['task']['id'])
    check('invalid import fails with actionable error', failed['status'] == 'FAILED' and failed['error_code'] == 'INVALID_DATA')
    after = alice.ok(f'/datasets/{a}')['dataset']
    check('failed import preserves raw data and published result', all(before[k] == after[k] for k in ('raw_rows', 'raw_revision', 'active_result_version')))
    retried = alice.ok('/tasks/' + failed['id'] + '/retry', 'POST')
    check('manual retry uses a new task', retried['id'] != failed['id'] and alice.wait(retried['id'])['status'] == 'FAILED')
    check('audit trail exists', any(r['action'] == 'DOWNLOAD_EXPORT' for r in alice.ok('/audit')))
    report = {'time': datetime.utcnow().isoformat() + 'Z', 'base': args.base, 'passed': len(checks), 'checks': checks,
              'datasets': [a, b], 'users': names, 'resultVersion': 2, 'exportId': export_id, 'additionalExports': extra_artifacts, 'tasks': [ca, cb, export_task, newer] + extra_tasks}
    REPORT.parent.mkdir(exist_ok=True, parents=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'All {len(checks)} checks passed; report: {REPORT}')
    print('Verification accounts/datasets are deliberately retained and labeled. Existing data was not deleted.')


def persistence(args):
    report = json.loads(REPORT.read_text())
    if report['base'] != args.base:
        raise RuntimeError('Run verify against this base URL first.')
    client = admin_client(args.base)
    for dataset in report['datasets']:
        if client.ok(f'/datasets/{dataset}')['dataset']['raw_rows'] <= 0:
            raise RuntimeError('Missing dataset observations')
    for task in report['tasks']:
        if client.ok('/tasks/' + task)['status'] != 'SUCCESS':
            raise RuntimeError('Missing successful task history')
    for artifact in [report['exportId']] + report.get('additionalExports', []):
        code, body = client.request('/exports/' + artifact + '/download')
        if code != 200 or 'datasetId,resultVersion' not in body:
            raise RuntimeError('Export artifact missing after restart')
    print('PASS: datasets, versions, task history, and private export file survive restart.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('credentials').set_defaults(run=credentials)
    for name, function in [('verify', verify), ('persistence-check', persistence)]:
        command = commands.add_parser(name)
        command.add_argument('--base', default='http://127.0.0.1:8087')
        command.set_defaults(run=function)
    args = parser.parse_args()
    args.run(args)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Create the private platform bootstrap password; existing credentials are never rotated."""
import os
from pathlib import Path
import re
import secrets

ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = ROOT / '.runtime' / 'auth.env'
PASSWORD = 'CLIMATE_PLATFORM_ADMIN_PASSWORD'
# Retain retired keys in existing private files for rollback; never use or generate them.
ALLOWED = {PASSWORD, 'CLIMATE_READ_KEY', 'CLIMATE_ADMIN_KEY'}


def _read():
    values = {}
    for line in KEY_FILE.read_text().splitlines():
        if not line.strip():
            continue
        key, separator, value = line.partition('=')
        if not separator or key not in ALLOWED or key in values or not re.fullmatch(r'[0-9a-f]{64}', value):
            raise SystemExit('Invalid private auth.env; expected generated hexadecimal credentials.')
        values[key] = value
    return values


def load_credentials():
    if not KEY_FILE.exists():
        raise SystemExit('Run python3 scripts/local_auth.py first, or set CLIMATE_PLATFORM_ADMIN_PASSWORD.')
    values = _read()
    if PASSWORD not in values:
        raise SystemExit('Run python3 scripts/local_auth.py to add the platform bootstrap password.')
    return {PASSWORD: values[PASSWORD]}


def ensure_credentials():
    KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(PASSWORD + '=' + secrets.token_hex(32) + '\n')
    if PASSWORD not in _read():
        content = KEY_FILE.read_text()
        with KEY_FILE.open('a') as stream:
            stream.write(('\n' if content and not content.endswith('\n') else '') + PASSWORD + '=' + secrets.token_hex(32) + '\n')
    load_credentials()
    KEY_FILE.chmod(0o600)


if __name__ == '__main__':
    ensure_credentials()
    print('Platform bootstrap password is ready in .runtime/auth.env (private; value not printed).')

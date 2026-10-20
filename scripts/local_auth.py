#!/usr/bin/env python3
"""Generate private local API keys once, without printing their values."""
import os
from pathlib import Path
import re
import secrets

ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = ROOT / '.runtime' / 'auth.env'


def load_keys():
    if not KEY_FILE.exists():
        raise SystemExit('Run python3 scripts/local_auth.py first, or set CLIMATE_READ_KEY / CLIMATE_ADMIN_KEY.')
    keys = dict(line.split('=', 1) for line in KEY_FILE.read_text().splitlines() if line.strip())
    required = {'CLIMATE_READ_KEY', 'CLIMATE_ADMIN_KEY'}
    if not required.issubset(keys) or set(keys) - required - {'CLIMATE_PLATFORM_ADMIN_PASSWORD'} or not all(re.fullmatch(r'[0-9a-f]{64}', value) for value in keys.values()):
        raise SystemExit('Invalid local auth.env; expected generated hexadecimal API keys and optional platform bootstrap password.')
    return keys


def reader_key():
    return os.environ.get('CLIMATE_READ_KEY') or load_keys()['CLIMATE_READ_KEY']


def admin_key():
    return os.environ.get('CLIMATE_ADMIN_KEY') or load_keys()['CLIMATE_ADMIN_KEY']


if __name__ == '__main__':
    KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not KEY_FILE.exists():
        content = 'CLIMATE_READ_KEY=' + secrets.token_hex(32) + '\nCLIMATE_ADMIN_KEY=' + secrets.token_hex(32) + '\n'
        try:
            descriptor = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            pass
        else:
            with os.fdopen(descriptor, 'w') as stream:
                stream.write(content)
    keys = load_keys()
    if 'CLIMATE_PLATFORM_ADMIN_PASSWORD' not in keys:
        with KEY_FILE.open('a') as stream:
            stream.write('CLIMATE_PLATFORM_ADMIN_PASSWORD=' + secrets.token_hex(32) + '\n')
    load_keys()
    KEY_FILE.chmod(0o600)
    print('Local API keys and platform bootstrap password are ready in .runtime/auth.env (private; values not printed).')
